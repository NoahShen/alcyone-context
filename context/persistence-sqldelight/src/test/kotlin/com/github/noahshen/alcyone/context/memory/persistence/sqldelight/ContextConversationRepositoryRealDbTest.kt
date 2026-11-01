package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT05-B1 Conversation 只读语义：幂等 / 冲突 / 窗口锚点与排序 / 空闲判定 /
 * pending 计数 / id 范围边界 / maxTurnId，全部跑在真实文件 SQLite 上。
 */
class ContextConversationRepositoryRealDbTest {
    private val session = ConversationFixtures.SESSION
    private val otherSession = ConversationFixtures.OTHER_SESSION
    private val timestamp = ConversationFixtures.TIMESTAMP

    @Test
    fun `insertTurn returns CREATED and the persisted row is pending`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val created = ConversationFixtures.insert(executor, "turn-1", messages = ConversationFixtures.messages("你好", "hi"))
            assertEquals(IngestStatus.CREATED, created.status)

            val stored = assertNotNull(repository.getById(created.id))
            assertEquals(created.id, stored.id)
            assertEquals(session, stored.sessionKey)
            assertEquals("turn-1", stored.sessionTurnId)
            assertEquals(timestamp, stored.timestamp)
            assertEquals(ConversationFixtures.CREATED_AT, stored.createdAt)
            assertEquals(ExtractionStatus.PENDING, stored.extractionStatus)
            assertEquals(ConversationFixtures.messages("你好", "hi"), stored.messages)
        }

    @Test
    fun `empty database returns empty results and nulls`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val idleBefore = ConversationFixtures.TIMESTAMP

            assertNull(repository.getById(1L))
            assertNull(repository.findByBusinessKey(session, "missing"))
            assertEquals(emptyList<StoredConversationTurn>(), repository.sessionWindow(session, null, null, 10))
            assertNull(repository.latestPending(session))
            assertEquals(0, repository.countPending(session))
            assertFalse(repository.idleCheck(session, idleBefore))
            assertEquals(emptyList(), repository.findIdleSessions(idleBefore))
            assertNull(repository.maxTurnId())
            assertEquals(emptyList<StoredConversationTurn>(), repository.idRange(0L, Long.MAX_VALUE, 10))
        }

    @Test
    fun `identical replay returns DUPLICATE with the original id and keeps createdAt`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val first = ConversationFixtures.insert(executor, "turn-1", messages = ConversationFixtures.messages("hello"))
            val replay =
                ConversationFixtures.insert(
                    executor,
                    "turn-1",
                    createdAt = ConversationFixtures.CREATED_AT.plusSeconds(3600),
                    messages = ConversationFixtures.messages("hello"),
                )

            assertEquals(IngestStatus.DUPLICATE, replay.status)
            assertEquals(first.id, replay.id)
            assertEquals(1L, ConversationFixtures.countTurns(executor), "幂等重放不得新增行")

            val stored = assertNotNull(repository.getById(first.id))
            assertEquals(ConversationFixtures.CREATED_AT, stored.createdAt, "重放不得刷新 created_at")
            assertEquals(ExtractionStatus.PENDING, stored.extractionStatus)
        }

    @Test
    fun `same key with different messages throws TURN_CONFLICT and keeps the original row`() =
        ConversationFixtures.withDatabase { executor ->
            val first = ConversationFixtures.insert(executor, "turn-1", messages = ConversationFixtures.messages("first"))
            val failure =
                assertFailsWith<ContextIngestionException> {
                    ConversationFixtures.insert(executor, "turn-1", messages = ConversationFixtures.messages("second"))
                }

            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, failure.errorCode)
            assertEquals(1L, ConversationFixtures.countTurns(executor))
            val stored = assertNotNull(ConversationRepositoryImpl(executor).getById(first.id))
            assertEquals(ConversationFixtures.messages("first"), stored.messages)
        }

    @Test
    fun `same key with reordered messages throws TURN_CONFLICT`() =
        ConversationFixtures.withDatabase { executor ->
            ConversationFixtures.insert(executor, "turn-1", messages = ConversationFixtures.messages("a", "b"))
            val failure =
                assertFailsWith<ContextIngestionException> {
                    ConversationFixtures.insert(executor, "turn-1", messages = ConversationFixtures.messages("b", "a"))
                }
            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, failure.errorCode)
        }

    @Test
    fun `same key with a different timestamp throws TURN_CONFLICT`() =
        ConversationFixtures.withDatabase { executor ->
            ConversationFixtures.insert(executor, "turn-1", timestamp = timestamp)
            val failure =
                assertFailsWith<ContextIngestionException> {
                    ConversationFixtures.insert(executor, "turn-1", timestamp = timestamp.plusMillis(1))
                }
            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, failure.errorCode)
        }

    @Test
    fun `sessionWindow includes the anchor and returns ascending order with the limit applied`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val ids =
                (1..5).map { index ->
                    ConversationFixtures
                        .insert(
                            executor,
                            "turn-$index",
                            timestamp = timestamp,
                            messages = ConversationFixtures.messages("m$index"),
                        ).id
                }
            // 其他会话的同一时刻数据不得混入。
            ConversationFixtures.insert(executor, "other-1", timestamp = timestamp, session = otherSession)

            val anchored = repository.sessionWindow(session, timestamp, ids[2], 10)
            assertEquals(ids.take(3), anchored.map { it.id })
            assertEquals(ids.take(3), anchored.map { it.id }.sorted(), "返回顺序必须是真实会话顺序（升序）")

            val limited = repository.sessionWindow(session, timestamp, ids[4], 2)
            assertEquals(listOf(ids[3], ids[4]), limited.map { it.id }, "limit 先按倒序截断，再反序返回")

            val noAnchor = repository.sessionWindow(session, null, null, 2)
            assertEquals(listOf(ids[3], ids[4]), noAnchor.map { it.id })
        }

    @Test
    fun `sessionWindow honours the timestamp boundary and the anchor id tie-break`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val firstAtTimestamp =
                ConversationFixtures.insert(executor, "turn-1", timestamp = timestamp, messages = ConversationFixtures.messages("a")).id
            val secondAtTimestamp =
                ConversationFixtures.insert(executor, "turn-2", timestamp = timestamp, messages = ConversationFixtures.messages("b")).id
            val later =
                ConversationFixtures
                    .insert(
                        executor,
                        "turn-3",
                        timestamp = timestamp.plusSeconds(1),
                        messages = ConversationFixtures.messages("c"),
                    ).id

            // 锚点为「更晚时刻」时，所有更早时刻的行都在窗口内。
            val beforeLater = repository.sessionWindow(session, timestamp.plusSeconds(1), later, 10)
            assertEquals(listOf(firstAtTimestamp, secondAtTimestamp, later), beforeLater.map { it.id })

            // 同一时刻按 id <= 锚点截断，且包含锚点自身。
            val tieBreak = repository.sessionWindow(session, timestamp, secondAtTimestamp, 10)
            assertEquals(listOf(firstAtTimestamp, secondAtTimestamp), tieBreak.map { it.id })
        }

    @Test
    fun `latestPending ignores completed turns and breaks ties by id`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val first = ConversationFixtures.insert(executor, "turn-1", timestamp = timestamp)
            val second = ConversationFixtures.insert(executor, "turn-2", timestamp = timestamp)
            val third = ConversationFixtures.insert(executor, "turn-3", timestamp = timestamp.plusSeconds(1))

            assertEquals(third.id, assertNotNull(repository.latestPending(session)).id)
            ConversationFixtures.markComplete(executor, third.id)
            assertEquals(second.id, assertNotNull(repository.latestPending(session)).id)
            ConversationFixtures.markComplete(executor, second.id)
            assertEquals(first.id, assertNotNull(repository.latestPending(session)).id)
        }

    @Test
    fun `countPending counts only pending turns of the requested session`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val first = ConversationFixtures.insert(executor, "turn-1")
            val second = ConversationFixtures.insert(executor, "turn-2")
            ConversationFixtures.insert(executor, "other-1", session = otherSession)

            assertEquals(2, repository.countPending(session))
            assertEquals(1, repository.countPending(otherSession))
            ConversationFixtures.markComplete(executor, first.id)
            ConversationFixtures.markFailed(executor, second.id)
            assertEquals(0, repository.countPending(session))
        }

    @Test
    fun `idleCheck requires a pending turn and all-status createdAt not newer than idleBefore`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val pending = ConversationFixtures.insert(executor, "turn-1", createdAt = ConversationFixtures.CREATED_AT)

            assertFalse(
                repository.idleCheck(session, ConversationFixtures.CREATED_AT.minusMillis(1)),
                "最新 created_at 晚于 idleBefore 时不算空闲",
            )
            assertTrue(repository.idleCheck(session, ConversationFixtures.CREATED_AT))

            // 已完成 Turn 的新 created_at 同样参与判定（全状态 MAX(created_at)）。
            val completed =
                ConversationFixtures.insert(
                    executor,
                    "turn-2",
                    createdAt = ConversationFixtures.CREATED_AT.plusSeconds(10),
                    messages = ConversationFixtures.messages("later"),
                )
            ConversationFixtures.markComplete(executor, completed.id)
            assertFalse(repository.idleCheck(session, ConversationFixtures.CREATED_AT.plusSeconds(5)))
            assertTrue(repository.idleCheck(session, ConversationFixtures.CREATED_AT.plusSeconds(10)))

            // 没有 pending 时即使很旧也不空闲。
            ConversationFixtures.markComplete(executor, pending.id)
            assertFalse(repository.idleCheck(session, ConversationFixtures.CREATED_AT.plusSeconds(100)))
        }

    @Test
    fun `findIdleSessions filters by all-status createdAt and excludes sessions without pending`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            ConversationFixtures.insert(executor, "turn-1", createdAt = ConversationFixtures.CREATED_AT)
            ConversationFixtures.insert(
                executor,
                "other-1",
                createdAt = ConversationFixtures.CREATED_AT,
                session = otherSession,
            )
            val otherCompleted =
                ConversationFixtures.insert(
                    executor,
                    "other-2",
                    createdAt = ConversationFixtures.CREATED_AT.plusSeconds(10),
                    session = otherSession,
                    messages = ConversationFixtures.messages("later"),
                )
            ConversationFixtures.markComplete(executor, otherCompleted.id)

            assertEquals(listOf(session), repository.findIdleSessions(ConversationFixtures.CREATED_AT))
            assertEquals(
                listOf(session, otherSession),
                repository.findIdleSessions(ConversationFixtures.CREATED_AT.plusSeconds(10)),
            )
        }

    @Test
    fun `maxTurnId is null on an empty table and the maximum afterwards`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            assertNull(repository.maxTurnId())
            val first = ConversationFixtures.insert(executor, "turn-1")
            val second = ConversationFixtures.insert(executor, "turn-2")
            assertEquals(second.id, repository.maxTurnId())
            assertTrue(second.id > first.id)
        }

    @Test
    fun `idRange uses an exclusive lower bound an inclusive upper bound and the limit`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val ids =
                (1..5).map { index ->
                    ConversationFixtures
                        .insert(
                            executor,
                            "turn-$index",
                            timestamp = timestamp.plusMillis(index.toLong()),
                            messages = ConversationFixtures.messages("m$index"),
                        ).id
                }

            assertEquals(listOf(ids[1], ids[2], ids[3]), repository.idRange(ids[0], ids[3], 10).map { it.id })
            assertEquals(listOf(ids[1], ids[2]), repository.idRange(ids[0], ids[4], 2).map { it.id })
            assertEquals(emptyList(), repository.idRange(ids[4], ids[4], 10))
        }

    @Test
    fun `instant with sub-millisecond precision is normalized by the stored text`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val precise = Instant.parse("2026-10-28T00:30:00.123999Z")
            val created = ConversationFixtures.insert(executor, "turn-1", timestamp = precise)
            val replay = ConversationFixtures.insert(executor, "turn-1", timestamp = precise)

            assertEquals(IngestStatus.DUPLICATE, replay.status)
            assertEquals(created.id, replay.id)
            assertEquals(Instant.parse("2026-10-28T00:30:00.123Z"), assertNotNull(repository.getById(created.id)).timestamp)
        }
}
