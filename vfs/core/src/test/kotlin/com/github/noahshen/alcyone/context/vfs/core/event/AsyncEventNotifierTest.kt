package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith

/**
 * T14 S2：进程内通知的队列、Consumer 隔离、三种取消和关闭语义。
 *
 * 等的都是 [CompletableDeferred]，超时的作用是「测试卡住时快速失败」，不是靠「等不到」来证明什么。
 */
@Timeout(60)
// asCoroutineDispatcher 属于 delicate API：这里只用来造一个「保证不是分发协程那个线程」的专用线程
@OptIn(DelicateCoroutinesApi::class)
class AsyncEventNotifierTest {
    /** 统一登记 + @AfterEach 关闭：断言失败也不留分发协程。 */
    private val notifiers = TrackedNotifiers()

    /** 用例结束（包括断言失败）统一关掉通知器：钩子写在测试类上，JUnit 才会执行。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    /** 造一个「创建了 resources/<name>」的通知事件。 */
    private fun event(name: String) = testRecord(VfsEventType.FILE_CREATED, VfsUri.parse("alcyone://resources/$name")).toVfsEvent()

    @Test
    fun `events reach subscribers in publish order and subscription order`() =
        runBlocking {
            val notifier = notifiers.create()
            val first = EventRecorder(3)
            val second = EventRecorder(3)
            // 跨 Consumer 的调用顺序：两个 Consumer 交替记，看得出「一条事件内按订阅顺序逐个跑」
            val callOrder = CopyOnWriteArrayList<String>()
            notifier.subscribe { event ->
                callOrder.add("first:${event.uri.path.segments.last()}")
                first.onEvent(event)
            }
            notifier.subscribe { event ->
                callOrder.add("second:${event.uri.path.segments.last()}")
                second.onEvent(event)
            }

            notifier.publish(listOf(event("a.txt"), event("b.txt"), event("c.txt")))
            first.await()
            second.await()

            assertEquals(
                listOf("first:a.txt", "second:a.txt", "first:b.txt", "second:b.txt", "first:c.txt", "second:c.txt"),
                callOrder,
                "同一个事件内按订阅顺序逐个跑，事件之间按发布顺序",
            )
            assertEquals(
                listOf("a.txt", "b.txt", "c.txt"),
                first.received.map {
                    it.uri.path.segments
                        .last()
                },
            )
            assertEquals(
                listOf("a.txt", "b.txt", "c.txt"),
                second.received.map {
                    it.uri.path.segments
                        .last()
                },
            )
            assertEquals(0, notifier.droppedEvents, "队列空着，一个都不该丢")
            notifier.close()
        }

    @Test
    fun `a failing consumer does not stop the others nor the later events`() =
        runBlocking {
            val notifier = notifiers.create()
            val healthy = EventRecorder(2)
            notifier.subscribe { throw IOException("consumer failed to push to its index") }
            notifier.subscribe { throw IllegalStateException("consumer has a bug") }
            notifier.subscribe(healthy)

            notifier.publish(listOf(event("a.txt"), event("b.txt")))
            healthy.await()

            assertEquals(2, healthy.received.size, "抛错的 Consumer 挡不住别人，也挡不住后面的事件")
            assertTrue(notifier.job.isActive)
            notifier.close()
        }

    @Test
    fun `a consumer that cancels itself does not silently stop the notifier`() =
        runBlocking {
            val notifier = notifiers.create()
            val beforeIt = EventRecorder(2)
            val afterIt = EventRecorder(2)
            // 中间的 Consumer 自己放弃：T07 的约定是 CancellationException 原样传播，那一轮到此为止，
            // 排在它后面的 Consumer 收不到这一条。首版接受这个取舍（可靠消费属 E05）。
            notifier.subscribe(beforeIt)
            notifier.subscribe { throw CancellationException("this consumer gave up on its own") }
            notifier.subscribe(afterIt)

            notifier.publish(listOf(event("a.txt"), event("b.txt")))
            beforeIt.await()

            assertEquals(2, beforeIt.received.size, "Consumer 自己放弃，不该把整个通知带走")
            assertTrue(notifier.job.isActive, "分发协程还活着")
            assertEquals(0, afterIt.received.size, "排在放弃者后面的 Consumer 收不到那一轮的事件")
            notifier.close()
        }

