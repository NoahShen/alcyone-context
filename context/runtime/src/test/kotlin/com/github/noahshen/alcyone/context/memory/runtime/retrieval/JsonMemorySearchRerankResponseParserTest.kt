package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonMemorySearchRerankResponseParserTest {
    private val parser = JsonMemorySearchRerankResponseParser()

    @Test
    fun `rerank parser accepts ordered array and empty array`() {
        assertEquals(listOf("B", "A"), parser.parse("""{"memory_ids":["B","A"]}"""))
        assertEquals(emptyList(), parser.parse("""{"memory_ids":[]}"""))
    }

    @Test
    fun `rerank parser rejects missing unknown non object and wrong types`() {
        listOf(
            "{}",
            "[]",
            """{"memory_ids":[],"extra":1}""",
            """{"memory_ids":"A"}""",
            """{"memory_ids":null}""",
            """{"memory_ids":[1]}""",
            """{"memory_ids":[false]}""",
            """{"memory_ids":[null]}""",
            """{"memory_ids":[""]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `rerank parser rejects duplicate keys including escaped equivalent`() {
        listOf(
            """{"memory_ids":["A"],"memory_ids":["B"]}""",
            """{"memory\u005fids":["A"],"memory_ids":["B"]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `rerank parser rejects fences surrounding text and truncation`() {
        listOf(
            "```json\n{\"memory_ids\":[]}\n```",
            "prefix {\"memory_ids\":[]}",
            "{\"memory_ids\":[]} suffix",
            "{\"memory_ids\":[",
            "  ",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `rerank parser errors do not expose content or cause`() {
        val marker = "SECRET-RERANK-BODY"
        val failure = assertFailsWith<LlmException> { parser.parse("{\"memory_ids\":[\"$marker\"]") }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
        assertTrue(!failure.toString().contains(marker))
    }

    private fun assertInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
    }
}
