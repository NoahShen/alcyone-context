package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonMemorySearchResponseParserTest {
    private val parser = JsonMemorySearchResponseParser()

    @Test
    fun `scanner parser accepts valid arrays including empty`() {
        assertEquals(listOf("A", "B"), parser.parse("""{"candidate_ids":["A","B"]}"""))
        assertEquals(emptyList(), parser.parse("""{"candidate_ids":[]}"""))
    }

    @Test
    fun `scanner parser rejects non object missing unknown and wrong field types`() {
        listOf(
            "[]",
            "null",
            "{}",
            """{"candidate_ids":[],"extra":1}""",
            """{"candidate_ids":"A"}""",
            """{"candidate_ids":null}""",
            """{"candidate_ids":[1]}""",
            """{"candidate_ids":[true]}""",
            """{"candidate_ids":[null]}""",
            """{"candidate_ids":[""]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `scanner parser rejects duplicate root keys before overwrite`() {
        listOf(
            """{"candidate_ids":["A"],"candidate_ids":["B"]}""",
            """{"candidate\u005fids":["A"],"candidate_ids":["B"]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `scanner parser rejects fences surrounding text and truncation`() {
        listOf(
            "```json\n{\"candidate_ids\":[]}\n```",
            "prefix {\"candidate_ids\":[]}",
            "{\"candidate_ids\":[]} suffix",
            "{\"candidate_ids\":[",
            "",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `scanner parser error is safe and has no cause`() {
        val marker = "SECRET-SEARCH-BODY"
        val failure = assertFailsWith<LlmException> { parser.parse("{\"candidate_ids\":[\"$marker\"]") }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
        assertTrue(!failure.message.orEmpty().contains(marker))
    }

    private fun assertInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
    }
}