    @Test
    fun `a full buffer drops the event instead of blocking the publisher`() =
        runBlocking {
            val notifier = notifiers.create(capacity = 1)
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val arrived = EventRecorder(2)
            // 第一个 Consumer 卡住不动，队列就只剩 1 个空位
            notifier.subscribe {
                parked.complete(Unit)
                release.await()
            }
            notifier.subscribe(arrived)

            notifier.publish(listOf(event("a.txt")))
            withTimeout(30_000) { parked.await() } // 等第一条真的被分发协程取走，队列腾空

            notifier.publish(listOf(event("b.txt"))) // 占掉那 1 个空位
            notifier.publish(listOf(event("c.txt"))) // 满了：丢弃，不抛给发布方

            assertEquals(1, notifier.droppedEvents, "满队列的策略是记日志丢弃")
            assertEquals(0, arrived.received.size, "还没轮到后面的事件")

            release.complete(Unit)
            arrived.await()
            assertEquals(
                listOf("a.txt", "b.txt"),
                arrived.received.map {
                    it.uri.path.segments
                        .last()
                },
                "丢的只有第三条：已入队的前两条在 Consumer 解开后照常送达",
            )
            assertEquals(1, notifier.droppedEvents)
            notifier.close()
        }

    @Test
    fun `publishing with no subscriber does not fail and is not counted as dropped`() =
        runBlocking {
            val notifier = notifiers.create(capacity = 4)

            notifier.publish(listOf(event("a.txt"), event("b.txt"), event("c.txt")))
            notifier.close()

            // 这三条进的是有界队列（容量之内，所以一个都没丢），分发时没人可投就被丢掉：
            // 不报错、不算「队列满」。close 是取消而不是排空，所以这里不断言队列被消费干净。
            assertEquals(0, notifier.droppedEvents, "没人订阅不是队列满")
            assertTrue(notifier.job.isCompleted)
        }

    @Test
    fun `unsubscribing stops delivery without disturbing the remaining consumers`() =
        runBlocking {
            val notifier = notifiers.create()
            val left = EventRecorder(1)
            val right = EventRecorder(1)
            notifier.subscribe(left)
            notifier.publish(listOf(event("a.txt")))
            left.await() // 第一条已经送达，队列是空的

            notifier.unsubscribe(left)
            notifier.subscribe(right)
            notifier.publish(listOf(event("b.txt")))
            right.await() // b 在退订之后才被分发，这条到达就说明分发确实发生在退订之后

            assertEquals(1, left.received.size, "退订之后的事件不再投给它")
            assertEquals(
                listOf("b.txt"),
                right.received.map {
                    it.uri.path.segments
                        .last()
                },
            )
            notifier.close()
        }

    @Test
    fun `close is idempotent, stops the worker and leaves the host job alone`() =
        runBlocking {
            val hostJob = SupervisorJob()
            val hostScope = CoroutineScope(hostJob + Dispatchers.Default)
            val notifier = notifiers.create()
            val recorder = EventRecorder(1)
            notifier.subscribe(recorder)
            notifier.publish(listOf(event("a.txt")))
            recorder.await()

            notifier.close()
            notifier.close() // 第二次是空操作

            assertTrue(notifier.isClosed)
            assertTrue(notifier.job.isCompleted, "close 之后自己的分发协程真的结束了")
            assertTrue(hostJob.isActive, "宿主自己的 Job 不该被通知器带走")

            notifier.publish(listOf(event("b.txt"))) // 关闭后发布：不抛、不惊动别人
            assertEquals(1, recorder.received.size)
            assertFalse(notifier.job.isActive)

            val stillWorks = CompletableDeferred<Unit>()
            hostScope.launch { stillWorks.complete(Unit) }
            withTimeout(30_000) { stillWorks.await() }
            hostJob.cancel()
        }

