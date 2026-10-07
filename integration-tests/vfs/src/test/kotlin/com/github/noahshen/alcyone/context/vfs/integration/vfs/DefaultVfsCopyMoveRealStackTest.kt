package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.DefaultVfs
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.event.VfsEventConsumer
import com.github.noahshen.alcyone.context.vfs.core.event.toVfsEvent
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * T21 S3：[DefaultVfs] 装上**真的 SQLite 状态库**和**两个临时 Local FS 根**跑跨 Mount 文件移动。
 *
 * 替身证明不了的在这里证明：文件真的从第一块盘消失、真的出现在第二块盘（逐字节一致）、
 * 缺失的父目录真的建在目标盘上、Node 与 Metadata 在同事务里被更新、真实 SQLite 事务失败时
 * 状态真的回滚而物理变更不回退。
 *
 * 状态库文件放在**两个挂载根之外**（同一个临时目录的第三层），所以它不在任何一块盘的物理范围内。
 * 精确失败点用包装 Storage / 包装事务注入（真文件系统不会自己停在**一半**），断言仍然对着真盘与真库。
 * 公开 Runtime 的成功闭环 / 重开 / 事件字段证据在 `RuntimeCopyMoveTest`，两类证据分开。
 */
class DefaultVfsCopyMoveRealStackTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var leftRoot: Path
    private lateinit var rightRoot: Path
    private lateinit var databaseFile: Path

    @BeforeEach
    fun setUp() {
        leftRoot = Files.createDirectory(tempDir.resolve("left"))
        rightRoot = Files.createDirectory(tempDir.resolve("right"))
        // 状态库在两块挂载根之外：它不属于任何一块盘的物理范围。
        databaseFile = tempDir.resolve("state/state.db")
        Files.createDirectories(databaseFile.parent)
    }

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    /** A01 / A02：真盘跨 Mount——源盘消失、目标盘逐字节一致、Node ID 与 Metadata 不变、只有一条 FILE_MOVED。 */
    @Test
    @Timeout(60)
    fun `A01 the real disks move a file across mounts and the identity survives`() =
        runBlocking {
            Files.writeString(leftRoot.resolve("note.txt"), "hello cross mount")
            val digestBefore = sha256(leftRoot.resolve("note.txt"))
            withStack { stack ->
                val written = stack.vfs.stat(uri("/local/note.txt")).id
                stack.metadata.put(written, NodeMetadata(setOf("ct"), "跨盘搬运"))

                val moved = stack.vfs.move(uri("/local/note.txt"), uri("/archive/2026/note.txt"))

                assertEquals(written, moved.id, "已登记源复用原 ID")
                assertFalse(Files.exists(leftRoot.resolve("note.txt")), "第一块盘上的旧位置真的没了")
                assertTrue(Files.isDirectory(rightRoot.resolve("2026")), "缺失的父目录真的建在第二块盘上")
                assertEquals(digestBefore, sha256(rightRoot.resolve("2026/note.txt")), "内容逐字节一致")
                assertEquals(
                    written,
                    stack.nodes.findByPath(VfsPath.parse("/resources/archive/2026/note.txt"))?.id,
                    "状态库里路径迁到目标",
                )
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/local/note.txt")), "旧路径不再有记录")
                assertEquals(NodeMetadata(setOf("ct"), "跨盘搬运"), stack.metadata.get(written), "Metadata 随身份保留")
                assertEquals(1, activeNodes(), "只有被移动的文件有记录，补出的父目录不登记")
                assertEquals(listOf("FILE_MOVED"), eventTypes(), "stat 懒注册不发事件，只有一条 FILE_MOVED")
            }
        }

    /** A03：真盘跨 Mount 时超过复制限额 → LIMIT_EXCEEDED，源保留、目标不写、状态不动。 */
    @Test
    @Timeout(60)
    fun `A03 a cross mount copy over the configured limit is refused and the source stays put`() =
        runBlocking {
            Files.writeString(leftRoot.resolve("big.txt"), "0123456789") // 10 字节
            withStack(limits = VfsLimits(defaultReadMaxBytes = 4, defaultWriteMaxBytes = 4)) { stack ->
                val written = stack.vfs.stat(uri("/local/big.txt")).id

                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/local/big.txt"), uri("/archive/deep/big.txt")) }

                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "超限由有界读取直接报")
                assertEquals("0123456789", Files.readString(leftRoot.resolve("big.txt")), "源在第一块盘上原样保留")
                assertFalse(Files.exists(rightRoot.resolve("deep/big.txt")), "目标盘上什么都没写")
                assertEquals(
                    emptyList<String>(),
                    Files.list(rightRoot).use { stream -> stream.map { it.fileName.toString() }.toList() },
                    "超限时目标盘上连缺失的父目录都没建——读取失败发生在补目录之前",
                )
                assertEquals(
                    written,
                    stack.nodes.findByPath(VfsPath.parse("/resources/local/big.txt"))?.id,
                    "逻辑路径仍指向源",
                )
                assertEquals(0, eventCount(), "失败不发成功事件")
            }
        }

    /** A04：目标写入失败 → 源保留，已补出来的父目录留在第二块盘上并报 PARTIAL。 */
    @Test
    @Timeout(60)
    fun `A04 a real target write failure keeps the source and leaves the created parents on the second disk`() =
        runBlocking {
            Files.writeString(leftRoot.resolve("note.txt"), "hello")
            withStack(wrapRight = { FailingWriteStorage(it) }) { stack ->
                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/local/note.txt"), uri("/archive/sub/note.txt")) }

                assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端原始错误码")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "archive/sub 这次真建在第二块盘上了，报 NONE 才是错的")
                assertTrue(Files.isDirectory(rightRoot.resolve("sub")), "补出的父目录留在真盘上，不自动回删")
                assertFalse(Files.exists(rightRoot.resolve("sub/note.txt")), "目标文件没写成")
                assertTrue(Files.exists(leftRoot.resolve("note.txt")), "源文件在第一块盘上原封不动")
                assertEquals(0, eventCount(), "失败不发移动事件")
            }
        }

    /** A04：确认时长度对不上（真盘被截短）→ CONFLICT，源保留、残留目标不自动删除。 */
    @Test
    @Timeout(60)
    fun `A04 a real length mismatch keeps the source and leaves the truncated target behind`() =
        runBlocking {
            Files.writeString(leftRoot.resolve("note.txt"), "hello")
            // 包一层存储：写到真盘上的是 3 字节而不是 5 字节，于是确认阶段长度对不上。
            withStack(wrapRight = { TruncatingWriteStorage(it, writtenSize = 3) }) { stack ->
                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/local/note.txt"), uri("/archive/note.txt")) }

                assertEquals(VfsErrorCode.CONFLICT, failure.code, "已知长度变化按 CONFLICT 报")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "目标已经写了，这是已知变更")
                assertEquals("hello", Files.readString(leftRoot.resolve("note.txt")), "源保留在第一块盘上")
                assertEquals(3, Files.size(rightRoot.resolve("note.txt")), "被截短的目标留在第二块盘上，不自动清理")
                assertEquals(0, eventCount(), "失败不发移动事件")
            }
        }

    /** A05：目标写成功但源删除失败 → 逻辑路径仍指向源、没有成功事件，两块盘上文件都在。 */
    @Test
    @Timeout(60)
    fun `A05 a real source delete failure after a good copy keeps the logical mapping at the source`() =
        runBlocking {
            Files.writeString(leftRoot.resolve("note.txt"), "hello")
            withStack(wrapLeft = { FailingSourceDeleteStorage(it) }) { stack ->
                val written = stack.vfs.stat(uri("/local/note.txt")).id

                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/local/note.txt"), uri("/archive/note.txt")) }

                assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "删源失败保留后端原始错误码")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "目标已经写出来了，这是已知变更")
                assertTrue(Files.exists(leftRoot.resolve("note.txt")), "源还在第一块盘上")
                assertEquals("hello", Files.readString(rightRoot.resolve("note.txt")), "目标也在第二块盘上")
                assertEquals(
                    written,
                    stack.nodes.findByPath(VfsPath.parse("/resources/local/note.txt"))?.id,
                    "逻辑路径仍指向源，不提交新映射",
                )
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/archive/note.txt")), "目标路径没有新记录")
                assertEquals(0, eventCount(), "删源失败没有成功事件")
            }
        }

    /** A05：真 SQLite 事务失败 → STATE_ERROR + PARTIAL，物理不回移、状态与事件一起回滚、没有成功通知。 */
    @Test
    @Timeout(60)
    fun `A05 a real sqlite append conflict after a real copy delete rolls the state back and keeps the bytes`() =
        runBlocking {
            // 测试装置：让本次事务的第一条事件用固定 ID，和库里已存在的那条撞主键——真实 SQLite 拒绝重复主键，
            // 回滚也是真实事务。两块盘上的复制与删除都是真的，回滚不了。
            val colliding = VfsEventId.parse("018f0a5c-1b2c-7def-8abc-0000000000f1")
            withStack(unitOfWork = { CopyCollidingEventUnitOfWork(it, colliding) }) { stack ->
                Files.writeString(leftRoot.resolve("note.txt"), "hello")
                // 预置不走 DefaultVfs：直接往真库里放文件行、Metadata 与一条已存在的 colliding 事件。
                val written = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000c1")
                val now = Instant.now()
                stack.nodes.register(
                    NodeRecord(
                        written,
                        VfsPath.parse("/resources/local/note.txt"),
                        com.github.noahshen.alcyone.context.vfs.NodeType.FILE,
                        true,
                        now,
                        now,
                    ),
                )
                stack.metadata.put(written, NodeMetadata(description = "keep"))
                stack.rawUnitOfWork.inTransaction { scope ->
                    scope.events.append(
                        EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/seed.txt")).copy(id = colliding),
                    )
                }
                val eventsBefore = eventCount()
                // 直接订阅核对「失败不通知」：预置事件走 rawUnitOfWork，不经过分发，所以收件箱基线是空的。
                val recorder = CopyMoveRecorder()
                stack.notifier.subscribe(recorder)

                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/local/note.txt"), uri("/archive/note.txt")) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(VfsEffect.PARTIAL, failure.effect, "源已删、目标已写，这是已知变更")
                assertNotNull(failure.cause, "底层原因保留在 cause 上")
                assertFalse(Files.exists(leftRoot.resolve("note.txt")), "物理删除不回退")
                assertEquals("hello", Files.readString(rightRoot.resolve("note.txt")), "目标内容保留")
                assertEquals(
                    written,
                    stack.nodes.findByPath(VfsPath.parse("/resources/local/note.txt"))?.id,
                    "Node 路径随事务回滚到源，不跟着物理走",
                )
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/archive/note.txt")))
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(written), "Metadata 不动")
                assertEquals(eventsBefore, eventCount(), "移动事件没进库")

                // 末尾哨兵按 ID 等：哨兵之前若真有成功通知，它一定已经先到。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(listOf(marker.id.value), recorder.received.map { it.id.value }, "失败的事务没有成功通知")
            }
        }

    /** A07：跨 Mount 复制时第二个变更被边界挡住，一次 Storage 都没碰。 */
    @Test
    @Timeout(60)
    fun `A07 a second cross mount move is held at the boundary before it touches either backend`() =
        runBlocking {
            withStack(wrapLeft = { PausingDeleteStorage(it, blockingSource = "a.txt") }) { stack ->
                val pausing = stack.storages.getValue("left") as PausingDeleteStorage
                val jobs = mutableListOf<Job>()
                try {
                    Files.writeString(leftRoot.resolve("a.txt"), "first")
                    Files.writeString(leftRoot.resolve("c.txt"), "second")

                    val first = async(Dispatchers.Default) { stack.vfs.move(uri("/local/a.txt"), uri("/archive/b.txt")) }
                    jobs += first
                    // 第一个移动已经进了 Storage.delete（源已写、已确认），正拿着边界。
                    pausing.awaitFirstDelete()
                    val backendCallsBeforeSecond = pausing.callLog().size

                    val second =
                        async(Dispatchers.Default, kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            runCatching { stack.vfs.move(uri("/local/c.txt"), uri("/archive/d.txt")) }.exceptionOrNull()
                        }
                    jobs += second
                    // UNDISPATCHED：第二个协程在当前线程直接开跑，直到第一个挂起点才把控制权交回来。
                    assertFalse(second.isCompleted, "第二个请求已经跑到第一个挂起点却直接跑完了——它没被边界挡住")
                    assertEquals(backendCallsBeforeSecond, pausing.callLog().size, "第二个请求一次 Storage 都没碰：${pausing.callLog()}")
                    assertEquals(1, pausing.pausedDeletes(), "此刻只有第一个移动还挂在闩锁上")

                    pausing.releaseDelete()

                    val firstInfo = withTimeout(30_000) { first.await() }
                    val secondFailure = withTimeout(30_000) { second.await() }

                    assertEquals(uri("/archive/b.txt"), firstInfo.uri, "第一个移动落到第二块盘")
                    assertTrue(secondFailure == null, "第二个移动随后正常完成：$secondFailure")
                    assertEquals("first", Files.readString(rightRoot.resolve("b.txt")))
                    assertEquals("second", Files.readString(rightRoot.resolve("d.txt")))
                    assertFalse(Files.exists(leftRoot.resolve("a.txt")), "第一个源真的删掉了")
                    assertFalse(Files.exists(leftRoot.resolve("c.txt")), "第二个源也真的删掉了")
                    assertEquals(listOf("FILE_MOVED", "FILE_MOVED"), eventTypes(), "两个移动各一条事件，互不交错")
                    assertEquals(1, pausing.totalPauses(), "只有第一个移动挂起过")
                    assertEquals(0, pausing.pausedDeletes(), "两个移动都跑完了，没有残留的挂起")
                } finally {
                    // 1. 放行清理门（幂等）；2. 取消 / 等待未完成的工作协程。
                    // withStack 的 finally 随后才关真库 / 真盘，顺序不会反。
                    pausing.releaseDelete()
                    jobs.forEach { it.cancelAndJoin() }
                }
            }
        }

    /** A07：进入复制流程后的受控取消原样传播、不删源、不提交，边界随后可放行。 */
    @Test
    @Timeout(60)
    fun `A07 a cross mount copy cancelled at the target write propagates and commits nothing`() =
        runBlocking {
            withStack(wrapRight = { PausingWriteStorage(it) }) { stack ->
                val pausing = stack.storages.getValue("right") as PausingWriteStorage
                val jobs = mutableListOf<Job>()
                try {
                    Files.writeString(leftRoot.resolve("note.txt"), "hello")
                    val written = stack.vfs.stat(uri("/local/note.txt")).id
                    val cancelled = CancellationException("cancelled while writing the copy target")

                    // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，
                    // 拿它当证据等于什么都没测，所以要留下移动链自己真正抛出来的那一个。
                    val escaped = CompletableDeferred<Throwable?>()
                    val job =
                        async(Dispatchers.Default) {
                            try {
                                stack.vfs.move(uri("/local/note.txt"), uri("/archive/note.txt"))
                                escaped.complete(null)
                            } catch (failure: Throwable) {
                                escaped.complete(failure)
                            }
                        }
                    jobs += job
                    // 复制流程已经走到写入目标这一步（读源、补父目录都真的发生了），取消就发生在这个挂起点上。
                    pausing.awaitFirstWrite()
                    job.cancel(cancelled)

                    withTimeout(30_000) { job.join() }
                    val escapedFailure = withTimeout(30_000) { escaped.await() }
                    assertNotNull(escapedFailure, "移动链应该把取消抛出来，而不是安静地结束")
                    assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                    assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                    assertEquals(0, pausing.pausedWrites(), "取消返回后移动链已经退出闩锁")
                    assertTrue(Files.exists(leftRoot.resolve("note.txt")), "取消后源还在第一块盘上")
                    assertFalse(Files.exists(rightRoot.resolve("note.txt")), "目标没有出现")
                    assertEquals(
                        written,
                        stack.nodes.findByPath(VfsPath.parse("/resources/local/note.txt"))?.id,
                        "取消不提交路径更新",
                    )
                    assertEquals(emptyList<String>(), eventTypes(), "stat 懒注册不发事件，取消也不发移动事件")

                    // 锁已放行：紧接着的移动能正常拿到同一把边界并完成。
                    pausing.releaseWrite()
                    val info = stack.vfs.move(uri("/local/note.txt"), uri("/archive/note.txt"))
                    assertEquals(written, info.id)
                    assertEquals("hello", Files.readString(rightRoot.resolve("note.txt")))
                    assertFalse(Files.exists(leftRoot.resolve("note.txt")))
                    assertEquals(2, pausing.totalPauses(), "取消那次 + 后续成功那次，都只挂在写入前")
                    assertEquals(0, pausing.pausedWrites(), "后续移动已经跑完，没有残留挂起")
                } finally {
                    // 取消用例同样保持失败路径可清理：先放门，再取消 / 等待未完成协程。
                    pausing.releaseWrite()
                    jobs.forEach { it.cancelAndJoin() }
                }
            }
        }

    /** 一次真实组装：Core 接线 + 真 SQLite + 两个真本地磁盘；用完就关。 */
    private suspend fun <T> withStack(
        limits: VfsLimits = VfsLimits(),
        wrapLeft: (Storage) -> Storage = { it },
        wrapRight: (Storage) -> Storage = { it },
        unitOfWork: (SqliteUnitOfWork) -> UnitOfWork = { it },
        block: suspend (TwoDiskStack) -> T,
    ): T {
        val state = VfsStateDatabase.file(databaseFile)
        val left = LocalFsStorage.create(leftRoot)
        val right = LocalFsStorage.create(rightRoot)
        try {
            val boundary = StateBoundary()
            val notifier = AsyncEventNotifier()
            val router =
                MountRouter.of(
                    setOf("resources"),
                    listOf(
                        MountRecord(VfsPath.parse("/resources/local"), "left"),
                        MountRecord(VfsPath.parse("/resources/archive"), "right"),
                    ),
                )
            val nodes = SqliteNodeRepository(state)
            val metadata = SqliteMetadataRepository(state)
            val wrappedLeft = wrapLeft(left)
            val wrappedRight = wrapRight(right)
            val byKey = mapOf("left" to wrappedLeft, "right" to wrappedRight)
            try {
                return block(
                    TwoDiskStack(
                        vfs =
                            DefaultVfs(
                                router = router,
                                capabilities =
                                    CapabilitySnapshot.of(
                                        byKey.mapValues { (_, storage) -> storage.capabilities() },
                                    ),
                                registry = NodeRegistry(router, nodes, { key -> byKey[key] }, boundary),
                                nodes = nodes,
                                metadata = metadata,
                                storages = { key -> byKey[key] },
                                boundary = boundary,
                                pipeline = EventPipeline(boundary, unitOfWork(SqliteUnitOfWork(state)), notifier),
                                limits = limits,
                            ),
                        nodes = nodes,
                        metadata = metadata,
                        notifier = notifier,
                        rawUnitOfWork = SqliteUnitOfWork(state),
                        storages = byKey,
                    ),
                )
            } finally {
                notifier.close()
            }
        } finally {
            state.close()
            left.close()
            right.close()
        }
    }

    private class TwoDiskStack(
        val vfs: DefaultVfs,
        val nodes: SqliteNodeRepository,
        val metadata: SqliteMetadataRepository,
        val notifier: AsyncEventNotifier,
        val rawUnitOfWork: SqliteUnitOfWork,
        val storages: Map<String, Storage>,
    )

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun eventTypes(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_type FROM event ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    private fun eventCount(): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM event").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun activeNodes(): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM node WHERE deleted_at IS NULL").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
}

