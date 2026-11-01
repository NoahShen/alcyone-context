package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CT09-B1 A03：关闭协议（先封闭准入、取消并真实 join、不 drain、共享结果含失败、
 * 等待者可取消、Runner 内关闭自身具名拒绝、NEW 未启动关闭）。
 */
class ExtractionSchedulerCloseTest {
    private val sessionA = SessionKey("rt", "agent", "session-a")
    private val sessionB = SessionKey("rt", "agent", "session-b")

    @Test
    fun `closing closes admission before starting new executions and does not drain`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val runner =
                FakeRunner {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val scheduler = ExtractionScheduler(runner)
            val hookEntered = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            scheduler.closeActionForTesting = {
                hookEntered.countDown()
                assertTrue(releaseHook.await(10, TimeUnit.SECONDS), "清理屏障必须有界放行")
            }
            scheduler.start()
            try {
                assertTrue(scheduler.request(sessionA))
                entered.await()
                // 第二个会话已排队，但关闭后不得被 drain 执行。
                assertTrue(scheduler.request(sessionB))

                val closing = async(Dispatchers.Default) { scheduler.closeAsync() }
                assertTrue(hookEntered.await(10, TimeUnit.SECONDS), "owner 应进入清理")
                assertEquals("CLOSING", scheduler.stateForTesting())
                assertFalse(scheduler.request(SessionKey("rt", "agent", "late")), "CLOSING 期间必须拒绝")
                assertEquals(1L, scheduler.executionCountForTesting())

                releaseHook.countDown()
                closing.await()
                assertEquals("CLOSED", scheduler.stateForTesting())
                assertEquals(1L, scheduler.executionCountForTesting(), "排队项不得被 drain 执行")
                assertTrue(scheduler.pendingSessionsForTesting().isEmpty(), "关闭后必须清理计数")
            } finally {
                releaseHook.countDown()
            }
        }

    @Test
    fun `close cancels the in-flight runner and waits for its finally`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val runnerFinally = CountDownLatch(1)
            val runner =
                FakeRunner {
                    try {
                        entered.countDown()
                        awaitCancellation()
                    } finally {
                        runnerFinally.countDown()
                    }
                }
            val scheduler = ExtractionScheduler(runner)
            scheduler.start()
            assertTrue(scheduler.request(sessionA))
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Runner 必须已开始执行")

