package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionService
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CT09-B2 A05（Count）：真实 SQLite 上验证 pending 阈值、状态过滤、跨 Session 隔离、
 * 查询失败隔离、取消传播，以及 `IngestionService` 的 CREATED/DUPLICATE 通知语义。
 */
class CountTriggerTest {
    private val session = SessionKey("runtime-1", "agent-1", "session-1")
    private val otherSession = SessionKey("runtime-1", "agent-1", "session-2")
    private val base: Instant = Instant.parse("2026-10-30T00:00:00Z")

    @Test
    fun `the threshold is reached exactly at five pending turns`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val failures = mutableListOf<Throwable>()
            val trigger =
                CountTrigger(
                    threshold = 5,
                    databaseProvider = { database },
                    request = { key ->
                        requested += key
                        true
                    },
                    reporter = IngestionNotificationReporter { failures += it },
                )

            repeat(4) { index ->
                insertTurn(database, session, "turn-$index", base)
                trigger.onCreated(session)
                assertTrue(requested.isEmpty(), "pending=${index + 1} 时不得触发")
            }
            insertTurn(database, session, "turn-4", base)
            trigger.onCreated(session)
            assertEquals(listOf(session), requested, "第 5 条 pending 必须触发一次")
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `complete and failed turns are not counted`() =
        withTriggerDatabase { database ->
            var requests = 0
            val trigger =
                CountTrigger(
                    threshold = 5,
                    databaseProvider = { database },
                    request = {
                        requests++
                        true
                    },
                    reporter = IngestionNotificationReporter { },
                )

            repeat(4) { index -> insertTurn(database, session, "pending-$index", base) }
            repeat(3) { index -> insertTurn(database, session, "complete-$index", base, TurnStatus.COMPLETE) }
            repeat(3) { index -> insertTurn(database, session, "failed-$index", base, TurnStatus.FAILED) }
            trigger.onCreated(session)
            assertEquals(0, requests, "只有 pending 计入阈值")

            insertTurn(database, session, "pending-4", base)
            trigger.onCreated(session)
            assertEquals(1, requests)
        }

    @Test
    fun `sessions are counted independently`() =
        withTriggerDatabase { database ->
            val requested = mutableListOf<SessionKey>()
            val trigger =
                CountTrigger(
                    threshold = 5,
                    databaseProvider = { database },
                    request = { key ->
                        requested += key
                        true
                    },
                    reporter = IngestionNotificationReporter { },
                )

            repeat(3) { index -> insertTurn(database, session, "a-$index", base) }
            repeat(2) { index -> insertTurn(database, otherSession, "b-$index", base) }
            trigger.onCreated(session)
            trigger.onCreated(otherSession)
            assertTrue(requested.isEmpty(), "跨 Session 不得合并计数")

            insertTurn(database, session, "a-3", base)
            insertTurn(database, session, "a-4", base)
            trigger.onCreated(session)
            trigger.onCreated(otherSession)
            assertEquals(listOf(session), requested)
        }

    @Test
    fun `a query failure goes to the reporter and never triggers`() =
        Slf4jCapture().use { capture ->
            withTriggerDatabase { database ->
                val flaky = FlakyDatabase(database, failures = 1)
                val requested = mutableListOf<SessionKey>()
                val failures = mutableListOf<Throwable>()
                val trigger =
                    CountTrigger(
                        threshold = 1,
                        databaseProvider = { flaky },
                        request = { key ->
                            requested += key
                            true
                        },
                        reporter = IngestionNotificationReporter { failures += it },
                    )

                trigger.onCreated(session)
                assertEquals(1, failures.size, "查询失败必须交给既有 reporter")
                assertTrue(requested.isEmpty(), "查询失败不得触发")
                assertTrue(
                    capture.logs.none { it.formattedMessage.contains(session.sessionId) },
                    "日志不得包含 SessionKey: ${capture.logs.map { it.formattedMessage }}",
                )
                assertTrue(capture.logs.none { it.formattedMessage.contains("ct09-b2-query-failure") })
            }
        }

    @Test
    fun `cancellation is propagated unchanged`() =
        withTriggerDatabase { database ->
            val flaky =
                FlakyDatabase(
                    delegate = database,
                    failures = 1,
                    failure = { CancellationException("ct09-b2-cancel") },
                )
            var reported = 0
            val trigger =
                CountTrigger(
                    threshold = 1,
                    databaseProvider = { flaky },
                    request = { true },
                    reporter = IngestionNotificationReporter { reported++ },
                )
            assertFailsWith<CancellationException> { trigger.onCreated(session) }
            assertEquals(0, reported, "取消不是查询失败，不得交给 reporter")
        }

    @Test
    fun `ingestion notifies only for created turns and count gates the request`() =
        withTriggerDatabase { database ->
            val sessionKey = SessionKey("runtime-1", "agent-1", "ingest-session")
            val requested = mutableListOf<SessionKey>()
            var notifications = 0
            val countTrigger =
                CountTrigger(
                    threshold = 5,
                    databaseProvider = { database },
                    request = { key ->
                        requested += key
                        true
                    },
                    reporter = IngestionNotificationReporter { },
                )
            val notifier =
                IngestionNotifier { key ->
                    notifications++
                    countTrigger.onCreated(key)
                }
            val service =
                IngestionService.create(
                    limits = IngestionLimits.DEFAULTS,
                    clock = Clock.fixed(base, java.time.ZoneOffset.UTC),
                    notifier = notifier,
                    reporter = IngestionNotificationReporter { },
                    databaseProvider = { database },
                )

            val turns =
                (1..5).map { index ->
                    ConversationTurn(
                        runtimeId = sessionKey.runtimeId,
                        agentId = sessionKey.agentId,
                        sessionId = sessionKey.sessionId,
                        sessionTurnId = "ingest-$index",
                        timestamp = base,
                        messages = listOf(ChatMessage(ChatRole.USER, "hello-$index")),
                    )
                }
            turns.forEach { turn ->
                assertEquals(IngestStatus.CREATED, service.ingest(turn).status)
                // Runtime 在 COMMIT 之后调用通知（DUPLICATE 不调用）。
                service.notifyCreated(sessionKey)
            }
            assertEquals(5, notifications, "每次 CREATED 都通知")
            assertEquals(listOf(sessionKey), requested, "只有第 5 条 pending 才发 Trigger")

            // DUPLICATE 不通知（CT07 契约），因此不会再次计数或触发。
            assertEquals(IngestStatus.DUPLICATE, service.ingest(turns.first()).status)
            assertEquals(5, notifications, "DUPLICATE 不得再次通知")
            assertEquals(1, requested.size)

            // 直接确认库内 pending 数 = 5（阈值判定只依赖 pending）。
            val pending = database.read { read -> read.conversations.countPending(sessionKey) }
            assertEquals(5, pending)
        }

    @Test
    fun `threshold below one is rejected at construction`() {
        val database: ContextDatabase? = null
        assertFailsWith<IllegalArgumentException> {
            CountTrigger(
                threshold = 0,
                databaseProvider = { database ?: error("unused") },
                request = { true },
                reporter = IngestionNotificationReporter { },
            )
        }
    }
}
