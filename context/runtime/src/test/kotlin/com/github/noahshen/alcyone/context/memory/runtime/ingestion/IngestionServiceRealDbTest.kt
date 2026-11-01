package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.ingestion.DelegatingIngestionService
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionService
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionTimestampText
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CT07-B1 入库服务：真实文件 SQLite 上的首次/重复/冲突、限额、并发、重开与通知语义。
 */
class IngestionServiceRealDbTest {
    @Test
    fun `created then duplicate returns the same id without refreshing createdAt or status`() =
        withService { service, database ->
            val clock = MutableClock(IngestionTurns.T0)
            val first = service.ingest(IngestionTurns.turn())
            assertEquals(IngestStatus.CREATED, first.status)

            clock.advanceTo(IngestionTurns.T0.plusSeconds(3600))
            val replayed = service.ingest(IngestionTurns.turn())
            assertEquals(IngestStatus.DUPLICATE, replayed.status)
            assertEquals(first.id, replayed.id)

            val stored = assertNotNull(runBlocking { database.read { it.conversations.getById(first.id) } })
            assertEquals(IngestionTurns.T0, stored.createdAt, "DUPLICATE 不得刷新 createdAt")
            assertEquals(ExtractionStatus.PENDING, stored.extractionStatus)
            assertEquals(IngestionTurns.messages("hello"), stored.messages)
        }

    @Test
    fun `equivalent timestamps are duplicates while message or content changes conflict`() =
        withService { service, _ ->
            val first = service.ingest(IngestionTurns.turn(timestamp = IngestionTurns.T0))
            assertEquals(IngestStatus.CREATED, first.status)

            // Instant 相同（不同 offset 表示在 API 层已归一）：DUPLICATE。
            val sameInstant = service.ingest(IngestionTurns.turn(timestamp = IngestionTurns.T0))
            assertEquals(IngestStatus.DUPLICATE, sameInstant.status)
            assertEquals(first.id, sameInstant.id)

            val ordered =
                assertFailsWith<ContextIngestionException> {
                    service.ingest(IngestionTurns.turn(messages = IngestionTurns.messages("b", "a")))
                }
            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, ordered.errorCode)

