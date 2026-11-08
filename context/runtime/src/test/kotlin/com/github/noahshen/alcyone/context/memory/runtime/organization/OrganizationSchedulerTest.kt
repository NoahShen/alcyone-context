package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.event.Level
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CT16 B1 A03/A07：饱和 Counter、单 Worker、轮末消费、退出竞态、退避与关闭。 */
class OrganizationSchedulerTest {
    @Test
    fun `one request runs one round and requests during the round accumulate`() =
        runBlocking {
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val rounds = CopyOnWriteArrayList<Int>()
            val runner =
                OrganizationRoundRunner {
                    val attempt = rounds.size + 1
                    rounds += attempt
                    if (attempt == 1) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
            val scheduler = OrganizationScheduler(runner)
            scheduler.start()
            try {
                assertTrue(scheduler.request())
                firstEntered.await()
                assertEquals(OrganizationWorkerStatus.RUNNING, scheduler.statusForTesting())
                assertEquals(1, scheduler.triggerCountForTesting(), "轮末前不得提前消费 Trigger")

                assertTrue(scheduler.request())
                assertTrue(scheduler.request())
                assertEquals(3, scheduler.triggerCountForTesting())
                assertEquals(1, scheduler.workerCreationCountForTesting())

                releaseFirst.complete(Unit)
                awaitOrganizationUntil { scheduler.roundCountForTesting() == 3L }
                awaitOrganizationUntil { scheduler.statusForTesting() == OrganizationWorkerStatus.IDLE }
                assertEquals(0, scheduler.triggerCountForTesting())
                assertEquals(listOf(1, 2, 3), rounds)
                assertEquals(1, scheduler.workerCreationCountForTesting(), "运行期 Trigger 不得创建第二个 Worker")
            } finally {
                scheduler.closeAsync()
            }
        }

    @Test
    fun `trigger count saturates at int max without starting another worker`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val scheduler =
                OrganizationScheduler(
                    OrganizationRoundRunner {
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                )
            scheduler.start()
            try {
                assertTrue(scheduler.request())
                entered.await()
                scheduler.setTriggerCountForTesting(Int.MAX_VALUE)

                assertTrue(scheduler.request())
                assertEquals(Int.MAX_VALUE, scheduler.triggerCountForTesting())
                assertEquals(1, scheduler.workerCreationCountForTesting())
            } finally {
                scheduler.closeAsync()
            }
        }

    @Test
    fun `ordinary round failures are logged isolated and do not mark failed`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val attempts = AtomicInteger(0)
                val scheduler =
                    OrganizationScheduler(
                        OrganizationRoundRunner {
                            if (attempts.incrementAndGet() == 1) throw IllegalStateException("ct16-round-marker")
                        },
                    )
                scheduler.start()
                try {
                    assertTrue(scheduler.request())
                    assertTrue(scheduler.request())
                    awaitOrganizationUntil { scheduler.roundCountForTesting() == 2L }
                    awaitOrganizationUntil { scheduler.statusForTesting() == OrganizationWorkerStatus.IDLE }
                    assertEquals(OrganizationWorkerStatus.IDLE, scheduler.statusForTesting(), "普通失败不得进入 FAILED")
                    val log = capture.logs.single { it.formattedMessage.contains("organization.round.failed") }
                    assertEquals(Level.WARN, log.level)
                    assertNull(log.throwable)
                    assertFalse(log.formattedMessage.contains("ct16-round-marker"))
                } finally {
                    scheduler.closeAsync()
                }
            }
        }

    @Test
    fun `persistence round failure waits for injectable backoff before the next trigger`() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val backoffEntered = CompletableDeferred<Unit>()
            val releaseBackoff = CompletableDeferred<Unit>()
            val scheduler =
                OrganizationScheduler(
                    OrganizationRoundRunner {
                        if (attempts.incrementAndGet() == 1) {
                            throw ContextPersistenceException(
                                ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                                "ct16-round-persistence-failure",
                            )
                        }
                    },
                    backoff = {
                        backoffEntered.complete(Unit)
                        releaseBackoff.await()
                    },
                )
            scheduler.start()
            try {
                assertTrue(scheduler.request())
                assertTrue(scheduler.request())
                backoffEntered.await()
                assertEquals(1L, scheduler.roundCountForTesting())
                assertEquals(1, scheduler.triggerCountForTesting())

                releaseBackoff.complete(Unit)
                awaitOrganizationUntil { scheduler.roundCountForTesting() == 2L }
                assertEquals(2, attempts.get())
                assertEquals(OrganizationScheduler.FAILURE_BACKOFF_MILLIS, 1_000L)
            } finally {
                releaseBackoff.complete(Unit)
                scheduler.closeAsync()
            }
        }

    @Test
    fun `a single worker serializes every accumulated trigger`() =
        runBlocking {
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val scheduler =
                OrganizationScheduler(
                    OrganizationRoundRunner {
                        val now = active.incrementAndGet()
                        maxActive.updateAndGet { current -> maxOf(current, now) }
                        try {
                            delay(10)
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                )
            scheduler.start()
            try {
                repeat(20) { assertTrue(scheduler.request()) }
                awaitOrganizationUntil { scheduler.roundCountForTesting() == 20L }
                assertEquals(1, maxActive.get())
                assertEquals(1, scheduler.workerCreationCountForTesting())
            } finally {
                scheduler.closeAsync()
            }
        }

    @Test
    fun `requests around worker exit are never lost`() =
        runBlocking {
            val scheduler = OrganizationScheduler(OrganizationRoundRunner { })
            scheduler.start()
            try {
                repeat(50) { expected ->
                    assertTrue(scheduler.request())
                    awaitOrganizationUntil { scheduler.roundCountForTesting() >= expected + 1L }
                }
                assertEquals(50L, scheduler.roundCountForTesting())
                awaitOrganizationUntil { scheduler.statusForTesting() == OrganizationWorkerStatus.IDLE }
            } finally {
                scheduler.closeAsync()
            }
        }

    @Test
    fun `close cancels and joins an in flight round then rejects later requests`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val scheduler =
                OrganizationScheduler(
                    OrganizationRoundRunner {
                        try {
                            entered.countDown()
                            awaitCancellation()
                        } finally {
                            finished.countDown()
                        }
                    },
                )
            scheduler.start()
            assertTrue(scheduler.request())
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            withTimeout(10_000L) { scheduler.closeAsync() }
            assertEquals(0L, finished.count)
            assertEquals("CLOSED", scheduler.stateForTesting())
            assertEquals(OrganizationWorkerStatus.STOPPED, scheduler.statusForTesting())
            assertFalse(scheduler.request())
            withTimeout(5_000L) { scheduler.closeAsync() }
        }

    @Test
    fun `cancellation from the round is observable as failed and never replayed`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val attempts = AtomicInteger(0)
                val scheduler =
                    OrganizationScheduler(
                        OrganizationRoundRunner {
                            attempts.incrementAndGet()
                            throw CancellationException("ct16-worker-cancel-marker")
                        },
                    )
                scheduler.start()
                try {
                    assertTrue(scheduler.request())
                    awaitOrganizationUntil { scheduler.statusForTesting() == OrganizationWorkerStatus.FAILED }
                    assertFalse(scheduler.request())
                    assertEquals(1, attempts.get())
                    val log = capture.logs.single { it.formattedMessage.contains("organization.worker.stopped") }
                    assertNull(log.throwable)
                    assertFalse(log.formattedMessage.contains("ct16-worker-cancel-marker"))
                } finally {
                    withTimeout(5_000L) { scheduler.closeAsync() }
                }
            }
        }

    @Test
    fun `start is idempotent and post close start is rejected`() =
        runBlocking {
            val scheduler = OrganizationScheduler(OrganizationRoundRunner { })
            assertFalse(scheduler.request(), "start 前 request 必须拒绝")
            scheduler.start()
            scheduler.start()
            scheduler.closeAsync()
            val failure = assertFailsWith<ContextLifecycleException> { scheduler.start() }
            assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
        }
}
