package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * CT09-B2 A05（Idle）：真实 SQLite 上的等号边界、所有状态 `created_at`、single-flight 真实跳过、
 * 查询失败隔离、取消传播与极端 `Duration`/`Instant` 的受控失败。
 */
class IdleTriggerTest {
    private val sessionA = SessionKey("runtime-1", "agent-1", "session-a")
    private val sessionB = SessionKey("runtime-1", "agent-1", "session-b")
    private val now: Instant = Instant.parse("2026-10-30T10:00:00Z")

    @Test
    fun `the created at boundary is inclusive at now minus timeout`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database, clock = MutableClock(now)) { requested += it }

            // 恰好 now - 10m：触发。
            insertTurn(database, sessionA, "a-1", now.minusSeconds(600))
            assertEquals(IdleScanOutcome.TRIGGERED, trigger.scanOnce())
            assertEquals(listOf(sessionA), requested)

            // 让第一个会话不再空闲，再验证「晚 1ms」不触发。
            requested.clear()
            insertTurn(database, sessionA, "a-2", now, TurnStatus.COMPLETE)
            insertTurn(database, sessionB, "b-1", now.minusSeconds(600).plusMillis(1))
            assertEquals(IdleScanOutcome.NO_CANDIDATES, trigger.scanOnce())
            assertTrue(requested.isEmpty(), "晚于边界 1ms 不得触发")
        }

    @Test
    fun `newer complete or failed turns block older pending turns`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database, clock = MutableClock(now)) { requested += it }

            insertTurn(database, sessionA, "a-pending", now.minusSeconds(3_600))
            insertTurn(database, sessionA, "a-complete", now.minusSeconds(60), TurnStatus.COMPLETE)
            insertTurn(database, sessionB, "b-pending", now.minusSeconds(3_600))
            insertTurn(database, sessionB, "b-failed", now.minusSeconds(30), TurnStatus.FAILED)

            assertEquals(IdleScanOutcome.NO_CANDIDATES, trigger.scanOnce())
            assertTrue(requested.isEmpty(), "较新的 complete/failed 必须阻止旧 pending 触发")
        }

    @Test
    fun `timestamp does not participate in the idle decision`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database, clock = MutableClock(now)) { requested += it }

            // created_at 很旧（空闲），timestamp 在未来：仍应触发。
            insertTurn(
                database,
                sessionA,
                "a-1",
                createdAt = now.minusSeconds(3_600),
                timestamp = now.plusSeconds(3_600),
            )
            assertEquals(IdleScanOutcome.TRIGGERED, trigger.scanOnce())
            assertEquals(listOf(sessionA), requested)
        }

    @Test
    fun `no pending or only failed sessions are not triggered`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database, clock = MutableClock(now)) { requested += it }

            assertEquals(IdleScanOutcome.NO_CANDIDATES, trigger.scanOnce(), "空库不触发")

            insertTurn(database, sessionA, "a-failed", now.minusSeconds(3_600), TurnStatus.FAILED)
            insertTurn(database, sessionB, "b-complete", now.minusSeconds(3_600), TurnStatus.COMPLETE)
            assertEquals(IdleScanOutcome.NO_CANDIDATES, trigger.scanOnce(), "仅 failed/complete 不触发")
            assertTrue(requested.isEmpty())
        }

    @Test
    fun `sessions across runtimes agents and sessions are isolated`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger = idleTrigger(database, clock = MutableClock(now)) { requested += it }
            val idle = SessionKey("runtime-1", "agent-1", "idle")
            val busy = SessionKey("runtime-1", "agent-1", "busy")
            val otherRuntime = SessionKey("runtime-2", "agent-1", "busy")
            val otherAgent = SessionKey("runtime-1", "agent-2", "busy")

            insertTurn(database, idle, "idle-1", now.minusSeconds(3_600))
            listOf(busy, otherRuntime, otherAgent).forEach { key ->
                insertTurn(database, key, "pending", now.minusSeconds(3_600))
                insertTurn(database, key, "recent", now.minusSeconds(10), TurnStatus.COMPLETE)
            }

            assertEquals(IdleScanOutcome.TRIGGERED, trigger.scanOnce())
            assertEquals(listOf(idle), requested, "只提交真正空闲的会话")
        }

    @Test
    fun `overlapping scans are skipped and counted`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val trigger = idleTrigger(database, clock = MutableClock(now)) { requested += it }
                insertTurn(database, sessionA, "a-1", now.minusSeconds(3_600))

                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                trigger.scanBarrierForTesting = {
                    entered.complete(Unit)
                    release.await()
                }

                coroutineScope {
                    val first = async(Dispatchers.Default) { trigger.scanOnce() }
                    entered.await()
                    assertEquals(IdleScanOutcome.SKIPPED, trigger.scanOnce(), "重叠扫描必须真实 skip")
                    assertEquals(1L, trigger.skippedForTesting())
                    assertTrue(
                        capture.logs.any { it.formattedMessage.contains("extraction.idle.skipped") },
                        "skip 必须有 DEBUG 日志: ${capture.logs.map { it.formattedMessage }}",
                    )

                    release.complete(Unit)
                    assertEquals(IdleScanOutcome.TRIGGERED, first.await())
                }
                assertEquals(1L, trigger.skippedForTesting(), "只有重叠的那次被跳过")
                assertFalse(trigger.isScanningForTesting(), "single-flight 标记必须释放")
            }
        }

    @Test
    fun `a query failure is isolated and the next scan succeeds`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val flaky = FlakyDatabase(database, failures = 1)
                val trigger = idleTrigger(flaky, clock = MutableClock(now)) { requested += it }
                insertTurn(database, sessionA, "a-1", now.minusSeconds(3_600))

                assertEquals(IdleScanOutcome.FAILED, trigger.scanOnce())
                assertTrue(requested.isEmpty(), "查询失败不得触发")
                assertFalse(trigger.isScanningForTesting(), "失败也必须释放 single-flight")
                val failureLog = capture.logs.single { it.formattedMessage.contains("extraction.idle.failed") }
                assertEquals(null, failureLog.throwable, "不得把 Throwable 传给 logger")
                assertTrue(capture.logs.none { it.formattedMessage.contains(sessionA.sessionId) })
                assertTrue(capture.logs.none { it.formattedMessage.contains("ct09-b2-query-failure") })

                assertEquals(IdleScanOutcome.TRIGGERED, trigger.scanOnce(), "下一次扫描必须成功")
                assertEquals(listOf(sessionA), requested)
            }
        }

    @Test
    fun `cancellation propagates without a failure log and releases single flight`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val cancelling =
                    FlakyDatabase(
                        delegate = database,
                        failures = 1,
                        failure = { CancellationException("ct09-b2-idle-cancel") },
                    )
                val trigger = idleTrigger(cancelling, clock = MutableClock(now)) { requested += it }
                insertTurn(database, sessionA, "a-1", now.minusSeconds(3_600))

                assertFailsWith<CancellationException> { trigger.scanOnce() }
                assertTrue(capture.logs.none { it.formattedMessage.contains("extraction.idle.failed") })
                assertFalse(trigger.isScanningForTesting())

                assertEquals(IdleScanOutcome.TRIGGERED, trigger.scanOnce())
                assertEquals(listOf(sessionA), requested)
            }
        }

    @Test
    fun `an overflowing idle window fails safely and the next scan still works`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                val clock = MutableClock(Instant.MAX)
                val trigger = idleTrigger(database, clock = clock) { requested += it }
                insertTurn(database, sessionA, "a-1", now.minusSeconds(3_600))

                // Instant.MAX - 10m 溢出：受控失败、0 触发、不得退化为错误短窗口。
                assertEquals(IdleScanOutcome.FAILED, trigger.scanOnce())
                assertTrue(requested.isEmpty(), "溢出不得触发任何会话")
                clock.current = Instant.MIN
                assertEquals(IdleScanOutcome.FAILED, trigger.scanOnce(), "Instant.MIN 同样受控失败")
                assertTrue(requested.isEmpty())
                assertTrue(
                    capture.logs.count { it.formattedMessage.contains("extraction.idle.failed") } >= 2,
                    "每次受控失败都必须 WARN",
                )

                // 时钟恢复正常后，同一实例的下一次扫描仍可触发。
                clock.current = now
                assertEquals(IdleScanOutcome.TRIGGERED, trigger.scanOnce())
                assertEquals(listOf(sessionA), requested)
            }
        }

    @Test
    fun `a huge finite duration fails safely instead of shrinking the window`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val requested = mutableListOf<SessionKey>()
                // 极大但有限的 Duration（约 2.9e11 年）：Instant 运算必然溢出。
                val trigger =
                    idleTrigger(
                        database,
                        clock = MutableClock(now),
                        timeout = Long.MAX_VALUE.seconds,
                    ) { requested += it }
                insertTurn(database, sessionA, "a-1", now.minusSeconds(3_600))

                assertEquals(IdleScanOutcome.FAILED, trigger.scanOnce())
                assertTrue(requested.isEmpty(), "极大有限 Duration 不得退化为短窗口触发")
                assertTrue(capture.logs.any { it.formattedMessage.contains("extraction.idle.failed") })
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun idleTrigger(
        database: com.github.noahshen.alcyone.context.memory.core.ContextDatabase,
        clock: java.time.Clock,
        timeout: Duration = 10.minutes,
        onRequest: (SessionKey) -> Unit,
    ): IdleTrigger =
        IdleTrigger(
            databaseProvider = { database },
            idleTimeout = timeout,
            clock = clock,
            request = { key ->
                onRequest(key)
                true
            },
        )
}