            val changedContent =
                assertFailsWith<ContextIngestionException> {
                    service.ingest(IngestionTurns.turn(messages = IngestionTurns.messages(" hello ")))
                }
            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, changedContent.errorCode)
        }

    @Test
    fun `complete replay stays duplicate and keeps the completed status`() =
        withService { service, database ->
            val first = service.ingest(IngestionTurns.turn())
            assertEquals(1, runBlocking { database.write { tx -> tx.conversations.markComplete(first.id) } })

            val replayed = service.ingest(IngestionTurns.turn())
            assertEquals(IngestStatus.DUPLICATE, replayed.status)
            assertEquals(first.id, replayed.id)
            assertEquals(
                ExtractionStatus.COMPLETE,
                runBlocking { database.read { it.conversations.getById(first.id) } }?.extractionStatus,
            )
        }

    @Test
    fun `limits reject identifiers messages and payload without writing any row`() =
        withService(
            limits =
                IngestionLimits(
                    maxTurnBytes = IngestionLimits.DEFAULT_MAX_TURN_BYTES,
                    maxMessages = 4,
                    maxIdLength = 8,
                ),
        ) { service, database ->
            val longId =
                assertFailsWith<ContextIngestionException> {
                    service.ingest(IngestionTurns.turn(sessionTurnId = "x".repeat(9)))
                }
            assertEquals(ContextIngestionErrorCode.PAYLOAD_TOO_LARGE, longId.errorCode)

            val tooMany =
                assertFailsWith<ContextIngestionException> {
                    service.ingest(IngestionTurns.turn(messages = IngestionTurns.messages("1", "2", "3", "4", "5")))
                }
            assertEquals(ContextIngestionErrorCode.PAYLOAD_TOO_LARGE, tooMany.errorCode)

            assertTrue(!longId.message.orEmpty().contains("x".repeat(9)), "错误消息不得回显 ID 值")
            assertFalse(hasAnyTurn(database), "拒绝的轮次不得落库")
        }

    @Test
    fun `default two MiB payload limit rejects oversized turns`() =
        withService { service, database ->
            val oversized = "a".repeat(IngestionLimits.DEFAULT_MAX_TURN_BYTES + 1)
            val failure =
                assertFailsWith<ContextIngestionException> {
                    service.ingest(IngestionTurns.turn(messages = IngestionTurns.messages(oversized)))
                }
            assertEquals(ContextIngestionErrorCode.PAYLOAD_TOO_LARGE, failure.errorCode)
            assertTrue(!failure.message.orEmpty().contains("aaaa"), "错误消息不得回显正文")
            assertFalse(hasAnyTurn(database), "拒绝的轮次不得落库")
        }

    @Test
    fun `blank identifiers and empty messages are rejected by the frozen api model`() =
        withService { _, database ->
            assertFailsWith<IllegalArgumentException> { IngestionTurns.turn(runtimeId = " ") }
            assertFailsWith<IllegalArgumentException> { IngestionTurns.turn(agentId = "") }
            assertFailsWith<IllegalArgumentException> { IngestionTurns.turn(sessionId = "  ") }
            assertFailsWith<IllegalArgumentException> { IngestionTurns.turn(sessionTurnId = "") }
            assertFailsWith<IllegalArgumentException> { IngestionTurns.turn(messages = emptyList()) }
            assertFalse(hasAnyTurn(database), "拒绝的轮次不得落库")
        }

    @Test
    fun `concurrent identical turns yield one CREATED and one DUPLICATE with the same id`() =
        withService { service, database ->
            val results =
                runBlocking {
                    listOf(
                        async { service.ingest(IngestionTurns.turn()) },
                        async { service.ingest(IngestionTurns.turn()) },
                    ).awaitAll()
                }
            assertEquals(setOf(IngestStatus.CREATED, IngestStatus.DUPLICATE), results.map { it.status }.toSet())
            assertEquals(1, results.map { it.id }.distinct().size)
            assertTrue(hasAnyTurn(database))
        }

    @Test
    fun `reopened database still treats the same business key as duplicate`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val firstId =
                fixture.open().use { executor ->
                    val database = ContextDatabaseImpl(executor)
                    val service =
                        IngestionService.create(
                            IngestionLimits.DEFAULTS,
                            MutableClock(IngestionTurns.T0),
                            IngestionNotifier { },
                        ) { database }
                    runBlocking { service.ingest(IngestionTurns.turn()).id }
                }
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                val service =
                    IngestionService.create(
                        IngestionLimits.DEFAULTS,
                        MutableClock(IngestionTurns.T0),
                        IngestionNotifier { },
                    ) { database }
                val replayed = runBlocking { service.ingest(IngestionTurns.turn()) }
                assertEquals(IngestStatus.DUPLICATE, replayed.status)
                assertEquals(firstId, replayed.id)
            }
        }

    @Test
    fun `stored timestamp text is the canonical plus eight milliseconds form`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                val service =
                    IngestionService.create(
                        IngestionLimits.DEFAULTS,
                        MutableClock(IngestionTurns.T0),
                        IngestionNotifier { },
                    ) { database }
                val created = runBlocking { service.ingest(IngestionTurns.turn()) }
                val raw =
                    runBlocking {
                        executor.queryRaw("SELECT timestamp FROM conversation_turn WHERE id = ${created.id}") { rs ->
                            assertTrue(rs.next())
                            rs.getString(1)
                        }
                    }
                assertEquals("2026-10-29T08:00:00.000+08:00", raw)
            }
        }

    @Test
    fun `explicit plus eight and Z offsets are the same instant and a duplicate`() =
        withService { service, _ ->
            val shanghai = IngestionTimestampText.parseRequiringOffset("2026-10-23T20:15:00+08:00")
            val utc = IngestionTimestampText.parseRequiringOffset("2026-10-23T12:15:00Z")
            val created = service.ingest(IngestionTurns.turn(timestamp = shanghai))
            assertEquals(IngestStatus.CREATED, created.status)
            val replayed = service.ingest(IngestionTurns.turn(timestamp = utc))
            assertEquals(IngestStatus.DUPLICATE, replayed.status)
            assertEquals(created.id, replayed.id)
        }

    @Test
    fun `notifyCreated reports the same failure to the reporter`() {
        val failure = IllegalStateException("notifier boom")
        val recorded = CopyOnWriteArrayList<Throwable>()
        withService(
            notifier = IngestionNotifier { throw failure },
            reporter = IngestionNotificationReporter { recorded += it },
        ) { service, _ ->
            service.notifyCreated(SessionKey("runtime-1", "agent-1", "session-1"))
        }
        assertEquals(listOf(failure), recorded.toList(), "reporter 必须收到同一异常实例")
        assertFalse(
            recorded
                .single()
                .message
                .orEmpty()
                .contains("runtime-1"),
            "reporter 不得携带 SessionKey 值",
        )
    }

    @Test
    fun `notifyCreated reports cancellation and rethrows it`() {
        val recorded = CopyOnWriteArrayList<Throwable>()
        val cancellation = kotlinx.coroutines.CancellationException("notifier cancelled")
        withService(
            notifier = IngestionNotifier { throw cancellation },
            reporter = IngestionNotificationReporter { recorded += it },
        ) { service, _ ->
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                service.notifyCreated(SessionKey("runtime-1", "agent-1", "session-1"))
            }
        }
        assertEquals(listOf(cancellation), recorded.toList(), "取消也必须先上报再原样抛出")
    }

    private fun hasAnyTurn(database: ContextDatabaseImpl): Boolean = runBlocking { database.read { it.conversations.maxTurnId() != null } }

    private fun withService(
        limits: IngestionLimits = IngestionLimits.DEFAULTS,
        notifier: IngestionNotifier = IngestionNotifier { },
        reporter: IngestionNotificationReporter = IngestionNotificationReporter { },
        block: suspend (DelegatingIngestionService, ContextDatabaseImpl) -> Unit,
    ) {
        ContextFixture().use { fixture ->
            fixture.initialize()
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                val service =
                    IngestionService.create(limits, MutableClock(IngestionTurns.T0), notifier, reporter) { database }
                runBlocking { block(service, database) }
            }
        }
    }
}
