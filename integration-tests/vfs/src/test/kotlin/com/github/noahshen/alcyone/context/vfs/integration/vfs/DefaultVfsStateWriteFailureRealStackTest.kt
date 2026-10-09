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
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.event.VfsEventConsumer
import com.github.noahshen.alcyone.context.vfs.core.event.toVfsEvent
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith

/**
 * T23 缺口 1：**状态写入失败**（`failOnStateWrite` 那一路）在经 [DefaultVfs] 的真实调用链上的接线证据。
 *
 * 历史上 `FakeStateUnitOfWork.failOnStateWrite` 只在内存替身的事务测试里用过，没有一条用例证明
 * 「写 / 删 / 文件移动 / 目录移动」走到真正的提交事务时，某一次状态写入失败会被 [DefaultVfs]
 * 报成 `STATE_ERROR` + 正确 effect、并把本事务已做的修改全部回滚（旧记录 / Metadata 保留、
 * 失败操作的成功事件未提交）、且不发成功通知。
 *
 * 这里装上**真的 SQLite + 真的本地磁盘**：物理副作用是真盘，状态 / 事件是真的 `SqliteUnitOfWork` 事务，
 * 只有「状态写入这一次本身失败」由包装层注入（真磁盘不会自己造出「事务写到一半被状态库拒绝」）。
 * 层级 = 真实组件 + 故障包装；不冒充「真实 SQLite 自身约束触发」，那一层的机制由
 * [com.github.noahshen.alcyone.context.vfs.integration.event.EventPipelineRealStackTest] 的追加冲突 / 真 COMMIT 失败覆盖。
 */
class DefaultVfsStateWriteFailureRealStackTest {
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

    /** A05：写入物理成功、提交事务里的 `nodes.register` 失败 → `STATE_ERROR` + `PARTIAL`，字节留着、状态回滚。 */
    @Test
    @Timeout(60)
    fun `A05 a state write failure after a real write keeps the bytes and rolls the node back`() =
        runBlocking {
            val injected = VfsException(VfsErrorCode.STATE_ERROR, "state write rejected the node")
            withStack(unitOfWork = { FailingStateWriteUnitOfWork(it, StateWrite.REGISTER, injected) }) { stack ->
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)

                val failure = assertFailsWith<VfsException> { stack.vfs.write(uri("/a.txt"), "written".toByteArray()) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code, "状态写入失败映射成 STATE_ERROR")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "物理已写入，这是已知变更")
                assertSame(injected, failure.cause, "注入的状态写入失败原样挂在 cause 上")
                assertEquals("written", Files.readString(diskRoot.resolve("a.txt")), "物理内容保留，不自动补偿")
                assertEquals(0, activeNodes(), "Node 没有随事务提交")
                assertEquals(0, eventCount(), "事件没有落库")

