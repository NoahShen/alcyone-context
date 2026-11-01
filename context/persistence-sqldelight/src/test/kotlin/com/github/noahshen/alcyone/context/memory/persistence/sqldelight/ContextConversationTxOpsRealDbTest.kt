package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CT05-B1 Conversation 事务内语义：同一 scope 读写共享连接、条件状态更新的真实行数、
 * 幂等重放不把已完成的 Turn 重置回 pending。
 */
class ContextConversationTxOpsRealDbTest {
    private val session = ConversationFixtures.SESSION

    @Test
    fun `transaction reads observe its own uncommitted writes`() =
        ConversationFixtures.withDatabase { executor ->
            val committedId =
                executor.write { tx ->
                    val ops = ConversationTxOpsImpl(tx)
                    val created =
                        ops.insertTurn(
                            session,
                            "turn-1",
                            ConversationFixtures.TIMESTAMP,
                            ConversationFixtures.CREATED_AT,
                            ConversationFixtures.messages("inside"),
                        )
                    assertEquals(IngestStatus.CREATED, created.status)

                    val inside = assertNotNull(ops.findByBusinessKey(session, "turn-1"))
                    assertEquals(created.id, inside.id)
                    assertEquals(ExtractionStatus.PENDING, inside.extractionStatus)
                    assertEquals(1, ops.countPending(session))

                    assertEquals(1, ops.markComplete(created.id))
                    assertEquals(ExtractionStatus.COMPLETE, assertNotNull(ops.getById(created.id)).extractionStatus)
                    assertEquals(0, ops.countPending(session))
                    created.id
                }

            // 提交后由独立读作用域确认（同一执行器，不同 scope）。
            val afterCommit = assertNotNull(ConversationRepositoryImpl(executor).getById(committedId))
            assertEquals(ExtractionStatus.COMPLETE, afterCommit.extractionStatus)
        }

    @Test
    fun `markComplete reports real row counts and only transitions pending rows`() =
        ConversationFixtures.withDatabase { executor ->
            val pending = ConversationFixtures.insert(executor, "turn-1")
            val counts =
                executor.write { tx ->
                    val ops = ConversationTxOpsImpl(tx)
                    listOf(
                        ops.markComplete(pending.id),
                        ops.markComplete(pending.id),
                        ops.markFailed(pending.id),
                        ops.markComplete(Long.MAX_VALUE),
                    )
                }

            assertEquals(listOf(1, 0, 0, 0), counts, "只有第一次命中 pending，其余必须返回 0")
            val stored = assertNotNull(ConversationRepositoryImpl(executor).getById(pending.id))
            assertEquals(ExtractionStatus.COMPLETE, stored.extractionStatus)
        }

    @Test
    fun `markFailed reports real row counts and keeps the failed status`() =
        ConversationFixtures.withDatabase { executor ->
            val pending = ConversationFixtures.insert(executor, "turn-1")
            val counts =
                executor.write { tx ->
                    val ops = ConversationTxOpsImpl(tx)
                    listOf(ops.markFailed(pending.id), ops.markFailed(pending.id), ops.markComplete(pending.id))
                }

            assertEquals(listOf(1, 0, 0), counts)
            val stored = assertNotNull(ConversationRepositoryImpl(executor).getById(pending.id))
            assertEquals(ExtractionStatus.FAILED, stored.extractionStatus)
        }

    @Test
    fun `duplicate replay never resets a completed turn back to pending`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            ConversationFixtures.markComplete(executor, created.id)
            val replay = ConversationFixtures.insert(executor, "turn-1")

            assertEquals(IngestStatus.DUPLICATE, replay.status)
            assertEquals(created.id, replay.id)
            val stored = assertNotNull(ConversationRepositoryImpl(executor).getById(created.id))
            assertEquals(ExtractionStatus.COMPLETE, stored.extractionStatus)
        }
}
