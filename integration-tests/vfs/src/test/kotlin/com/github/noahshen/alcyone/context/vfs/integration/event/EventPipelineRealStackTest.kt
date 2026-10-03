package com.github.noahshen.alcyone.context.vfs.integration.event

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.event.toVfsEvent
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith

/**
 * T14 S3：事件接线装上**真的 SQLite 状态库**跑一遍。
 *
 * 替身证明不了的部分在这里证明：状态和事件真的同批提交、事件追加失败会把同批状态一起回滚、
 * 关掉重开库读到的还是那一条、提交之前 Consumer 收不到任何东西。
 *
 * 时序用闩锁控制，不用 `Thread.sleep`；并发用例都有 [Timeout] 兜底。
 */
class EventPipelineRealStackTest {
    @TempDir
    lateinit var tempDir: Path

    /** 状态库文件；关掉再打开才能证明「提交过的都还在」。 */
    private lateinit var databaseFile: Path

    private val events = EventFactory()
    private val nodeId = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000c1")
    private val uri = VfsUri.parse("alcyone://resources/notes/a.txt")

    @BeforeEach
    fun setUp() {
        databaseFile = tempDir.resolve("state.db")
    }

    private fun record(
        type: VfsEventType = VfsEventType.FILE_WRITTEN,
        uri: VfsUri = this.uri,
    ) = events.newRecord(type, nodeId, uri)

