package com.github.noahshen.alcyone.context.vfs.integration.registry

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
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
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T13 S3：Node Registry 装上**真的 SQLite 状态库**和**真的本地磁盘**跑一遍。
 *
 * Core 单测用内存替身，能证明分支走向；这里证明的是替身证明不了的那部分：
 * 状态真的落库、重开库还能读到、并发下数据库唯一索引真的只留一条、
 * 没提交的状态在同一个连接上确实会被别人看见（所以必须有那把共享边界）。
 *
 * 时序全部用闩锁控制，不用 `Thread.sleep`；并发用例都有 [Timeout] 兜底。
 */
class NodeRegistryRealStackTest {
    @TempDir
    lateinit var tempDir: Path

    /** 模拟挂载根（这块盘的物理目录）。 */
    private lateinit var diskRoot: Path

    /** 状态库文件；关掉再打开才能证明「提交过的都还在」。 */
    private lateinit var databaseFile: Path

    @BeforeEach
    fun setUp() {
        diskRoot = Files.createDirectory(tempDir.resolve("disk"))
        databaseFile = tempDir.resolve("state.db")
    }

    @Test
    fun `A01 the first stat on disk hands out a uuid v7 that survives a restart`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            val first =
                withStack { stack ->
                    val info = stack.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
                    assertEquals(5L, info.storage?.sizeBytes, "磁盘上的文件真的被读过属性")
                    assertEquals('7', info.id.toString()[14], "ID 是 UUIDv7（版本位在第 15 个字符）")
                    info
                }

            // 整个组件拆掉重建：状态库重新打开，磁盘还是那块磁盘
            val reopened = withStack { stack -> stack.registry.getNode(first.id) }

