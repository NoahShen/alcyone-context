package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonDreamingReflectionParserTest {
    private val parser = JsonDreamingReflectionParser()

    @Test
    fun `empty and multiple insights preserve model order and original content`() {
        assertEquals(emptyList(), parser.parse("""{"insights":[]}"""))

        val result =
            parser.parse(
                """{"insights":[{"content":"  first  ","sourceTurnIds":[3,1,3]},{"content":"second","sourceTurnIds":[2]}]}""",
            )

        assertEquals(listOf("  first  ", "second"), result.map { it.content })
        assertEquals(listOf(listOf(3L, 1L, 3L), listOf(2L)), result.map { it.sourceTurnIds })
    }

    @Test
    fun `top level requires exactly one insights array`() {
        listOf(
            """{}""",
            """{"insights":null}""",
            """{"insights":{},"extra":1}""",
            """{"insights":[],"extra":1}""",
            """{"Insights":[]}""",
            """[]""",
            """null""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `insight requires exactly content and sourceTurnIds with correct types`() {
        listOf(
            """{"insights":[{}]}""",
            """{"insights":[{"content":"ok"}]}""",
            """{"insights":[{"sourceTurnIds":[1]}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":[1],"extra":true}]}""",
            """{"insights":[{"content":null,"sourceTurnIds":[1]}]}""",
            """{"insights":[{"content":1,"sourceTurnIds":[1]}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":null}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":[]}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":"1"}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":{}}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":[{"memoryId":"R-1"}]}]} """,
            """{"insights":[{"content":"ok","sourceTurnIds":[true]}]} """,
        ).forEach(::assertInvalid)
    }

    @Test
    fun `content must contain a non whitespace character but whitespace is preserved`() {
        listOf("", " ", "\t", "\n", " \n\t").forEach { content ->
            assertInvalid("""{"insights":[{"content":"$content","sourceTurnIds":[1]}]}""")
        }

        assertEquals(" x ", parser.parse("""{"insights":[{"content":" x ","sourceTurnIds":[1]}]}""").single().content)
    }

    @Test
    fun `source ids must be JSON integral long values`() {
        listOf(
            "0",
            "1",
            "-1",
            "9223372036854775807",
            "-9223372036854775808",
        ).forEach { id ->
            assertEquals(
                id.toLong(),
                parser
                    .parse("""{"insights":[{"content":"ok","sourceTurnIds":[$id]}]}""")
                    .single()
                    .sourceTurnIds
                    .single(),
            )
        }

        listOf(
            "null",
            "true",
            "\"1\"",
            "1.0",
            "1e3",
            "01",
            "9223372036854775808",
            "-9223372036854775809",
            "{}",
            "[]",
        ).forEach { id ->
            assertInvalid("""{"insights":[{"content":"ok","sourceTurnIds":[$id]}]}""")
        }
    }

    @Test
    fun `strict parser rejects fences explanations malformed and trailing json`() {
        listOf(
            "",
            "null",
            "[]",
            """```json\n{"insights":[]}\n```""",
            """prefix {"insights":[]}""",
            """{"insights":[]} suffix""",
            """{"insights":[]}{"insights":[]}""",
            """{"insights":[}""",
            """{"insights":[{"content":"ok","sourceTurnIds":[1]}]}""".dropLast(1),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `duplicate keys are rejected at every object depth including escaped equivalents`() {
        listOf(
            """{"insights":[],"insights":[]}""",
            """{"insights":[{"content":"ok","content":"again","sourceTurnIds":[1]}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":[1],"sourceTurnIds":[1]}]}""",
            """{"insights":[{"content":"ok","sourceTurnIds":[1]}],"ins\u0069ghts":[]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `all parser failures are safe immutable and attributed to reflection`() {
        val marker = "SECRET-REFLECTION-BODY"
        val failure = assertFailsWith<LlmException> { parser.parse("""{"insights":[{"content":"$marker"}""") }

        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertEquals(LlmTask.DREAMING_REFLECTION, failure.task)
        assertNull(failure.cause)
        assertTrue(!failure.message.orEmpty().contains(marker), failure.message.orEmpty())
    }

    private fun assertInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertEquals(LlmTask.DREAMING_REFLECTION, failure.task)
        assertNull(failure.cause)
    }
}
