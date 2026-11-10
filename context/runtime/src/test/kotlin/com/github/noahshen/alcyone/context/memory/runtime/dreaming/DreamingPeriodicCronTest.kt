package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DreamingPeriodicCronTest {
    private val now = Instant.parse("2026-11-09T08:23:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `next tick is strictly future in configured timezone and no catch up`() {
        val cron = DreamingPeriodicCron("0 9 * * *", "Asia/Shanghai", request = { false }, clock = clock)
        assertEquals(Instant.parse("2026-11-09T01:00:00Z").plusSeconds(24 * 3600), cron.nextAfter(now))
        assertEquals(Instant.parse("2026-11-10T01:00:00Z"), cron.nextAfter(Instant.parse("2026-11-09T01:00:00Z")))
    }

    @Test
    fun `start does not tick immediately and each tick only requests background`() =
        runBlocking {
            val ticks = ManualDreamingTicks()
            val requests = AtomicInteger(0)
            val cron = DreamingPeriodicCron("0 3 * * *", "Asia/Shanghai", { requests.incrementAndGet() > 0 }, clock, ticks)
            cron.start()
            try {
                assertEquals(0, requests.get())
                ticks.tick()
                awaitUntil { requests.get() == 1 }
            } finally {
                cron.closeAsync()
                ticks.close()
            }
        }

    @Test
    fun `tick failure uses fixed cancellable backoff and close does not wait`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val ticks = ManualDreamingTicks()
                val entered = CompletableDeferred<Unit>()
                val cron =
                    DreamingPeriodicCron(
                        "0 3 * * *",
                        "Asia/Shanghai",
                        { true },
                        clock,
                        ticks,
                        backoff = {
                            entered.complete(Unit)
                            awaitCancellation()
                        },
                    )
                cron.start()
                try {
                    ticks.fail()
                    entered.await()
                    val log = capture.logs.single { it.formattedMessage.contains("dreaming.periodic.failed") }
                    assertEquals(Level.WARN, log.level)
                    assertNull(log.throwable)
                    assertEquals(1_000L, DreamingPeriodicCron.FAILURE_BACKOFF_MILLIS)
                    withTimeout(900L) { cron.closeAsync() }
                    assertEquals("CLOSED", cron.stateForTesting())
                } finally {
                    cron.closeAsync()
                    ticks.close()
                }
            }
        }

    @Test
    fun `close cancels and joins tick loop`() =
        runBlocking {
            val ticks = ManualDreamingTicks()
            val cron = DreamingPeriodicCron("0 3 * * *", "Asia/Shanghai", { true }, clock, ticks)
            cron.start()
            withTimeout(10_000L) { cron.closeAsync() }
            assertEquals("CLOSED", cron.stateForTesting())
            ticks.close()
        }

    @Test
    fun `concurrent cron close shares the same failure and runs cleanup once`() =
        runBlocking {
            val failure = IllegalStateException("ct22-cron-close-failure")
            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val cleanups = AtomicInteger(0)
            val ticks = ManualDreamingTicks()
            val cron = DreamingPeriodicCron("0 3 * * *", "Asia/Shanghai", { true }, clock, ticks)
            cron.closeActionForTesting = {
                cleanups.incrementAndGet()
                cleanupEntered.complete(Unit)
                runBlocking { releaseCleanup.await() }
                throw failure
            }
            cron.start()
            try {
                val first = async(Dispatchers.Default) { runCatching { cron.closeAsync() } }
                cleanupEntered.await()
                val second = async(Dispatchers.Default) { runCatching { cron.closeAsync() } }
                releaseCleanup.complete(Unit)
                assertSame(failure, first.await().exceptionOrNull())
                assertSame(failure, second.await().exceptionOrNull())
                assertEquals(1, cleanups.get())
                assertEquals("CLOSED", cron.stateForTesting())
            } finally {
                releaseCleanup.complete(Unit)
                ticks.close()
            }
        }

    @Test
    fun `running background skip is returned without throwing and cron does not queue trigger`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertDreamingTurn(fixture.database, sessionTurnId = "one")
                val entered = CompletableDeferred<Unit>()
                val coordinator = DreamingRunCoordinator(fixture.database, RecordingDreamingProcessor(), CountingDreamingBudget(0, 0, 0))
                coordinator.runHookForTesting = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val ticks = ManualDreamingTicks()
                val cron = DreamingPeriodicCron("0 3 * * *", "Asia/Shanghai", coordinator::requestBackground, clock, ticks)
                cron.start()
                try {
                    ticks.tick()
                    entered.await()
                    ticks.tick()
                    awaitUntil { coordinator.roundCountForTesting() == 1L }
                    assertEquals(1, coordinator.ownedRunCountForTesting())
                    assertEquals(1L, coordinator.roundCountForTesting())
                } finally {
                    cron.closeAsync()
                    coordinator.closeAsync()
                    ticks.close()
                }
            }
        }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(10_000L) {
            while (!condition()) kotlinx.coroutines.delay(10)
        }
    }
}
