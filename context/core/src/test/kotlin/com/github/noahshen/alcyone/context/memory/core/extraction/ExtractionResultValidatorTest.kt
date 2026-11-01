package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CT11-B1 A03/A04（Core 规则）：默认校验器的合法映射、重复去重、零记忆、content/source 非法、
 * 来源不在 Snapshot / 其他 Session / 仅 History 的整批拒绝，以及错误消息不泄漏正文。
 */
class ExtractionResultValidatorTest {
    private val session = SessionKey("rt", "agent", "s1")
    private val bodyMarker = "ct11-b1-validator-body-marker-3f71"
    private val base: Instant = Instant.parse("2026-10-31T02:00:00Z")
    private val validator = DefaultExtractionResultValidator()

    private val context =
        ExtractionContext(
            session,
            listOf(
                turn(1, ExtractionStatus.PENDING, "target-1"),
                turn(2, ExtractionStatus.COMPLETE, "history-complete"),
                turn(3, ExtractionStatus.FAILED, "history-failed"),
                turn(4, ExtractionStatus.PENDING, "target-2"),
            ),
        )

    @Test
    fun `valid target and history sources are mapped to stable business keys`() {
        val validated =
            validator.validate(
                ExtractionCandidate(
                    listOf(ExtractionCandidateMemory(content = "$bodyMarker memory", sourceTurnIds = listOf(1, 2))),
                ),
                context,
            )

        assertEquals(1, validated.size)
        assertEquals("$bodyMarker memory", validated.single().content, "content 原样保留")
        assertEquals(
            listOf(
                MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "target-1"),
                MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "history-complete"),
            ),
            validated.single().sources,
        )
    }

    @Test
    fun `duplicate source ids are deduplicated by first occurrence`() {
        val validated =
            validator.validate(
                ExtractionCandidate(
                    listOf(
                        ExtractionCandidateMemory(
                            content = "memory",
                            sourceTurnIds = listOf(4, 1, 4, 2, 1),
                        ),
                    ),
                ),
                context,
            )

        assertEquals(
            listOf(
                MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "target-2"),
                MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "target-1"),
                MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "history-complete"),
            ),
            validated.single().sources,
        )
    }

    @Test
    fun `empty memories is a legal success`() {
        assertEquals(emptyList(), validator.validate(ExtractionCandidate(emptyList()), context))
    }

    @Test
    fun `blank content is rejected`() {
        listOf("", "   ", "\n\t ").forEach { content ->
            val failure =
                assertFailsWith<ExtractionValidationException> {
                    validator.validate(
                        ExtractionCandidate(listOf(ExtractionCandidateMemory(content, listOf(1)))),
                        context,
                    )
                }
            assertFalse(failure.message.orEmpty().contains(content.ifBlank { "$bodyMarker" }), failure.message.orEmpty())
            assertEquals(null, failure.cause)
        }
    }

    @Test
    fun `empty source list is rejected`() {
        val failure =
            assertFailsWith<ExtractionValidationException> {
                validator.validate(
                    ExtractionCandidate(listOf(ExtractionCandidateMemory("memory", emptyList()))),
                    context,
                )
            }
        assertTrue(failure.message.orEmpty().contains("at least one source"), failure.message.orEmpty())
    }

    @Test
    fun `sources outside the snapshot or from another session are rejected`() {
        // 99 不在 Snapshot；7 属于另一个 Session（不在本 context）。
        listOf(99, 7, Int.MAX_VALUE).forEach { sourceId ->
            val failure =
                assertFailsWith<ExtractionValidationException> {
                    validator.validate(
                        ExtractionCandidate(listOf(ExtractionCandidateMemory("memory", listOf(sourceId)))),
                        context,
                    )
                }
            assertTrue(
                failure.message.orEmpty().contains("not in the snapshot"),
                failure.message.orEmpty(),
            )
        }
    }

    @Test
    fun `history only sources are rejected because no pending target is referenced`() {
        val failure =
            assertFailsWith<ExtractionValidationException> {
                validator.validate(
                    ExtractionCandidate(
                        listOf(ExtractionCandidateMemory("memory", listOf(2, 3))),
                    ),
                    context,
                )
            }
        assertTrue(failure.message.orEmpty().contains("pending target"), failure.message.orEmpty())
    }

    @Test
    fun `a batch referencing at least one target is accepted even with history sources`() {
        val validated =
            validator.validate(
                ExtractionCandidate(
                    listOf(
                        ExtractionCandidateMemory("only history memory", listOf(2)),
                        ExtractionCandidateMemory("target memory", listOf(1)),
                    ),
                ),
                context,
            )
        assertEquals(2, validated.size)
    }

    @Test
    fun `validation errors never leak the memory body`() {
        val failure =
            assertFailsWith<ExtractionValidationException> {
                validator.validate(
                    ExtractionCandidate(listOf(ExtractionCandidateMemory("$bodyMarker valid", listOf(99)))),
                    context,
                )
            }
        assertFalse(failure.message.orEmpty().contains(bodyMarker), failure.message.orEmpty())
        assertEquals(null, failure.cause, "校验异常不得挂 cause")
    }

    // ------------------------------------------------------------------ 工具

    private fun turn(
        id: Long,
        status: ExtractionStatus,
        sessionTurnId: String,
    ): StoredConversationTurn =
        StoredConversationTurn(
            id = id,
            runtimeId = session.runtimeId,
            agentId = session.agentId,
            sessionId = session.sessionId,
            sessionTurnId = sessionTurnId,
            timestamp = base.plusSeconds(id),
            createdAt = base.plusSeconds(id),
            messages = listOf(ChatMessage(ChatRole.USER, "user-$sessionTurnId")),
            extractionStatus = status,
        )
}
