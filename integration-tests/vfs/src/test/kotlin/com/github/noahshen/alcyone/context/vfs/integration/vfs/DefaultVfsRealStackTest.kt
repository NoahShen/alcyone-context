package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsEntry
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
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
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * T15 S3：[DefaultVfs] 装上**真的 SQLite 状态库**和**真的本地磁盘**跑一遍。
 *
 * 替身证明不了的三件事在这里证明：磁盘内容真的变了、Node 与事件真的同批提交与回滚、
 * 共享边界真的让两个写排成一条队。
 *
 * 时序用闩锁控制，不用 `Thread.sleep`；并发用例都有 [Timeout] 兜底。
 */
class DefaultVfsRealStackTest {
    @TempDir
    lateinit var tempDir: Path

    /** 模拟挂载根（父盘）。 */
    private lateinit var diskRoot: Path

    /** 状态库文件；关掉重开才能证明「提交过的都还在」。 */
    private lateinit var databaseFile: Path

    @BeforeEach
    fun setUp() {
        diskRoot = Files.createDirectory(tempDir.resolve("disk"))
        databaseFile = tempDir.resolve("state.db")
    }

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    @Test
    @Timeout(60)
    fun `A01 an unregistered file reads fine and only stat gives it an identity`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                assertEquals("hello", stack.vfs.read(uri("/a.txt")).toString(Charsets.UTF_8), "没登记也读得到")
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/a.txt")), "读不登记")
                assertEquals(0, countRows("event"), "读不发事件")

                val unknownId = assertFailsWith<VfsException> { stack.vfs.getNode(randomNodeId()) }
                assertEquals(VfsErrorCode.NOT_FOUND, unknownId.code, "getNode 不会凭空找到一个没登记的文件")

                val first = stack.vfs.stat(uri("/a.txt"))
                val again = stack.vfs.stat(uri("/a.txt"))
                val logical = stack.vfs.stat(uri("/a.txt"), StatOptions(includeStorage = false))

