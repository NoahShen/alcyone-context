package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import com.github.noahshen.alcyone.context.vfs.core.transaction.FakeStateUnitOfWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList

/** T14 S1：状态与事件同事务、提交成功才通知。内存替身管分支，真实 SQLite 的原子性放集成测试。 */
@Timeout(60)
class EventPipelineTest {
    /** 统一登记 + @AfterEach 关闭：断言失败也不留分发协程。 */
    private val notifiers = TrackedNotifiers()

    private val filePath = VfsPath.parse("/notes/a.txt")

    private fun node(name: String = "a.txt") =
        NodeRecord(
            id = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000b1"),
            path = VfsPath.parse("/notes/$name"),
            type = NodeType.FILE,
            physical = true,
            registeredAt = FIXED_TIME,
            updatedAt = FIXED_TIME,
        )

    @Test
    fun `A01 commit publishes exactly the appended events and the state is already committed`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val notifier = notifiers.create()
            val committedAtDelivery = CompletableDeferred<Int>()
            val recorder = EventRecorder(2)
            notifier.subscribe { committedAtDelivery.complete(uow.snapshot().third.size) }
            notifier.subscribe(recorder)
            val pipeline = EventPipeline(StateBoundary(), uow, notifier)
            val created = testRecord(VfsEventType.FILE_CREATED)
            val written = testRecord(VfsEventType.FILE_WRITTEN)

            val returned =
                pipeline.commit { scope ->
                    scope.nodes.register(node())
                    scope.events.append(created)
                    scope.events.append(written)
                    "written"
                }
            recorder.await()

