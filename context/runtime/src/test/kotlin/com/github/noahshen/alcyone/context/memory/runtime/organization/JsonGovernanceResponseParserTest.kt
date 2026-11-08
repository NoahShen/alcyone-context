package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT14-B1 A07（Runtime 严格 JSON）：只允许 action/targetMemoryId/content/domains；
 * 未知字段、类型错误、围栏、前后文本、空 action 一律 `INVALID_RESPONSE`；**不做任何修复**。
 */
class JsonGovernanceResponseParserTest {
    private val parser = JsonGovernanceResponseParser()

    @Test
    fun `a valid decision returns the parsed fields`() {
        val created = parser.parse("""{"action":"NEW","content":"standalone fact","domains":["preferences"]}""")

        assertEquals(GovernanceAction.NEW, created.action)
        assertNull(created.targetMemoryId)
        assertEquals("standalone fact", created.content)
        assertEquals(listOf("preferences"), created.domains)

        val merged = parser.parse("""{"action":"MERGE","targetMemoryId":"O-MEM-0001","content":"补充"}""")

        assertEquals(GovernanceAction.MERGE, merged.action)
        assertEquals("O-MEM-0001", merged.targetMemoryId)
        assertEquals("补充", merged.content)
        assertNull(merged.domains)

        val updated =
            parser.parse("""{"action":"UPDATE","targetMemoryId":"O-MEM-0001","content":"新认知","domains":["work"]}""")

        assertEquals(GovernanceAction.UPDATE, updated.action)
        assertEquals("O-MEM-0001", updated.targetMemoryId)
        assertEquals(listOf("work"), updated.domains)
    }

    @Test
    fun `optional fields may be absent or explicitly null`() {
        val minimal = parser.parse("""{"action":"MERGE","targetMemoryId":"O-MEM-0001"}""")

        assertNull(minimal.content)
        assertNull(minimal.domains)

        val explicitNull =
            parser.parse("""{"action":"MERGE","targetMemoryId":"O-MEM-0001","content":null,"domains":null}""")

        assertNull(explicitNull.content)
        assertNull(explicitNull.domains)
    }

    @Test
    fun `unknown fields and non object payloads are rejected`() {
        listOf(
            """{"action":"NEW","content":"c","domains":["d"],"extra":1}""",
            """{"action":"NEW","content":"c","domains":["d"],"confidence":0.9}""",
            """{"action":"NEW","content":"c","domains":["d"],"reason":"because"}""",
            """["action"]""",
            """"NEW"""",
            """42""",
            """null""",
        ).forEach { payload ->
            assertInvalid(payload)
        }
    }

    @Test
    fun `a missing blank or unknown action is rejected`() {
        listOf(
            """{"content":"c","domains":["d"]}""",
            """{"action":"","content":"c","domains":["d"]}""",
            """{"action":"   ","content":"c","domains":["d"]}""",
            """{"action":"DELETE","content":"c","domains":["d"]}""",
            """{"action":"new","content":"c","domains":["d"]}""",
            """{"action":1,"content":"c","domains":["d"]}""",
            """{"action":null,"content":"c","domains":["d"]}""",
            """{"action":["NEW"],"content":"c","domains":["d"]}""",
        ).forEach { payload ->
            assertInvalid(payload)
        }
    }

    @Test
    fun `wrong field types are rejected`() {
        listOf(
            """{"action":"NEW","targetMemoryId":1,"content":"c","domains":["d"]}""",
            """{"action":"NEW","targetMemoryId":true,"content":"c","domains":["d"]}""",
            """{"action":"NEW","targetMemoryId":["O-MEM-0001"],"content":"c","domains":["d"]}""",
            """{"action":"NEW","content":1,"domains":["d"]}""",
            """{"action":"NEW","content":false,"domains":["d"]}""",
            """{"action":"NEW","content":{"text":"c"},"domains":["d"]}""",
            """{"action":"NEW","content":"c","domains":"preferences"}""",
            """{"action":"NEW","content":"c","domains":{"0":"preferences"}}""",
            """{"action":"NEW","content":"c","domains":[1]}""",
            """{"action":"NEW","content":"c","domains":[null]}""",
            """{"action":"NEW","content":"c","domains":[["preferences"]]}""",
        ).forEach { payload ->
            assertInvalid(payload)
        }
    }

    @Test
    fun `fenced or surrounded json is never repaired`() {
        listOf(
            "```json\n{\"action\":\"NEW\",\"content\":\"c\",\"domains\":[\"d\"]}\n```",
            "```\n{\"action\":\"NEW\",\"content\":\"c\",\"domains\":[\"d\"]}\n```",
            "Here is the decision: {\"action\":\"NEW\",\"content\":\"c\",\"domains\":[\"d\"]}",
            "{\"action\":\"NEW\",\"content\":\"c\",\"domains\":[\"d\"]} trailing",
            "{\"action\":\"NEW\",\"content\":\"c\",\"domains\":[\"d\"]}{\"action\":\"NEW\"}",
            "not json",
            "",
            "   ",
        ).forEach { payload ->
            assertInvalid(payload)
        }
    }

    @Test
    fun `errors never carry the raw model content`() {
        val payload = """{"action":"DELETE","content":"SECRET-BODY-MARKER","domains":["d"]}"""

        val failure = assertFailsWith<LlmException> { parser.parse(payload) }

        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
        assertTrue(!failure.message.orEmpty().contains("SECRET-BODY-MARKER"), failure.message.orEmpty())
        assertTrue(!failure.toString().contains("SECRET-BODY-MARKER"), failure.toString())
    }

    private fun assertInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { parser.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode, payload)
    }
}