                assertEquals(first.id, again.id, "同一个文件始终同一个身份")
                assertEquals(first.id, logical.id)
                assertNull(logical.storage, "includeStorage=false 是纯逻辑查询")
                assertEquals(5L, first.storage?.sizeBytes, "stat 默认问后端要属性")
                assertEquals(first.id, stack.vfs.getNode(first.id).id)
            }
        }

    @Test
    @Timeout(60)
    fun `A02 creating and overwriting keeps one identity and the event follows real existence`() =
        runBlocking {
            withStack { stack ->
                val created = stack.vfs.write(uri("/notes/a.txt"), "first".toByteArray())

                assertEquals("first", Files.readString(diskRoot.resolve("notes/a.txt")), "父目录和内容都真的在磁盘上")
                assertEquals(listOf("FILE_CREATED"), eventTypes(), "第一次写是 FILE_CREATED")
                assertEquals(created.id, stack.nodes.findByPath(VfsPath.parse("/resources/notes/a.txt"))?.id)

                val overwritten = stack.vfs.write(uri("/notes/a.txt"), "second".toByteArray())

                assertEquals(created.id, overwritten.id, "覆盖保留同一个 Node ID")
                assertEquals(created.registeredAt, overwritten.registeredAt, "登记时间不变")
                assertEquals("second", Files.readString(diskRoot.resolve("notes/a.txt")))
                assertEquals(listOf("FILE_CREATED", "FILE_WRITTEN"), eventTypes())
                assertEquals(1, activeNodes(), "覆盖不产生第二条记录")
            }
        }

    @Test
    @Timeout(60)
    fun `A02 overwriting a file that bypassed VFS says FILE_WRITTEN and still gets an identity`() =
        runBlocking {
            // 先绕过 VFS 在磁盘上放一个文件：它没有身份，内容却真的在。
            Files.writeString(diskRoot.resolve("a.txt"), "outside")
            withStack { stack ->
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/a.txt")))

                val info = stack.vfs.write(uri("/a.txt"), "inside".toByteArray(), WriteOptions(WriteMode.UPSERT))

                assertEquals(listOf("FILE_WRITTEN"), eventTypes(), "文件本来就存在，不是创建")
                assertEquals(info.id, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "写成功后建立身份")
                assertEquals("inside", Files.readString(diskRoot.resolve("a.txt")))
            }
        }

    @Test
    @Timeout(60)
    fun `A03 missing parents are really created and never registered as nodes`() =
        runBlocking {
            withStack { stack ->
                stack.vfs.write(uri("/a/b/c.txt"), "deep".toByteArray())

                assertTrue(Files.isDirectory(diskRoot.resolve("a/b")), "多层父目录真的建出来了")
                assertEquals("deep", Files.readString(diskRoot.resolve("a/b/c.txt")))
                assertEquals(listOf("/resources/a/b/c.txt"), stack.nodes.findSubtree(VfsPath.root).map { it.path.toString() }, "父目录不批量登记")
                assertEquals(1, eventCount(), "补目录不发单独的事件")

                val replaced =
                    assertFailsWith<VfsException> {
                        stack.vfs.write(uri("/x/y.txt"), "nope".toByteArray(), WriteOptions(WriteMode.REPLACE_EXISTING))
                    }

                assertEquals(VfsErrorCode.NOT_FOUND, replaced.code)
                assertFalse(Files.exists(diskRoot.resolve("x")), "REPLACE_EXISTING 目标不存在时不留下父目录")
                assertEquals(1, activeNodes(), "被拒的写没有多出 Node")
            }
        }

    @Test
    @Timeout(60)
    fun `A04 the logical root and a deep virtual ancestor list config entries without a backend`() =
        runBlocking {
            val childRoot = Files.createDirectory(tempDir.resolve("ct-disk"))
            withStack(
                mounts = listOf(MountRecord(VfsPath.parse("/resources/medical/ct"), "ct")),
                extraStorages = mapOf("ct" to LocalFsStorage.create(childRoot)),
            ) { stack ->
                assertEquals(listOf("/resources" to NodeType.DIRECTORY), stack.vfs.list(VfsUri.parse("alcyone://")).pairs())
                assertEquals(listOf("/resources/medical" to NodeType.DIRECTORY), stack.vfs.list(uri("")).pairs())
                val medical = stack.vfs.list(uri("/medical"))
                assertEquals(listOf("/resources/medical/ct" to NodeType.DIRECTORY), medical.pairs())
                assertNull(medical.single().storage, "纯虚拟目录条目没有磁盘属性")
                assertNull(medical.single().nodeId, "不因为列了一次就登记")
                assertEquals(0, activeNodes(), "查询后 Node 数不增长")
            }
        }

    @Test
    @Timeout(60)
    fun `A04 a real directory merges physical children and a nested mount shadows the same name`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("note.md"), "hi")
            Files.createDirectory(diskRoot.resolve("ct"))
            Files.writeString(diskRoot.resolve("ct/a.dcm"), "parent copy")
            val childRoot = Files.createDirectory(tempDir.resolve("ct-disk"))
            Files.writeString(childRoot.resolve("a.dcm"), "child copy")
            withStack(
                mounts =
                    listOf(
                        MountRecord(VfsPath.parse("/resources"), "local"),
                        MountRecord(VfsPath.parse("/resources/ct"), "ct"),
                    ),
                extraStorages = mapOf("ct" to LocalFsStorage.create(childRoot)),
            ) { stack ->
                stack.vfs.write(uri("/note.md"), "hi".toByteArray()) // 给它一个身份，验证列表补 ID

                val entries = stack.vfs.list(uri(""))

                assertEquals(
                    listOf("/resources/ct" to NodeType.DIRECTORY, "/resources/note.md" to NodeType.FILE),
                    entries.pairs(),
                    "同名时配置入口优先，父盘那个 ct 目录不露出来",
                )
                val ct = entries.first { it.uri.path.toString() == "/resources/ct" }
                assertNull(ct.storage, "配置入口没有磁盘属性")
                val note = entries.first { it.uri.path.toString() == "/resources/note.md" }
                assertNotNull(note.nodeId, "已登记的条目批量补上了 ID")
                assertEquals(2L, note.storage?.sizeBytes)
                assertEquals(
                    listOf("/resources/ct/a.dcm" to NodeType.FILE),
                    stack.vfs.list(uri("/ct")).pairs(),
                    "进子挂载看的是子盘",
                )
                assertEquals(1, activeNodes(), "列目录不登记新 Node")
            }
        }

    @Test
    @Timeout(60)
    fun `A04 a closed child backend does not hide its entry from the parent listing`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("note.md"), "hi")
            val childRoot = Files.createDirectory(tempDir.resolve("ct-disk"))
            withStack(
                mounts =
                    listOf(
                        MountRecord(VfsPath.parse("/resources"), "local"),
                        MountRecord(VfsPath.parse("/resources/ct"), "ct"),
                    ),
                extraStorages = mapOf("ct" to LocalFsStorage.create(childRoot).also { it.close() }),
            ) { stack ->
                val entries = stack.vfs.list(uri(""))

                assertEquals(
                    listOf("/resources/ct" to NodeType.DIRECTORY, "/resources/note.md" to NodeType.FILE),
                    entries.pairs(),
                    "父列表不连接子后端",
                )
                val inside = assertFailsWith<VfsException> { stack.vfs.list(uri("/ct")) }
                assertEquals(VfsErrorCode.CLOSED, inside.code, "真要进去列就报真实错误")
            }
        }

    @Test
    @Timeout(60)
    fun `A05 a file that was written but whose event commit failed keeps the bytes and rolls the state back`() =
        runBlocking {
            // 测试装置：让本次事务里的第一条事件用固定 ID，和库里已存在的那条撞主键——
            // 真实 SQLite 拒绝重复主键，回滚也是真实事务，不是假驱动造的。
            val colliding = VfsEventId.parse("018f0a5c-1b2c-7def-8abc-0000000000e5")
            withStack(unitOfWork = { CollidingEventUnitOfWork(it, colliding) }) { stack ->
                stack.rawUnitOfWork.inTransaction { scope ->
                    scope.events.append(
                        EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/seed.txt")).copy(id = colliding),
                    )
                }
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)

                val failure = assertFailsWith<VfsException> { stack.vfs.write(uri("/a.txt"), "written".toByteArray()) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(VfsEffect.PARTIAL, failure.effect, "文件真的写进去了，这是已知变更")
                assertEquals("written", Files.readString(diskRoot.resolve("a.txt")), "物理内容保留，不自动补偿")
                assertEquals(0, activeNodes(), "同批的 Node 一起回滚")
                assertEquals(1, eventCount(), "库里只有预置那一条事件")

                val tail = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(tail.toVfsEvent()))
                recorder.await(tail.id)
                assertEquals(listOf(tail.id.value), recorder.received.map { it.id.value }, "没有成功通知")
            }
        }

    @Test
    @Timeout(60)
    fun `A06 two writes of one path are serialized by the shared boundary`() =
        runBlocking {
            withStack(wrapStorage = { PausingStorage(it) }) { stack ->
                val pausing = stack.storages.getValue("local") as PausingStorage
                val secondStarted = CompletableDeferred<Unit>()

                val first =
                    async(Dispatchers.Default) {
                        stack.vfs.write(uri("/a.txt"), "first".toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
                    }
                pausing.awaitFirstWrite() // 第一个写已经进了 Storage I/O，正拿着边界
                val second =
                    async(Dispatchers.Default) {
                        secondStarted.complete(Unit)
                        runCatching { stack.vfs.write(uri("/a.txt"), "second".toByteArray(), WriteOptions(WriteMode.CREATE_NEW)) }
                            .exceptionOrNull()
                    }
                secondStarted.await()
                try {
                    // 竞态判据：第二个写拿不到边界，所以在第一个放开之前它不可能完成。
                    // 判据是「提前返回了」而不是「此刻还没完成」——后者会把「协程还没跑到」误当成互斥成立。
                    val leaked = withTimeoutOrNull(2_000) { second.await() }
                    assertNull(leaked, "第一个写还在闩锁上，第二个写就已经完成——共享边界没有生效")
                } finally {
                    pausing.releaseFirstWrite()
                }

                val info = withTimeout(30_000) { first.await() }
                val failure = withTimeout(30_000) { second.await() }

                assertEquals(VfsErrorCode.ALREADY_EXISTS, (failure as? VfsException)?.code, "后到的那个看到的是已存在")
                assertEquals("first", Files.readString(diskRoot.resolve("a.txt")), "第二个写没有做物理变更")
                assertEquals(1, pausing.writeCalls.get(), "Storage.write 只被调过一次")
                assertEquals(info.id, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id)
                assertEquals(1, activeNodes())
            }
        }

    /** 一次真实组装：Core 接线 + 真 SQLite + 真本地磁盘；用完就关，必要时还能重开验证持久性。 */
    private suspend fun <T> withStack(
        mounts: List<MountRecord> = listOf(MountRecord(VfsPath.parse("/resources"), "local")),
        extraStorages: Map<String, LocalFsStorage> = emptyMap(),
        /** 包一层存储：用 PausingStorage 控制第一个写什么时候落盘（A06）。 */
        wrapStorage: (Storage) -> Storage = { it },
        /** 包一层事务：让本次事务的第一条事件撞上库里的主键（A05）。 */
        unitOfWork: (SqliteUnitOfWork) -> UnitOfWork = { it },
        block: suspend (RealStack) -> T,
    ): T {
        val state = VfsStateDatabase.file(databaseFile)
        val opened = mutableListOf<LocalFsStorage>()
        val storages = LinkedHashMap<String, Storage>()
        try {
            val local = LocalFsStorage.create(diskRoot)
            opened += local
            storages["local"] = wrapStorage(local)
            extraStorages.forEach { (key, storage) ->
                opened += storage
                storages[key] = storage
            }
            val boundary = StateBoundary()
            val notifier = AsyncEventNotifier()
            val router = MountRouter.of(setOf("resources"), mounts)
            val nodes = SqliteNodeRepository(state)
            try {
                return block(
                    RealStack(
                        vfs =
                            DefaultVfs(
                                router = router,
                                capabilities = CapabilitySnapshot.of(storages.mapValues { it.value.capabilities() }),
                                registry = NodeRegistry(router, nodes, { key -> storages[key] }, boundary),
                                nodes = nodes,
                                storages = { key -> storages[key] },
                                boundary = boundary,
                                pipeline = EventPipeline(boundary, unitOfWork(SqliteUnitOfWork(state)), notifier),
                                limits = VfsLimits(),
                            ),
                        nodes = nodes,
                        metadata = SqliteMetadataRepository(state),
                        notifier = notifier,
                        rawUnitOfWork = SqliteUnitOfWork(state),
                        storages = storages,
                    ),
                )
            } finally {
                notifier.close()
            }
        } finally {
            state.close()
            opened.forEach { it.close() }
        }
    }

    private class RealStack(
        val vfs: DefaultVfs,
        val nodes: SqliteNodeRepository,
        val metadata: SqliteMetadataRepository,
        val notifier: AsyncEventNotifier,
        /** 未包装的事务：A05 用来先真实地放一条事件进去。 */
        val rawUnitOfWork: SqliteUnitOfWork,
        val storages: Map<String, Storage>,
    )

    /** 列表结果压成「路径 -> 类型」，按集合比较：list 不保证顺序。 */
    private fun List<VfsEntry>.pairs(): List<Pair<String, NodeType>> = map { it.uri.path.toString() to it.type }.sortedBy { it.first }

    /** 库里事件类型按写入顺序。 */
    private fun eventTypes(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_type FROM event ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    /** 直接查表行数：只读统计，走 JDK 自带 JDBC。 */
    private fun countRows(table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun eventCount(): Int = countRows("event")

    private fun activeNodes(): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM node WHERE deleted_at IS NULL").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun randomNodeId() = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000ff")

    /**
     * 按 ID 等待的事件记录器：「某条事件没被通知」用它证明。
     *
     * 每个 ID 各等各的：同一条 FIFO 链上先等的那次会把信号消费掉，第二次 await 会立刻返回，
     * 断言就变成和后台消费赛跑（T14 复核已复现过一次这种不稳定）。
     */
    private class TailRecorder : VfsEventConsumer {
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

    // __REAL_STACK_APPEND__
}

/**
 * 包一层存储：第一个 [write] 先挂住，由测试放行。
 *
 * 挂住的位置在真正落盘之前，而 DefaultVfs 整条写链都拿着共享边界——所以这个闩锁
 * 能确定第一个写此刻正占着边界，第二个写必须排队。
 */
private class PausingStorage(
    private val delegate: Storage,
) : Storage by delegate {
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    /** Storage.write 被调了几次（不管成功还是失败）。 */
    val writeCalls = AtomicInteger()

    override suspend fun write(
        path: com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath,
        content: ByteArray,
        mode: com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode,
    ): com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes {
        if (writeCalls.incrementAndGet() == 1) {
            entered.complete(Unit)
            release.await()
        }
        return delegate.write(path, content, mode)
    }

    /** 第一个写已经进了 Storage I/O（也就是正拿着边界）。 */
    suspend fun awaitFirstWrite() = entered.await()

    /** 放行第一个写；幂等。 */
    fun releaseFirstWrite() {
        release.complete(Unit)
    }
}

/**
 * 包一层事务：本次事务里**第一条**事件换成固定 ID，于是它会和库里已存在的那条撞主键。
 *
 * 事务、约束、索引、回滚全都是真的；被改的只有事件 ID 这一个字段。
 */
private class CollidingEventUnitOfWork(
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
