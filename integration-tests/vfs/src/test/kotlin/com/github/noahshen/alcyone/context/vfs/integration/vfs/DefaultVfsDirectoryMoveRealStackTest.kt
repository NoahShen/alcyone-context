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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * T22 A05：目录移动装上**真的 SQLite 状态库**和**两个临时 Local FS 根**，在根与至少一个后代已经 `updatePath`
 * 之后让事件追加冲突，验证真实事务把全部逻辑路径 / ID / Metadata 一起回滚，物理变更不回移。
 *
 * 公开 Runtime 的成功 / 重开证据在 `RuntimeDirectoryMoveTest`，Core 替身证据在 `DefaultVfsDirectoryMoveTest`。
 */
class DefaultVfsDirectoryMoveRealStackTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var leftRoot: Path
    private lateinit var rightRoot: Path
    private lateinit var databaseFile: Path

    @BeforeEach
    fun setUp() {
        leftRoot = Files.createDirectory(tempDir.resolve("left"))
        rightRoot = Files.createDirectory(tempDir.resolve("right"))
        databaseFile = tempDir.resolve("state/state.db")
        Files.createDirectories(databaseFile.parent)
    }

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    /**
     * A05：真 SQLite 里根与后代都更新后事件追加冲突 → `STATE_ERROR` + `PARTIAL`，
     * 全部原路径 / ID / Metadata 回滚，物理不回移，没有持久化成功事件，也没有成功通知。
     */
    @Test
    @Timeout(60)
    fun `A05 a real sqlite append conflict after a directory move rolls every path back and keeps the bytes`() =
        runBlocking {
            Files.createDirectory(leftRoot.resolve("work"))
            Files.writeString(leftRoot.resolve("work/a.txt"), "A")
            Files.writeString(leftRoot.resolve("work/b.txt"), "B")
            val colliding = VfsEventId.parse("018f0a5c-1b2c-7def-8abc-0000000000f2")
            withStack(unitOfWork = { DirectoryCollidingEventUnitOfWork(it, colliding) }) { stack ->
                val rootId = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000c2")
                val childId = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000c3")
                val now = Instant.now()
                stack.nodes.register(NodeRecord(rootId, VfsPath.parse("/resources/local/work"), NodeType.DIRECTORY, true, now, now))
                stack.nodes.register(NodeRecord(childId, VfsPath.parse("/resources/local/work/a.txt"), NodeType.FILE, true, now, now))
                stack.metadata.put(rootId, NodeMetadata(description = "keep"))
                // 预置一条 colliding 事件：本次事务的唯一事件会被换成同一个 ID，真实主键冲突。
                stack.rawUnitOfWork.inTransaction { scope ->
                    scope.events.append(
                        EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/seed.txt")).copy(id = colliding),
                    )
                }
                val eventsBefore = eventCount()
                val recorder = DirectoryMoveRecorder()
                stack.notifier.subscribe(recorder)

                val failure =
                    assertFailsWith<VfsException> {
                        stack.vfs.move(uri("/local/work"), uri("/archive/work"))
                    }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(VfsEffect.PARTIAL, failure.effect, "物理已搬完")
                assertNotNull(failure.cause, "底层原因保留在 cause 上")
                assertFalse(Files.exists(leftRoot.resolve("work")), "物理删除不回退")
                assertEquals("A", Files.readString(rightRoot.resolve("work/a.txt")), "目标内容保留")
                assertEquals("B", Files.readString(rightRoot.resolve("work/b.txt")))
                assertEquals(rootId, stack.nodes.findByPath(VfsPath.parse("/resources/local/work"))?.id, "根路径回滚到源")
                assertEquals(childId, stack.nodes.findByPath(VfsPath.parse("/resources/local/work/a.txt"))?.id, "后代路径也回滚")
                assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/archive/work")), "目标路径没有记录")
                assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(rootId), "Metadata 不动")
                assertEquals(eventsBefore, eventCount(), "移动事件没进库")

                // 末尾哨兵按 ID 等：哨兵之前若真有成功通知，它一定已经先到。
                val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                stack.notifier.publish(listOf(marker.toVfsEvent()))
                recorder.await(marker.id)
                assertEquals(listOf(marker.id.value), recorder.received.map { it.id.value }, "失败的事务没有成功通知")
            }
        }

    /**
     * A06：目录移动物理已完成（源已删、目标完整）、第一次 `updatePath` 已写入但事务未 COMMIT 时取消 →
     * 取消原样传播，物理事实保留、全部逻辑路径 / Metadata 随事务回滚、无成功通知，边界随后可用。
     */
    @Test
    @Timeout(60)
    fun `A06 a directory move cancelled before the commit propagates and rolls the paths back`() =
        runBlocking {
            Files.createDirectory(leftRoot.resolve("work"))
            Files.writeString(leftRoot.resolve("work/a.txt"), "A")
            Files.writeString(leftRoot.resolve("work/b.txt"), "B")
            lateinit var pausing: PausingDirectoryCommitUnitOfWork
            withStack(unitOfWork = { real -> PausingDirectoryCommitUnitOfWork(real).also { wrapper -> pausing = wrapper } }) { stack ->
                val root = stack.vfs.stat(uri("/local/work")).id
                val child = stack.vfs.stat(uri("/local/work/a.txt")).id
                stack.metadata.put(root, NodeMetadata(description = "keep"))
                // 先订阅：分发是异步的，订阅晚一步就可能收不到后面那次的创建事件。
                val recorder = DirectoryMoveRecorder()
                stack.notifier.subscribe(recorder)
                val cancelled = CancellationException("cancelled while committing the directory move")

                // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，
                // 拿它当证据等于什么都没测，所以要留下移动链自己真正抛出来的那一个。
                val escaped = CompletableDeferred<Throwable?>()
                val job =
                    async(Dispatchers.Default) {
                        try {
                            stack.vfs.move(uri("/local/work"), uri("/archive/work"))
                            escaped.complete(null)
                        } catch (failure: Throwable) {
                            escaped.complete(failure)
                        }
                    }
                // 从这里开始用 try/finally 托管：不管 awaitUpdatePath 超时还是线程被中断，
                // 都会先把在途子协程取消并等它（含事务回滚）真正结束，再退出 withStack 关资源。
                try {
                    pausing.awaitUpdatePath() // 物理已搬完、第一次 updatePath 已写进库，事务还没 COMMIT
                    job.cancel(cancelled)

                    withTimeout(30_000) { job.join() }
                    val escapedFailure = withTimeout(30_000) { escaped.await() }
                    assertNotNull(escapedFailure, "移动链应该把取消抛出来，而不是安静地结束")
                    assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                    assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                    assertSame(cancelled, escapedFailure?.cause ?: escapedFailure, "取消原因一致")

                    // 实际事实：物理搬完这件事不会因为取消而消失；逻辑路径随事务回滚。
                    assertFalse(Files.exists(leftRoot.resolve("work")), "物理删除已完成，不声称「取消 = 没搬过」")
                    assertEquals("A", Files.readString(rightRoot.resolve("work/a.txt")), "目标内容保留")
                    assertEquals("B", Files.readString(rightRoot.resolve("work/b.txt")))
                    assertEquals(root, stack.nodes.findByPath(VfsPath.parse("/resources/local/work"))?.id, "根路径回滚到源")
                    assertEquals(child, stack.nodes.findByPath(VfsPath.parse("/resources/local/work/a.txt"))?.id, "后代路径也回滚")
                    assertNull(stack.nodes.findByPath(VfsPath.parse("/resources/archive/work")), "目标路径没有记录")
                    assertEquals(NodeMetadata(description = "keep"), stack.metadata.get(root), "Metadata 未改")
                    assertEquals(0, eventCount(), "取消不追加目录事件")
                    assertEquals(1, pausing.pausedUpdates(), "只有第一次 updatePath 挂起过")

                    // 锁已放行：紧接着的写能拿到同一把边界并完成——能跑完就是锁没被留下的证据。
                    val after = stack.vfs.write(uri("/local/after.txt"), "after".toByteArray())
                    assertEquals("after", Files.readString(leftRoot.resolve("after.txt")))
                    assertEquals(listOf("FILE_CREATED"), eventTypes(), "被取消的那次没有留下目录事件")

                    // 末尾哨兵按 ID 等：被取消的那次一条通知都没有。
                    val marker = EventFactory().newRecord(VfsEventType.FILE_CREATED, null, uri("/tail.txt"))
                    stack.notifier.publish(listOf(marker.toVfsEvent()))
                    recorder.await(marker.id)
                    assertEquals(listOf(after.id, null), recorder.received.map { it.nodeId }, "被取消的那次没有通知")
                } finally {
                    // 兜底：正常路径已 join 过，这里是异常路径的保障。取消与等待都必须躲开外层的取消，
                    // 否则「结束在途工作」会自己先被取消掉；完成这一步之后 withStack 的 finally 才关资源。
                    withContext(NonCancellable) {
                        if (job.isActive) job.cancel(cancelled)
                        job.join()
                    }
                }
            }
        }

    /** 用真库与两个真盘组装一个 [DefaultVfs]；用完就关。 */
    private suspend fun <T> withStack(
        unitOfWork: (SqliteUnitOfWork) -> UnitOfWork = { it },
        block: suspend (DirectoryStack) -> T,
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
            val byKey = mapOf("left" to left, "right" to right)
            try {
                return block(
                    DirectoryStack(
                        vfs =
                            DefaultVfs(
                                router = router,
                                capabilities = CapabilitySnapshot.of(byKey.mapValues { (_, storage) -> storage.capabilities() }),
                                registry = NodeRegistry(router, nodes, { key -> byKey[key] }, boundary),
                                nodes = nodes,
                                metadata = metadata,
                                storages = { key -> byKey[key] },
                                boundary = boundary,
                                pipeline = EventPipeline(boundary, unitOfWork(SqliteUnitOfWork(state)), notifier),
                            ),
                        nodes = nodes,
                        metadata = metadata,
                        notifier = notifier,
                        rawUnitOfWork = SqliteUnitOfWork(state),
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

    private class DirectoryStack(
        val vfs: DefaultVfs,
        val nodes: SqliteNodeRepository,
        val metadata: SqliteMetadataRepository,
        val notifier: AsyncEventNotifier,
        val rawUnitOfWork: SqliteUnitOfWork,
    )

    private fun eventCount(): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM event").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun eventTypes(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_type FROM event ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
}

/** 包一层事务：第一条事件换成固定 ID，与预置事件撞主键；事务、约束、回滚都是真的。 */
private class DirectoryCollidingEventUnitOfWork(
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

/** 按事件 ID 等待的记录器：核对「失败不发成功通知」。 */
private class DirectoryMoveRecorder : VfsEventConsumer {
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
 * 包一层事务：第一次 [NodeRepository.updatePath] **真写进库里**之后停一下，让取消正好落在
 * 「物理搬完、事务还没 COMMIT」的窗口里。
 *
 * 事务、回滚全是真的（SqliteUnitOfWork 在 NonCancellable 里 ROLLBACK）；包装器只多加一个挂起点。
 */
private class PausingDirectoryCommitUnitOfWork(
    private val delegate: SqliteUnitOfWork,
) : UnitOfWork {
    /** 见过几次 updatePath；只有第一次会真的挂起。 */
    private val updates = AtomicInteger()

    /** 真的挂起了几次：取消之后应当仍然是 1，说明后来的操作没再被拦住。 */
    private val paused = AtomicInteger()
    private val arrivals = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T =
        delegate.inTransaction { scope ->
            val decorated =
                object : TransactionScope {
                    override val nodes: NodeRepository =
                        object : NodeRepository by scope.nodes {
                            override suspend fun updatePath(
                                id: NodeId,
                                newPath: VfsPath,
                                updatedAt: Instant,
                            ) {
                                scope.nodes.updatePath(id, newPath, updatedAt) // 先真的写进库里
                                if (updates.incrementAndGet() == 1) {
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

    /** 第一次 updatePath 已经写入、事务尚未提交。 */
    suspend fun awaitUpdatePath() {
        withTimeout(30_000) { arrivals.receive() }
    }

    /** 真的挂起过几次：取消之后仍应是 1。 */
    fun pausedUpdates(): Int = paused.get()
}