    private fun node(
        path: String,
        id: String = "018f0a5c-1b2c-7def-8abc-0000000000c1",
    ) = NodeRecord(
        id = NodeId.parse(id),
        path = VfsPath.parse(path),
        type = NodeType.FILE,
        physical = true,
        registeredAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    @Timeout(60)
    fun `A02 a rejected event id rolls back the node registered in the same transaction`() =
        runBlocking {
            withStack { stack ->
                val first = record()

                stack.pipeline.commit { scope ->
                    scope.nodes.register(node("/notes/a.txt"))
                    scope.events.append(first)
                }

                // 第二次用同一个事件 ID：数据库主键冲突 → 真实失败，不是假驱动造的
                val failure =
                    assertFailsWith<VfsException> {
                        stack.pipeline.commit { scope ->
                            scope.nodes.register(node("/notes/b.txt", "018f0a5c-1b2c-7def-8abc-0000000000c2"))
                            scope.events.append(first.copy(uri = VfsUri.parse("alcyone://resources/notes/b.txt")))
                        }
                    }

                assertEquals(1, countRows("event"), "失败的那批事件没留下")
                assertEquals(1, activeNodes(), "同批登记的 /notes/b.txt 一起回滚了")
                assertEquals(nodeId, stack.nodes.findByPath(path("/notes/a.txt"))?.id, "先提交的那次还在")
                assertEquals(VfsErrorCode.STATE_ERROR, failure.code, "状态库拒绝了重复的事件 ID")
            }
        }

    private fun path(text: String) = VfsPath.parse(text)

    @Test
    @Timeout(60)
    fun `A01 A02 a committed event is still there after the database is closed and reopened`() =
        runBlocking {
            val written = record()
            withStack { stack ->
                stack.pipeline.commit { scope ->
                    scope.nodes.register(node("/notes/a.txt"))
                    scope.metadata.put(nodeId, NodeMetadata(description = "写好的说明"))
                    scope.events.append(written)
                }
            } // 到这里状态库整个关掉了

            val reopened = withReopenedDatabase { state -> SqliteMetadataRepository(state).get(nodeId) }
            val rows = readEvents()

            assertEquals(1, rows.size, "重开库后事件日志里就是那一条")
            assertEquals(written.id.value, rows[0].id)
            assertEquals("FILE_WRITTEN", rows[0].type)
            assertEquals(uri.toString(), rows[0].uri)
            assertEquals(nodeId.value, rows[0].nodeId)
            assertEquals(written.occurredAt.toEpochMilli(), rows[0].occurredAt, "存的是 epoch 毫秒，和通知里的时间是同一个值")
            assertEquals(NodeMetadata(description = "写好的说明"), reopened)
        }

    @Test
    @Timeout(60)
    fun `A03 no consumer hears anything before the transaction commits`() =
        runBlocking {
            withStack { stack ->
                val probeArrived = CompletableDeferred<Unit>()
                val heard = CopyOnWriteArrayList<VfsEvent>()
                stack.notifier.subscribe { probeArrived.complete(Unit) }
                stack.notifier.subscribe { heard.add(it) }
                // 探针：先证明分发链路真的活着，下面「没收到」才是有意义的
                stack.notifier.publish(listOf(record(VfsEventType.FILE_CREATED).toVfsEvent()))
                withTimeout(30_000) { probeArrived.await() }

                val committed = record()
                val committedIds = CopyOnWriteArrayList<String>()
                val notifiedAt = CompletableDeferred<Long>()
                stack.notifier.subscribe { event ->
                    if (event.id == committed.id) {
                        committedIds.add(event.id.value)
                        notifiedAt.complete(event.occurredAt.toEpochMilli())
                    }
                }

                val insideTransaction = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val commit =
                    launch(Dispatchers.Default) {
                        stack.pipeline.commit { scope ->
                            scope.nodes.register(node("/notes/a.txt"))
                            scope.events.append(committed)
                            insideTransaction.complete(Unit)
                            release.await() // 事务开着：COMMIT 还没发生
                        }
                    }
                insideTransaction.await()
                try {
                    assertTrue(committedIds.isEmpty(), "事务还没提交，Consumer 一个事件都没收到")
                    assertEquals(0, activeNodes(), "同批的 Node 也还没落库")
                    assertEquals(0, countRows("event"))
                    assertEquals(1, heard.size, "到此刻只收到探针那一条")
                } finally {
                    release.complete(Unit)
                }
                commit.join()
                val millis = withTimeout(30_000) { notifiedAt.await() }

                assertEquals(listOf(committed.id.value), committedIds, "提交后才收到，而且只收到这一条")
                assertEquals(2, heard.size, "探针 + 提交的那一条")
                assertEquals(readEvents().single().let { it.id to it.occurredAt }, committedIds.single() to millis, "通知的就是库里那一条")
                assertEquals(1, activeNodes())
            }
        }

    @Test
    @Timeout(60)
    fun `A04 a failing consumer and a parked one do not change the committed result`() =
        runBlocking {
            withStack { stack ->
                val delivered = CopyOnWriteArrayList<VfsEvent>()
                val parkedIn = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val bothArrived = CompletableDeferred<Unit>()
                stack.notifier.subscribe { throw IOException("index push failed") } // 抛错的那个
                stack.notifier.subscribe {
                    // 挂起的那个
                    parkedIn.complete(Unit)
                    release.await()
                }
                stack.notifier.subscribe { event ->
                    delivered.add(event)
                    if (delivered.size == 2) bothArrived.complete(Unit)
                }

                val first = record()
                withTimeout(30_000) {
                    stack.pipeline.commit { scope ->
                        scope.nodes.register(node("/notes/a.txt"))
                        scope.events.append(first)
                    }
                }
                withTimeout(30_000) { parkedIn.await() }

                val second = record(VfsEventType.METADATA_UPDATED, VfsUri.parse("alcyone://resources/notes/b.txt"))
                withTimeout(30_000) { stack.pipeline.commit { scope -> scope.events.append(second) } }

                // 抛错的和挂起的都还在进行中，提交入口已经返回，数据也真的提交了
                assertEquals(2, countRows("event"), "Consumer 挂住不挡提交，事件都入库了")
                assertEquals(1, activeNodes())

                release.complete(Unit)
                withTimeout(30_000) { bothArrived.await() }

                assertEquals(listOf(first.id, second.id), delivered.map { it.id }, "抛错的 Consumer 不影响其他人和后续事件")
                assertEquals(listOf(first.id.value, second.id.value), readEvents().map { it.id })
            }
        }

    @Test
    @Timeout(60)
    fun `A05 a full buffer drops the in-process event and leaves the committed log untouched`() =
        runBlocking {
            withStack(capacity = 1) { stack ->
                val parkedIn = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val delivered = CopyOnWriteArrayList<VfsEvent>()
                val bothArrived = CompletableDeferred<Unit>()
                stack.notifier.subscribe {
                    parkedIn.complete(Unit)
                    release.await() // 队列被这个 Consumer 堵着
                }
                stack.notifier.subscribe { event ->
                    delivered.add(event)
                    if (delivered.size == 2) bothArrived.complete(Unit)
                }

                val first = record()
                withTimeout(30_000) { stack.pipeline.commit { scope -> scope.events.append(first) } }
                withTimeout(30_000) { parkedIn.await() } // 第一条已被分发协程取走，队列腾空

                val second = record(VfsEventType.FILE_WRITTEN, VfsUri.parse("alcyone://resources/notes/b.txt"))
                val third = record(VfsEventType.METADATA_UPDATED, VfsUri.parse("alcyone://resources/notes/c.txt"))
                withTimeout(30_000) { stack.pipeline.commit { scope -> scope.events.append(second) } }
                withTimeout(30_000) { stack.pipeline.commit { scope -> scope.events.append(third) } }

                assertEquals(1, stack.notifier.droppedEvents, "满了就丢：不阻塞发布方，也不报错")
                assertEquals(3, countRows("event"), "丢弃只影响进程内通知，已提交的事件日志一条不少")
                assertEquals(
                    listOf(first.id.value, second.id.value, third.id.value),
                    readEvents().map { it.id },
                    "被丢掉的第三条照样在事件日志里",
                )

                release.complete(Unit)
                withTimeout(30_000) { bothArrived.await() }

                assertEquals(listOf(first.id, second.id), delivered.map { it.id }, "丢的是第三条，入队成功的照常送达")
                assertEquals(1, stack.notifier.droppedEvents)
            }
        }

    @Test
    @Timeout(60)
    fun `A06 close is idempotent, stops the worker and leaves the host job alone`() =
        runBlocking {
            withStack { stack ->
                val hostJob = SupervisorJob()
                val hostScope = CoroutineScope(hostJob + Dispatchers.Default)
                val delivered = CopyOnWriteArrayList<VfsEvent>()
                stack.notifier.subscribe { delivered.add(it) }
                stack.pipeline.commit { scope -> scope.events.append(record()) }
                withTimeout(30_000) {
                    while (delivered.isEmpty()) {
                        delay(5)
                    }
                }

                stack.notifier.close()
                stack.notifier.close() // 第二次是空操作

                assertTrue(stack.notifier.isClosed)
                assertTrue(stack.notifier.job.isCompleted, "close 之后自己的分发协程真的结束了")
                assertTrue(hostJob.isActive, "通知器不碰宿主自己的 Job")

                val afterClose = record(VfsEventType.FILE_CREATED)
                stack.pipeline.commit { scope -> scope.events.append(afterClose) } // 关闭后提交：不抛、不惊动别人

                assertEquals(2, countRows("event"), "关闭后提交照样入库，只是不再有进程内通知")
                assertEquals(0, stack.notifier.droppedEvents, "关闭不是队列满，不计入丢弃")
                assertEquals(1, delivered.size)

                val hostStillRuns = CompletableDeferred<Unit>()
                hostScope.launch { hostStillRuns.complete(Unit) }
                withTimeout(30_000) { hostStillRuns.await() }
                hostJob.cancel()
            }
        }

    @Test
    @Timeout(60)
    fun `A06 a cancelled caller is not wrapped and nothing is committed or notified`() =
        runBlocking {
            withStack { stack ->
                val heard = CopyOnWriteArrayList<VfsEvent>()
                val probeArrived = CompletableDeferred<Unit>()
                stack.notifier.subscribe { probeArrived.complete(Unit) }
                stack.notifier.subscribe { heard.add(it) }
                stack.notifier.publish(listOf(record(VfsEventType.FILE_CREATED).toVfsEvent()))
                withTimeout(30_000) { probeArrived.await() } // 先证明分发链路是活的

                val parkedInside = CompletableDeferred<Unit>()
                val caller =
                    launch(Dispatchers.Default) {
                        stack.pipeline.commit { scope ->
                            scope.nodes.register(node("/notes/a.txt"))
                            scope.events.append(record())
                            parkedInside.complete(Unit)
                            awaitCancellation() // 控制的是「提交前」这个阶段
                        }
                    }
                parkedInside.await()
                caller.cancel()
                caller.join()

                assertTrue(caller.isCancelled, "调用方取消原样传播")
                assertEquals(0, countRows("event"), "提交前取消：事件没落库")
                assertEquals(0, activeNodes(), "提交前取消：状态也一起回滚")
                assertEquals(1, heard.size, "只有探针那条，取消的事务没通知任何人")
            }
        }

    @Test
    @Timeout(60)
    fun `A03 a transaction that throws publishes nothing`() =
        runBlocking {
            withStack { stack ->
                val heard = CopyOnWriteArrayList<VfsEvent>()
                val probeArrived = CompletableDeferred<Unit>()
                stack.notifier.subscribe { probeArrived.complete(Unit) }
                stack.notifier.subscribe { heard.add(it) }
                stack.notifier.publish(listOf(record(VfsEventType.FILE_CREATED).toVfsEvent()))
                withTimeout(30_000) { probeArrived.await() }

                val thrown =
                    assertFailsWith<IllegalStateException> {
                        stack.pipeline.commit { scope ->
                            scope.nodes.register(node("/notes/a.txt"))
                            scope.events.append(record())
                            error("the caller gave up before commit")
                        }
                    }

                assertEquals(0, countRows("event"))
                assertEquals(0, activeNodes())
                assertEquals(1, heard.size, "失败的事务一个事件都不通知")
                assertTrue(thrown.message!!.contains("gave up"))
            }
        }

    @Test
    @Timeout(60)
    fun `A03 a real commit failure publishes nothing`() =
        runBlocking {
            withStack { stack ->
                val heard = CopyOnWriteArrayList<VfsEvent>()
                val probeArrived = CompletableDeferred<Unit>()
                stack.notifier.subscribe { probeArrived.complete(Unit) }
                stack.notifier.subscribe { heard.add(it) }
                stack.notifier.publish(listOf(record(VfsEventType.FILE_CREATED).toVfsEvent()))
                withTimeout(30_000) { probeArrived.await() } // 先证明分发链路是活的

                val insideTransaction = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val outcome = CompletableDeferred<Throwable?>()
                val commit =
                    launch(Dispatchers.Default) {
                        outcome.complete(
                            runCatching {
                                stack.pipeline.commit { scope ->
                                    scope.nodes.register(node("/notes/a.txt"))
                                    scope.events.append(record())
                                    insideTransaction.complete(Unit)
                                    release.await() // 事务开着，COMMIT 还没发生
                                }
                            }.exceptionOrNull(),
                        )
                    }
                insideTransaction.await()
                stack.closeState() // 真把状态库关掉：COMMIT 这一步会真的失败
                release.complete(Unit)
                commit.join()

                val thrown = withTimeout(30_000) { outcome.await() }
                assertEquals(VfsErrorCode.STATE_ERROR, (thrown as? VfsException)?.code, "提交真的失败了，实际：$thrown")
                assertEquals(1, heard.size, "提交失败的这一批一个事件都没通知，收到的那条是探针")
                assertEquals(0, stack.notifier.droppedEvents)
            }
        }

    /** 关掉重开：证明「提交过的东西在库里」，不是内存里的假象。 */
    private suspend fun <T> withReopenedDatabase(block: suspend (VfsStateDatabase) -> T): T {
        val state = VfsStateDatabase.file(databaseFile)
        try {
            return block(state)
        } finally {
            state.close()
        }
    }

    private data class EventRow(
        val id: String,
        val type: String,
        val nodeId: String?,
        val occurredAt: Long,
        val uri: String,
    )

    private fun readEvents(): List<EventRow> =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_id, event_type, node_id, occurred_at, uri FROM event ORDER BY rowid").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(EventRow(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4), rows.getString(5)))
                        }
                    }
                }
            }
        }

    /** 一次真实组装：Core 的接线 + 真 SQLite；用完就关，关掉还能重开验证持久性。 */
    private suspend fun <T> withStack(
        /** 队列容量调小是为了逼出「满了怎么办」的行为；默认就是生产用的容量。 */
        capacity: Int = AsyncEventNotifier.DEFAULT_CAPACITY,
        block: suspend (RealStack) -> T,
    ): T {
        val state = VfsStateDatabase.file(databaseFile)
        try {
            val boundary = StateBoundary()
            val notifier = AsyncEventNotifier(capacity)
            val stack =
                RealStack(
                    pipeline = EventPipeline(boundary, SqliteUnitOfWork(state), notifier),
                    nodes = SqliteNodeRepository(state),
                    metadata = SqliteMetadataRepository(state),
                    notifier = notifier,
                    closeState = { state.close() },
                )
            try {
                return block(stack)
            } finally {
                notifier.close()
            }
        } finally {
            state.close()
        }
    }

    private class RealStack(
        val pipeline: EventPipeline,
        val nodes: SqliteNodeRepository,
        val metadata: SqliteMetadataRepository,
        val notifier: AsyncEventNotifier,
        /** 关掉状态库：用来让「提交这一步真的失败」，close 之后可以重复调。 */
        val closeState: () -> Unit,
    )

    /** 直接查表：只读统计与事件行，走 JDK 自带 JDBC，不额外引入依赖。 */
    private fun countRows(table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
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