            withTimeout(10_000L) { scheduler.closeAsync() }
            assertEquals(0L, runnerFinally.count, "closeAsync 返回时 Runner 的 finally 必须已执行")
            assertEquals("CLOSED", scheduler.stateForTesting())
            assertEquals(1L, scheduler.executionCountForTesting())
            assertEquals(0, scheduler.queueDepthForTesting())
            assertTrue(scheduler.pendingSessionsForTesting().isEmpty())
        }

    @Test
    fun `a cleanup failure is published as the same instance on every close`() =
        runBlocking {
            val runner = FakeRunner { }
            val scheduler = ExtractionScheduler(runner)
            scheduler.start()
            val unique = IllegalStateException("ct09-b1-close-failure-3e18")
            scheduler.closeActionForTesting = { throw unique }
            try {
                val first = assertFailsWith<IllegalStateException> { scheduler.closeAsync() }
                assertSame(unique, first, "owner 必须抛出清理失败本身")
                assertEquals("CLOSED", scheduler.stateForTesting(), "失败也必须发布 CLOSED")

                val second = assertFailsWith<IllegalStateException> { scheduler.closeAsync() }
                assertSame(unique, second, "重复关闭必须重抛同一异常实例")
            } finally {
                // 清理钩子仍会失败；这里只确认不会滞留 CLOSING。
                assertEquals("CLOSED", scheduler.stateForTesting())
            }
        }

    @Test
    fun `a cancelled close waiter does not disturb the owner`() =
        runBlocking {
            val runner = FakeRunner { }
            val scheduler = ExtractionScheduler(runner)
            val hookEntered = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            scheduler.closeActionForTesting = {
                hookEntered.countDown()
                assertTrue(releaseHook.await(10, TimeUnit.SECONDS), "清理屏障必须有界放行")
            }
            scheduler.start()
            try {
                val owner = async(Dispatchers.Default) { scheduler.closeAsync() }
                assertTrue(hookEntered.await(10, TimeUnit.SECONDS), "owner 应进入清理")

                val waiter = async(Dispatchers.Default) { scheduler.closeAsync() }
                delay(100)
                waiter.cancel()
                assertFailsWith<CancellationException> { waiter.await() }

                releaseHook.countDown()
                owner.await()
                assertEquals("CLOSED", scheduler.stateForTesting())
                // 后续关闭正常返回。
                scheduler.closeAsync()
            } finally {
                releaseHook.countDown()
            }
        }

    @Test
    fun `closeAsync from inside the runner is rejected by name without self waiting`() =
        runBlocking {
            val rejection = CompletableDeferred<Throwable>()
            lateinit var scheduler: ExtractionScheduler
            val runner =
                FakeRunner {
                    try {
                        withTimeout(5_000L) { scheduler.closeAsync() }
                        rejection.complete(IllegalStateException("closeAsync should have been rejected"))
                    } catch (failure: Throwable) {
                        rejection.complete(failure)
                    }
                }
            scheduler = ExtractionScheduler(runner)
            scheduler.start()
            try {
                assertTrue(scheduler.request(sessionA))
                val failure = assertNotNull(rejection.await())
                assertTrue(failure is ContextLifecycleException, "必须是具名 CLOSED 拒绝，实际 $failure")
                assertEquals(ContextLifecycleErrorCode.CLOSED, (failure as ContextLifecycleException).errorCode)
                assertTrue(
                    failure.message.orEmpty().contains("cannot be called from inside its own extraction runner"),
                    failure.message.orEmpty(),
                )
                // 外部关闭仍可完成。
                withTimeout(10_000L) { scheduler.closeAsync() }
                assertEquals("CLOSED", scheduler.stateForTesting())
            } finally {
                withTimeout(10_000L) { scheduler.closeAsync() }
            }
        }

    @Test
    fun `closing a never started scheduler is safe and idempotent`() =
        runBlocking {
            val runner = FakeRunner { }
            val scheduler = ExtractionScheduler(runner)
            assertEquals("NEW", scheduler.stateForTesting())
            scheduler.closeAsync()
            assertEquals("CLOSED", scheduler.stateForTesting())
            scheduler.closeAsync()
            assertEquals("CLOSED", scheduler.stateForTesting())
            assertFalse(scheduler.request(sessionA))
            assertEquals(0L, scheduler.executionCountForTesting())
            assertNull(runner.executed.firstOrNull())
        }

    @Test
    fun `unexpected cancellation waits for real worker children before closing`() =
        runBlocking {
            val childStarted = CompletableDeferred<Unit>()
            val releaseChild = CompletableDeferred<Unit>()
            val childFinished = CompletableDeferred<Unit>()
            var child: Job? = null
            val scheduler =
                ExtractionScheduler(
                    object : ExtractionRunner {
                        override suspend fun execute(sessionKey: SessionKey) {
                            // Worker 的真实结构化子任务（不是游离任务）。
                            child =
                                CoroutineScope(currentCoroutineContext()).launch {
                                    try {
                                        childStarted.complete(Unit)
                                        awaitCancellation()
                                    } finally {
                                        withContext(NonCancellable) {
                                            releaseChild.await()
                                            childFinished.complete(Unit)
                                        }
                                    }
                                }
                            childStarted.await()
                            throw CancellationException("ct09-b1-r1-runner-cancelled")
                        }
                    },
                )
            scheduler.start()
            try {
                assertTrue(scheduler.request(sessionA))
                withTimeout(5_000L) {
                    while (scheduler.stateForTesting() == "RUNNING") yield()
                }
                assertEquals("CLOSING", scheduler.stateForTesting(), "非关闭 CE 只能封闭准入")

                val closing = async(start = CoroutineStart.UNDISPATCHED) { scheduler.closeAsync() }
                assertFalse(closing.isCompleted, "子任务 finally 屏障未放行前 close 不得返回")
                assertFalse(childFinished.isCompleted)

                releaseChild.complete(Unit)
                withTimeout(5_000L) { closing.await() }
                assertTrue(childFinished.isCompleted, "close 返回时子任务 finally 必须已执行")
                assertEquals("CLOSED", scheduler.stateForTesting())
                assertFalse(scheduler.request(sessionA))
                assertEquals(1L, scheduler.executionCountForTesting(), "不得重放")
            } finally {
                releaseChild.complete(Unit)
                withTimeout(5_000L) {
                    child?.join()
                    scheduler.closeAsync()
                }
            }
        }

    @Test
    fun `unexpected cancellation publishes the cleanup failure as the same shared instance`() =
        runBlocking {
            val unique = IllegalStateException("ct09-b1-r1-cleanup-failure")
            val scheduler = ExtractionScheduler(FakeRunner { throw CancellationException("ct09-b1-r1-runner-cancelled") })
            scheduler.closeActionForTesting = { throw unique }
            scheduler.start()
            assertTrue(scheduler.request(sessionA))
            withTimeout(5_000L) {
                while (scheduler.stateForTesting() == "RUNNING") yield()
            }

            val first =
                assertFailsWith<IllegalStateException> {
                    withTimeout(5_000L) { scheduler.closeAsync() }
                }
            // withTimeout 的协程边界会由 kotlinx.coroutines stack-trace recovery 复制异常实例；
            // 复制体保留原实例为 cause，据此证明共享结果仍是同一个清理失败实例。
            assertSame(unique, first.cause, "共享清理失败必须是同一实例（跨边界复制后 cause 保持原实例）")
            assertEquals("CLOSED", scheduler.stateForTesting(), "失败也必须发布 CLOSED")

            val second = assertFailsWith<IllegalStateException> { scheduler.closeAsync() }
            assertSame(unique, second, "重复关闭必须重抛同一异常实例")
        }

    @Test
    fun `unexpected cancellation runs the cleanup exactly once for concurrent closes`() =
        runBlocking {
            val hookRuns = AtomicInteger()
            val hookEntered = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            val scheduler = ExtractionScheduler(FakeRunner { throw CancellationException("ct09-b1-r1-runner-cancelled") })
            scheduler.closeActionForTesting = {
                hookRuns.incrementAndGet()
                hookEntered.countDown()
                assertTrue(releaseHook.await(10, TimeUnit.SECONDS), "清理屏障必须有界放行")
            }
            scheduler.start()
            try {
                assertTrue(scheduler.request(sessionA))
                withTimeout(5_000L) {
                    while (scheduler.stateForTesting() == "RUNNING") yield()
                }

                val closers = (1..3).map { async(Dispatchers.Default) { scheduler.closeAsync() } }
                assertTrue(hookEntered.await(10, TimeUnit.SECONDS), "唯一 owner 必须进入清理")
                assertEquals(1, hookRuns.get(), "清理只能执行一次")

                closers[0].cancel()
                assertFailsWith<CancellationException> { closers[0].await() }
                releaseHook.countDown()
                closers.drop(1).forEach { closer -> withTimeout(5_000L) { closer.await() } }

                assertEquals(1, hookRuns.get(), "并发关闭不得重复清理")
                assertEquals("CLOSED", scheduler.stateForTesting())
            } finally {
                releaseHook.countDown()
            }
        }

    // ------------------------------------------------------------------ 工具

    private class FakeRunner(
        private val behavior: suspend (attempt: Int) -> Unit = {},
    ) : ExtractionRunner {
        val executed: MutableList<SessionKey> = CopyOnWriteArrayList()
        private val attempts = AtomicInteger()

        override suspend fun execute(sessionKey: SessionKey) {
            executed += sessionKey
            behavior(attempts.incrementAndGet())
        }
    }
}
