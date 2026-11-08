package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue

class MemoryMapPeriodicCronTest {
    private val now = Instant.parse("2026-11-08T01:23:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val sourceUpdatedAt = Instant.parse("2026-11-08T01:20:00Z")

    @Test
    fun `next tick is strictly future in Asia Shanghai and default override cron are supported`() {
        val defaultCron =
            MemoryMapPeriodicCron(
                MemoryMapDefaults.CRON,
                UnusedDatabase,
                MemoryMapStatusTracker(),
                request = { false },
                clock = clock,
            )
        val override =
            MemoryMapPeriodicCron(
                "0 9 * * *",
                UnusedDatabase,
                MemoryMapStatusTracker(),
                request = { false },
                clock = clock,
            )

        assertEquals(Instant.parse("2026-11-08T01:30:00Z"), defaultCron.nextAfter(now))
        assertEquals(Instant.parse("2026-11-08T01:00:00Z").plusSeconds(24 * 3600), override.nextAfter(now))
        assertEquals(Instant.parse("2026-11-09T01:00:00Z"), override.nextAfter(Instant.parse("2026-11-08T01:00:00Z")))
    }

    @Test
    fun `start does not scan immediately and a CURRENT map skips refresh`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-CRON-1", sourceUpdatedAt)
            val tracking = TrackingMemoryMapDatabase(fixture.database)
            val tracker = MemoryMapStatusTracker()
            tracker.update(MemoryMapView("current", now, sourceUpdatedAt), MapStatus.CURRENT)
            val requests = AtomicInteger(0)
            val ticks = ManualMemoryMapTicks()
            val cron = MemoryMapPeriodicCron("*/5 * * * *", tracking, tracker, { requests.incrementAndGet() > 0 }, clock, ticks)
            cron.start()
            try {
                assertEquals(0, tracking.reads.get(), "start must not scan immediately")
                ticks.tick()
                awaitMemoryMapUntil { tracking.reads.get() == 1 }
                assertEquals(0, requests.get())
            } finally {
                cron.closeAsync()
                ticks.close()
            }
        }

    @Test
    fun `missing stale and unknown maps request a coordinator refresh`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-CRON-2", sourceUpdatedAt)
            val states =
                listOf(
                    MemoryMapState(null, MapStatus.MISSING),
                    MemoryMapState(MemoryMapView("stale", now, sourceUpdatedAt.minusSeconds(1)), MapStatus.STALE),
                    MemoryMapState(MemoryMapView("unknown", now, sourceUpdatedAt), MapStatus.UNKNOWN),
                )
            states.forEach { state ->
                val tracker = MemoryMapStatusTracker()
                tracker.restore(state)
                val requests = AtomicInteger(0)
                val ticks = ManualMemoryMapTicks()
                val cron = MemoryMapPeriodicCron("*/5 * * * *", fixture.database, tracker, { requests.incrementAndGet() > 0 }, clock, ticks)
                cron.start()
                try {
                    ticks.tick()
                    awaitMemoryMapUntil { requests.get() == 1 }
                } finally {
                    cron.closeAsync()
                    ticks.close()
                }
            }
        }

    @Test
    fun `query failure logs safely and uses cancellable fixed backoff`() =
        withMemoryMapFixture { fixture ->
            Slf4jCapture().use { capture ->
                val tracking = TrackingMemoryMapDatabase(fixture.database, readFailures = Int.MAX_VALUE)
                val backoffEntered = CompletableDeferred<Unit>()
                val ticks = ManualMemoryMapTicks()
                val cron =
                    MemoryMapPeriodicCron(
                        "*/5 * * * *",
                        tracking,
                        MemoryMapStatusTracker(),
                        { true },
                        clock,
                        ticks,
                        backoff = {
                            backoffEntered.complete(Unit)
                            awaitCancellation()
                        },
                    )
                cron.start()
                try {
                    ticks.tick()
                    backoffEntered.await()
                    val log = capture.logs.single { it.formattedMessage.contains("memoryMap.periodic.failed") }
                    assertEquals(Level.WARN, log.level)
                    assertNull(log.throwable)
                    assertEquals(1_000L, MemoryMapPeriodicCron.FAILURE_BACKOFF_MILLIS)
                    withTimeout(900L) { cron.closeAsync() }
                    assertEquals("CLOSED", cron.stateForTesting())
                } finally {
                    cron.closeAsync()
                    ticks.close()
                }
            }
        }

    @Test
    fun `close cancels and joins an in flight lightweight scan`() =
        runBlocking {
            val database = BlockingMemoryMapDatabase()
            val ticks = ManualMemoryMapTicks()
            val cron = MemoryMapPeriodicCron("*/5 * * * *", database, MemoryMapStatusTracker(), { true }, clock, ticks)
            cron.start()
            ticks.tick()
            assertTrue(database.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))

            withTimeout(10_000L) { cron.closeAsync() }

            assertEquals(0L, database.finished.count)
            assertEquals("CLOSED", cron.stateForTesting())
            ticks.close()
        }

    @Test
    fun `ticks never execute overlapping scans`() =
        withMemoryMapFixture { fixture ->
            val delegate = TrackingMemoryMapDatabase(fixture.database)
            val gate = CompletableDeferred<Unit>()
            val firstEntered = CompletableDeferred<Unit>()
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val database =
                object : ContextDatabase {
                    override suspend fun <T> read(block: (ReadContext) -> T): T {
                        val current = active.incrementAndGet()
                        maxActive.updateAndGet { previous -> maxOf(previous, current) }
                        try {
                            if (firstEntered.complete(Unit)) gate.await()
                            return delegate.read(block)
                        } finally {
                            active.decrementAndGet()
                        }
                    }

                    override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)
                }
            val ticks = ManualMemoryMapTicks()
            val requests = AtomicInteger(0)
            val cron =
                MemoryMapPeriodicCron("*/5 * * * *", database, MemoryMapStatusTracker(), { requests.incrementAndGet() > 0 }, clock, ticks)
            cron.start()
            try {
                ticks.tick()
                firstEntered.await()
                ticks.tick()
                ticks.tick()
                gate.complete(Unit)
                awaitMemoryMapUntil { delegate.reads.get() == 3 }
                assertEquals(1, maxActive.get())
                assertEquals(3, requests.get())
            } finally {
                gate.complete(Unit)
                cron.closeAsync()
                ticks.close()
            }
        }

    private object UnusedDatabase : ContextDatabase {
        override suspend fun <T> read(block: (ReadContext) -> T): T = throw UnsupportedOperationException()

        override suspend fun <T> write(block: (TransactionContext) -> T): T = throw UnsupportedOperationException()
    }
}
