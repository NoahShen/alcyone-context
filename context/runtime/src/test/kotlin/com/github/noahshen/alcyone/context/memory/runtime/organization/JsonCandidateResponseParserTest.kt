package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT13-B1 A03（严格 JSON）：只允许 `candidate_ids` 字符串数组；未知字段/非字符串/非数组/围栏/前后文本
 * 一律 `INVALID_RESPONSE`，且错误链不含原始正文。
 */
class JsonCandidateResponseParserTest {
    private val parser = JsonCandidateResponseParser()

    @Test
    fun `a valid response returns the candidate ids in model order`() {
        assertEquals(
            listOf("O-MEM-0002", "O-MEM-0001"),
            parser.parse("""{"candidate_ids":["O-MEM-0002","O-MEM-0001"]}"""),
        )
    }

    @Test
    fun `an empty candidate array is legal`() {
        assertEquals(emptyList(), parser.parse("""{"candidate_ids":[]}"""))
    }

    @Test
    fun `strict structural violations are rejected`() {
        val payloads =
            listOf(
                // 顶层不是 object
                """["O-MEM-0001"]""",
                "\"candidate_ids\"",
                // 未知字段
                """{"candidate_ids":[],"extra":1}""",
                // 缺字段
                """{}""",
                // 非数组
                """{"candidate_ids":"O-MEM-0001"}""",
                """{"candidate_ids":{}}""",
                // 非字符串元素
                """{"candidate_ids":[1]}""",
                """{"candidate_ids":[true]}""",
                """{"candidate_ids":[null]}""",
                """{"candidate_ids":[["O-MEM-0001"]]}""",
                // 空白字符串
                """{"candidate_ids":[""]}""",
                """{"candidate_ids":["   "]}""",
                // 非法 JSON / 围栏 / 前后文本
                "not json",
                """```json
{"candidate_ids":[]}
```""",
                """Here you go: {"candidate_ids":[]}""",
            )

        payloads.forEach { payload ->
            val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode, payload)
            assertTrue(
                failure.message.orEmpty().endsWith("invalid candidate response"),
                failure.message.orEmpty(),
            )
            assertNull(failure.cause, "不得挂 cause: $payload")
        }
    }

    @Test
    fun `errors never carry the raw model content`() {
        val marker = "ct13-b1-parser-body-marker-8d21"
        val payload = """{"candidate_ids":["$marker"],"extra":1}"""
        val failure = assertFailsWith<LlmException> { parser.parse(payload) }
        assertFalse(failure.message.orEmpty().contains(marker), failure.message.orEmpty())
        assertFalse(failure.toString().contains(marker), failure.toString())
        assertFalse(failure.stackTraceToString().contains(marker), "堆栈不得含正文")
        assertNull(failure.cause)
    }
}