            assertEquals("written", returned)
            assertEquals(listOf(created.id, written.id), recorder.received.map { it.id }, "通知的 ID 就是提交的那两条")
            assertEquals(listOf(created.uri, written.uri), recorder.received.map { it.uri })
            assertEquals(2, withTimeout(30_000) { committedAtDelivery.await() }, "通知发生时事务已经提交完成")
            assertEquals(listOf(created, written), uow.snapshot().third)
        }

    @Test
    fun `A02 an event append failure rolls the state change back and publishes nothing`() =
        runBlocking {
            val uow =
                FakeStateUnitOfWork().apply {
                    failOnEventAppend = FakeStateUnitOfWork.storageFailure("event log write failed")
                }
            val notifier = notifiers.create()
            val recorder = EventRecorder(1) // 只等末尾标记
            notifier.subscribe(recorder)
            val pipeline = EventPipeline(StateBoundary(), uow, notifier)
            val record = testRecord()

            val failure =
                runCatching {
                    pipeline.commit { scope ->
                        scope.nodes.register(node())
                        scope.events.append(record)
                    }
                }.exceptionOrNull()

            assertTrue(failure != null, "追加事件失败必须让事务失败")
            assertTrue(uow.snapshot().first.isEmpty(), "同批的 Node 变更一起回滚")
            val marker = sentinelEvent()
            notifier.publish(listOf(marker))
            recorder.await() // 等到标记 = 队列里排在它前面的都处理完了

            assertEquals(listOf(marker.id), recorder.received.map { it.id }, "失败的这次事务一个事件都没通知")
        }

    @Test
    fun `A03 nothing is published while the transaction is still open`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val notifier = notifiers.create()
            val probe = EventRecorder(1)
            val pipeline = EventPipeline(StateBoundary(), uow, notifier)
            val record = testRecord()
            // 只盯这一条事件的 Consumer：探针不算进来
            val seen = CopyOnWriteArrayList<String>()
            val delivered = CompletableDeferred<Unit>()
            notifier.subscribe { event ->
                if (event.id == record.id) {
                    seen.add(event.id.value)
                    delivered.complete(Unit)
                }
            }
            notifier.subscribe(probe)
            notifier.publish(listOf(testRecord(VfsEventType.FILE_CREATED, VfsUri.parse("alcyone://resources/probe.txt")).toVfsEvent()))
            probe.await() // 探针到达：分发链路确实活着，下面的「没有」才有意义

            val insideTransaction = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val commit =
                launch(Dispatchers.Default) {
                    pipeline.commit { scope ->
                        scope.nodes.register(node())
                        scope.events.append(record)
                        insideTransaction.complete(Unit)
                        release.await() // 事务开着：状态没提交，通知也不该发生
                    }
                }
            insideTransaction.await()
            try {
                assertTrue(seen.isEmpty(), "提交之前 Consumer 一个事件都收不到")
                assertTrue(uow.calls.none { it == "commit" }, "此刻还没有提交")
            } finally {
                release.complete(Unit)
            }
            commit.join()
            withTimeout(30_000) { delivered.await() }

            assertEquals(listOf(record.id.value), seen, "提交之后收到的是库里那一条，同一个 ID")
        }

    @Test
    fun `A03 a block that fails publishes nothing`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val notifier = notifiers.create()
            val recorder = EventRecorder(1)
            notifier.subscribe(recorder)
            val pipeline = EventPipeline(StateBoundary(), uow, notifier)
            val record = testRecord()

            val thrown =
                runCatching {
                    pipeline.commit { scope ->
                        scope.nodes.register(node())
                        scope.events.append(record)
                        error("storage wrote but the caller gave up")
                    }
                }.exceptionOrNull()

            assertEquals("storage wrote but the caller gave up", thrown?.message)
            assertTrue(uow.snapshot().first.isEmpty())
            assertTrue(uow.snapshot().third.isEmpty())
            val marker = sentinelEvent()
            notifier.publish(listOf(marker))
            recorder.await()

            assertEquals(listOf(marker.id), recorder.received.map { it.id }, "回滚的事务不通知任何 Consumer")
        }

    @Test
    fun `A03 a failed commit publishes nothing`() =
        runBlocking {
            val uow = FakeStateUnitOfWork().apply { failOnCommit = FakeStateUnitOfWork.storageFailure("commit failed") }
            val notifier = notifiers.create()
            val recorder = EventRecorder(1)
            notifier.subscribe(recorder)
            val pipeline = EventPipeline(StateBoundary(), uow, notifier)
            val record = testRecord()

            val thrown = runCatching { pipeline.commit { scope -> scope.events.append(record) } }.exceptionOrNull()

            assertTrue(thrown != null, "提交失败要照实抛出")
            assertTrue(uow.snapshot().third.isEmpty())
            val marker = sentinelEvent()
            notifier.publish(listOf(marker))
            recorder.await()

            assertEquals(listOf(marker.id), recorder.received.map { it.id }, "提交失败不通知")
        }

    @Test
    fun `A04 a consumer parked on its own work does not delay the commit returning`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val notifier = notifiers.create()
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val recorder = EventRecorder(2)
            notifier.subscribe {
                parked.complete(Unit)
                release.await() // 模拟一个慢 Consumer：业务还没做完
            }
            notifier.subscribe(recorder)
            val pipeline = EventPipeline(StateBoundary(), uow, notifier)
            val first = testRecord(VfsEventType.FILE_WRITTEN)
            val second = testRecord(VfsEventType.METADATA_UPDATED, VfsUri.parse("alcyone://resources/notes/b.txt"))

            withTimeout(30_000) { pipeline.commit { scope -> scope.events.append(first) } }
            assertTrue(recorder.received.isEmpty(), "第一条还卡在慢 Consumer 那里")
            withTimeout(30_000) { pipeline.commit { scope -> scope.events.append(second) } }
            assertEquals(listOf(first, second), uow.snapshot().third, "慢 Consumer 不挡提交，两次变更都落库了")

            release.complete(Unit)
            recorder.await()
            assertEquals(listOf(first.id, second.id), recorder.received.map { it.id }, "Consumer 解开后按顺序补上两条")
        }

    @Test
    fun `A03 the commit queues behind the shared boundary and sees what the holder committed`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val boundary = StateBoundary()
            val pipeline = EventPipeline(boundary, uow, notifiers.create())
            val heldPath = VfsPath.parse("/notes/held.txt")
            val heldNode = node("held.txt")
            val holderInside = CompletableDeferred<Unit>()
            val releaseHolder = CompletableDeferred<Unit>()

            val holder =
                launch(Dispatchers.Default) {
                    boundary.withLock {
                        uow.inTransaction { scope ->
                            scope.nodes.register(heldNode)
                            holderInside.complete(Unit)
                            releaseHolder.await() // 持锁者还没提交
                        }
                    }
                }
            holderInside.await()
            try {
                val pending = async(Dispatchers.Default) { pipeline.commit { scope -> scope.nodes.findByPath(heldPath) } }
                releaseHolder.complete(Unit)

                val seen = withTimeout(30_000) { pending.await() }

                assertEquals(heldNode.id, seen?.id, "commit 排在共享边界后面，看到的是持有者提交的那条记录")
            } finally {
                releaseHolder.complete(Unit)
                holder.join()
            }
        }

    @Test
    fun `A07 lazy registration and logical reads still publish nothing`() =
        runBlocking {
            val disk = StorageFakeImpl().apply { withFile("a.txt") }
            val boundary = StateBoundary()
            val registry =
                NodeRegistry(
                    MountRouter.of(setOf("resources"), listOf(MountRecord(VfsPath.parse("/resources"), "disk"))),
                    InMemoryNodeRepository(),
                    { _: String -> disk },
                    boundary,
                )
            val uow = FakeStateUnitOfWork()
            val notifier = notifiers.create()
            val recorder = EventRecorder(1)
            notifier.subscribe(recorder)
            val pipeline = EventPipeline(boundary, uow, notifier)

            val registered = registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
            // T15 的接线形状：事务里调不加锁的逻辑查询，不会自己等自己
            val inside = pipeline.commit { scope -> registry.getNodeInsideBoundary(registered.id) }

            assertEquals(registered.id, inside.id)
            assertTrue(uow.snapshot().third.isEmpty(), "懒注册和逻辑查询都不产生事件")
            val marker = sentinelEvent()
            notifier.publish(listOf(marker))
            recorder.await()

            assertEquals(listOf(marker.id), recorder.received.map { it.id }, "注册本身没有通知任何人")
        }
}
