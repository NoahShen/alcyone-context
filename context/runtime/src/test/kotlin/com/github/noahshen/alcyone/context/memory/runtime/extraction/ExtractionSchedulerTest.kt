package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.event.Level
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT09-B1 A02：计数饱和、单队列、全局串行、普通异常隔离、`trySend` 回滚、
 * 关闭后静默 no-op、`start` 幂等、非关闭态 Runner 自行取消的可观察终止。
 */
class ExtractionSchedulerTest {
    private val sessionA = SessionKey("rt", "agent", "session-a")
    private val sessionB = SessionKey("rt", "agent", "session-b")
    private val sessionC = SessionKey("rt", "agent", "session-c")

    @Test
    fun `a request runs once and repeated requests during execution run again`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val runner =
                FakeRunner { attempt ->
                    if (attempt == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                }
            val scheduler = ExtractionScheduler(runner)
            scheduler.start()
            try {
                assertTrue(scheduler.request(sessionA))
                firstStarted.await()
                // 执行期间再触发两次：只加计数，不重复入队。
                assertTrue(scheduler.request(sessionA))
                assertTrue(scheduler.request(sessionA))
                assertEquals(3, scheduler.pendingForTesting(sessionA))
                assertEquals(0, scheduler.queueDepthForTesting(), "执行中不得重复入队")

                releaseFirst.complete(Unit)
                awaitUntil { scheduler.executionCountForTesting() == 3L }
                assertEquals(3, runner.executed.size)
                awaitUntil { scheduler.pendingSessionsForTesting().isEmpty() }
                assertEquals(0, scheduler.pendingForTesting(sessionA))
            } finally {
                scheduler.closeAsync()
            }
        }

    @Test
    fun `executions are globally serial and never concurrent`() =
        runBlocking {
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val runner =
                FakeRunner {
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { max -> maxOf(max, now) }
                    try {
                        delay(30)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            val scheduler = ExtractionScheduler(runner)
            scheduler.start()
            try {
                assertTrue(scheduler.request(sessionA))
                assertTrue(scheduler.request(sessionB))
                assertTrue(scheduler.request(sessionC))
                assertTrue(scheduler.request(sessionA))
                assertTrue(scheduler.request(sessionA))

                awaitUntil { scheduler.executionCountForTesting() == 5L }
                assertEquals(1, maxActive.get(), "单 Worker 全局串行，最大并发必须为 1")
                assertEquals(5, runner.executed.size)
                awaitUntil { scheduler.pendingSessionsForTesting().isEmpty() }
            } finally {
                scheduler.closeAsync()
            }
        }

    @Test
    fun `a runner failure is isolated and logged without leaking the session or throwable`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val failing = SessionKey("rt", "agent", "failing-session-marker-7f31")
                val runner =
                    FakeRunner { attempt ->
                        if (attempt == 1) throw IllegalStateException("boom ct09-b1-failure-marker")
                    }
                val scheduler = ExtractionScheduler(runner)
                scheduler.start()
                try {
                    assertTrue(scheduler.request(failing))
                    assertTrue(scheduler.request(sessionB))
                    awaitUntil { scheduler.executionCountForTesting() == 2L }

                    val record = capture.logs.single { it.formattedMessage.contains("extraction.run.failed") }
                    assertEquals(Level.WARN, record.level)
                    assertNull(record.throwable, "不得把 Throwable 传给 logger")
                    assertFalse(record.formattedMessage.contains("failing-session-marker-7f31"))
                    assertFalse(record.formattedMessage.contains("ct09-b1-failure-marker"))
                    assertFalse(record.formattedMessage.contains("boom"))

                    // 普通异常消费一次，后续会话继续执行。
                    awaitUntil { scheduler.pendingSessionsForTesting().isEmpty() }
                    assertEquals(0, scheduler.pendingForTesting(failing))
                    assertEquals(2, runner.executed.size)
                } finally {
                    scheduler.closeAsync()
                }
            }
        }

