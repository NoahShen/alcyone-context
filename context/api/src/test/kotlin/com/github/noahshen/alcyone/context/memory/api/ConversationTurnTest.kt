package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationTurnTest {
    private val userMsg = ChatMessage(ChatRole.USER, "hello")
    private val assistantMsg = ChatMessage(ChatRole.ASSISTANT, "hi")

    private val turn =
        ConversationTurn.create(
            runtimeId = "rt-1",
            agentId = "agent-1",
            sessionId = "session-1",
            sessionTurnId = "turn-1",
            timestamp = Instant.parse("2026-10-23T18:30:25.123Z"),
            messages = listOf(userMsg, assistantMsg),
        )

    @Test
    fun `ConversationTurn 仅含六字段，不含内部 id createdAt extractionStatus`() {
        assertEquals("rt-1", turn.runtimeId)
        assertEquals("agent-1", turn.agentId)
        assertEquals("session-1", turn.sessionId)
        assertEquals("turn-1", turn.sessionTurnId)
        assertNotNull(turn.timestamp)
        assertEquals(2, turn.messages.size)
        assertEquals(SessionKey("rt-1", "agent-1", "session-1"), turn.sessionKey)
    }

    @Test
    fun `字段值与解构保持原样`() {
        val (rt, ag, ss, st, ts, msgs) = turn
        assertEquals("rt-1", rt)
        assertEquals("agent-1", ag)
        assertEquals("session-1", ss)
        assertEquals("turn-1", st)
        assertEquals(Instant.parse("2026-10-23T18:30:25.123Z"), ts)
        assertEquals(listOf(userMsg, assistantMsg), msgs)
    }

    @Test
    fun `messages 为空或业务字段空白时拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            ConversationTurn.create("rt", "ag", "ss", "tt", Instant.now(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            ConversationTurn.create("", "ag", "ss", "tt", Instant.now(), listOf(userMsg))
        }
        assertFailsWith<IllegalArgumentException> {
            ConversationTurn.create("rt", " ", "ss", "tt", Instant.now(), listOf(userMsg))
        }
        assertFailsWith<IllegalArgumentException> {
            ConversationTurn.create("rt", "ag", "", "tt", Instant.now(), listOf(userMsg))
        }
        assertFailsWith<IllegalArgumentException> {
            ConversationTurn.create("rt", "ag", "ss", " ", Instant.now(), listOf(userMsg))
        }
    }

    @Test
    fun `messages 构造与 copy 路径均保持不可修改快照且多元素修改被隔离`() {
        val mutableOriginal = mutableListOf(userMsg, assistantMsg)
        val model =
            ConversationTurn(
                runtimeId = "rt-1",
                agentId = "ag-1",
                sessionId = "ss-1",
                sessionTurnId = "turn-1",
                timestamp = Instant.EPOCH,
                messages = mutableOriginal,
            )

        // 1. 修改原集合，不影响 model
        mutableOriginal.clear()
        assertEquals(2, model.messages.size)

        // 2. 尝试修改返回的集合视图，必抛 UnsupportedOperationException
        @Suppress("UNCHECKED_CAST")
        val mutableView = model.messages as java.util.List<ChatMessage>
        assertFailsWith<UnsupportedOperationException> {
            mutableView.add(ChatMessage(ChatRole.TOOL, "tool result"))
        }
        assertFailsWith<UnsupportedOperationException> {
            mutableView.clear()
        }

        // 3. copy 传入 mutable list，随后外部清空，副本不受影响
        val mutableForCopy = mutableListOf(userMsg, assistantMsg)
        val copied = model.copy(messages = mutableForCopy)
        mutableForCopy.clear()
        assertEquals(2, copied.messages.size)
        assertEquals(model, copied)
    }

    @Test
    fun `equals 与 hashCode 与 toString 行为正确`() {
        val same = turn.copy()
        assertEquals(turn, same)
        assertEquals(turn.hashCode(), same.hashCode())
        val different = turn.copy(sessionTurnId = "turn-2")
        assertNotEquals(turn, different)
        assertTrue(turn.toString().contains("rt-1"))
    }

    @Test
    fun `ChatRole 仅包含 USER 和 ASSISTANT 和 TOOL`() {
        assertEquals(
            setOf("USER", "ASSISTANT", "TOOL"),
            ChatRole.entries.map { it.name }.toSet(),
        )
    }
}
