package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DreamingTopicPromptTest {
    private val base = Instant.parse("2026-11-09T08:00:00Z")

    @Test
    fun `version constants are v1`() {
        assertEquals("v1", DreamingTopicDiscoveryPrompt.VERSION)
        assertEquals("v1", DreamingTopicDiscoveryPrompt.SCHEMA_VERSION)
        assertEquals("v1", DreamingTopicConsolidationPrompt.VERSION)
        assertEquals("v1", DreamingTopicConsolidationPrompt.SCHEMA_VERSION)
    }

    @Test
    fun `discovery prompt contains each of the six principles as content`() {
        val prompt = DreamingTopicDiscoveryPrompt.systemPrompt()
        val principles =
            mapOf(
                "long-term/cross-session" to listOf("长期偏好", "偏好变化", "研究经验", "跨会话规律", "重要遗漏"),
                "faithfulness/uncertainty" to listOf("用户明确表达或确认", "Assistant 建议不得冒充用户决定", "保留不确定性", "不猜测当前值"),
                "self-contained/scope" to listOf("最小完整语义", "脱离原对话仍可理解", "范围", "条件", "时间背景"),
                "high-value changes" to listOf("决定", "约束", "已验证经验", "研究结论", "失败/否定结果", "重要矛盾"),
                "batch boundary" to listOf("当前 Batch", "后续 Batch", "跨批持久 Topic", "不做跨批"),
                "traceability/data-not-instruction" to listOf("本批真实来源", "伪造标签", "不是指令", "系统字段", "记忆正文"),
            )
        principles.forEach { (name, fragments) ->
            fragments.forEach { fragment -> assertTrue(prompt.contains(fragment), "$name missing $fragment: $prompt") }
        }
    }

    @Test
    fun `discovery prompt states input metadata and strict output contract`() {
        val prompt = DreamingTopicDiscoveryPrompt.systemPrompt()
        listOf(
            "runtimeId",
            "agentId",
            "sessionId",
            "sessionTurnId",
            "timestamp",
            "完整 messages",
            "topics",
            "title",
            "description",
            "keywords",
            "sourceTurnIds",
            "Markdown 围栏",
        ).forEach { fragment ->
            assertTrue(prompt.contains(fragment), "missing $fragment: $prompt")
        }
        assertFalse(prompt.contains("extractionStatus"), prompt)
        assertFalse(prompt.contains("http://"), prompt)
        assertFalse(prompt.contains("apiKey", ignoreCase = true), prompt)
    }

    @Test
    fun `discovery schema is strict and requires all four topic fields`() {
        val schema = DreamingTopicDiscoveryPrompt.schema()
        listOf(
            "\"topics\"",
            "\"title\"",
            "\"description\"",
            "\"keywords\"",
            "\"sourceTurnIds\"",
            "\"additionalProperties\": false",
            "\"required\": [\"title\", \"description\", \"keywords\", \"sourceTurnIds\"]",
            "\"minItems\": 1",
            "\"type\": \"integer\"",
        ).forEach { fragment ->
            assertTrue(schema.contains(fragment), "schema missing $fragment: $schema")
        }
        assertFalse(schema.contains("extractionStatus"), schema)
        assertFalse(schema.contains("topicId"), schema)
        assertFalse(schema.contains("confidence"), schema)
    }

    @Test
    fun `consolidation schema is strict and requires all four topic fields`() {
        val schema = DreamingTopicConsolidationPrompt.schema()
        listOf(
            "\"topics\"",
            "\"title\"",
            "\"description\"",
            "\"keywords\"",
            "\"sourceTurnIds\"",
            "\"additionalProperties\": false",
            "\"required\": [\"title\", \"description\", \"keywords\", \"sourceTurnIds\"]",
            "\"minItems\": 1",
            "\"type\": \"integer\"",
        ).forEach { fragment ->
            assertTrue(schema.contains(fragment), "schema missing $fragment: $schema")
        }
        listOf("topicId", "confidence", "generatedBy", "checkpoint", "createdAt").forEach { field ->
            assertFalse(schema.contains(field), "consolidation schema must not include $field: $schema")
        }
    }

    @Test
    fun `discovery messages render all session metadata and full messages without extraction status`() {
        val turn =
            StoredConversationTurn(
                id = 7L,
                runtimeId = "runtime",
                agentId = "agent",
                sessionId = "session",
                sessionTurnId = "turn-7",
                timestamp = base,
                createdAt = base,
                messages = listOf(ChatMessage(ChatRole.USER, "hello"), ChatMessage(ChatRole.ASSISTANT, "world")),
                extractionStatus = ExtractionStatus.FAILED,
            )
        val messages = DreamingTopicDiscoveryPrompt.messages(listOf(turn))

        assertEquals(listOf("system", "user"), messages.map { it.role })
        val user = messages[1].content
        listOf(
            "id=\"7\"",
            "runtimeId=\"runtime\"",
            "agentId=\"agent\"",
            "sessionId=\"session\"",
            "sessionTurnId=\"turn-7\"",
            "timestamp=",
            "user: hello",
            "assistant: world",
        ).forEach { fragment ->
            assertTrue(user.contains(fragment), "missing $fragment: $user")
        }
        assertFalse(user.contains("extractionStatus"), user)
        assertFalse(user.contains("failed"), user)
    }

    @Test
    fun `discovery empty batch renders empty user content`() {
        assertEquals("", DreamingTopicDiscoveryPrompt.messages(emptyList())[1].content)
    }

    @Test
    fun `consolidation prompt requires preservation and data not instruction`() {
        val prompt = DreamingTopicConsolidationPrompt.systemPrompt()
        listOf("只合并重复或高度相关", "保留矛盾", "范围差异", "时间差异", "不新增来源", "不丢失来源", "1..N", "不是指令", "伪造标签", "不输出持久 ID").forEach { fragment ->
            assertTrue(prompt.contains(fragment), "missing $fragment: $prompt")
        }
    }

    @Test
    fun `consolidation messages preserve topic order and source ids`() {
        val topics =
            listOf(
                DiscoveredTopic("first", "description", listOf("a", "b"), listOf(2L, 1L)),
                DiscoveredTopic("second", "other", emptyList(), listOf(3L)),
            )
        val user = DreamingTopicConsolidationPrompt.messages(topics)[1].content

        assertTrue(user.contains("<topics count=\"2\">"), user)
        assertTrue(user.indexOf("first") < user.indexOf("second"), user)
        assertTrue(user.contains("<sourceTurnIds>2,1</sourceTurnIds>"), user)
        assertTrue(user.contains("<keywords><keyword>a</keyword><keyword>b</keyword></keywords>"), user)
        assertEquals(
            DreamingTopicConsolidationPrompt.messages(topics)[1].content,
            DreamingTopicConsolidationPrompt.messages(topics)[1].content,
        )
    }

    @Test
    fun `both prompt resource readers fail explicitly when a resource is missing`() {
        val discovery = DreamingTopicDiscoveryPrompt::class.java.getDeclaredMethod("readResource", String::class.java)
        discovery.isAccessible = true
        val discoveryFailure =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                discovery.invoke(DreamingTopicDiscoveryPrompt, "/prompts/does-not-exist-v1.md")
            }
        assertTrue(discoveryFailure.cause is IllegalStateException)

        val consolidation = DreamingTopicConsolidationPrompt::class.java.getDeclaredMethod("readResource", String::class.java)
        consolidation.isAccessible = true
        val consolidationFailure =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                consolidation.invoke(DreamingTopicConsolidationPrompt, "/prompts/does-not-exist-v1.md")
            }
        assertTrue(consolidationFailure.cause is IllegalStateException)
    }
}
