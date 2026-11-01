package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT11-B1 A03/A04（Runtime JSON）：严格解析（object/顶层与元素字段/整数来源）、
 * 非法结构与非 Snapshot 来源统一 `INVALID_RESPONSE`，且错误链不含原始正文。
 */
class JsonExtractionResultValidatorTest {
    private val session = SessionKey("rt", "agent", "s1")
    private val bodyMarker = "ct11-b1-json-body-marker-9c42"
    private val base: Instant = Instant.parse("2026-10-31T02:00:00Z")
    private val validator = JsonExtractionResultValidator()

    private val context =
        ExtractionContext(
            session,
            listOf(
                turn(1, ExtractionStatus.PENDING, "target-1"),
                turn(2, ExtractionStatus.COMPLETE, "history-complete"),
                turn(3, ExtractionStatus.FAILED, "history-failed"),
            ),
        )

    @Test
    fun `a valid response is parsed and mapped`() {
        val validated =
            validator.validate(
                """{"memories":[{"content":"$bodyMarker memory","source_turn_ids":[1,2]}]}""",
                context,
            )
        assertEquals(1, validated.size)
        assertEquals("$bodyMarker memory", validated.single().content)
        assertEquals(
            listOf(
                MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "target-1"),
                MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "history-complete"),
            ),
            validated.single().sources,
        )
    }

    @Test
    fun `an empty memories array is a legal success`() {
        assertEquals(emptyList(), validator.validate("""{"memories":[]}""", context))
    }

    @Test
    fun `strict structure violations are rejected as invalid response`() {
        val payloads =
            listOf(
                // 顶层未知字段
                """{"memories":[],"extra":1}""",
                // 缺 memories
                """{}""",
                // memories 不是数组
                """{"memories":{}}""",
                // 元素未知字段
                """{"memories":[{"content":"x","source_turn_ids":[1],"extra":1}]}""",
                // 元素不是对象
                """{"memories":["x"]}""",
                // content 缺失/非 string/空白
                """{"memories":[{"source_turn_ids":[1]}]}""",
                """{"memories":[{"content":1,"source_turn_ids":[1]}]}""",
                """{"memories":[{"content":"   ","source_turn_ids":[1]}]}""",
                // source 缺失/非数组/空
                """{"memories":[{"content":"x"}]}""",
                """{"memories":[{"content":"x","source_turn_ids":1}]}""",
                """{"memories":[{"content":"x","source_turn_ids":[]}]}""",
                // source 元素类型非法：string / bool / 浮点 / null / 超大
                """{"memories":[{"content":"x","source_turn_ids":["1"]}]}""",
                """{"memories":[{"content":"x","source_turn_ids":[true]}]}""",
                """{"memories":[{"content":"x","source_turn_ids":[1.5]}]}""",
                """{"memories":[{"content":"x","source_turn_ids":[null]}]}""",
                """{"memories":[{"content":"x","source_turn_ids":[3000000000]}]}""",
                // 非法 JSON / Markdown 围栏
                "not json",
                """```json
{"memories":[]}
```""",
            )

        payloads.forEach { payload ->
            val failure = assertFailsWith<LlmException>(payload) { validator.validate(payload, context) }
            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode, payload)
            assertTrue(
                failure.message.orEmpty().endsWith("invalid extraction response"),
                failure.message.orEmpty(),
            )
            assertNull(failure.cause, "不得挂 cause（避免泄漏原始 content）: $payload")
        }
    }

    @Test
    fun `sources outside the snapshot other sessions or history only are rejected`() {
        val payloads =
            listOf(
                // 不在 Snapshot
                """{"memories":[{"content":"x","source_turn_ids":[99]}]}""",
                // 其他 Session 的 id
                """{"memories":[{"content":"x","source_turn_ids":[7]}]}""",
                // 仅 History，无 Target
                """{"memories":[{"content":"x","source_turn_ids":[2,3]}]}""",
            )
        payloads.forEach { payload ->
            val failure = assertFailsWith<LlmException>(payload) { validator.validate(payload, context) }
            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode, payload)
            assertTrue(
                failure.message.orEmpty().endsWith("invalid extraction response"),
                failure.message.orEmpty(),
            )
        }
    }

    @Test
    fun `errors never carry the raw model content`() {
        val payload = """{"memories":[{"content":"$bodyMarker","source_turn_ids":[99]}]}"""
        val failure = assertFailsWith<LlmException> { validator.validate(payload, context) }
        assertTrue(failure.message.orEmpty().endsWith("invalid extraction response"), failure.message.orEmpty())
        assertFalse(failure.message.orEmpty().contains(bodyMarker), failure.message.orEmpty())
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(bodyMarker), failure.toString())
        assertTrue(failure.stackTraceToString().let { !it.contains(bodyMarker) }, "堆栈不得含正文")
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
