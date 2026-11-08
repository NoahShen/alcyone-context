package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonMemoryMapResponseParserTest {
    private val parser = JsonMemoryMapResponseParser()

    @Test
    fun `a valid envelope returns all three fields`() {
        val draft =
            parser.parse(
                """{"overview":"overview","navigation":"navigation","domains":["work","finance"]}""",
            )

        assertEquals("overview", draft.overview)
        assertEquals("navigation", draft.navigation)
        assertEquals(listOf("work", "finance"), draft.domains)
    }

    @Test
    fun `missing and unknown fields are rejected`() {
        listOf(
            """{"navigation":"n","domains":[]}""",
            """{"overview":"o","domains":[]}""",
            """{"overview":"o","navigation":"n"}""",
            """{"overview":"o","navigation":"n","domains":[],"extra":1}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `wrong field types are rejected`() {
        listOf(
            """{"overview":1,"navigation":"n","domains":[]}""",
            """{"overview":"o","navigation":null,"domains":[]}""",
            """{"overview":"o","navigation":"n","domains":"work"}""",
            """{"overview":"o","navigation":"n","domains":[1]}""",
            """{"overview":"o","navigation":"n","domains":[null]}""",
            """{"overview":"o","navigation":"n","domains":[["work"]]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `duplicate root keys are rejected before json object overwrite`() {
        listOf(
            """{"overview":"first","overview":"second","navigation":"n","domains":[]}""",
            """{"overview":"o","navigation":"first","navigation":"second","domains":[]}""",
            """{"overview":"o","navigation":"n","domains":[],"domains":["work"]}""",
            """{"over\u0076iew":"first","overview":"second","navigation":"n","domains":[]}""",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `non object null arrays and primitive roots are rejected`() {
        listOf("[]", "null", "42", "\"text\"", "true").forEach(::assertInvalid)
    }

    @Test
    fun `fenced surrounded truncated and empty payloads are never repaired`() {
        listOf(
            "```json\n{\"overview\":\"o\",\"navigation\":\"n\",\"domains\":[]}\n```",
            "Here: {\"overview\":\"o\",\"navigation\":\"n\",\"domains\":[]}",
            "{\"overview\":\"o\",\"navigation\":\"n\",\"domains\":[]} trailing",
            "{\"overview\":\"o\",\"navigation\":\"n\",\"domains\":[",
            "",
            "   ",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `blank semantic values are left to Core validation but array shape remains strict`() {
        val draft = parser.parse("""{"overview":"","navigation":"  ","domains":["", "work"]}""")

        assertEquals("", draft.overview)
        assertEquals("  ", draft.navigation)
        assertEquals(listOf("", "work"), draft.domains)
    }

    @Test
    fun `errors do not expose model content or cause`() {
        val marker = "SECRET-MODEL-BODY"
        val failure = assertFailsWith<LlmException> { parser.parse("{\"overview\":\"$marker\"}") }

        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
        assertTrue(!failure.message.orEmpty().contains(marker))
        assertTrue(!failure.toString().contains(marker))
    }

    private fun assertInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode, payload)
    }
}
