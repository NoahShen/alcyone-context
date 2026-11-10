package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonDreamingConversationNeedParserTest {
    private val parser = JsonDreamingConversationNeedParser()

    @Test
    fun `parser accepts empty and duplicate ids`() {
        assertEquals(emptyList(), parser.parse("""{"memoryIds":[]}""").memoryIds)
        assertEquals(listOf("R-1", "R-1", "R-2"), parser.parse("""{"memoryIds":["R-1","R-1","R-2"]}""").memoryIds)
    }

    @Test
    fun `parser rejects unknown missing wrong type null bool number and blank ids`() {
        listOf(
            """{"memoryIds":[],"extra":1}""",
            """{"ids":[]}""",
            """{"memoryIds":"R-1"}""",
            """{"memoryIds":null}""",
            """{"memoryIds":true}""",
            """{"memoryIds":1}""",
            """{"memoryIds":[1]}""",
            """{"memoryIds":[null]}""",
            """{"memoryIds":[""]}""",
            """{"memoryIds":[" "]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `parser rejects duplicate keys fences surrounding text and truncation`() {
        listOf(
            """{"memoryIds":[],"memoryIds":["R-1"]}""",
            "```json\n{\"memoryIds\":[]}\n```",
            "prefix {\"memoryIds\":[]}",
            "{\"memoryIds\":[]} suffix",
            "{\"memoryIds\":[",
            "",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `parser errors are safe and have no cause`() {
        val marker = "R-SECRET-MARKER"
        val failure = assertFailsWith<LlmException> { parser.parse("""{"memoryIds":[],"$marker":1}""") }
        assertTrue(failure.message.orEmpty().endsWith("invalid dreaming conversation need response"))
        assertNull(failure.cause)
        assertTrue(!failure.message.orEmpty().contains(marker))
    }

    private fun assertInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertTrue(failure.message.orEmpty().endsWith("invalid dreaming conversation need response"))
        assertNull(failure.cause)
    }
}
