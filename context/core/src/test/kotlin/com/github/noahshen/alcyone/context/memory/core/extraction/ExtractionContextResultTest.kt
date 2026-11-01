package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CT10-B1 A02：`ExtractionContext` 的防御性不可变快照、`ExtractionBuildResult` 四态字段稳定性，
 * 以及 `toString` 不展开正文。
 */
class ExtractionContextResultTest {
    private val sessionKey = SessionKey("rt", "agent", "session")
    private val base: Instant = Instant.parse("2026-10-30T02:00:00.123Z")
    private val bodyMarker = "ct10-b1-secret-body-marker-7a21"

    @Test
    fun `turns are defensively copied and unmodifiable`() {
        val mutable = mutableListOf(turn(1), turn(2))
        val context = ExtractionContext(sessionKey, mutable)

        mutable.clear()
        assertEquals(2, context.turns.size, "外部集合修改不得影响快照")

        @Suppress("UNCHECKED_CAST")
        val view = context.turns as java.util.List<StoredConversationTurn>
        assertFailsWith<UnsupportedOperationException> { view.add(turn(3)) }
        assertFailsWith<UnsupportedOperationException> { view.clear() }
    }

    @Test
    fun `equals hashCode and toString are stable and do not expand message bodies`() {
        val first = ExtractionContext(sessionKey, listOf(turn(1)))
        val second = ExtractionContext(sessionKey, listOf(turn(1)))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, ExtractionContext(sessionKey, listOf(turn(2))))

        val text = first.toString()
        assertTrue(text.contains("turns=1"), text)
        assertFalse(text.contains(bodyMarker), "toString 不得展开正文: $text")
    }

    @Test
    fun `built result carries the context`() {
        val context = ExtractionContext(sessionKey, listOf(turn(1)))
        val result = ExtractionBuildResult.Built(context)
        assertSame(context, result.context)
        assertFalse(result.toString().contains(bodyMarker), "toString 不得展开正文: $result")
    }

    @Test
    fun `no pending is a singleton result`() {
        assertSame(ExtractionBuildResult.NoPending, ExtractionBuildResult.NoPending)
        assertEquals("NoPending", ExtractionBuildResult.NoPending.toString())
    }

    @Test
    fun `single turn over budget keeps anchor id and required total`() {
        val result = ExtractionBuildResult.SingleTurnOverBudget(anchorTurnId = 1001L, requiredTotalTokens = 70_000L)
        assertEquals(1001L, result.anchorTurnId)
        assertEquals(70_000L, result.requiredTotalTokens)
        assertEquals(ExtractionBuildResult.SingleTurnOverBudget(1001L, 70_000L), result)
        assertFalse(result.toString().contains(bodyMarker), result.toString())
    }

    @Test
    fun `fixed overhead over budget keeps the required total`() {
        val result = ExtractionBuildResult.FixedOverheadOverBudget(requiredTotalTokens = 65_000L)
        assertEquals(65_000L, result.requiredTotalTokens)
        assertEquals(ExtractionBuildResult.FixedOverheadOverBudget(65_000L), result)
    }

    // ------------------------------------------------------------------ 工具

    private fun turn(id: Long): StoredConversationTurn =
        StoredConversationTurn(
            id = id,
            runtimeId = sessionKey.runtimeId,
            agentId = sessionKey.agentId,
            sessionId = sessionKey.sessionId,
            sessionTurnId = "turn-$id",
            timestamp = base,
            createdAt = base,
            messages = listOf(ChatMessage(ChatRole.USER, bodyMarker)),
            extractionStatus = ExtractionStatus.PENDING,
        )
}
