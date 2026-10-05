package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
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
import com.github.noahshen.alcyone.context.vfs.core.repository.MetadataRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
    fun `A02 creating and overwriting keeps one identity, the metadata and the event follows real existence`() =
        runBlocking {
            withStack { stack ->
                val created = stack.vfs.write(uri("/notes/a.txt"), "first".toByteArray())
                // 直接用测试已有的 Repository 预置 Metadata（setMetadata 属 T17 尚未交付）。
                val metadata = NodeMetadata(setOf("ct", "影像"), "胸部 CT 报告")
                stack.metadata.put(created.id, metadata)

                assertEquals("first", Files.readString(diskRoot.resolve("notes/a.txt")), "父目录和内容都真的在磁盘上")
                assertEquals(listOf("FILE_CREATED"), eventTypes(), "第一次写是 FILE_CREATED")
                assertEquals(created.id, stack.nodes.findByPath(VfsPath.parse("/resources/notes/a.txt"))?.id)

                val overwritten = stack.vfs.write(uri("/notes/a.txt"), "second".toByteArray())

                assertEquals(created.id, overwritten.id, "覆盖保留同一个 Node ID")
                assertEquals(created.registeredAt, overwritten.registeredAt, "登记时间不变")
                assertEquals("second", Files.readString(diskRoot.resolve("notes/a.txt")))
                assertEquals(metadata, stack.metadata.get(created.id), "覆盖只推进 updatedAt，不清空 Metadata")
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
    fun `A05 a parent creation that fails halfway says PARTIAL and leaves the directories it did create`() =
        runBlocking {
            withStack(wrapStorage = { BlockerStorage(it) }) { stack ->
                // 第一层 a 真建到盘上，第二层 a/b 在后端这里失败——这就是「补父目录建到一半」。
                val failure = assertFailsWith<VfsException> { stack.vfs.write(uri("/a/b/c.txt"), "x".toByteArray()) }

                assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端原始错误码")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "a 已经建在盘上，报 NONE 才是错的")
                assertTrue(Files.isDirectory(diskRoot.resolve("a")), "第一层目录真的留在盘上，不自动回删")
                assertFalse(Files.exists(diskRoot.resolve("a/b")), "第二层确实没建成")
                assertFalse(Files.exists(diskRoot.resolve("a/b/c.txt")), "内容一个字节都没写")
                assertEquals(0, activeNodes(), "失败不提交 Node")
                assertEquals(0, eventCount(), "失败不发成功事件")
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
    fun `A06 the second write is held at the boundary before it touches the backend`() =
        runBlocking {
            withStack(wrapStorage = { PausingStorage(it, blockingPath = null) }) { stack ->
                val pausing = stack.storages.getValue("local") as PausingStorage

                val first =
                    async(Dispatchers.Default) {
                        stack.vfs.write(uri("/a.txt"), "first".toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
                    }
                pausing.awaitFirstWrite() // 第一个写已经进了 Storage I/O，正拿着边界
                val backendCallsBeforeSecond = pausing.callLog().size

                val second =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        runCatching { stack.vfs.write(uri("/a.txt"), "second".toByteArray(), WriteOptions(WriteMode.CREATE_NEW)) }
                            .exceptionOrNull()
                    }
                // UNDISPATCHED：第二个协程在当前线程直接开跑，直到第一个挂起点才把控制权交回来。
                // write 入口只有限额检查，真正会挂住的就是取锁——所以它现在一定卡在边界上。
                assertFalse(second.isCompleted, "第二个请求已经跑到第一个挂起点，却直接跑完了——它没被边界挡住")
                assertEquals(
                    backendCallsBeforeSecond,
                    pausing.callLog().size,
                    "第二个请求一次 Storage 都没碰：${pausing.callLog()}",
                )
                assertEquals(1, pausing.pausedWrites(), "此刻只有第一个写还挂在闩锁上")

                pausing.releaseFirstWrite()

                val info = withTimeout(30_000) { first.await() }
                val failure = withTimeout(30_000) { second.await() }

                // 竞争发生过了（一成一败），事后顺序也符合边界语义：第二个写的 stat 排在第一个写的物理写之后。
                assertEquals(
                    listOf("stat:a.txt", "write:a.txt", "stat:a.txt"),
                    pausing.callLog(),
                    "第二个写直到第一个写完成才动过后端",
                )
                assertEquals(VfsErrorCode.ALREADY_EXISTS, (failure as? VfsException)?.code, "后到的那个看到的是已存在")
                assertEquals("first", Files.readString(diskRoot.resolve("a.txt")), "第二次物理变更没有落地")
                assertEquals(1, pausing.writeCalls.get(), "只有第一个写真的调过 Storage.write")
                assertEquals(info.id, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id)
                assertEquals(1, activeNodes())
            }
        }

    @Test
    @Timeout(60)
    fun `A06 a write cancelled at the DefaultVfs entry propagates, releases the lock and commits nothing`() =
        runBlocking {
            withStack(wrapStorage = { PausingStorage(it, blockingPath = "a.txt") }) { stack ->
                val pausing = stack.storages.getValue("local") as PausingStorage
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)
                val cancelled = CancellationException("cancelled while writing")

                // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，
                // 拿它当证据等于什么都没测，所以要留下写链自己真正抛出来的那一个。
                val escaped = CompletableDeferred<Throwable?>()
                val job =
                    async(Dispatchers.Default) {
                        try {
                            stack.vfs.write(uri("/a.txt"), "written".toByteArray())
                            escaped.complete(null)
                        } catch (failure: Throwable) {
                            escaped.complete(failure)
                        }
                    }
                pausing.awaitFirstWrite() // 写链已经走到 Storage I/O，取消就发生在这个挂起点上
                job.cancel(cancelled)

                withTimeout(30_000) { job.join() } // 取消当场结束，不会挂在边界上等
                val escapedFailure = withTimeout(30_000) { escaped.await() }
                assertNotNull(escapedFailure, "写链应该把取消抛出来，而不是安静地结束")
                assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                assertSame(
                    cancelled,
                    escapedFailure?.cause ?: escapedFailure,
                    "取消原因一致（协程栈帧恢复可能复制异常，但原实例在 cause 上）",
                )
                assertEquals(0, pausing.pausedWrites(), "取消返回后写链已经退出闩锁")
                assertFalse(Files.exists(diskRoot.resolve("a.txt")), "落盘那一步被取消，内容没有写进去")
                assertEquals(0, activeNodes(), "取消不提交 Node")
                assertEquals(0, eventCount(), "取消不发成功事件")

                // 锁已放行：紧接着的写能正常拿到同一把边界并完成——它能挂到闩锁上就是锁没被留下的证据。
                val info = withTimeout(30_000) { stack.vfs.write(uri("/a.txt"), "after".toByteArray()) }
                assertEquals(0, pausing.pausedWrites(), "挂起发生在取消之后，锁已经放行")
                pausing.releaseFirstWrite()
                assertEquals("after", Files.readString(diskRoot.resolve("a.txt")))
                assertEquals(info.id, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id)

                // 末尾标记按 ID 等：如果还有别的通知，它一定排在标记之前。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                // 被取消的那次一个 Node 都没建，所以它不可能产生任何通知；
                // 收件箱里只有「成功那次 + 末尾标记」两条。
                assertEquals(listOf(info.id, null), recorder.received.map { it.nodeId }, "只有成功的那次有通知")
            }
        }

    /** 一次真实组装：Core 接线 + 真 SQLite + 真本地磁盘；用完就关，必要时还能重开验证持久性。 */
    private suspend fun <T> withStack(
        mounts: List<MountRecord> = listOf(MountRecord(VfsPath.parse("/resources"), "local")),
        extraStorages: Map<String, LocalFsStorage> = emptyMap(),
        /** 包一层存储：用 PausingStorage 控制第一个写什么时候落盘（A06）。 */
        wrapStorage: (Storage) -> Storage = { it },
        /** 包一层状态库：记下每次 Metadata 读 / 写，用来证明被挡住的请求还没碰状态库（A06）。 */
        wrapMetadata: (MetadataRepository) -> MetadataRepository = { it },
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
            val metadata = SqliteMetadataRepository(state)
            val observedMetadata = wrapMetadata(metadata)
            try {
                return block(
                    RealStack(
                        vfs =
                            DefaultVfs(
                                router = router,
                                capabilities = CapabilitySnapshot.of(storages.mapValues { it.value.capabilities() }),
                                registry = NodeRegistry(router, nodes, { key -> storages[key] }, boundary),
                                nodes = nodes,
                                metadata = observedMetadata,
                                storages = { key -> storages[key] },
                                boundary = boundary,
                                pipeline = EventPipeline(boundary, unitOfWork(SqliteUnitOfWork(state)), notifier),
                                limits = VfsLimits(),
                            ),
                        nodes = nodes,
                        metadata = metadata,
                        observedMetadata = observedMetadata,
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
        /** 包了一层的 Metadata 仓库（[wrapMetadata] 的结果），A06 用它的调用记录。 */
        val observedMetadata: MetadataRepository,
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

    /** 库里事件带的 Node ID，按写入顺序；历史事件保留旧 ID 用它断言。 */
    private fun eventNodeIds(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COALESCE(node_id, '') FROM event ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    /** 当前有效 Node 的逻辑路径，排序后比较（删除失败时记录应当原样留着）。 */
    private fun activePaths(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT vfs_path FROM node WHERE deleted_at IS NULL ORDER BY vfs_path").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    /** 库里最后一条事件的 ID（写入是同步提交的，返回时就已落库）：用来按 ID 等它被分发完。 */
    private fun lastEventId(): String =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_id FROM event ORDER BY rowid DESC LIMIT 1").use { rows ->
                    rows.next()
                    rows.getString(1)
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

    @Test
    @Timeout(60)
    fun `A01 the real disk loses a registered file an empty directory and refuses a full one`() =
        runBlocking {
            withStack { stack ->
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                Files.createDirectory(diskRoot.resolve("empty"))
                Files.createDirectory(diskRoot.resolve("full"))
                Files.writeString(diskRoot.resolve("full/b.txt"), "child")

                stack.vfs.delete(uri("/a.txt"))
                assertFalse(Files.exists(diskRoot.resolve("a.txt")), "文件真的从盘上没了")

                stack.vfs.delete(uri("/empty"))
                assertFalse(Files.exists(diskRoot.resolve("empty")), "空目录非递归也能删")

                val refused = assertFailsWith<VfsException> { stack.vfs.delete(uri("/full")) }
                assertEquals(VfsErrorCode.DIRECTORY_NOT_EMPTY, refused.code)
                assertEquals(VfsEffect.NONE, refused.effect, "非空目录非递归：一个字节都没删")
                assertTrue(Files.exists(diskRoot.resolve("full/b.txt")), "目录里的东西还在")

                val missing = assertFailsWith<VfsException> { stack.vfs.delete(uri("/gone.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "缺失目标不当作幂等成功")

                stack.vfs.delete(uri("/full"), DeleteOptions(recursive = true))
                assertFalse(Files.exists(diskRoot.resolve("full")), "递归删掉整棵子树")
                assertTrue(Files.isDirectory(diskRoot), "挂载根不动：删 a 不顺带删目标之外的父目录")
            }
        }

    @Test
    @Timeout(60)
    fun `A03 the real database retires the whole registered subtree and a rebuild gets a new id`() =
        runBlocking {
            withStack { stack ->
                stack.vfs.write(uri("/a/b.txt"), "b".toByteArray())
                stack.vfs.write(uri("/a/deep/c.txt"), "c".toByteArray())
                stack.vfs.write(uri("/a-old/keep.txt"), "keep".toByteArray())
                stack.vfs.write(uri("/A.txt"), "case".toByteArray())
                // 绕过 VFS 直接删掉磁盘上的 a/deep/c.txt：物理没了，逻辑记录还挂着——这正是要验证的「幽灵记录」。
                Files.delete(diskRoot.resolve("a/deep/c.txt"))

                // 夹具前提：目录 a 自己**没有**身份，只有子项登记过——删除链必须照样清掉子树。
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/a")), "目录 a 从没登记过")
                val childB = stack.vfs.stat(uri("/a/b.txt")).id
                val childC = stack.nodes.findByPath(VfsPath.parse("/resources/a/deep/c.txt"))!!.id
                val sibling = stack.nodes.findByPath(VfsPath.parse("/resources/a-old/keep.txt"))!!.id
                val caseSibling = stack.nodes.findByPath(VfsPath.parse("/resources/A.txt"))!!.id
                stack.metadata.put(childB, NodeMetadata(setOf("ct"), "胸部 CT"))
                stack.metadata.put(childC, NodeMetadata(description = "already gone on disk"))
                stack.metadata.put(sibling, NodeMetadata(description = "keep me"))

                stack.vfs.delete(uri("/a"), DeleteOptions(recursive = true))

                assertFalse(Files.exists(diskRoot.resolve("a")), "物理子树真的没了")
                assertTrue(stack.nodes.findSubtree(VfsPath.parse("/resources/a")).isEmpty(), "逻辑子树整体退役")
                listOf(childB, childC).forEach { id ->
                    val gone = assertFailsWith<VfsException> { stack.vfs.getNode(id) }
                    assertEquals(VfsErrorCode.NOT_FOUND, gone.code, "旧 ID 查不到了")
                    assertNull(stack.metadata.get(id), "Metadata 跟着清掉")
                }
                assertEquals(sibling, stack.nodes.findByPath(VfsPath.parse("/resources/a-old/keep.txt"))?.id, "同名前缀兄弟不受影响")
                assertEquals(caseSibling, stack.nodes.findByPath(VfsPath.parse("/resources/A.txt"))?.id, "大小写兄弟不受影响")
                assertEquals(NodeMetadata(description = "keep me"), stack.metadata.get(sibling))
                assertTrue(Files.exists(diskRoot.resolve("a-old/keep.txt")))
                assertTrue(Files.exists(diskRoot.resolve("A.txt")))
                assertTrue(Files.isDirectory(diskRoot), "目标之外的父目录（这里是挂载根）留着")

                assertEquals(
                    listOf("FILE_CREATED", "FILE_CREATED", "FILE_CREATED", "FILE_CREATED", "DIRECTORY_DELETED"),
                    eventTypes(),
                    "一次删除一条目标级事件，不为后代逐个造事件",
                )

                // 同一路径重建：旧记录已经失效，新记录是新身份；历史事件仍带着旧 ID。
                val rebuilt = stack.vfs.write(uri("/a/b.txt"), "again".toByteArray())
                assertNotEquals(childB, rebuilt.id, "同路径重建获得新 Node ID")
                assertTrue(eventNodeIds().contains(childB.value), "历史事件保留删除前的旧 ID")
            }
        }

    @Test
    @Timeout(60)
    fun `A04 a real sqlite append conflict after a real delete rolls the state back and sends nothing`() =
        runBlocking {
            // 测试装置：让本次事务的第一条事件用固定 ID，和库里已存在的那条撞主键——
            // 真实 SQLite 拒绝重复主键，回滚也是真实事务。磁盘上的删除是真的，回滚不了。
            val colliding = VfsEventId.parse("018f0a5c-1b2c-7def-8abc-0000000000e6")
            withStack(unitOfWork = { CollidingEventUnitOfWork(it, colliding) }) { stack ->
                // 预置不用 DefaultVfs：这个包装器会把**每个事务的第一条事件**换成 colliding，
                // 所以文件、Node、Metadata 和历史事件都直接用真库预置，只让待测的删除撞上主键。
                Files.writeString(diskRoot.resolve("a.txt"), "hello")
                val written =
                    NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000a4")
                val now = Instant.now()
                stack.nodes.register(NodeRecord(written, VfsPath.parse("/resources/a.txt"), NodeType.FILE, true, now, now))
                stack.metadata.put(written, NodeMetadata(description = "keep"))
                stack.rawUnitOfWork.inTransaction { scope ->
                    scope.events.append(
                        EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/seed.txt")).copy(id = colliding),
                    )
                }
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)

                val failure = assertFailsWith<VfsException> { stack.vfs.delete(uri("/a.txt")) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(VfsEffect.PARTIAL, failure.effect, "文件真的删掉了，这是已知变更")
                assertFalse(Files.exists(diskRoot.resolve("a.txt")), "物理删除不自动补偿")
                assertEquals(written, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "Node 标记删除随事务回滚")
                // 事件追加冲突发生在 markDeleted 和 metadata.delete 之后，真实事务回滚恢复 Node 与 Metadata。
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(written), "Metadata 随事务回滚")
                assertEquals(1, eventCount(), "库里只有预置那一条，删除事件没进库")

                // 失败不该有通知：按 ID 末尾标记确认（标记之前若还有别的通知，一定排在它前面）。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(listOf(marker.id.value), recorder.received.map { it.id.value }, "失败的事务没有成功通知")
            }
        }

    @Test
    @Timeout(60)
    fun `A05 a half-finished backend delete keeps its effect and the records stay`() =
        runBlocking {
            // 故障注入，不是 OS 自发故障：包装器真的删掉子树里的一个文件，然后抛出带指定 effect 的错误。
            // 真文件系统没法自己造出「删了一半才失败」，所以用包装器；断言仍然对着真盘。
            val partial = VfsException(VfsErrorCode.STORAGE_ERROR, "lost the reply halfway", effect = VfsEffect.PARTIAL)
            withStack(
                wrapStorage = { PartialDeleteStorage(it, partialPath = "a/one.txt", failure = partial) },
            ) { stack ->
                stack.vfs.write(uri("/a/one.txt"), "1".toByteArray())
                stack.vfs.write(uri("/a/two.txt"), "2".toByteArray())
                val one = stack.vfs.stat(uri("/a/one.txt")).id
                val two = stack.vfs.stat(uri("/a/two.txt")).id
                stack.metadata.put(one, NodeMetadata(description = "one"))
                stack.metadata.put(two, NodeMetadata(description = "two"))

                val failure = assertFailsWith<VfsException> { stack.vfs.delete(uri("/a"), DeleteOptions(recursive = true)) }

                assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端自己的错误码")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "确实删掉了一个文件，PARTIAL 保真，不降级成 NONE")
                assertFalse(Files.exists(diskRoot.resolve("a/one.txt")), "注入的那次删除真的生效了")
                assertTrue(Files.exists(diskRoot.resolve("a/two.txt")), "另一个文件还在")
                assertEquals(listOf("/resources/a/one.txt", "/resources/a/two.txt"), activePaths(), "物理失败就不按猜测部分清理逻辑状态")
                assertEquals(NodeMetadata(description = "one"), stack.metadata.get(one), "Metadata 也不提前清")
                assertEquals(listOf("FILE_CREATED", "FILE_CREATED"), eventTypes(), "没有发布目录删除成功的事件")
            }
        }

    @Test
    @Timeout(60)
    fun `A05 an UNKNOWN backend delete is reported as UNKNOWN`() =
        runBlocking {
            val unknown = VfsException(VfsErrorCode.STORAGE_ERROR, "connection lost mid delete", effect = VfsEffect.UNKNOWN)
            withStack(wrapStorage = { FailingDeleteStorage(it, unknown) }) { stack ->
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id

                val failure = assertFailsWith<VfsException> { stack.vfs.delete(uri("/a.txt")) }

                assertEquals(VfsEffect.UNKNOWN, failure.effect, "后端说不清就是说不清，不改报 NONE / PARTIAL")
                assertTrue(Files.exists(diskRoot.resolve("a.txt")))
                assertEquals(id, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id)
                assertEquals(listOf("FILE_CREATED"), eventTypes())
            }
        }

    @Test
    @Timeout(60)
    fun `A06 the second delete is held at the boundary before it touches the backend`() =
        runBlocking {
            withStack(wrapStorage = { PausingStorage(it, blockingPath = null, blockingDeletePath = "a") }) { stack ->
                val pausing = stack.storages.getValue("local") as PausingStorage
                pausing.releaseFirstWrite() // 写链不参与本用例的闩锁，接下来只拦删除
                stack.vfs.write(uri("/a/one.txt"), "1".toByteArray())
                stack.vfs.write(uri("/a/two.txt"), "2".toByteArray())

                val first = async(Dispatchers.Default) { stack.vfs.delete(uri("/a"), DeleteOptions(recursive = true)) }
                pausing.awaitFirstDelete() // 第一个删除已经进了 Storage I/O，正拿着边界
                val backendCallsBeforeSecond = pausing.callLog().size

                val second =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        runCatching { stack.vfs.delete(uri("/a/two.txt")) }.exceptionOrNull()
                    }
                // UNDISPATCHED：第二个协程在当前线程直接开跑，直到第一个挂起点才交回控制权。
                assertFalse(second.isCompleted, "第二个请求已经跑到第一个挂起点，却直接跑完了——它没被边界挡住")
                assertEquals(
                    backendCallsBeforeSecond,
                    pausing.callLog().size,
                    "第二个请求一次 Storage 都没碰：${pausing.callLog()}",
                )
                assertEquals(1, pausing.pausedDeletes(), "此刻只有第一个删除还挂在闩锁上")

                pausing.releaseDelete()

                withTimeout(30_000) { first.await() }
                val failure = withTimeout(30_000) { second.await() }

                assertFalse(Files.exists(diskRoot.resolve("a")), "第一个删除真的删掉了子树")
                assertEquals(VfsErrorCode.NOT_FOUND, (failure as? VfsException)?.code, "后到的那个看到的是目标已经不存在")
                assertEquals(
                    listOf("delete:a", "stat:a/two.txt"),
                    pausing.callLog().takeLast(2),
                    "第二个删除直到第一个删除提交完才动过后端",
                )
                assertEquals(
                    listOf("FILE_CREATED", "FILE_CREATED", "DIRECTORY_DELETED"),
                    eventTypes(),
                    "被挡住的第二个删除没有产生任何事件",
                )
            }
        }

    @Test
    @Timeout(60)
    fun `A06 a delete cancelled at the DefaultVfs entry propagates, releases the lock and commits nothing`() =
        runBlocking {
            withStack(wrapStorage = { PausingStorage(it, blockingPath = null, blockingDeletePath = "a.txt") }) { stack ->
                val pausing = stack.storages.getValue("local") as PausingStorage
                pausing.releaseFirstWrite() // 写链不参与本用例的闩锁，接下来只拦删除
                // 先订阅再写：分发是异步的，写返回只说明创建事件已入队，订阅晚一步就可能收到它、也可能收不到。
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val written = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                recorder.await(VfsEventId.parse(lastEventId())) // 按 ID 等这条创建事件真的被处理完，之后收件箱的基线才是确定的
                assertEquals(listOf(written), recorder.received.map { it.nodeId }, "预置阶段只有这条创建事件")
                val cancelled = CancellationException("cancelled while deleting")

                // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，
                // 拿它当证据等于什么都没测，要留下删除链自己真正抛出来的那一个。
                val escaped = CompletableDeferred<Throwable?>()
                val job =
                    async(Dispatchers.Default) {
                        try {
                            stack.vfs.delete(uri("/a.txt"))
                            escaped.complete(null)
                        } catch (failure: Throwable) {
                            escaped.complete(failure)
                        }
                    }
                pausing.awaitFirstDelete() // 删除链已经走到 Storage I/O，取消就发生在这个挂起点上
                job.cancel(cancelled)

                withTimeout(30_000) { job.join() }
                val escapedFailure = withTimeout(30_000) { escaped.await() }
                assertNotNull(escapedFailure, "删除链应该把取消抛出来，而不是安静地结束")
                assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                assertSame(
                    cancelled,
                    escapedFailure?.cause ?: escapedFailure,
                    "取消原因一致（协程栈帧恢复可能复制异常，但原实例在 cause 上）",
                )
                assertEquals(0, pausing.pausedDeletes(), "取消返回后删除链已经退出闩锁")
                assertTrue(Files.exists(diskRoot.resolve("a.txt")), "取消发生在真正删盘之前")
                assertEquals(written, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "取消不提交 Node")
                assertEquals(listOf("FILE_CREATED"), eventTypes(), "取消不发成功事件")

                // 锁已放行：紧接着的删除能正常拿到同一把边界并完成——它能挂上闩锁就是锁没被留下的证据。
                stack.vfs.delete(uri("/a.txt"))
                assertEquals(0, pausing.pausedDeletes(), "挂起发生在取消之后，锁已经放行")
                assertFalse(Files.exists(diskRoot.resolve("a.txt")))

                // 末尾标记按 ID 等：如果还有别的通知，它一定排在标记之前。
                val marker = EventFactory().newRecord(VfsEventType.FILE_DELETED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                // 预置的创建事件（已在开头按 ID 等过）+ 成功那次删除 + 哨兵；被取消的那次一条都没有。
                assertEquals(listOf(written, written, null), recorder.received.map { it.nodeId }, "只有成功的那次有通知")
            }
        }

    @Test
    @Timeout(60)
    fun `A06 a delete cancelled inside the commit propagates the original exception and rolls the state back`() =
        runBlocking {
            // 取消点放在「物理删除已完成、事务还没 COMMIT」的窗口里：包装器让第一次 markDeleted
            // 真写进库之后停一下，取消就从这里抛出来，正好落进提交附近那个 catch。
            lateinit var pausing: PausingCommitUnitOfWork
            withStack(unitOfWork = { PausingCommitUnitOfWork(it).also { wrapper -> pausing = wrapper } }) { stack ->
                // 先订阅再写：分发是异步的，写返回只说明创建事件已入队，订阅晚一步就可能收到它、也可能收不到。
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val written = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                stack.metadata.put(written, NodeMetadata(description = "keep"))
                recorder.await(VfsEventId.parse(lastEventId())) // 按 ID 等这条创建事件真的被处理完，之后收件箱的基线才是确定的
                assertEquals(listOf(written), recorder.received.map { it.nodeId }, "预置阶段只有这条创建事件")
                val cancelled = CancellationException("cancelled while committing the delete")

                // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，
                // 拿它当证据等于什么都没测，要留下删除链自己真正抛出来的那一个。
                val escaped = CompletableDeferred<Throwable?>()
                val job =
                    async(Dispatchers.Default) {
                        try {
                            stack.vfs.delete(uri("/a.txt"))
                            escaped.complete(null)
                        } catch (failure: Throwable) {
                            escaped.complete(failure)
                        }
                    }
                pausing.awaitMarkDeleted() // markDeleted 已写入，物理删除已完成，事务还没 COMMIT
                job.cancel(cancelled)

                withTimeout(30_000) { job.join() }
                val escapedFailure = withTimeout(30_000) { escaped.await() }
                assertNotNull(escapedFailure, "删除链应该把取消抛出来，而不是安静地结束")
                assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                assertSame(
                    cancelled,
                    escapedFailure?.cause ?: escapedFailure,
                    "取消原因一致（协程栈帧恢复可能复制异常，但原实例在 cause 上）",
                )

                // 实际事实：物理删除**已经**完成，取消不能把它变回来；逻辑状态随事务回滚。
                assertFalse(Files.exists(diskRoot.resolve("a.txt")), "物理删除已完成，不声称「取消 = 没删过」")
                assertEquals(written, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "取消时事务回滚，Node 记录原样")
                // 本例取消发生在 markDeleted 后、metadata.delete 前，证明 Node 回滚和 Metadata 未改；
                // Metadata 删除后的普通提交失败回滚由 A04 验证。
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(written), "Metadata 未改")
                assertEquals(listOf("FILE_CREATED"), eventTypes(), "取消不追加删除事件")

                // 锁已放行：紧接着的写与删能拿到同一把边界并完成。
                val second = stack.vfs.write(uri("/b.txt"), "second".toByteArray())
                stack.vfs.delete(uri("/b.txt"))
                assertFalse(Files.exists(diskRoot.resolve("b.txt")), "锁没有被留在手里")
                assertEquals(1, pausing.pausedMarks(), "只有第一次 markDeleted 挂起过")

                // 末尾标记按 ID 等：如果还有别的通知，它一定排在标记之前。
                val marker = EventFactory().newRecord(VfsEventType.FILE_DELETED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                // 预置的创建事件 + b.txt 的写 + b.txt 的删 + 哨兵；被取消的那次一条都没有。
                assertEquals(listOf(written, second.id, second.id, null), recorder.received.map { it.nodeId }, "被取消的那次没有通知")
            }
        }

    @Test
    @Timeout(60)
    fun `A02 the metadata survives a reopened database and an empty set clears the row`() =
        runBlocking {
            lateinit var id: NodeId
            val full = NodeMetadata(setOf("ct", "影像"), "胸部 CT", buildJsonObject { put("dicom", "1.2.840") })
            withStack { stack ->
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id

                stack.vfs.setMetadata(id, full)
                assertEquals(full, stack.metadata.get(id), "三个字段都真写进了 SQLite")
                assertEquals(listOf("FILE_CREATED", "METADATA_UPDATED"), eventTypes())
            }

            withStack { stack ->
                // 关掉重开：新连接、新事务，只有真落库的数据还在。
                assertEquals(full, stack.vfs.getMetadata(id), "重新打开数据库后仍是完整的替换结果")
                stack.vfs.setMetadata(id, NodeMetadata(description = "报告"))
                assertEquals(
                    NodeMetadata(description = "报告"),
                    stack.vfs.getMetadata(id),
                    "整体替换：不和上一批的 tags 合并",
                )
                stack.vfs.setMetadata(id, NodeMetadata())
                assertEquals(NodeMetadata(), stack.vfs.getMetadata(id), "空对象清空")
            }
        }

    @Test
    @Timeout(60)
    fun `A03 both operations work after the file vanishes and stop working after a delete`() =
        runBlocking {
            withStack { stack ->
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                stack.vfs.write(uri("/b.txt"), "hello".toByteArray())
                val gone = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                val deleted = stack.nodes.findByPath(VfsPath.parse("/resources/b.txt"))!!.id
                // 绕过 VFS 直接删掉磁盘上的 a.txt：物理没了，逻辑记录还挂着。
                Files.delete(diskRoot.resolve("a.txt"))

                stack.vfs.setMetadata(gone, NodeMetadata(setOf("ct"), "物理文件已经不在了"))
                assertEquals(NodeMetadata(setOf("ct"), "物理文件已经不在了"), stack.vfs.getMetadata(gone), "Metadata 不依赖物理可读")

                stack.vfs.delete(uri("/b.txt"), DeleteOptions(recursive = true))
                val missing = assertFailsWith<VfsException> { stack.vfs.getMetadata(deleted) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "经 VFS 删除后旧 ID 查不到")
                val refused = assertFailsWith<VfsException> { stack.vfs.setMetadata(deleted, NodeMetadata(description = "x")) }
                assertEquals(VfsErrorCode.NOT_FOUND, refused.code)
                assertNull(stack.metadata.get(deleted), "删除时 Metadata 被清理")
            }
        }

    @Test
    @Timeout(60)
    fun `A04 every set notifies once including a repeated value and get notifies nothing`() =
        runBlocking {
            withStack { stack ->
                // 先订阅再操作：分发是异步的，订阅晚一步就可能收不到，也可能收到上次残留的。
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                recorder.await(VfsEventId.parse(lastEventId())) // 按 ID 等创建事件真的被处理完
                assertEquals(listOf(id), recorder.received.map { it.nodeId }, "基线只有创建事件")

                val value = NodeMetadata(setOf("ct"), "报告")
                stack.vfs.setMetadata(id, value)
                recorder.await(VfsEventId.parse(lastEventId()))

                // 传完全相同的值：仍然是一条事件（本轮不做相等比较）。
                stack.vfs.setMetadata(id, value)
                recorder.await(VfsEventId.parse(lastEventId()))

                // 重复清空：两次各一条。
                stack.vfs.setMetadata(id, NodeMetadata())
                recorder.await(VfsEventId.parse(lastEventId()))
                stack.vfs.setMetadata(id, NodeMetadata())
                recorder.await(VfsEventId.parse(lastEventId()))

                assertEquals(NodeMetadata(), stack.vfs.getMetadata(id), "清空之后读回空对象")

                // 末尾哨兵：同一条分发链上，查询要是真发了通知，它一定排在哨兵之前。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)

                val metadataEvents = recorder.received.filter { it.type == VfsEventType.METADATA_UPDATED }
                assertEquals(4, metadataEvents.size, "四次 set（含同值与重复清空）各一条，查询零条")
                assertTrue(metadataEvents.all { it.nodeId == id }, "事件带目标 ID")
                assertTrue(metadataEvents.all { it.uri.path.toString() == "/resources/a.txt" }, "事件 URI 是 Node 当时所在路径")
                assertEquals(
                    listOf("FILE_CREATED") + List(4) { "METADATA_UPDATED" },
                    eventTypes(),
                    "库里也只有那五条：查询没写入任何事件",
                )
            }
        }

    @Test
    @Timeout(60)
    fun `A04 the logical time moves and the identity stays when the metadata changes`() =
        runBlocking {
            withStack { stack ->
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                val before = stack.nodes.findById(id)!!
                val modifiedOnDisk = Files.getLastModifiedTime(diskRoot.resolve("a.txt"))

                stack.vfs.setMetadata(id, NodeMetadata(description = "一次逻辑变更"))

                val after = stack.nodes.findById(id)!!
                assertEquals(before.id, after.id, "身份不变")
                assertEquals(before.path, after.path, "路径不变")
                assertEquals(before.type, after.type, "类型不变")
                assertEquals(before.registeredAt, after.registeredAt, "登记时间不变")
                assertTrue(after.updatedAt >= before.updatedAt, "逻辑更新时间推进，不要求严格递增")
                assertEquals(modifiedOnDisk, Files.getLastModifiedTime(diskRoot.resolve("a.txt")), "不动物理文件时间")
            }
        }

    @Test
    @Timeout(60)
    fun `A05 a real sqlite append conflict rolls the metadata and the node time back`() =
        runBlocking {
            // 测试装置：本次事务的第一条事件用固定 ID，和库里已存在的那条撞主键。
            // 碰撞发生在 metadata.put 和 nodes.touch **之后**，所以回滚必须把三样东西一起恢复。
            val colliding = VfsEventId.parse("018f0a5c-1b2c-7def-8abc-0000000000e7")
            withStack(unitOfWork = { CollidingEventUnitOfWork(it, colliding) }) { stack ->
                // 预置不走 DefaultVfs：这个包装器会让每个事务的第一条事件都撞主键。
                val id = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000a7")
                val now = Instant.parse("2026-10-04T09:00:00Z")
                stack.nodes.register(NodeRecord(id, VfsPath.parse("/resources/a.txt"), NodeType.FILE, true, now, now))
                stack.metadata.put(id, NodeMetadata(setOf("ct"), "旧说明"))
                stack.rawUnitOfWork.inTransaction { scope ->
                    scope.events.append(
                        EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/seed.txt")).copy(id = colliding),
                    )
                }
                val before = stack.nodes.findById(id)!!
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)

                val failure =
                    assertFailsWith<VfsException> {
                        stack.vfs.setMetadata(id, NodeMetadata(setOf("mr"), "新说明"))
                    }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(VfsEffect.NONE, failure.effect, "这一批全在状态库里，没有 Storage 副作用，不套 PARTIAL")
                assertNotNull(failure.cause, "底层原因保留在 cause 上")
                assertEquals(NodeMetadata(setOf("ct"), "旧说明"), stack.metadata.get(id), "Metadata 随事务回滚")
                assertEquals(before.updatedAt, stack.nodes.findById(id)?.updatedAt, "Node 逻辑时间一起回滚")
                assertEquals(1, eventCount(), "事件没进库")

                // 失败不该有通知：末尾哨兵按 ID 等，哨兵之前的任何通知都已经被看到。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(listOf(marker.id.value), recorder.received.map { it.id.value }, "失败的事务没有成功通知")
            }
        }

    @Test
    @Timeout(60)
    fun `A06 a metadata query waits at the boundary and then reads the committed value`() =
        runBlocking {
            lateinit var pausing: PausingMetadataCommitUnitOfWork
            lateinit var observed: RecordingMetadataRepository
            withStack(
                unitOfWork = { real -> PausingMetadataCommitUnitOfWork(real).also { wrapper -> pausing = wrapper } },
                wrapMetadata = { real -> RecordingMetadataRepository(real).also { wrapper -> observed = wrapper } },
            ) { stack ->
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id

                pausing.arm()
                val first = async(Dispatchers.Default) { stack.vfs.setMetadata(id, NodeMetadata(description = "first")) }
                pausing.awaitEventAppend() // Metadata、Node 时间和事件都已真写进库，事务未提交，正拿着边界
                assertEquals(1, pausing.pausedAppends())
                val readsBefore = observed.callLog().size

                val second =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        runCatching { stack.vfs.getMetadata(id) }.exceptionOrNull()
                    }
                // UNDISPATCHED：查询在当前线程直接开跑，第一个挂起点就是取锁——所以它现在一定卡在边界上。
                assertFalse(second.isCompleted, "查询已经跑到第一个挂起点却直接跑完了——它没被边界挡住")
                assertEquals(readsBefore, observed.callLog().size, "被挡住的查询一次状态库都没读")

                pausing.release()

                withTimeout(30_000) { first.await() }
                val value = withTimeout(30_000) { second.await() }
                assertNull(value, "放行后查询正常返回")
                assertEquals(NodeMetadata(description = "first"), stack.vfs.getMetadata(id), "读到的是已提交的值")
                assertTrue(observed.callLog().any { it.startsWith("get:") }, "查询确实读了状态库")
                assertEquals(listOf("FILE_CREATED", "METADATA_UPDATED"), eventTypes())
            }
        }

    @Test
    @Timeout(60)
    fun `A06 a metadata update cancelled before the commit rolls everything back and frees the lock`() =
        runBlocking {
            // 取消点放在事务最末尾：Metadata 替换、Node 时间和事件都已真写进库，COMMIT 还没发生。
            lateinit var pausing: PausingMetadataCommitUnitOfWork
            withStack(unitOfWork = { real -> PausingMetadataCommitUnitOfWork(real).also { wrapper -> pausing = wrapper } }) { stack ->
                // 先订阅：分发是异步的，订阅晚一步就可能收不到预置那几条。
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                stack.vfs.setMetadata(id, NodeMetadata(setOf("ct"), "旧说明"))
                recorder.await(VfsEventId.parse(lastEventId())) // 按 ID 等预置阶段的两条事件真的被处理完
                assertEquals(listOf(id, id), recorder.received.map { it.nodeId }, "基线确定")
                val before = stack.nodes.findById(id)!!
                val eventsBefore = eventCount()
                val cancelled = CancellationException("cancelled while committing the metadata")

                // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，拿它当证据等于什么都没测。
                val escaped = CompletableDeferred<Throwable?>()
                pausing.arm()
                val job =
                    async(Dispatchers.Default) {
                        try {
                            stack.vfs.setMetadata(id, NodeMetadata(setOf("mr"), "新说明"))
                            escaped.complete(null)
                        } catch (failure: Throwable) {
                            escaped.complete(failure)
                        }
                    }
                pausing.awaitEventAppend() // 三样都已经真写进库，事务还没 COMMIT

                job.cancel(cancelled)
                withTimeout(30_000) { job.join() }
                val escapedFailure = withTimeout(30_000) { escaped.await() }
                assertNotNull(escapedFailure, "更新链应该把取消抛出来，而不是安静地结束")
                assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                assertSame(cancelled, escapedFailure?.cause ?: escapedFailure, "取消原因一致")

                assertEquals(NodeMetadata(setOf("ct"), "旧说明"), stack.metadata.get(id), "取消时事务回滚，Metadata 旧值还在")
                assertEquals(before.updatedAt, stack.nodes.findById(id)?.updatedAt, "Node 逻辑时间一起回滚")
                assertEquals(eventsBefore, eventCount(), "取消不追加事件")

                // 锁已放行：紧接着的更新能拿到同一把边界并完成——能跑完就是锁没被留下的证据。
                stack.vfs.setMetadata(id, NodeMetadata(description = "after"))
                assertEquals(NodeMetadata(description = "after"), stack.vfs.getMetadata(id), "锁没有被留在手里")
                assertEquals(1, pausing.pausedAppends(), "只有第一次挂起过")

                // 末尾哨兵按 ID 等：被取消的那次一条通知都没有。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(
                    listOf(id, id, id, null),
                    recorder.received.map { it.nodeId },
                    "预置两条 + 取消后那次成功 + 哨兵",
                )
            }
        }

    // __REAL_STACK_APPEND__
}

/**
 * 包一层存储：写进来先挂住，由测试放行；同时按顺序记下每次 `stat` / `write`。
 *
 * 挂住的位置在真正落盘之前，而 DefaultVfs 整条写链都拿着共享边界——所以第一个写此刻正占着边界。
 * [callLog] 记的是「谁在什么时候第一次碰到后端」：两个写各自的 `stat` 一前一后，就是第二个写
 * 排在边界后面的事后证据，不依赖「它此刻还没返回」这种随时会骗人的判据。
 */
private class PausingStorage(
    private val delegate: Storage,
    /** 哪个相对路径的 write 会挂住；null 表示每一次都挂（并发排队用例）。 */
    private val blockingPath: String?,
    /** 哪个相对路径的 delete 会挂住；null 表示不拦删除（T16 的删除并发 / 取消用例）。 */
    private val blockingDeletePath: String? = null,
) : Storage by delegate {
    private val release = CompletableDeferred<Unit>()
    private val paused = AtomicInteger()
    private val pauseArrivals = Channel<Unit>(Channel.UNLIMITED)
    private val calls = CopyOnWriteArrayList<String>()
    private val deleteRelease = CompletableDeferred<Unit>()
    private val pausedDelete = AtomicInteger()
    private val deleteArrivals = Channel<Unit>(Channel.UNLIMITED)

    /** Storage.write 被调了几次（不管成功还是失败）。 */
    val writeCalls = AtomicInteger()

    override suspend fun stat(
        path: com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath,
    ): com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes {
        calls += "stat:${path.toRelativeString()}"
        return delegate.stat(path)
    }

    override suspend fun write(
        path: com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath,
        content: ByteArray,
        mode: com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode,
    ): com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes {
        calls += "write:${path.toRelativeString()}"
        val call = writeCalls.incrementAndGet()
        if (call == 1 || path.toRelativeString() == blockingPath) {
            paused.incrementAndGet()
            pauseArrivals.trySend(Unit)
            try {
                release.await()
            } catch (stopped: CancellationException) {
                // OpenDAL 的落盘在 OpenDAL 自己的执行器上，取消不一定会把它撤回来；
                // 测试里明确放弃这次写，免得两个写真的去抢同一个文件。
                release.complete(Unit)
                throw stopped
            } finally {
                paused.decrementAndGet()
            }
        }
        return delegate.write(path, content, mode)
    }

    /** 第一个写已经进了 Storage I/O（也就是正拿着边界）。 */
    suspend fun awaitFirstWrite() {
        withTimeout(30_000) { pauseArrivals.receive() }
    }

    /** 挂住这次删除：日志先记一行，再在**真正删盘之前**等放行。 */
    override suspend fun delete(
        path: com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath,
        recursive: Boolean,
    ) {
        calls += "delete:${path.toRelativeString()}"
        if (blockingDeletePath == null || path.toRelativeString() == blockingDeletePath) {
            pausedDelete.incrementAndGet()
            deleteArrivals.trySend(Unit)
            try {
                deleteRelease.await()
            } catch (stopped: CancellationException) {
                deleteRelease.complete(Unit)
                throw stopped
            } finally {
                pausedDelete.decrementAndGet()
            }
        }
        delegate.delete(path, recursive)
    }

    /** 此刻停在闩锁上等放行的写次数（取消之后必须是 0，说明写链已经退出）。 */
    fun pausedWrites(): Int = paused.get()

    /** 按发生顺序记下的后端调用。 */
    fun callLog(): List<String> = calls.toList()

    /** 放行所有挂住的写；幂等。 */
    fun releaseFirstWrite() {
        release.complete(Unit)
    }

    /** 第一次删除已经进了 Storage I/O（也就是正拿着边界）。 */
    suspend fun awaitFirstDelete() {
        withTimeout(30_000) { deleteArrivals.receive() }
    }

    /** 此刻停在闩锁上等放行的删除次数（取消之后必须是 0，说明删除链已经退出）。 */
    fun pausedDeletes(): Int = pausedDelete.get()

    /** 放行所有挂住的删除；幂等。 */
    fun releaseDelete() {
        deleteRelease.complete(Unit)
    }
}

/**
 * 包一层存储：先**真的**删掉子树里的一个文件，再抛出带指定 effect 的错误。
 *
 * 这是**故障注入，不是 OS 自发故障**：真文件系统不会自己停在一半，后端丢掉回包才产生那种局面。
 * 所以这里证明的是「编排保留后端说出的 PARTIAL / UNKNOWN」，不等于所有真实后端故障行为都已验证。
 */
private class PartialDeleteStorage(
    private val delegate: Storage,
    private val partialPath: String,
    private val failure: VfsException,
) : Storage by delegate {
    override suspend fun delete(
        path: com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath,
        recursive: Boolean,
    ) {
        runCatching {
            delegate.delete(
                com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
                    .parse(partialPath),
                false,
            )
        }
        throw failure
    }
}

/** 包一层存储：每次删除都直接报错，原样带出它自己的 code 和 effect。 */
private class FailingDeleteStorage(
    private val delegate: Storage,
    private val failure: VfsException,
) : Storage by delegate {
    override suspend fun delete(
        path: com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath,
        recursive: Boolean,
    ): Unit = throw failure
}

/**
 * 包一层存储：本次调用里第 [failAt] 次 [Storage.createDirectory] 直接报错，其余都真的建到盘上。
 *
 * 写 `/resources/a/b/c.txt` 时的调用顺序是建 `a`、建 `a/b`、写内容，所以 [failAt] = 2 造出来的正是
 * 「第一层建成了、第二层失败」。失败注入在 Storage 端口上（真文件系统没法预置这种阻断，
 * 第一层是这次调用自己建的），但建出来的目录是真的落在真盘上的，断言仍然对着真盘。
 */
private class BlockerStorage(
    private val delegate: Storage,
    private val failAt: Int = 2,
) : Storage by delegate {
    private var seen = 0

    override suspend fun createDirectory(path: com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath) {
        if (++seen == failAt) {
            throw VfsException(VfsErrorCode.STORAGE_ERROR, "cannot create directory under the blocking file")
        }
        delegate.createDirectory(path)
    }
}

/**
 * 包一层事务：第一次 [NodeRepository.markDeleted] **真写进库里**之后停一下，让取消正好落在
 * 「物理删除已完成、事务还没 COMMIT」的窗口里。
 *
 * 事务、回滚全是真的（SqliteUnitOfWork 在 NonCancellable 里 ROLLBACK）；包装器只多加一个挂起点，
 * 所以它能覆盖到 DefaultVfs 提交附近那个 catch——挂在 Storage I/O 上的取消用例覆盖不到那里。
 */
private class PausingCommitUnitOfWork(
    private val delegate: SqliteUnitOfWork,
) : UnitOfWork {
    /** 见到过几次 markDeleted；只有第一次会真的挂起。 */
    private val marks = AtomicInteger()

    /** 真的挂起了几次：取消之后应当仍然是 1，说明后来的操作没再被拦住。 */
    private val paused = AtomicInteger()
    private val arrivals = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T =
        delegate.inTransaction { scope ->
            val decorated =
                object : TransactionScope {
                    override val nodes: NodeRepository =
                        object : NodeRepository by scope.nodes {
                            override suspend fun markDeleted(
                                ids: Collection<NodeId>,
                                deletedAt: Instant,
                            ) {
                                scope.nodes.markDeleted(ids, deletedAt) // 先真的写进库里
                                if (marks.incrementAndGet() == 1) {
                                    paused.incrementAndGet()
                                    arrivals.trySend(Unit)
                                    awaitCancellation() // 取消就在这里抛出来，事务随之回滚
                                }
                            }
                        }

                    override val metadata get() = scope.metadata

                    override val events get() = scope.events
                }
            block(decorated)
        }

    /** 第一次 markDeleted 已经写入、事务尚未提交。 */
    suspend fun awaitMarkDeleted() {
        withTimeout(30_000) { arrivals.receive() }
    }

    /** 真的挂起过几次：取消之后仍应是 1。 */
    fun pausedMarks(): Int = paused.get()
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

/**
 * 包一层 Metadata 仓库：按发生顺序记下每次 `get` / `put` / `delete`。
 *
 * 「一个被边界挡住的请求一次状态库都没碰」就是这么证的：不看它有没有返回（那随时会骗人），
 * 看它的仓库调用记录有没有长。
 */
private class RecordingMetadataRepository(
    private val delegate: MetadataRepository,
) : MetadataRepository {
    private val calls = CopyOnWriteArrayList<String>()

    override suspend fun get(id: NodeId): NodeMetadata? {
        calls += "get:$id"
        return delegate.get(id)
    }

    override suspend fun put(
        id: NodeId,
        metadata: NodeMetadata,
    ) {
        calls += "put:$id"
        delegate.put(id, metadata)
    }

    override suspend fun delete(id: NodeId) {
        calls += "delete:$id"
        delegate.delete(id)
    }

    /** 按发生顺序记下的仓库调用。 */
    fun callLog(): List<String> = calls.toList()
}

/**
 * 包一层事务：[arm] 之后的第一条事件**真写进库里**之后停一下。
 *
 * 「Metadata 替换 + Node 时间 + 事件」三样都落在同一个事务里，所以停在事件追加之后就是停在 COMMIT 之前：
 * 取消从这里抛出来，能证明的是「这一次更新整体回滚了」，而不是「取消 = 什么都没发生」。
 * 事务、回滚、NOT NULL 约束全都是真的（SqliteUnitOfWork 在 NonCancellable 里 ROLLBACK），包一层只多加一个挂起点。
 */
private class PausingMetadataCommitUnitOfWork(
    private val delegate: SqliteUnitOfWork,
) : UnitOfWork {
    /** 是否还等着拦下一次事件追加；[arm] 打开、[compareAndSet] 关闭，所以只拦一次。 */
    private val armed = AtomicBoolean(false)
    private val paused = AtomicInteger()
    private val arrivals = Channel<Unit>(Channel.UNLIMITED)
    private val released = CompletableDeferred<Unit>()

    /** 打开挂起点：从这里起的第一条事件追加会在写入之后停住。 */
    fun arm() {
        armed.set(true)
    }

    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T =
        delegate.inTransaction { scope ->
            val decorated =
                object : TransactionScope {
                    override val nodes get() = scope.nodes

                    override val metadata get() = scope.metadata

                    override val events: EventRepository =
                        object : EventRepository {
                            override suspend fun append(event: EventRecord) {
                                scope.events.append(event) // 先真的写进库里
                                if (armed.compareAndSet(true, false)) {
                                    paused.incrementAndGet()
                                    arrivals.trySend(Unit)
                                    // 并发排队用例靠放行，取消用例靠取消；两者都发生在 COMMIT 之前。
                                    try {
                                        released.await()
                                    } catch (stopped: CancellationException) {
                                        released.complete(Unit)
                                        throw stopped
                                    }
                                }
                            }
                        }
                }
            block(decorated)
        }

    /** 三样都已写入、事务尚未提交。 */
    suspend fun awaitEventAppend() {
        withTimeout(30_000) { arrivals.receive() }
    }

    /** 放行挂起的追加（取消用例里由取消结束）。 */
    fun release() {
        released.complete(Unit)
    }

    /** 真的挂起过几次：取消之后仍应是 1，说明后来的操作没再被拦住。 */
    fun pausedAppends(): Int = paused.get()
}