    @Test
    fun `pending counts saturate instead of wrapping`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val runner =
                FakeRunner {
                    started.complete(Unit)
                    awaitCancellation()
                }
            val scheduler = ExtractionScheduler(runner)
            scheduler.start()
            try {
                assertTrue(scheduler.request(sessionA))
                started.await()
                scheduler.setPendingForTesting(sessionA, Int.MAX_VALUE)
                assertEquals(Int.MAX_VALUE, scheduler.pendingForTesting(sessionA))

                assertTrue(scheduler.request(sessionA))
                assertEquals(Int.MAX_VALUE, scheduler.pendingForTesting(sessionA), "必须饱和，不得 wrap 成负数")
                assertEquals(0, scheduler.queueDepthForTesting(), "饱和期间不得额外入队")
                assertEquals(1L, scheduler.executionCountForTesting())
            } finally {
                scheduler.closeAsync()
            }
        }

    @Test
    fun `a failed enqueue rolls back the count and is observable`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val runner = FakeRunner { }
                val scheduler = ExtractionScheduler(runner)
                scheduler.start()
                try {
                    scheduler.closeChannelForTesting()

                    assertFalse(scheduler.request(sessionA))
                    assertEquals(0, scheduler.pendingForTesting(sessionA), "失败必须回滚该次计数")
                    assertEquals(0, scheduler.queueDepthForTesting())

                    val record =
                        capture.logs.single { it.formattedMessage.contains("extraction.scheduler.enqueue.failed") }
                    assertEquals(Level.WARN, record.level)
                    assertNull(record.throwable)
                    assertFalse(record.formattedMessage.contains("session-a"))
                } finally {
                    scheduler.closeAsync()
                }
            }
        }

    @Test
    fun `request after close is a silent no-op and start is idempotent`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val runner = FakeRunner { }
                val scheduler = ExtractionScheduler(runner)
                scheduler.start()
                // 幂等：不产生第二个消费者。
                scheduler.start()
                try {
                    assertTrue(scheduler.request(sessionA))
                    awaitUntil { scheduler.executionCountForTesting() == 1L }
                    scheduler.closeAsync()
                    assertEquals("CLOSED", scheduler.stateForTesting())

                    assertFalse(scheduler.request(sessionA))
                    assertFalse(scheduler.request(sessionB))
                    assertTrue(
                        capture.logs.none { it.formattedMessage.startsWith("extraction.") },
                        "关闭后 request 不得打 WARN: ${capture.logs.map { it.formattedMessage }}",
                    )
                    assertFailsWith<ContextLifecycleException> { scheduler.start() }
                    // 保持 JUnit 5 可见：表达式体不能以非 Unit 值结尾。
                    Unit
                } finally {
                    scheduler.closeAsync()
                }
            }
        }

    @Test
    fun `a runner cancellation outside close stops the worker observably without replay`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val runner = FakeRunner { throw CancellationException("runner cancelled itself ct09-b1-ce-marker") }
                val scheduler = ExtractionScheduler(runner)
                scheduler.start()
                try {
                    assertTrue(scheduler.request(sessionA))
                    awaitUntil { scheduler.stateForTesting() == "CLOSED" }
                    assertEquals(1L, scheduler.executionCountForTesting())

                    assertFalse(scheduler.request(sessionA), "终止后不得继续接单")
                    val record = capture.logs.single { it.formattedMessage.contains("extraction.worker.stopped") }
                    assertEquals(Level.WARN, record.level)
                    assertNull(record.throwable)
                    assertFalse(record.formattedMessage.contains("ct09-b1-ce-marker"))

                    // 终态已发布：closeAsync 不得悬挂。
                    withTimeout(5_000) { scheduler.closeAsync() }
                    assertEquals("CLOSED", scheduler.stateForTesting())
                    assertEquals(1L, scheduler.executionCountForTesting(), "不得重启/重放")
                } finally {
                    withTimeout(5_000) { scheduler.closeAsync() }
                }
            }
        }

    // ------------------------------------------------------------------ 工具

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(10_000L) {
            while (!condition()) delay(10)
        }
    }

    /** 记录执行次数与顺序的替身；[behavior] 收到的是第几次执行（1 起）。 */
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
