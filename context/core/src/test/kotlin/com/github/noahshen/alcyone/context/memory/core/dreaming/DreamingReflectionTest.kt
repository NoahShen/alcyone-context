package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DreamingReflectionTest {
    private val turns = listOf(turn(1), turn(2, "user", "assistant", "session-2"))

    @Test
    fun `valid topics survive with exact tuple dedupe model order and content whitespace`() {
        val candidates =
            listOf(
                DreamingInsightCandidate("  first  ", listOf(2, 1, 2)),
                DreamingInsightCandidate("second", listOf(1)),
            )

        val result = DreamingReflectionValidator.validate(candidates, turns)

        assertEquals(listOf("  first  ", "second"), result.map { it.content })
        assertEquals(2, result[0].sources.size)
        assertEquals(1, result[1].sources.size)
        assertEquals(listOf("session-2", "session"), result[0].sources.map { it.sessionId })
        assertEquals("turn-1", result[1].sources.single().sessionTurnId)
    }

    @Test
    fun `duplicate tuples with different restricted session turn ids remain distinct`() {
        val reused =
            turn(
                id = 3,
                sessionId = "session-2",
                sessionTurnId = "turn-1",
                content = "different content",
            )
        val result =
            DreamingReflectionValidator.validate(
                listOf(DreamingInsightCandidate("both", listOf(1, reused.id))),
                turns + reused,
            )

        assertEquals(2, result.single().sources.size)
        assertEquals(
            setOf(1L, 3L),
            result
                .single()
                .sources
                .map { source -> findTurnId(source, turns + reused) }
                .toSet(),
        )
    }

    @Test
    fun `same business key with different internal ids fails closed`() {
        val conflicting =
            turn(
                id = 3,
                sessionTurnId = "turn-1",
                content = "different content",
            )
        val failure =
            assertFailsWith<DreamingReflectionValidationException> {
                DreamingReflectionValidator.validate(emptyList(), turns + conflicting)
            }

        assertSafe(failure)
        assertEquals("dreaming source turns contain an ambiguous business key", failure.message)
    }

    @Test
    fun `same internal id with different business keys fails closed`() {
        val conflicting =
            turn(
                id = 1,
                sessionTurnId = "turn-different",
                content = "different content",
            )
        val failure =
            assertFailsWith<DreamingReflectionValidationException> {
                DreamingReflectionValidator.validate(emptyList(), turns + conflicting)
            }

        assertSafe(failure)
        assertEquals("dreaming source turns contain an ambiguous internal id", failure.message)
    }

    @Test
    fun `unknown zero negative and out of topic source ids fail the complete candidate`() {
        listOf(0L, -1L, 99L).forEach { invalid ->
            val failure =
                assertFailsWith<DreamingReflectionValidationException> {
                    DreamingReflectionValidator.validate(
                        listOf(
                            DreamingInsightCandidate("otherwise valid", listOf(1)),
                            DreamingInsightCandidate("bad", listOf(invalid)),
                        ),
                        turns,
                    )
                }
            assertSafe(failure)
        }
    }

    @Test
    fun `blank content and empty sources are rejected`() {
        listOf(
            DreamingInsightCandidate("", listOf(1)),
            DreamingInsightCandidate(" \t\n", listOf(1)),
            DreamingInsightCandidate("content", emptyList()),
        ).forEach { candidate ->
            assertFailsWith<DreamingReflectionValidationException> {
                DreamingReflectionValidator.validate(listOf(candidate), turns)
            }
        }
    }

    @Test
    fun `candidate source order is preserved while duplicate source refs are removed`() {
        val result =
            DreamingReflectionValidator.validate(
                listOf(DreamingInsightCandidate("insight", listOf(2, 1, 2, 1))),
                turns,
            )

        assertEquals(listOf("turn-2", "turn-1"), result.single().sources.map { it.sessionTurnId })
    }

    @Test
    fun `validation exceptions are safe and carry no source content`() {
        val marker = "SECRET-UNKNOWN-SOURCE"
        val failure =
            assertFailsWith<DreamingReflectionValidationException> {
                DreamingReflectionValidator.validate(
                    listOf(DreamingInsightCandidate(marker, listOf(999_999))),
                    turns,
                )
            }

        assertSafe(failure, marker)
        assertEquals("dreaming insight source is not part of the current topic", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `validated output is defensive against caller mutation`() {
        val sourceIds = mutableListOf(1L)
        val candidates = mutableListOf(DreamingInsightCandidate("content", sourceIds))
        val result = DreamingReflectionValidator.validate(candidates, turns)
        candidates.clear()
        sourceIds.clear()

        assertEquals(1, result.size)
        assertEquals(1, result.single().sources.size)
    }

    private fun assertSafe(
        failure: DreamingReflectionValidationException,
        marker: String = "SECRET",
    ) {
        assertFalse(failure.message.orEmpty().contains(marker), failure.message.orEmpty())
        assertNull(failure.cause)
    }

    private fun findTurnId(
        source: com.github.noahshen.alcyone.context.memory.api.MemorySourceRef.ConversationTurnRef,
        candidates: List<StoredConversationTurn> = turns,
    ): Long =
        candidates
            .single {
                it.runtimeId == source.runtimeId &&
                    it.agentId == source.agentId &&
                    it.sessionId == source.sessionId &&
                    it.sessionTurnId == source.sessionTurnId
            }.id

    private fun turn(
        id: Long,
        role: String = "user",
        secondRole: String? = null,
        sessionId: String = "session",
        sessionTurnId: String = "turn-$id",
        content: String = "content-$id",
    ): StoredConversationTurn {
        val chatRole = if (role == "assistant") ChatRole.ASSISTANT else ChatRole.USER
        val messages = mutableListOf(ChatMessage(chatRole, content))
        if (secondRole != null) messages += ChatMessage(ChatRole.ASSISTANT, "assistant-$id")
        return StoredConversationTurn(
            id = id,
            runtimeId = "runtime",
            agentId = "agent",
            sessionId = sessionId,
            sessionTurnId = sessionTurnId,
            timestamp = BASE.plusSeconds(id),
            createdAt = BASE.plusSeconds(id),
            messages = messages,
            extractionStatus = ExtractionStatus.PENDING,
        )
    }

    private companion object {
        val BASE: Instant = Instant.parse("2026-11-09T09:00:00Z")
    }
}
