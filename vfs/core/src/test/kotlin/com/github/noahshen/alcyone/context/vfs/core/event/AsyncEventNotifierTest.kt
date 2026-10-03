package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * T14 S2：进程内通知的队列、Consumer 隔离、三种取消和关闭语义。
 *
 * 等的都是 [CompletableDeferred]，超时的作用是「测试卡住时快速失败」，不是靠「等不到」来证明什么。
 */
@Timeout(60)
class AsyncEventNotifierTest {
    /** 造一个「创建了 resources/<name>」的通知事件。 */
    private fun event(name: String) = testRecord(VfsEventType.FILE_CREATED, VfsUri.parse("alcyone://resources/$name")).toVfsEvent()

    @Test
    fun `events reach subscribers in publish order and subscription order`() =
        runBlocking {
            val notifier = AsyncEventNotifier()
            val first = EventRecorder(3)
            val second = EventRecorder(3)
            notifier.subscribe(first)
            notifier.subscribe(second)

            notifier.publish(listOf(event("a.txt"), event("b.txt"), event("c.txt")))
            first.await()
            second.await()

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
            val notifier = AsyncEventNotifier()
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
            val notifier = AsyncEventNotifier()
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
            val notifier = AsyncEventNotifier(capacity = 1)
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
    fun `publishing with no subscriber is a silent no-op`() =
        runBlocking {
            val notifier = AsyncEventNotifier(capacity = 4)

            notifier.publish(listOf(event("a.txt"), event("b.txt"), event("c.txt")))
            notifier.close() // 等分发协程真的停掉：队列里那三条已经被消费掉了

            assertEquals(0, notifier.droppedEvents, "没人订阅不是丢弃：不是队列满，也没有失败")
            assertTrue(notifier.job.isCompleted)
        }

    @Test
    fun `unsubscribing stops delivery without disturbing the remaining consumers`() =
        runBlocking {
            val notifier = AsyncEventNotifier()
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
            val notifier = AsyncEventNotifier()
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
    fun `close cancels a consumer parked on a cancellable suspension`() =
        runBlocking {
            val notifier = AsyncEventNotifier()
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