/**
 * 包一层存储：目标盘写入直接报错（创建父目录、stat、delete 全部照常）。
 * 真文件系统不会恰好在写入那一刻坏掉，所以失败点由这层包装注入，断言仍然对着真盘与真库。
 */
private class FailingWriteStorage(
    private val delegate: Storage,
) : Storage by delegate {
    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode,
    ): StorageAttributes = throw VfsException(VfsErrorCode.STORAGE_ERROR, "the target disk refused the write")
}

/**
 * 包一层存储：写到真盘上的只有 [writtenSize] 字节（内容被截短），写入回执也如实报这个长度。
 * 于是确认阶段「实际复制 5 字节 vs 目标 3 字节」对不上，生产代码必须拒并且不删源。
 */
private class TruncatingWriteStorage(
    private val delegate: Storage,
    private val writtenSize: Int,
) : Storage by delegate {
    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode,
    ): StorageAttributes = delegate.write(path, content.copyOf(writtenSize), mode)
}

/**
 * 包一层存储：确认通过之后，源盘删除直接报错——造「目标完整但源删不掉」的受控反例。
 * 真实磁盘不会恰好在 delete 那一刻失败；其余调用（含确认阶段的读）全部照常走真后端。
 */
private class FailingSourceDeleteStorage(
    private val delegate: Storage,
) : Storage by delegate {
    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ): Unit = throw VfsException(VfsErrorCode.STORAGE_ERROR, "the source disk refused the delete")
}

