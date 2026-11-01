package com.github.noahshen.alcyone.context.memory.core

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoredConversationTurnTest {
    private val userMsg = ChatMessage(ChatRole.USER, "hello")
    private val assistantMsg = ChatMessage(ChatRole.ASSISTANT, "hi")
    private val now = Instant.parse("2026-10-23T18:30:25.123Z")

    private val turn =
        StoredConversationTurn(
            id = 100L,
            runtimeId = "rt-1",
            agentId = "ag-1",
            sessionId = "ss-1",
            sessionTurnId = "turn-1",
            timestamp = now,
            createdAt = now.plusSeconds(1),
            messages = listOf(userMsg, assistantMsg),
            extractionStatus = ExtractionStatus.PENDING,
        )

    @Test
    fun `属性读取与解构正确`() {
        assertEquals(100L, turn.id)
        assertEquals("rt-1", turn.runtimeId)
        assertEquals("ag-1", turn.agentId)
        assertEquals("ss-1", turn.sessionId)
        assertEquals("turn-1", turn.sessionTurnId)
        assertEquals(now, turn.timestamp)
        assertEquals(now.plusSeconds(1), turn.createdAt)
        assertEquals(2, turn.messages.size)
        assertSame(ExtractionStatus.PENDING, turn.extractionStatus)
        assertEquals(SessionKey("rt-1", "ag-1", "ss-1"), turn.sessionKey)

        val (id, rt, ag, ss, st, ts, cAt, msgs, status) = turn
        assertEquals(100L, id)
        assertEquals("rt-1", rt)
        assertEquals("ag-1", ag)
        assertEquals("ss-1", ss)
        assertEquals("turn-1", st)
        assertEquals(now, ts)
        assertEquals(now.plusSeconds(1), cAt)
        assertEquals(2, msgs.size)
        assertSame(ExtractionStatus.PENDING, status)
    }

    @Test
    fun `messages 构造与 copy 均保持不可修改快照且多元素修改被隔离`() {
        val mutableOriginal = mutableListOf(userMsg, assistantMsg)
        val model =
            StoredConversationTurn(
                id = 1L,
                runtimeId = "rt",
                agentId = "ag",
                sessionId = "ss",
                sessionTurnId = "tt",
                timestamp = now,
                createdAt = now,
                messages = mutableOriginal,
                extractionStatus = ExtractionStatus.PENDING,
            )

        // 1. 修改外部集合，原对象不受影响
        mutableOriginal.clear()
        assertEquals(2, model.messages.size)

        // 2. 尝试直接修改暴露的集合视图，必抛 UnsupportedOperationException
        @Suppress("UNCHECKED_CAST")
        val mutableView = model.messages as java.util.List<ChatMessage>
        assertFailsWith<UnsupportedOperationException> {
            mutableView.add(ChatMessage(ChatRole.TOOL, "tool"))
        }
        assertFailsWith<UnsupportedOperationException> {
            mutableView.clear()
        }

        // 3. copy 传入 mutable list，随后外部清空，副本不受影响
        val copyMessages = mutableListOf(userMsg, assistantMsg)
        val copied = model.copy(messages = copyMessages)
        copyMessages.clear()
        assertEquals(2, copied.messages.size)
        assertEquals(model, copied)
    }

    @Test
    fun `equals 与 hashCode 与 toString 行为正确`() {
        val same = turn.copy()
        assertEquals(turn, same)
        assertEquals(turn.hashCode(), same.hashCode())
        val different = turn.copy(extractionStatus = ExtractionStatus.COMPLETE)
        assertNotEquals(turn, different)
        assertTrue(turn.toString().contains("rt-1"))
    }

    @Test
    fun `空白字段或空消息拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            StoredConversationTurn(1L, "", "ag", "ss", "tt", now, now, listOf(userMsg), ExtractionStatus.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            StoredConversationTurn(1L, "rt", " ", "ss", "tt", now, now, listOf(userMsg), ExtractionStatus.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            StoredConversationTurn(1L, "rt", "ag", "", "tt", now, now, listOf(userMsg), ExtractionStatus.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            StoredConversationTurn(1L, "rt", "ag", "ss", " ", now, now, listOf(userMsg), ExtractionStatus.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            StoredConversationTurn(1L, "rt", "ag", "ss", "tt", now, now, emptyList(), ExtractionStatus.PENDING)
        }
    }
}
