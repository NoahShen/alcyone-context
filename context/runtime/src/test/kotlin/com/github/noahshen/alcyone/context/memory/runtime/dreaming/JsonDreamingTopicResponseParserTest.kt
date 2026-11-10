package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonDreamingTopicResponseParserTest {
    private val parser = JsonDreamingTopicResponseParser()

    @Test
    fun `valid topics and empty topics are accepted`() {
        val parsed =
            parser.parse(
                """{"topics":[{"title":"t","description":"d","keywords":["a","a"],"sourceTurnIds":[2,1,2]}]}""",
            )

        assertEquals("t", parsed.single().title)
        assertEquals(listOf("a"), parsed.single().keywords)
        assertEquals(listOf(2L, 1L), parsed.single().sourceTurnIds)
        assertEquals(emptyList(), parser.parse("""{"topics":[]}"""))
    }

    @Test
    fun `top level and topic unknown or missing fields are rejected`() {
        listOf(
            """{"topics":[],"extra":1}""",
            """{"topic":[]}""",
            """{"topics":[{"title":"t","description":"d","keywords":[],"sourceTurnIds":[1],"topicId":"x"}]}""",
            """{"topics":[{"title":"t","description":"d","keywords":[]}]}""",
            """{"topics":[{"title":"t","description":"d","sourceTurnIds":[1]}]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `non object top level and wrong field types are rejected`() {
        listOf(
            """[]""",
            """null""",
            """"topics"""",
            """{"topics":{}}""",
            """{"topics":[[]]}""",
            """{"topics":[{"title":1,"description":"d","keywords":[],"sourceTurnIds":[1]}]}""",
            """{"topics":[{"title":"t","description":false,"keywords":[],"sourceTurnIds":[1]}]}""",
            """{"topics":[{"title":"t","description":"d","keywords":"a","sourceTurnIds":[1]}]}""",
            """{"topics":[{"title":"t","description":"d","keywords":[],"sourceTurnIds":"1"}]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `blank title description and keyword are rejected`() {
        listOf(
            """{"topics":[{"title":" ","description":"d","keywords":[],"sourceTurnIds":[1]}]}""",
            """{"topics":[{"title":"t","description":"\t","keywords":[],"sourceTurnIds":[1]}]}""",
            """{"topics":[{"title":"t","description":"d","keywords":[""],"sourceTurnIds":[1]}]}""",
            """{"topics":[{"title":"t","description":"d","keywords":[" "],"sourceTurnIds":[1]}]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `source ids reject string bool float exponent null oversized and negative values`() {
        listOf(
            "\"1\"",
            "true",
            "1.0",
            "1e2",
            "null",
            "9223372036854775808",
            "-1",
        ).forEach { id ->
            assertInvalid("""{"topics":[{"title":"t","description":"d","keywords":[],"sourceTurnIds":[$id]}]}""")
        }
    }

    @Test
    fun `source ids reject an empty array`() {
        assertInvalid("""{"topics":[{"title":"t","description":"d","keywords":[],"sourceTurnIds":[]}]}""")
    }

    @Test
    fun `duplicate top and nested keys are rejected before overwrite`() {
        listOf(
            """{"topics":[],"topics":[]}""",
            """{"topics":[{"title":"a","title":"b","description":"d","keywords":[],"sourceTurnIds":[1]}]}""",
            """{"topics":[{"title":"t","title":"t2","description":"d","keywords":[],"sourceTurnIds":[1]}]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `fences surrounding text and truncated json are never repaired`() {
        listOf(
            "```json\n{\"topics\":[]}\n```",
            "prefix {\"topics\":[]}",
            "{\"topics\":[]} suffix",
            "{\"topics\":[",
            "",
            "not json",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `parser failures are safe and have no cause`() {
        val marker = "SECRET-DREAMING-TOPIC-BODY"
        val failure = assertFailsWith<LlmException> { parser.parse("""{"topics":[{"title":"$marker"}""") }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
        assertTrue(!failure.message.orEmpty().contains(marker), failure.message.orEmpty())
    }

    @Test
    fun `Long boundary source id is accepted`() {
        val parsed =
            parser.parse(
                """{"topics":[{"title":"t","description":"d","keywords":[],"sourceTurnIds":[9223372036854775807]}]}""",
            )
        assertEquals(listOf(Long.MAX_VALUE), parsed.single().sourceTurnIds)
    }

    private fun assertInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
    }
}
