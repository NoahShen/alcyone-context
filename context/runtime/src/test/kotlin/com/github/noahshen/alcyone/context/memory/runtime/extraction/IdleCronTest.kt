package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * CT09-B2 A06（Cron）：UNIX5/固定上海时区的严格未来 tick、不启动即扫、不补跑、
 * 扫描未结束时下一 tick 的真实 skip（single-flight）、关闭停循环并 join 在途扫描、
 * 调度失败的受控日志。
 */
class IdleCronTest {
    private val session = SessionKey("runtime-1", "agent-1", "idle-session")
    private val now: Instant = Instant.parse("2026-10-30T00:30:00Z")
    private val utc: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `next tick is strictly in the future in Asia Shanghai`() {
        val cron = IdleCron(unusedTrigger(), "0 9 * * *", utc)
        // 08:30 Asia/Shanghai → 当日 09:00（= 01:00Z）。
        assertEquals(Instant.parse("2026-10-30T01:00:00Z"), cron.nextAfter(now))
        // 恰好处于 09:00 时不能返回同一时刻，必须严格未来。
        assertEquals(Instant.parse("2026-10-31T01:00:00Z"), cron.nextAfter(Instant.parse("2026-10-30T01:00:00Z")))
    }

    @Test
    fun `missed ticks are not replayed`() {
        val cron = IdleCron(unusedTrigger(), "*/5 * * * *", utc)
        // 08:23 → 08:25（不补 08:05/08:10/08:15/08:20）。
        assertEquals(Instant.parse("2026-10-30T00:25:00Z"), cron.nextAfter(Instant.parse("2026-10-30T00:23:00Z")))
    }

    @Test
    fun `a cron without a future tick reports no next tick`() {
        // 2 月 30 日永远不成立。
        val cron = IdleCron(unusedTrigger(), "0 0 30 2 *", utc)
        assertNull(cron.nextAfter(now))
    }