/**
 * 包一层存储：指定源路径的 [Storage.delete] 在**真正删除之前**挂住，由测试放行；同时按顺序记下每次后端调用。
 *
 * 挂住的位置在确认之后、真正 unlink 之前，而 DefaultVfs 整条复制链都拿着共享边界——所以第一个移动此刻正占着边界。
 */
private class PausingDeleteStorage(
    private val delegate: Storage,
    private val blockingSource: String,
) : Storage by delegate {
    private val release = CompletableDeferred<Unit>()
    private val paused = AtomicInteger()
    private val pauses = AtomicInteger()
    private val arrivals = Channel<Unit>(Channel.UNLIMITED)
    private val calls = CopyOnWriteArrayList<String>()

    override suspend fun stat(path: StoragePath): StorageAttributes {
        calls += "stat:${path.toRelativeString()}"
        return delegate.stat(path)
    }

    override suspend fun read(
        path: StoragePath,
        maxBytes: Long,
    ): com.github.noahshen.alcyone.context.vfs.core.storage.StorageContent {
        calls += "read:${path.toRelativeString()}"
        return delegate.read(path, maxBytes)
    }

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        calls += "delete:${path.toRelativeString()}"
        if (path.toRelativeString() == blockingSource) {
            paused.incrementAndGet()
            pauses.incrementAndGet()
            arrivals.trySend(Unit)
            try {
                release.await()
            } catch (stopped: CancellationException) {
                release.complete(Unit)
                throw stopped
            } finally {
                paused.decrementAndGet()
            }
        }
        delegate.delete(path, recursive)
    }

    suspend fun awaitFirstDelete() {
        withTimeout(30_000) { arrivals.receive() }
    }

    fun pausedDeletes(): Int = paused.get()

    /** 一共挂起过几次（不管当时被放行还是被取消）。 */
    fun totalPauses(): Int = pauses.get()

    fun releaseDelete() {
        release.complete(Unit)
    }

    fun callLog(): List<String> = calls.toList()
}

