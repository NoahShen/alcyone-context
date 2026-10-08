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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