                // 失败不该有通知：按 ID 末尾标记确认（标记之前若还有别的通知，一定排在它前面）。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(listOf(marker.id.value), recorder.received.map { it.id.value }, "失败的事务没有成功通知")
            }
        }

    /** A05：物理删除成功、提交事务里的 `nodes.markDeleted` 失败 → `STATE_ERROR` + `PARTIAL`，文件不复活、Node / Metadata 保持。 */
    @Test
    @Timeout(60)
    fun `A05 a state write failure after a real delete keeps the delete and the records stay`() =
        runBlocking {
            val injected = VfsException(VfsErrorCode.STATE_ERROR, "state write rejected the delete")
            withStack(unitOfWork = { FailingStateWriteUnitOfWork(it, StateWrite.MARK_DELETED, injected) }) { stack ->
                // 先订阅再预置：分发是异步的，订阅晚一步就可能收不到预置的创建事件。
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)
                // 预置：一次成功的写 + Metadata；它用的 register 不在失败开关上，所以照常提交。
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                stack.metadata.put(id, NodeMetadata(description = "keep"))
                recorder.await(VfsEventId.parse(lastEventId())) // 预置的创建事件已处理完，基线确定
                val baseline = recorder.received.map { it.nodeId }

                val failure = assertFailsWith<VfsException> { stack.vfs.delete(uri("/a.txt")) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code, "状态写入失败映射成 STATE_ERROR")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "物理已删，这是已知变更")
                assertSame(injected, failure.cause, "注入的状态写入失败原样挂在 cause 上")
                assertFalse(Files.exists(diskRoot.resolve("a.txt")), "物理删除不被回退")
                assertEquals(id, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "Node 没有被标删")
                assertEquals(1, activeNodes(), "旧记录仍是有效记录")
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(id), "Metadata 随事务保持")
                assertEquals(1, eventCount(), "只有预置的创建事件，删除事件没落库")

                val marker = EventFactory().newRecord(VfsEventType.FILE_DELETED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(baseline + marker.nodeId, recorder.received.map { it.nodeId }, "删除失败没有成功通知")
            }
        }

    /** A06：文件移动物理成功、提交事务里的 `nodes.updatePath` 失败 → `STATE_ERROR` + `PARTIAL`，文件到目标、路径仍在源。 */
    @Test
    @Timeout(60)
    fun `A06 a state write failure after a real file move keeps the bytes at the target and the path at the source`() =
        runBlocking {
            val injected = VfsException(VfsErrorCode.STATE_ERROR, "state write rejected the path update")
            withStack(unitOfWork = { FailingStateWriteUnitOfWork(it, StateWrite.UPDATE_PATH, injected) }) { stack ->
                // 先订阅再预置：分发是异步的，订阅晚一步就可能收不到预置的创建事件。
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)
                stack.vfs.write(uri("/a.txt"), "hello".toByteArray())
                val id = stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))!!.id
                stack.metadata.put(id, NodeMetadata(description = "keep"))
                recorder.await(VfsEventId.parse(lastEventId()))
                val baseline = recorder.received.map { it.nodeId }

                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/a.txt"), uri("/b.txt")) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code, "状态写入失败映射成 STATE_ERROR")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "物理已搬到目标，这是已知变更")
                assertSame(injected, failure.cause, "注入的状态写入失败原样挂在 cause 上")
                assertFalse(Files.exists(diskRoot.resolve("a.txt")), "旧物理位置真的没了")
                assertEquals("hello", Files.readString(diskRoot.resolve("b.txt")), "目标内容保留，不回移")
                assertEquals(id, stack.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "Node 路径回滚到源")
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/b.txt")), "目标路径没有新记录，也没有重建身份")
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(id), "Metadata 不动")
                assertEquals(1, eventCount(), "移动事件没落库")

                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(baseline + marker.nodeId, recorder.received.map { it.nodeId }, "移动失败没有成功通知")
            }
        }

    /** A05：目录移动物理完成、提交事务里的 `nodes.updatePath` 失败 → `STATE_ERROR` + `PARTIAL`，全部路径 / Metadata 回滚。 */
    @Test
    @Timeout(60)
    fun `A05 a state write failure after a real directory move rolls every path back and keeps the bytes`() =
        runBlocking {
            val injected = VfsException(VfsErrorCode.STATE_ERROR, "state write rejected the directory path update")
            withStack(unitOfWork = { FailingStateWriteUnitOfWork(it, StateWrite.UPDATE_PATH, injected, occurrence = 2) }) { stack ->
                Files.createDirectory(diskRoot.resolve("work"))
                Files.writeString(diskRoot.resolve("work/a.txt"), "A")
                Files.writeString(diskRoot.resolve("work/b.txt"), "B")
                val root = stack.vfs.stat(uri("/work")).id
                val child = stack.vfs.stat(uri("/work/a.txt")).id
                stack.metadata.put(root, NodeMetadata(description = "keep"))

                // stat 懒注册不发事件，所以失败前的收件箱就是空的。
                val recorder = TailRecorder()
                stack.notifier.subscribe(recorder)

                val failure = assertFailsWith<VfsException> { stack.vfs.move(uri("/work"), uri("/archive/work")) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code, "状态写入失败映射成 STATE_ERROR")
                assertEquals(VfsEffect.PARTIAL, failure.effect, "物理已整树搬完，这是已知变更")
                assertSame(injected, failure.cause, "注入的状态写入失败原样挂在 cause 上")
                assertFalse(Files.exists(diskRoot.resolve("work")), "物理不回移")
                assertEquals("A", Files.readString(diskRoot.resolve("archive/work/a.txt")), "目标内容保留")
                assertEquals("B", Files.readString(diskRoot.resolve("archive/work/b.txt")))
                assertEquals(root, stack.nodes.findByPath(VfsPath.parse("/resources/work"))?.id, "根路径回滚到源")
                assertEquals(child, stack.nodes.findByPath(VfsPath.parse("/resources/work/a.txt"))?.id, "后代路径也回滚")
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/archive/work")), "目标路径没有记录")
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(root), "Metadata 不动")
                assertEquals(0, eventCount(), "目录移动事件没落库")

                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(listOf(marker.id.value), recorder.received.map { it.id.value }, "失败的事务没有成功通知")
            }
        }

    private suspend fun <T> withStack(
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
            try {
                return block(
                    RealStack(
                        vfs =
                            DefaultVfs(
                                router = router,
                                capabilities = CapabilitySnapshot.of(mapOf("local" to local.capabilities())),
                                registry = NodeRegistry(router, nodes, { key -> if (key == "local") local else null }, boundary),
                                nodes = nodes,
                                metadata = metadata,
                                storages = { key -> if (key == "local") local else null },
                                boundary = boundary,
                                pipeline = EventPipeline(boundary, unitOfWork(SqliteUnitOfWork(state)), notifier),
                            ),
                        nodes = nodes,
                        metadata = metadata,
                        notifier = notifier,
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
    )

    /** 直接查表：只读统计，走 JDK 自带 JDBC。 */
    private fun eventCount(): Int = countRows("event")

    private fun activeNodes(): Int = countRows("node WHERE deleted_at IS NULL")

    private fun lastEventId(): String =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_id FROM event ORDER BY rowid DESC LIMIT 1").use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }

    private fun countRows(fromWhere: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $fromWhere").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /** 按事件 ID 等待的记录器：核对「失败不发成功通知」。 */
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
}

/** [DefaultVfsStateWriteFailureRealStackTest] 要点火的那次状态写入。 */
private enum class StateWrite {
    REGISTER,
    UPDATE_PATH,
    MARK_DELETED,
}

/**
 * 包一层事务：命中 [failOn] 的那次状态写入直接抛出 [failure]，事务、回滚、约束全是真实 SQLite。
 *
 * 只拦这一个方法；其余（`findByPath` / `findSubtree` / Metadata / 事件）原样转发。
 * 注入的是「状态写入这一次失败」这个事实，不模拟状态库的每一种拒绝原因。
 */
private class FailingStateWriteUnitOfWork(
    private val delegate: SqliteUnitOfWork,
    private val failOn: StateWrite,
    private val failure: VfsException,
    /** 命中 [failOn] 的第几次调用才点火：>1 时前面那几次会真的写进事务，用来验证回滚。 */
    private val occurrence: Int = 1,
) : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T =
        delegate.inTransaction { scope ->
            var seen = 0
            val decorated =
                object : TransactionScope {
                    override val nodes: NodeRepository =
                        object : NodeRepository by scope.nodes {
                            override suspend fun register(record: NodeRecord): NodeRecord {
                                if (failOn == StateWrite.REGISTER && ++seen == occurrence) throw failure
                                return scope.nodes.register(record)
                            }

                            override suspend fun updatePath(
                                id: NodeId,
                                newPath: VfsPath,
                                updatedAt: Instant,
                            ) {
                                if (failOn == StateWrite.UPDATE_PATH && ++seen == occurrence) throw failure
                                scope.nodes.updatePath(id, newPath, updatedAt)
                            }

                            override suspend fun markDeleted(
                                ids: Collection<NodeId>,
                                deletedAt: Instant,
                            ) {
                                if (failOn == StateWrite.MARK_DELETED && ++seen == occurrence) throw failure
                                scope.nodes.markDeleted(ids, deletedAt)
                            }
                        }

                    override val metadata get() = scope.metadata

                    override val events get() = scope.events
                }
            block(decorated)
        }
}