/**
 * 包一层存储：目标盘 [Storage.write] 在**真正落盘之前**挂住，由测试放行或取消——T21 选定的受控取消点。
 * 挂住的位置是「读源与补父目录都完成之后」，所以取消时能同时核对已发生与未发生的复制事实。
 */
private class PausingWriteStorage(
    private val delegate: Storage,
) : Storage by delegate {
    private val release = CompletableDeferred<Unit>()
    private val arrivals = Channel<Unit>(Channel.UNLIMITED)
    private val paused = AtomicInteger()
    private val pauses = AtomicInteger()

    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode,
    ): StorageAttributes {
        paused.incrementAndGet()
        pauses.incrementAndGet()
        arrivals.trySend(Unit)
        try {
            release.await()
        } catch (stopped: CancellationException) {
            release.complete(Unit)
            throw stopped
        } finally {
            paused.decrementAndGet()
        }
        return delegate.write(path, content, mode)
    }

    suspend fun awaitFirstWrite() {
        withTimeout(30_000) { arrivals.receive() }
    }

    fun pausedWrites(): Int = paused.get()

    fun totalPauses(): Int = pauses.get()

    fun releaseWrite() {
        release.complete(Unit)
    }
}

/**
 * 按事件 ID 等待的记录器：直接订阅核对「失败不发成功通知」。
 *
 * 每个 ID 各等各的信号：同一条 FIFO 链上先等的那次会把信号消费掉，第二次 await 会立刻返回。
 */
