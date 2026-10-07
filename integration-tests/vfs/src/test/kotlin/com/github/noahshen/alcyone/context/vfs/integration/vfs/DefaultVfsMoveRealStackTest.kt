package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.DefaultVfs
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
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
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
import kotlinx.coroutines.CoroutineStart
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
import org.junit.jupiter.api.Assertions.assertSame
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
 * T20 S3：[DefaultVfs] 装上**真的 SQLite 状态库**和**真的本地磁盘**跑同 Mount 文件移动。
 *
 * 替身证明不了的三件事在这里证明：磁盘上文件真的从旧位置挪到新位置、补出的父目录是真的、
 * 物理移动成功但状态 / 事件提交失败时 Node 与 Metadata 真的随事务回滚。
 *
 * 精确失败点用包装 Storage / 包装事务注入（真文件系统不会自己停在**一半**），断言仍然对着真盘与真库。
 * 公开 Runtime 的成功闭环 / 重开 / 事件字段证据在 `RuntimeMoveTest`，两类证据分开。
 */
class DefaultVfsMoveRealStackTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var diskRoot: Path
    private lateinit var databaseFile: Path

    @BeforeEach
    fun setUp() {
        diskRoot = Files.createDirectory(tempDir.resolve("disk"))
        databaseFile = tempDir.resolve("state.db")
    }

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    @Test
    @Timeout(60)
    fun `A01 the real disk moves a file to another subdirectory and creates the parents`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("note.txt"), "hello")
            val digestBefore = sha256(diskRoot.resolve("note.txt"))
            withStack { stack ->
                val written = stack.vfs.stat(uri("/note.txt")).id

                val moved = stack.vfs.move(uri("/note.txt"), uri("/archive/sub/note.txt"))

                assertEquals(written, moved.id, "已登记源复用原 ID")
                assertFalse(Files.exists(diskRoot.resolve("note.txt")), "旧物理位置真的没了")
                assertEquals(digestBefore, sha256(diskRoot.resolve("archive/sub/note.txt")), "内容逐字节一致")
                assertEquals(written, stack.nodes.findByPath(VfsPath.parse("/resources/archive/sub/note.txt"))?.id)
                assertEquals(1, activeNodes(), "只有被移动的文件有记录，父目录不登记")
                assertEquals(listOf("FILE_MOVED"), eventTypes(), "stat 懒注册不发事件，只有一条 FILE_MOVED")
            }
        }

    @Test
    @Timeout(60)
    fun `A04 a parent creation that fails halfway says PARTIAL and leaves the created directory`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("note.txt"), "hello")
            // 包一层存储：第 2 次 createDirectory（也就是第二层 archive/sub 里的 sub）直接报错，第一层是真的建出来的。
            withStack(wrapStorage = { FailingDirectoryStorage(it, failAt = 2) }) { stack ->
                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/note.txt"), uri("/a/b/note.txt")) }

                assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端原始错误码")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "第一层目录真的建在盘上，报 NONE 才是错的")
                assertTrue(Files.isDirectory(diskRoot.resolve("a")), "第一层目录留在真盘上，不自动回删")
                assertFalse(Files.exists(diskRoot.resolve("a/b")), "第二层确实没建成")
                assertTrue(Files.exists(diskRoot.resolve("note.txt")), "源文件一个字节都没动")
                assertEquals(0, eventCount(), "失败不发移动事件")
            }
        }

    @Test
    @Timeout(60)
    fun `A06 a real sqlite append conflict after a real move rolls the path back and keeps the bytes`() =
        runBlocking {
            // 测试装置：让本次事务的第一条事件用固定 ID，和库里已存在的那条撞主键——真实 SQLite 拒绝重复主键，
            // 回滚也是真实事务。磁盘上的移动是真的，回滚不了。
            val colliding = VfsEventId.parse("018f0a5c-1b2c-7def-8abc-0000000000f1")
            withStack(unitOfWork = { MoveCollidingEventUnitOfWork(it, colliding) }) { stack ->
                // 预置不走 DefaultVfs：这个包装器会把每个事务的第一条事件换成 colliding。
                // 直接往真库里放文件行、Metadata 与一条已存在的 colliding 事件，再让待测移动去撞它。
                Files.writeString(diskRoot.resolve("note.txt"), "hello")
                val written = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000a8")
                val now = Instant.now()
                stack.nodes.register(NodeRecord(written, VfsPath.parse("/resources/note.txt"), NodeType.FILE, true, now, now))
                stack.metadata.put(written, NodeMetadata(description = "keep"))
                stack.rawUnitOfWork.inTransaction { scope ->
                    scope.events.append(
                        EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/seed.txt")).copy(id = colliding),
                    )
                }
                val eventsBefore = eventCount()
                // 直接订阅核对「失败不通知」：预置事件走 rawUnitOfWork，不经过分发，所以收件箱基线是空的。
                val recorder = MoveRecorder()
                stack.notifier.subscribe(recorder)

                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/note.txt"), uri("/b.txt")) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(VfsEffect.PARTIAL, failure.effect, "文件真的搬到目标了，这是已知变更")
                assertNotNull(failure.cause, "底层原因保留在 cause 上")
                assertFalse(Files.exists(diskRoot.resolve("note.txt")), "物理移动不回移")
                assertEquals("hello", Files.readString(diskRoot.resolve("b.txt")), "目标内容保留")
                assertEquals(written, stack.nodes.findByPath(VfsPath.parse("/resources/note.txt"))?.id, "Node 路径随事务回滚到源，不回移文件")
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(written), "Metadata 不动")
                assertEquals(eventsBefore, eventCount(), "移动事件没进库")

                // 末尾哨兵按 ID 等：哨兵之前若真有成功通知，它一定已经先到。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(listOf(marker.id.value), recorder.received.map { it.id.value }, "失败的事务没有成功通知")
            }
        }

    @Test
    @Timeout(60)
    fun `R1 a source record missing from the committing transaction fails instead of recreating identity`() =
        runBlocking {
            // 受控反例（复核 R1）：事务视图里查不到已登记的源记录。生产代码必须报状态异常，
            // 而不是补一条新身份、重建 registeredAt 后继续成功。
            val hidden = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000b2")
            withStack(unitOfWork = { HidingNodeUnitOfWork(it, hidden) }) { stack ->
                Files.writeString(diskRoot.resolve("note.txt"), "hello")
                val now = Instant.parse("2026-10-07T00:00:00Z")
                stack.nodes.register(NodeRecord(hidden, VfsPath.parse("/resources/note.txt"), NodeType.FILE, true, now, now))
                stack.metadata.put(hidden, NodeMetadata(description = "keep"))

                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/note.txt"), uri("/b.txt")) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code, "提交视图缺源记录 → 状态异常")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "物理移动已经发生，这是已知变更")
                assertNotNull(failure.cause, "原始失败保留在 cause 上")
                assertFalse(Files.exists(diskRoot.resolve("note.txt")), "物理移动不回移")
                assertEquals("hello", Files.readString(diskRoot.resolve("b.txt")), "目标内容保留")
                // 没有静默重建身份：源路径上仍是原记录，ID / registeredAt 未变；目标路径没有新记录。
                val source = stack.nodes.findByPath(VfsPath.parse("/resources/note.txt"))
                assertNotNull(source)
                assertEquals(hidden, source!!.id, "源记录没有被换成新身份")
                assertEquals(now, source.registeredAt, "registeredAt 没有被重建")
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/b.txt")), "没有在目标路径新建身份")
                assertEquals(0, eventCount(), "失败不发事件")
            }
        }

    @Test
    @Timeout(60)
    fun `A07 a second move is held at the boundary before it touches the backend`() =
        runBlocking {
            withStack(wrapStorage = { PausingMoveStorage(it, blockingSource = "a.txt") }) { stack ->
                val pausing = stack.storages.getValue("local") as PausingMoveStorage
                val jobs = mutableListOf<Job>()
                try {
                    Files.writeString(diskRoot.resolve("a.txt"), "first")
                    Files.writeString(diskRoot.resolve("c.txt"), "second")

                    val first = async(Dispatchers.Default) { stack.vfs.move(uri("/a.txt"), uri("/b.txt")) }
                    jobs += first
                    pausing.awaitFirstMove() // 第一个移动已经进了 Storage.move，正拿着边界
                    val backendCallsBeforeSecond = pausing.callLog().size

                    val second =
                        async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            runCatching { stack.vfs.move(uri("/c.txt"), uri("/d.txt")) }.exceptionOrNull()
                        }
                    jobs += second
                    // UNDISPATCHED：第二个协程在当前线程直接开跑，直到第一个挂起点才把控制权交回来。
                    assertFalse(second.isCompleted, "第二个请求已经跑到第一个挂起点却直接跑完了——它没被边界挡住")
                    assertEquals(backendCallsBeforeSecond, pausing.callLog().size, "第二个请求一次 Storage 都没碰：${pausing.callLog()}")
                    assertEquals(1, pausing.pausedMoves(), "此刻只有第一个移动还挂在闩锁上")

                    pausing.releaseMove()

                    val firstInfo = withTimeout(30_000) { first.await() }
                    val secondFailure = withTimeout(30_000) { second.await() }

                    assertEquals(uri("/b.txt"), firstInfo.uri, "第一个移动落到 b.txt")
                    assertTrue(secondFailure == null, "第二个移动随后正常完成：$secondFailure")
                    assertEquals("first", Files.readString(diskRoot.resolve("b.txt")))
                    assertEquals("second", Files.readString(diskRoot.resolve("d.txt")))
                    assertEquals(listOf("FILE_MOVED", "FILE_MOVED"), eventTypes(), "两个移动各一条事件，互不交错")
                    assertEquals(1, pausing.totalPauses(), "只有第一个移动挂起过")
                    assertEquals(0, pausing.pausedMoves(), "两个移动都跑完了，没有残留的挂起")
                } finally {
                    // 1. 放行清理门（幂等）；2. 取消 / 等待未完成的工作协程。
                    // withStack 的 finally 随后才关真库 / 真盘，顺序不会反（复核 R3）。
                    pausing.releaseMove()
                    jobs.forEach { it.cancelAndJoin() }
                }
            }
        }

    @Test
    @Timeout(60)
    fun `A07 a move cancelled before the physical move propagates, releases the lock and commits nothing`() =
        runBlocking {
            withStack(wrapStorage = { PausingMoveStorage(it, blockingSource = "a.txt") }) { stack ->
                val pausing = stack.storages.getValue("local") as PausingMoveStorage
                val jobs = mutableListOf<Job>()
                try {
                    Files.writeString(diskRoot.resolve("a.txt"), "hello")
                    val written = stack.vfs.stat(uri("/a.txt")).id
                    val cancelled = CancellationException("cancelled while moving")

                    // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，
                    // 拿它当证据等于什么都没测，所以要留下移动链自己真正抛出来的那一个。
                    val escaped = CompletableDeferred<Throwable?>()
                    val job =
                        async(Dispatchers.Default) {
                            try {
                                stack.vfs.move(uri("/a.txt"), uri("/b.txt"))
                                escaped.complete(null)
                            } catch (failure: Throwable) {
                                escaped.complete(failure)
                            }
                        }
                    jobs += job
                    pausing.awaitFirstMove() // 移动链已经走到 Storage.move，取消就发生在这个挂起点上
                    job.cancel(cancelled)

                    withTimeout(30_000) { job.join() }
                    val escapedFailure = withTimeout(30_000) { escaped.await() }
                    assertNotNull(escapedFailure, "移动链应该把取消抛出来，而不是安静地结束")
                    assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                    assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                    assertSame(cancelled, escapedFailure?.cause ?: escapedFailure, "取消原因一致（协程栈帧恢复可能复制异常，但原实例在 cause 上）")
                    assertEquals(0, pausing.pausedMoves(), "取消返回后移动链已经退出闩锁")
                    assertTrue(Files.exists(diskRoot.resolve("a.txt")), "取消发生在真正移动之前，源还在")
                    assertFalse(Files.exists(diskRoot.resolve("b.txt")), "目标没有出现")
                    assertEquals(written, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "取消不提交路径更新")
                    assertEquals(emptyList<String>(), eventTypes(), "stat 懒注册不发事件，取消也不发移动事件")

                    // 锁已放行：紧接着的移动能正常拿到同一把边界并完成。
                    pausing.releaseMove()
                    val info = stack.vfs.move(uri("/a.txt"), uri("/b.txt"))
                    assertEquals(written, info.id)
                    assertEquals("hello", Files.readString(diskRoot.resolve("b.txt")))
                    assertEquals(2, pausing.totalPauses(), "取消那次 + 后续成功那次，都只挂在物理移动前")
                    assertEquals(0, pausing.pausedMoves(), "后续移动已经跑完，没有残留挂起")
                } finally {
                    // 取消用例同样保持失败路径可清理：先放门，再取消 / 等待未完成协程。
                    pausing.releaseMove()
                    jobs.forEach { it.cancelAndJoin() }
                }
            }
        }

    /** 一次真实组装：Core 接线 + 真 SQLite + 真本地磁盘；用完就关。 */
    private suspend fun <T> withStack(
        wrapStorage: (Storage) -> Storage = { it },
        unitOfWork: (SqliteUnitOfWork) -> UnitOfWork = { it },
        block: suspend (RealStack) -> T,
    ): T {
        val state = VfsStateDatabase.file(databaseFile)
        val local = LocalFsStorage.create(diskRoot)
        try {
            val boundary = StateBoundary()
            val notifier = AsyncEventNotifier()
            val router = MountRouter.of(setOf("resources"), listOf(MountRecord(VfsPath.parse("/resources"), "local")))
            val nodes = SqliteNodeRepository(state)
            val metadata = SqliteMetadataRepository(state)
            val wrapped = wrapStorage(local)
            try {
                return block(
                    RealStack(
                        vfs =
                            DefaultVfs(
                                router = router,
                                capabilities = CapabilitySnapshot.of(mapOf("local" to wrapped.capabilities())),
                                registry = NodeRegistry(router, nodes, { key -> if (key == "local") wrapped else null }, boundary),
                                nodes = nodes,
                                metadata = metadata,
                                storages = { key -> if (key == "local") wrapped else null },
                                boundary = boundary,
                                pipeline = EventPipeline(boundary, unitOfWork(SqliteUnitOfWork(state)), notifier),
                            ),
                        nodes = nodes,
                        metadata = metadata,
                        notifier = notifier,
                        rawUnitOfWork = SqliteUnitOfWork(state),
                        storages = mapOf("local" to wrapped),
                    ),
                )
            } finally {
                notifier.close()
            }
        } finally {
            state.close()
            local.close()
        }
    }

    private class RealStack(
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
 * 包一层存储：本次调用里第 [failAt] 次 [Storage.createDirectory] 直接报错，其余都真的建到盘上。
 * 移动 `/note.txt → /a/b/note.txt` 会先建 `a`、再建 `a/b`，所以 [failAt] = 2 造出的正是「第一层建成了、第二层失败」。
 */
private class FailingDirectoryStorage(
    private val delegate: Storage,
    private val failAt: Int,
) : Storage by delegate {
    private var seen = 0

    override suspend fun createDirectory(path: StoragePath) {
        if (++seen == failAt) throw VfsException(VfsErrorCode.STORAGE_ERROR, "cannot create the second directory")
        delegate.createDirectory(path)
    }
}

/**
 * 包一层存储：指定源路径的 [Storage.move] 在**真正移动之前**挂住，由测试放行；同时按顺序记下每次后端调用。
 *
 * 挂住的位置在真正 rename 之前，而 DefaultVfs 整条移动链都拿着共享边界——所以第一个移动此刻正占着边界。
 */
private class PausingMoveStorage(
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

    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes {
        calls += "move:${source.toRelativeString()}->${target.toRelativeString()}"
        if (source.toRelativeString() == blockingSource) {
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
        return delegate.move(source, target)
    }

    suspend fun awaitFirstMove() {
        withTimeout(30_000) { arrivals.receive() }
    }

    fun pausedMoves(): Int = paused.get()

    /** 一共挂起过几次（不管当时被放行还是被取消）。 */
    fun totalPauses(): Int = pauses.get()

    fun releaseMove() {
        release.complete(Unit)
    }

    fun callLog(): List<String> = calls.toList()
}

/**
 * 按事件 ID 等待的记录器：直接订阅核对「失败不发成功通知」。
 *
 * 每个 ID 各等各的信号：同一条 FIFO 链上先等的那次会把信号消费掉，第二次 await 会立刻返回。
 */
private class MoveRecorder : VfsEventConsumer {
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
 * 包一层事务：把指定 ID 从事务视图的 [NodeRepository.findById] 里藏起来，其余原样转发。
 *
 * 用来造「已登记的源在提交阶段查不到」的受控反例（复核 R1）：真实 SQLite 与自动提交仓库都正常，
 * 只有这次提交视图看不到它，从而验证生产代码不会静默重建身份。
 */
private class HidingNodeUnitOfWork(
    private val delegate: SqliteUnitOfWork,
    private val hidden: NodeId,
) : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T =
        delegate.inTransaction { scope ->
            val decorated =
                object : TransactionScope {
                    override val nodes: NodeRepository =
                        object : NodeRepository by scope.nodes {
                            override suspend fun findById(id: NodeId): NodeRecord? = if (id == hidden) null else scope.nodes.findById(id)
                        }

                    override val metadata get() = scope.metadata

                    override val events get() = scope.events
                }
            block(decorated)
        }
}

/**
 * 包一层事务：本次事务里**第一条**事件换成固定 ID，于是它会和库里已存在的那条撞主键。
 * 事务、约束、索引、回滚全都是真的；被改的只有事件 ID 这一个字段。
 */
private class MoveCollidingEventUnitOfWork(
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