    @Test
    fun `R1 closing from inside a consumer callback ends without waiting for itself`() =
        runBlocking {
            val notifier = notifiers.create()
            val closedInside = CompletableDeferred<Unit>()
            notifier.subscribe {
                notifier.close() // 就在回调里关：不进等待分支，否则自己等自己
                closedInside.complete(Unit)
            }

            notifier.publish(listOf(event("a.txt")))
            withTimeout(30_000) { closedInside.await() }
            withTimeout(30_000) { notifier.job.join() }

            assertTrue(notifier.isClosed)
        }

    @Test
    fun `R1 closing from inside a consumer that switched dispatchers also ends`() =
        runBlocking {
            val notifier = notifiers.create()
            // 专用线程：确保回调真的换到了别的线程上（Dispatchers.IO 可能把任务发回当前线程，那样就测不到迁移）
            val otherThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                val closedInside = CompletableDeferred<Unit>()
                notifier.subscribe {
                    withContext(otherThread) {
                        notifier.close()
                        closedInside.complete(Unit)
                    }
                }

                notifier.publish(listOf(event("a.txt")))
                withTimeout(30_000) { closedInside.await() }
                withTimeout(30_000) { notifier.job.join() }

                assertTrue(notifier.isClosed)
            } finally {
                otherThread.close()
            }
        }

    @Test
    fun `R1 an external close still waits for a consumer that migrated threads`() =
        runBlocking {
            val notifier = notifiers.create()
            val otherThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                val migrated = CompletableDeferred<Unit>()
                notifier.subscribe {
                    withContext(otherThread) { migrated.complete(Unit) } // 消费者换线程跑一圈就回来
                    awaitCancellation()
                }

                notifier.publish(listOf(event("a.txt")))
                withTimeout(30_000) { migrated.await() }
                notifier.close() // 外部线程：标记不残留，照旧等协作取消完成

                assertTrue(notifier.job.isCompleted, "外部 close 会等到分发协程真正结束")
            } finally {
                otherThread.close()
            }
        }

    @Test
    fun `R2 the capacity must be a bounded positive buffer size`() {
        // 0 = rendezvous，-1 = conflated，-2 = Channel.BUFFERED（用调度器默认容量），Int.MAX_VALUE = 无界；
        // 另外普通负值（-5）也得拒——留个口子在它明年变特殊值，就等于把队列语义交给下一个人。
        for (rejected in listOf(0, -1, -2, -5, Int.MAX_VALUE)) {
            val failure = assertFailsWith<IllegalArgumentException> { AsyncEventNotifier(rejected) }

            assertTrue(
                failure.message!!.contains("bounded positive buffer size"),
                "容量 $rejected 应被拒绝并说明允许范围，实际：${failure.message}",
            )
        }

        for (usable in listOf(1, 2, AsyncEventNotifier.DEFAULT_CAPACITY, Int.MAX_VALUE - 1)) {
            notifiers.create(usable).close()
        }
    }

    @Test
    fun `close cancels a consumer parked on a cancellable suspension`() =
        runBlocking {
            val notifier = notifiers.create()
            val parked = CompletableDeferred<Unit>()
            notifier.subscribe {
                parked.complete(Unit)
                awaitCancellation() // 协作取消能收得到的挂起点
            }

            notifier.publish(listOf(event("a.txt")))
            withTimeout(30_000) { parked.await() }
            notifier.close()

            assertTrue(notifier.job.isCompleted, "Consumer 挂在可取消的挂起点上，close 会停掉它；不可中断的阻塞代码不在此保证内")
        }
}