private class CopyMoveRecorder : VfsEventConsumer {
    val received = CopyOnWriteArrayList<VfsEvent>()
    private val arrived = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override suspend fun onEvent(event: VfsEvent) {
        received.add(event)
        arrived.computeIfAbsent(event.id.value) { CompletableDeferred() }.complete(Unit)
    }

    suspend fun await(id: VfsEventId): List<VfsEvent> {
        withTimeout(30_000) { arrived.computeIfAbsent(id.value) { CompletableDeferred() }.await() }
        return received.toList()
    }
}

/**
 * 包一层事务：本次事务里**第一条**事件换成固定 ID，于是它会和库里已存在的那条撞主键。
 * 事务、约束、索引、回滚全都是真的；被改的只有事件 ID 这一个字段。
 */
private class CopyCollidingEventUnitOfWork(
    private val delegate: SqliteUnitOfWork,
    private val colliding: VfsEventId,
) : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T =
        delegate.inTransaction { scope ->
            val decorated =
                object : TransactionScope {
                    override val nodes get() = scope.nodes

                    override val metadata get() = scope.metadata

                    override val events: EventRepository =
                        object : EventRepository {
                            private var first = true

                            override suspend fun append(event: EventRecord) {
                                if (first) {
                                    first = false
                                    scope.events.append(event.copy(id = colliding))
                                } else {
                                    scope.events.append(event)
                                }
                            }
                        }
                }
            block(decorated)
        }
}
