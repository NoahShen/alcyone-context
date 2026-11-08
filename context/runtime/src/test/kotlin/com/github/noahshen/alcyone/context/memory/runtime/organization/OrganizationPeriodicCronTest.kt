package com.github.noahshen.alcyone.context.memory.runtime.organization

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CT16 B1 A05：Periodic 严格未来、不补跑、hasAnyPending 门、失败退避与关闭。 */
class OrganizationPeriodicCronTest {
    private val now = Instant.parse("2026-11-07T01:23:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `next tick is strictly future in Asia Shanghai and missed ticks are not replayed`() {
        val cron = OrganizationPeriodicCron("0 9 * * *", UnusedDatabase, request = { false }, clock = clock)
        assertEquals(Instant.parse("2026-11-07T01:00:00Z").plusSeconds(24 * 3600), cron.nextAfter(now))
        assertEquals(Instant.parse("2026-11-08T01:00:00Z"), cron.nextAfter(Instant.parse("2026-11-07T01:00:00Z")))

        val everyFive = OrganizationPeriodicCron("*/5 * * * *", UnusedDatabase, request = { false }, clock = clock)
        assertEquals(Instant.parse("2026-11-07T01:25:00Z"), everyFive.nextAfter(now))
    }

    @Test
    fun `a cron without a future tick logs safely and never requests`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                withOrganizationDatabase { test ->
                    val requests = AtomicInteger(0)
                    val cron =
                        OrganizationPeriodicCron(
                            "0 0 30 2 *",
                            test.database,
                            { requests.incrementAndGet() > 0 },
                            clock = clock,
                        )
                    cron.start()
                    try {
                        awaitOrganizationUntil { capture.logs.any { it.formattedMessage.contains("organization.periodic.failed") } }
                        assertEquals(0, requests.get())
                        assertNull(capture.logs.single { it.formattedMessage.contains("organization.periodic.failed") }.throwable)
                    } finally {
                        cron.closeAsync()
                    }
                }
            }
        }

    @Test
    fun `start does not scan immediately and false pending does not request`() =
        runBlocking {
            withOrganizationDatabase { test ->
                val tracking = TrackingOrganizationDatabase(test.database)
                val ticks = ManualOrganizationTicks()
                val requests = AtomicInteger(0)
                val cron =
                    OrganizationPeriodicCron("*/5 * * * *", tracking, { requests.incrementAndGet() > 0 }, clock = clock, tickSource = ticks)
                cron.start()
                try {
                    assertEquals(0, tracking.readCount.get(), "start 不得立即查询")
                    ticks.tick()
                    tracking.completedReads.receive()
                    assertEquals(0, requests.get())
                } finally {
                    cron.closeAsync()
                    ticks.close()
                }
            }
        }

    @Test
    fun `one tick with pending requests exactly once and sequential ticks request again`() =
        runBlocking {
            withOrganizationDatabase { test ->
                prepareRaw(test.database, "raw-periodic", now, pending = true)
                val tracking = TrackingOrganizationDatabase(test.database)
                val ticks = ManualOrganizationTicks()
                val requests = AtomicInteger(0)
                val cron =
                    OrganizationPeriodicCron("*/5 * * * *", tracking, { requests.incrementAndGet() > 0 }, clock = clock, tickSource = ticks)
                cron.start()
                try {
                    ticks.tick()
                    awaitOrganizationUntil { requests.get() == 1 }
                    ticks.tick()
                    awaitOrganizationUntil { requests.get() == 2 }
                    assertEquals(2, tracking.readCount.get())
                } finally {
                    cron.closeAsync()
                    ticks.close()
                }
            }
        }

    @Test
    fun `tick failure is logged without throwable and the next tick still works`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                withOrganizationDatabase { test ->
                    prepareRaw(test.database, "raw-after-tick-failure", now, pending = true)
                    val ticks = ManualOrganizationTicks()
                    val requests = AtomicInteger(0)
                    val cron =
                        OrganizationPeriodicCron(
                            "*/5 * * * *",
                            test.database,
                            { requests.incrementAndGet() > 0 },
                            clock = clock,
                            tickSource = ticks,
                            backoff = { },
                        )
                    ticks.fail()
                    ticks.tick()
                    cron.start()
                    try {
                        awaitOrganizationUntil { requests.get() == 1 }
                        val log = capture.logs.single { it.formattedMessage.contains("organization.periodic.failed") }
                        assertEquals(Level.WARN, log.level)
                        assertNull(log.throwable)
                        assertFalse(log.formattedMessage.contains("ct16-tick-failure-marker"))
                    } finally {
                        cron.closeAsync()
                        ticks.close()
                    }
                }
            }
        }

    @Test
    fun `pending query failure uses fixed cancellable backoff and close does not wait for it`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                withOrganizationDatabase { test ->
                    val tracking = TrackingOrganizationDatabase(test.database, readFailures = Int.MAX_VALUE)
                    val ticks = ManualOrganizationTicks()
                    val backoffEntered = CompletableDeferred<Unit>()
                    val cron =
                        OrganizationPeriodicCron(
                            "*/5 * * * *",
                            tracking,
                            { true },
                            clock = clock,
                            tickSource = ticks,
                            backoff = {
                                backoffEntered.complete(Unit)
                                awaitCancellation()
                            },
                        )
                    cron.start()
                    try {
                        ticks.tick()
                        backoffEntered.await()
                        assertEquals(1_000L, OrganizationPeriodicCron.FAILURE_BACKOFF_MILLIS)
                        assertTrue(capture.logs.any { it.formattedMessage.contains("organization.periodic.failed") })
                        withTimeout(900L) { cron.closeAsync() }
                        assertEquals("CLOSED", cron.stateForTesting())
                    } finally {
                        cron.closeAsync()
                        ticks.close()
                    }
                }
            }
        }

    @Test
    fun `close cancels and joins an in flight pending query`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val database =
                object : ContextDatabase {
                    override suspend fun <T> read(block: (ReadContext) -> T): T {
                        entered.countDown()
                        try {
                            awaitCancellation()
                        } finally {
                            finished.countDown()
                        }
                    }

                    override suspend fun <T> write(block: (TransactionContext) -> T): T = throw UnsupportedOperationException()
                }
            val ticks = ManualOrganizationTicks()
            val cron = OrganizationPeriodicCron("*/5 * * * *", database, { true }, clock = clock, tickSource = ticks)
            cron.start()
            ticks.tick()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000L) { cron.closeAsync() }
            assertEquals(0L, finished.count)
            assertEquals("CLOSED", cron.stateForTesting())
        }

    private object UnusedDatabase : ContextDatabase {
        override suspend fun <T> read(block: (ReadContext) -> T): T = throw UnsupportedOperationException()

        override suspend fun <T> write(block: (TransactionContext) -> T): T = throw UnsupportedOperationException()
    }
}