    @Test
    fun `no scan happens at start and every tick triggers one scan`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database) { requested += it }
            insertTurn(database, session, "t-1", now.minusSeconds(3_600))

            val ticks = ManualTicks()
            val cron = IdleCron(trigger, "*/5 * * * *", utc, ticks)
            cron.start()
            try {
                delay(150)
                assertTrue(requested.isEmpty(), "启动不得立即扫描")

                ticks.tick()
                awaitUntil { requested.size == 1 }
                assertEquals(listOf(session), requested)
            } finally {
                cron.closeAsync()
                ticks.close()
            }
        }

    @Test
    fun `a tick arriving during a scan is really skipped by single flight`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val trigger = idleTrigger(database) { requested += it }
                insertTurn(database, session, "t-1", now.minusSeconds(3_600))

                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                trigger.scanBarrierForTesting = {
                    entered.complete(Unit)
                    release.await()
                }

                val ticks = ManualTicks()
                val cron = IdleCron(trigger, "*/5 * * * *", utc, ticks)
                cron.start()
                try {
                    ticks.tick()
                    entered.await()
                    // 扫描未结束时到达下一 tick：循环不得串行 await，必须真实走到 single-flight skip。
                    ticks.tick()
                    // 计数先于日志写入，等待日志本身（有界）而不是直接断言。
                    awaitUntil { capture.logs.any { it.formattedMessage.contains("extraction.idle.skipped") } }
                    assertEquals(1L, trigger.skippedForTesting())

                    release.complete(Unit)
                    awaitUntil { requested.size == 1 }
                    assertEquals(1L, trigger.skippedForTesting())
                } finally {
                    release.complete(Unit)
                    cron.closeAsync()
                    ticks.close()
                }
            }
        }

    @Test
    fun `close stops the loop and joins the in-flight scan`() =
        withTriggerDatabase { database ->
            val barrierEntries = AtomicInteger()
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val trigger = idleTrigger(database) { }
            trigger.scanBarrierForTesting = {
                barrierEntries.incrementAndGet()
                try {
                    entered.countDown()
                    awaitCancellation()
                } finally {
                    finished.countDown()
                }
            }
            insertTurn(database, session, "t-1", now.minusSeconds(3_600))

            val ticks = ManualTicks()
            val cron = IdleCron(trigger, "*/5 * * * *", utc, ticks)
            cron.start()
            try {
                ticks.tick()
                assertTrue(entered.await(10, TimeUnit.SECONDS), "扫描必须已开始")

                withTimeout(10_000L) { cron.closeAsync() }
                assertEquals(0L, finished.count, "close 返回时扫描的 finally 必须已执行")
                assertEquals(0, cron.inFlightScanCountForTesting())
                assertEquals("CLOSED", cron.stateForTesting())

                ticks.tick()
                delay(200)
                assertEquals(1, barrierEntries.get(), "关闭后 tick 不得再触发扫描")
                val closed = assertFailsWith<com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException> { cron.start() }
                assertEquals(com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode.CLOSED, closed.errorCode)
            } finally {
                cron.closeAsync()
                ticks.close()
            }
        }

    @Test
    fun `a tick source failure is logged and the next tick still triggers`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val trigger = idleTrigger(database) { requested += it }
                insertTurn(database, session, "t-1", now.minusSeconds(3_600))

                val ticks = ManualTicks()
                ticks.failNextAwait()
                val cron = IdleCron(trigger, "*/5 * * * *", utc, ticks)
                cron.start()
                try {
                    awaitUntil { capture.logs.any { it.formattedMessage.contains("extraction.idle.failed") } }
                    assertTrue(requested.isEmpty(), "调度失败不得触发")

                    ticks.tick()
                    awaitUntil { requested.size == 1 }
                } finally {
                    cron.closeAsync()
                    ticks.close()
                }
            }
        }

    @Test
    fun `a closed tick source ends the loop without scanning`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database) { requested += it }
            insertTurn(database, session, "t-1", now.minusSeconds(3_600))

            val ticks = ManualTicks()
            ticks.close()
            val cron = IdleCron(trigger, "*/5 * * * *", utc, ticks)
            cron.start()
            try {
                delay(200)
                assertTrue(requested.isEmpty())
                withTimeout(10_000L) { cron.closeAsync() }
                assertEquals("CLOSED", cron.stateForTesting())
            } finally {
                cron.closeAsync()
            }
        }

    @Test
    fun `a persistent default tick failure stays cancellable and never scans`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val trigger = idleTrigger(database) { requested += it }
                insertTurn(database, session, "t-1", now.minusSeconds(3_600))

                // 默认 tick 源 + 极端 Clock：nextAfter 持续失败。
                val clock = CountingClock(Instant.MAX)
                val cron = IdleCron(trigger, "*/5 * * * *", clock)
                cron.start()
                try {
                    awaitUntil {
                        capture.logs.count { it.formattedMessage.contains("extraction.idle.failed") } >= 1
                    }
                    val readsBeforeClose = clock.reads.get()
                    withTimeout(3_000L) { cron.closeAsync() }

                    assertEquals("CLOSED", cron.stateForTesting())
                    assertEquals(0, cron.inFlightScanCountForTesting())
                    assertTrue(requested.isEmpty(), "持续调度失败不得扫描")
                    assertTrue(readsBeforeClose <= 5, "失败期 clock 读取必须受控（每轮 1 次 + 1s 退避）: $readsBeforeClose")
                    assertTrue(
                        clock.reads.get() - readsBeforeClose <= 3,
                        "关闭前后不得出现紧凑循环级读取: $readsBeforeClose -> ${clock.reads.get()}",
                    )
                } finally {
                    cron.closeAsync()
                }
            }
        }

    @Test
    fun `consecutive scheduling failures keep a controlled interval`() =
        withTriggerDatabase { database ->
            val trigger = idleTrigger(database) { }
            val logger = TimestampLogger()
            val cron = IdleCron(trigger, "*/5 * * * *", CountingClock(Instant.MAX), logger = logger)
            cron.start()
            try {
                awaitUntil { logger.warningTimestamps().size >= 3 }
                val timestamps = logger.warningTimestamps().take(3)
                timestamps.zipWithNext { previous, next ->
                    assertTrue(next - previous >= 900L, "相邻失败必须相隔约 1s，实际: $timestamps")
                }
            } finally {
                cron.closeAsync()
            }
        }

    @Test
    fun `closing during the scheduling backoff completes without waiting for it`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val trigger = idleTrigger(database) { requested += it }
                insertTurn(database, session, "t-1", now.minusSeconds(3_600))

                val cron = IdleCron(trigger, "*/5 * * * *", CountingClock(Instant.MAX))
                cron.start()
                try {
                    awaitUntil {
                        capture.logs.any { it.formattedMessage.contains("extraction.idle.failed") }
                    }
                    val startedAt = System.nanoTime() / 1_000_000L
                    // 若关闭要等满 1s 退避，这里会超时。
                    withTimeout(900L) { cron.closeAsync() }
                    val elapsed = System.nanoTime() / 1_000_000L - startedAt
                    assertTrue(elapsed < 900L, "关闭不得等待满 1s 退避，实际 ${elapsed}ms")
                    assertEquals("CLOSED", cron.stateForTesting())
                    assertTrue(requested.isEmpty(), "退避中关闭不得扫描")
                } finally {
                    cron.closeAsync()
                }
            }
        }

    @Test
    fun `a recovered default tick source waits for the next natural tick without replay`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database) { requested += it }
            insertTurn(database, session, "t-1", now.minusSeconds(3_600))

            val clock = CountingClock(Instant.MAX)
            val cron = IdleCron(trigger, "*/5 * * * *", clock)
            cron.start()
            try {
                awaitUntil { clock.reads.get() >= 1 }
                delay(1_200)
                assertTrue(requested.isEmpty(), "持续失败期间不得扫描")

                // 恢复到下一 tick 之前（00:04:59Z → next 00:05:00Z）。
                clock.current = Instant.parse("2026-10-30T00:04:59Z")
                delay(400)
                assertTrue(requested.isEmpty(), "恢复后不得立即扫描（退避不是 tick）")

                awaitUntil { requested.size == 1 }
                assertEquals(1, requested.size, "到自然 tick 恰好扫描一次")

                // 把时钟推进到更远的未来，验证不会补跑此前错过的 tick。
                clock.current = Instant.parse("2026-10-30T00:05:30Z")
                delay(700)
                assertEquals(1, requested.size, "不得补跑错过的 tick")
            } finally {
                cron.closeAsync()
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun unusedTrigger(): IdleTrigger =
        IdleTrigger(
            databaseProvider = { throw IllegalStateException("tick 计算不应访问数据库") },
            idleTimeout = 10.minutes,
            clock = utc,
            request = { true },
        )

    private fun idleTrigger(
        database: ContextDatabase,
        onRequest: (SessionKey) -> Unit,
    ): IdleTrigger =
        IdleTrigger(
            databaseProvider = { database },
            idleTimeout = 10.minutes,
            clock = MutableClock(now),
            request = { key ->
                onRequest(key)
                true
            },
        )

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(10_000L) {
            while (!condition()) delay(10)
        }
    }

    /** 记录读取次数的可变时钟（默认 tick 源持续失败/恢复用）。 */
    private class CountingClock(
        var current: Instant,
    ) : Clock() {
        val reads = AtomicInteger()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant {
            reads.incrementAndGet()
            return current
        }
    }

    /** 记录 WARN 单调时间戳的测试 Logger（验证连续失败之间的受控间隔）。 */
    private class TimestampLogger : AbstractLogger() {
        private val timestamps = java.util.concurrent.CopyOnWriteArrayList<Long>()

        fun warningTimestamps(): List<Long> = timestamps.toList()

        override fun getFullyQualifiedCallerName(): String? = null

        override fun handleNormalizedLoggingCall(
            level: Level,
            marker: org.slf4j.Marker?,
            pattern: String?,
            arguments: Array<out Any>?,
            throwable: Throwable?,
        ) {
            if (level == Level.WARN) timestamps += System.nanoTime() / 1_000_000L
        }

        override fun isTraceEnabled(): Boolean = true

        override fun isTraceEnabled(marker: org.slf4j.Marker?): Boolean = true

        override fun isDebugEnabled(): Boolean = true

        override fun isDebugEnabled(marker: org.slf4j.Marker?): Boolean = true

        override fun isInfoEnabled(): Boolean = true

        override fun isInfoEnabled(marker: org.slf4j.Marker?): Boolean = true

        override fun isWarnEnabled(): Boolean = true

        override fun isWarnEnabled(marker: org.slf4j.Marker?): Boolean = true

        override fun isErrorEnabled(): Boolean = true

        override fun isErrorEnabled(marker: org.slf4j.Marker?): Boolean = true
    }

    /** 可控 tick 来源：`tick()` 投递一次，`failNextAwait()` 让下一次等待受控失败。 */
    private class ManualTicks : IdleTickSource {
        private val channel = Channel<Unit>(Channel.UNLIMITED)
        private val failNext =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        fun tick() {
            channel.trySend(Unit)
        }

        fun failNextAwait() {
            failNext.set(true)
        }

        fun close() {
            channel.close()
        }

        override suspend fun awaitTick(): Boolean {
            if (failNext.compareAndSet(true, false)) {
                throw IllegalStateException("ct09-b2-tick-source-failure")
            }
            val received = channel.receiveCatching()
            return !received.isClosed
        }
    }
}