            assertEquals(first.id, reopened.id, "重开库后同一个 ID 还是同一个节点")
            assertEquals("/resources/a.txt", reopened.uri.path.toString())
            assertEquals(first.registeredAt, reopened.registeredAt)
            assertEquals(NodeType.FILE, reopened.type)
            assertNull(reopened.storage, "getNode 是纯逻辑查询")
        }

    @Test
    fun `A01 a path registered again after deletion gets a new id`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                val path = VfsPath.parse("/resources/a.txt")
                val first = stack.registry.resolveOrRegister(path)
                // 删除动作本身属 T15，这里用既有 Repository 准备状态
                stack.nodes.markDeleted(listOf(first.id), Instant.now())

                val second = stack.registry.resolveOrRegister(path)

                assertFalse(first.id == second.id, "删除后同路径是新身份")
                assertEquals(2, stack.rowCount("node"), "旧行留着（标记删除），只有一条是有效的")
                assertEquals(1, stack.activeNodeCount())
            }
        }

    @Test
    fun `A06 registering a node writes no event and touches no metadata`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                val path = VfsPath.parse("/resources/a.txt")
                assertEquals(0, stack.rowCount("node"))
                assertEquals(0, stack.rowCount("event"))
                assertEquals(0, stack.rowCount("metadata"))

                val info = stack.registry.resolveOrRegister(path)

                assertEquals(1, stack.rowCount("node"), "只多了一条 Node")
                assertEquals(0, stack.rowCount("event"), "注册不发事件，首版也不对外发布 NODE_REGISTERED")
                assertEquals(0, stack.rowCount("metadata"), "注册不碰 Metadata")
                assertNull(stack.metadata.get(info.id), "这条 Node 上没有 Metadata")

                // 已经有 Metadata 的节点，再查一次也不能被改动
                stack.metadata.put(info.id, NodeMetadata(description = "写好的说明"))
                val again = stack.registry.resolveOrRegister(path)

                assertEquals(1, stack.rowCount("metadata"))
                assertEquals(NodeMetadata(description = "写好的说明"), stack.metadata.get(again.id))
                assertEquals(info.registeredAt, again.registeredAt, "查询不改逻辑时间")
                assertEquals(info.id, again.id)
            }
        }

    @Test
    fun `A02 a file removed outside VFS is NOT_FOUND and the record survives`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                val path = VfsPath.parse("/resources/a.txt")
                val registered = stack.registry.resolveOrRegister(path)
                Files.delete(diskRoot.resolve("a.txt")) // 绕过 VFS 直接删

                val failure = assertFailsWith<VfsException> { stack.registry.resolveOrRegister(path) }

                assertEquals(VfsErrorCode.NOT_FOUND, failure.code)
                assertEquals(registered.id, stack.nodes.findByPath(path)?.id, "查询不改数据")
                assertEquals(1, stack.rowCount("node"))
                assertEquals(
                    registered.id,
                    stack.registry.getNode(registered.id).id,
                    "纯逻辑查询照样拿得到旧记录",
                )
                assertNull(stack.registry.getNode(registered.id).storage)
            }
        }

    @Test
    fun `A03 a missing mount ancestor becomes a virtual directory without touching the disk`() =
        runBlocking {
            // 只挂 /resources/medical/ct，磁盘上根本没有 medical 这个目录
            withStack(mounts = listOf(MountRecord(VfsPath.parse("/resources/medical/ct"), "local"))) { stack ->
                val info = stack.registry.resolveOrRegister(VfsPath.parse("/resources/medical"))

                assertEquals(NodeType.DIRECTORY, info.type)
                assertNull(info.storage, "虚拟目录没有磁盘属性")
                assertEquals(false, stack.nodes.findByPath(VfsPath.parse("/resources/medical"))?.physical)
                assertFalse(Files.exists(diskRoot.resolve("medical")), "不许为虚拟目录创建物理目录")

                // 挂载根是真的挂上了：确认后端目录存在
                val mountRoot = stack.registry.resolveOrRegister(VfsPath.parse("/resources/medical/ct"))
                assertEquals(NodeType.DIRECTORY, mountRoot.type)
                assertTrue(stack.nodes.findByPath(VfsPath.parse("/resources/medical/ct"))!!.physical)
            }
        }

    @Test
    fun `A03 a path outside every mount is MOUNT_NOT_FOUND`() =
        runBlocking {
            withStack { stack ->
                val failure = assertFailsWith<VfsException> { stack.registry.resolveOrRegister(VfsPath.parse("/elsewhere/a.txt")) }

                assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, failure.code)
                assertEquals(0, stack.rowCount("node"), "没有 Mount 就不该凭空造节点")
            }
        }

    @Test
    fun `A03 the longest mount decides which disk is asked`() =
        runBlocking {
            val childRoot = Files.createDirectory(tempDir.resolve("ct-disk"))
            Files.createDirectory(diskRoot.resolve("ct"))
            Files.writeString(childRoot.resolve("a.dcm"), "dicom")
            // 父盘上放一个同名文件：路由已经定了子盘，绝不能拿父盘顶替
            Files.writeString(diskRoot.resolve("ct").resolve("a.dcm"), "wrong disk")

            withStack(
                mounts =
                    listOf(
                        MountRecord(VfsPath.parse("/resources"), "parent"),
                        MountRecord(VfsPath.parse("/resources/ct"), "child"),
                    ),
                extraStorages = mapOf("parent" to LocalFsStorage.create(diskRoot), "child" to LocalFsStorage.create(childRoot)),
            ) { stack ->
                val info = stack.registry.resolveOrRegister(VfsPath.parse("/resources/ct/a.dcm"))

                assertEquals(5L, info.storage?.sizeBytes, "问的是子盘：'dicom' 5 字节，父盘那个是 'wrong disk'")
            }
        }

    @Test
    @Timeout(60)
    fun `A04 concurrent stats of one path leave exactly one node in a real database`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                val path = VfsPath.parse("/resources/a.txt")

                val ids =
                    (1..8)
                        .map { async(Dispatchers.Default) { stack.registry.resolveOrRegister(path).id } }
                        .awaitAll()

                assertEquals(1, ids.distinct().size, "并发 stat 同一路径，最终只有一个有效 Node，实际：$ids")
                assertEquals(1, stack.rowCount("node"), "数据库里只有一条有效记录")
                assertEquals(1, stack.activeNodeCount(), "并且它就是有效的那条")
                assertEquals(ids.first(), stack.nodes.findByPath(path)?.id)
            }
        }

    @Test
    @Timeout(60)
    fun `A04 the registry queues behind an open transaction that shares the boundary`() =
        runBlocking {
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                val path = VfsPath.parse("/resources/a.txt")
                val uncommittedId = NodeId.parse("018f0000-0000-7000-8000-0000000000ee")
                val transactionOpen = CompletableDeferred<Unit>()
                val commitNow = CompletableDeferred<Unit>()

                // 另一个协程持着同一把边界，在事务里写了一条还没提交的 Node
                val holder =
                    launch(Dispatchers.Default) {
                        stack.boundary.withLock {
                            stack.unitOfWork.inTransaction { scope ->
                                scope.nodes.register(
                                    NodeRecord(
                                        id = uncommittedId,
                                        path = path,
                                        type = NodeType.FILE,
                                        physical = true,
                                        registeredAt = Instant.now(),
                                        updatedAt = Instant.now(),
                                    ),
                                )
                                transactionOpen.complete(Unit)
                                commitNow.await() // 事务保持打开，状态还没提交
                            }
                        }
                    }
                transactionOpen.await()
                // finally：断言失败也必须把事务放掉，否则挂到超时而不是立刻失败
                try {
                    // Registry 用的是同一把边界，所以只能排队等着
                    val querying = CompletableDeferred<Unit>()
                    val pending =
                        async(Dispatchers.Default) {
                            querying.complete(Unit)
                            stack.registry.resolveOrRegister(path)
                        }
                    querying.await()

                    // 正向检测违规：事务还开着，Registry 就把值返回了 = 共享边界没生效。
                    // 不能只断言「它此刻还没完成」——协程可能只是还没跑到，睡一下就骗过去了。
                    val leaked = withTimeoutOrNull(2_000) { pending.await() }

                    assertNull(leaked, "事务提交前 Registry 就返回了 —— 共享边界没有生效")

                    commitNow.complete(Unit)
                    holder.join()
                    val info = withTimeout(30_000) { pending.await() }

                    assertEquals(uncommittedId, info.id, "边界释放后读到的是事务提交的那条记录")
                    assertEquals(1, stack.rowCount("node"))
                } finally {
                    commitNow.complete(Unit)
                    holder.join()
                }
            }
        }

    @Test
    @Timeout(60)
    fun `A04 an auto-commit read really can see uncommitted state, which is why the boundary is shared`() =
        runBlocking {
            // 反证：同一时刻、同一个连接上，没有共享边界的读**确实能看见别人没提交的行**（T11 复核已复现）。
            // 这条用例把「为什么必须有共享边界」钉成实测事实，不是推断。
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                val path = VfsPath.parse("/resources/a.txt")
                val uncommittedId = NodeId.parse("018f0000-0000-7000-8000-0000000000ee")
                val transactionOpen = CompletableDeferred<Unit>()
                val commitNow = CompletableDeferred<Unit>()
                // 另一个 Registry 实例，用的是**另一把**边界
                val careless =
                    NodeRegistry(
                        stack.router,
                        stack.nodes,
                        { _: String -> stack.storage },
                        StateBoundary(),
                    )

                val holder =
                    launch(Dispatchers.Default) {
                        stack.unitOfWork.inTransaction { scope ->
                            scope.nodes.register(
                                NodeRecord(
                                    id = uncommittedId,
                                    path = path,
                                    type = NodeType.FILE,
                                    physical = true,
                                    registeredAt = Instant.now(),
                                    updatedAt = Instant.now(),
                                ),
                            )
                            transactionOpen.complete(Unit)
                            commitNow.await()
                        }
                    }
                transactionOpen.await()
                try {
                    val info = careless.resolveOrRegister(path)

                    assertEquals(
                        uncommittedId,
                        info.id,
                        "不共享边界时就能读到未提交的行——所以 T15 之后所有状态读写必须走同一把边界",
                    )
                } finally {
                    commitNow.complete(Unit)
                    holder.join()
                }
            }
        }

    @Test
    fun `A05 a closed state store fails with STATE_ERROR and keeps the cause`() =
        runBlocking {
            // 状态库整个关掉之后调 Registry：必须报 STATE_ERROR，原始 JDBC 异常留在 cause 上，
            // 不能被换成 NOT_FOUND、MOUNT_NOT_FOUND 之类「业务上看着正常」的错。
            Files.writeString(diskRoot.resolve("a.txt"), "hello")
            withStack { stack ->
                val path = VfsPath.parse("/resources/a.txt")
                val info = stack.registry.resolveOrRegister(path)
                stack.closeState()

                val resolveFailure = assertFailsWith<VfsException> { stack.registry.resolveOrRegister(path) }

                assertEquals(VfsErrorCode.STATE_ERROR, resolveFailure.code, "查状态失败就是 STATE_ERROR")
                assertNotNull(resolveFailure.cause, "原始异常要留在 cause 上，不许吞掉")

                val getFailure = assertFailsWith<VfsException> { stack.registry.getNode(info.id) }

                assertEquals(VfsErrorCode.STATE_ERROR, getFailure.code)
                assertNotNull(getFailure.cause)
            }
        }

    /** 一次真实的组装：状态库 + 本地磁盘 + 路由 + 边界 + Registry。 */
    private suspend fun <T> withStack(
        mounts: List<MountRecord> = listOf(MountRecord(VfsPath.parse("/resources"), "local")),
        extraStorages: Map<String, LocalFsStorage> = emptyMap(),
        block: suspend (RealStack) -> T,
    ): T {
        val state = VfsStateDatabase.file(databaseFile)
        val opened = mutableListOf<LocalFsStorage>()
        val storages = LinkedHashMap<String, Storage>()
        try {
            val local = LocalFsStorage.create(diskRoot)
            opened += local
            storages["local"] = local
            extraStorages.forEach { (key, storage) ->
                opened += storage
                storages[key] = storage
            }
            val boundary = StateBoundary()
            val stack =
                RealStack(
                    registry =
                        NodeRegistry(
                            MountRouter.of(setOf("resources"), mounts),
                            SqliteNodeRepository(state),
                            { key -> storages[key] },
                            boundary,
                        ),
                    router = MountRouter.of(setOf("resources"), mounts),
                    nodes = SqliteNodeRepository(state),
                    metadata = SqliteMetadataRepository(state),
                    unitOfWork = SqliteUnitOfWork(state),
                    boundary = boundary,
                    storage = local,
                    rowCount = { table -> countRows(table) },
                    closeState = { state.close() },
                    activeNodeCount = { SqliteNodeRepository(state).findSubtree(VfsPath.root).size },
                )
            return block(stack)
        } finally {
            state.close()
            opened.forEach { it.close() }
        }
    }

    /** 直接查表行数：只读统计，走的是 JDK 自带的 JDBC，不额外引入依赖。 */
    private fun countRows(table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /** 组装出来的东西，测试里要用到哪样就拿哪样。 */
    private class RealStack(
        val registry: NodeRegistry,
        val router: MountRouter,
        val nodes: SqliteNodeRepository,
        val metadata: SqliteMetadataRepository,
        val unitOfWork: SqliteUnitOfWork,
        val boundary: StateBoundary,
        val storage: LocalFsStorage,
        val rowCount: (String) -> Int,
        /** 关掉状态库：用来验证「状态库出问题时 Registry 报什么」，close 之后可以重复调。 */
        val closeState: () -> Unit,
        val activeNodeCount: suspend () -> Int,
    )
}
