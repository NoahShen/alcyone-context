package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CT10-B1 A01/A03：Prompt v1 版本、资源内容与六原则/JSON 契约、轮次渲染格式
 * （顺序 / id / `+08:00` 时间戳 / target 三态 / 角色与正文原样 / 空列表）、渲染稳定性与无泄漏。
 */
class ExtractionPromptTest {
    private val base: Instant = Instant.parse("2026-10-30T02:00:00.123Z")

    @Test
    fun `version constants are v1`() {
        assertEquals("v1", ExtractionPrompt.VERSION)
        assertEquals("v1", ExtractionPrompt.SCHEMA_VERSION)
    }

    @Test
    fun `system prompt covers the six principles and the output contract`() {
        val system = ExtractionPrompt.systemPrompt()
        assertTrue(system.isNotBlank(), "system prompt 不得为空")
        assertTrue(system.contains("六原则"), system)

        // 逐条断言原则的**内容**（与资源中的稳定措辞一致），不只数编号。
        val principles =
            mapOf(
                "价值优先" to listOf("长期", "偏好", "决定", "目标", "忽略寒暄"),
                "忠实来源" to listOf("不推测", "不编造", "Assistant", "明确采纳"),
                "完整原子化" to listOf("最小完整语义", "原因", "条件", "时间", "碎片"),
                "关注重要变化" to listOf("变化", "失败", "否定性结果", "研究结论", "已验证的经验"),
                "批内去冗余" to listOf("去冗余", "合并", "多个来源", "Raw Memory", "再次提取"),
                "证据可追溯" to listOf("source_turn_ids", "Target", "可追溯", "不猜测"),
            )
        principles.forEach { (title, fragments) ->
            assertTrue(system.contains(title), "缺少原则标题 $title: $system")
            fragments.forEach { fragment ->
                assertTrue(system.contains(fragment), "原则「$title」缺少关键内容 $fragment: $system")
            }
        }

        // Target / History 区分与来源约束
        assertTrue(system.contains("target=\"true\""), system)
        assertTrue(system.contains("Target"), system)
        assertTrue(system.contains("History"), system)
        assertTrue(system.contains("不得单独作为新记忆的来源"), system)
        // 时间表达：相对时间需给绝对锚点，不补造
        assertTrue(system.contains("绝对时间锚点"), system)
        assertTrue(system.contains("不补造"), system)
        // JSON 输出契约：memories 可空、无系统字段、无围栏/解释
        assertTrue(system.contains("\"memories\": []"), system)
        assertTrue(system.contains("系统字段"), system)
        assertTrue(system.contains("Markdown 围栏"), system)
        assertTrue(system.contains("source_turn_ids"), system)
        // 不含 Key/URL/无关元数据。
        assertFalse(system.contains("http://"), system)
        assertFalse(system.contains("https://"), system)
        assertFalse(system.contains("apiKey", ignoreCase = true), system)
    }

    @Test
    fun `schema declares the memories contract without system fields`() {
        val schema = ExtractionPrompt.schema()
        assertTrue(schema.isNotBlank(), "schema 不得为空")
        listOf(
            "\"memories\"",
            "\"content\"",
            "\"source_turn_ids\"",
            "\"additionalProperties\": false",
            "\"type\": \"array\"",
            "\"type\": \"integer\"",
            "\"minItems\": 1",
        ).forEach { fragment -> assertTrue(schema.contains(fragment), "schema 缺少 $fragment: $schema") }
        assertFalse(schema.contains("http://"), schema)
        assertFalse(schema.contains("https://"), schema)
        // 不要求模型输出系统字段。
        listOf("createdAt", "extractionStatus", "confidence").forEach { field ->
            assertFalse(schema.contains(field), "schema 不得要求系统字段 $field")
        }
    }

    @Test
    fun `messages render system plus user in session order`() {
        val turns =
            listOf(
                turn(1001, ExtractionStatus.PENDING, listOf(user("hello"), assistant("hi"))),
                turn(1002, ExtractionStatus.COMPLETE, listOf(user("thanks"))),
            )

        val messages = ExtractionPrompt.messages(turns)

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals(ExtractionPrompt.systemPrompt(), messages[0].content)
        assertEquals("user", messages[1].role)
        assertEquals(
            """
            <turn id="1001" timestamp="2026-10-30T10:00:00.123+08:00" target="true">
            user: hello
            assistant: hi
            </turn>
            <turn id="1002" timestamp="2026-10-30T10:00:00.123+08:00" target="false">
            user: thanks
            </turn>
            """.trimIndent(),
            messages[1].content,
        )
    }

    @Test
    fun `an empty turn list renders an empty user content`() {
        val messages = ExtractionPrompt.messages(emptyList())
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
        assertEquals("", messages[1].content)
        assertEquals("", ExtractionPrompt.renderTurns(emptyList()))
    }

    @Test
    fun `target flag follows the extraction status`() {
        val rendered =
            ExtractionPrompt.renderTurns(
                listOf(
                    turn(1, ExtractionStatus.PENDING, listOf(user("a"))),
                    turn(2, ExtractionStatus.COMPLETE, listOf(user("b"))),
                    turn(3, ExtractionStatus.FAILED, listOf(user("c"))),
                ),
            )
        val blocks = rendered.split("</turn>").filter { it.isNotBlank() }
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].contains("id=\"1\"") && blocks[0].contains("target=\"true\""), blocks[0])
        assertTrue(blocks[1].contains("id=\"2\"") && blocks[1].contains("target=\"false\""), blocks[1])
        assertTrue(blocks[2].contains("id=\"3\"") && blocks[2].contains("target=\"false\""), blocks[2])
    }

    @Test
    fun `multi message turns keep roles and content verbatim`() {
        val rendered =
            ExtractionPrompt.renderTurns(
                listOf(
                    turn(
                        7,
                        ExtractionStatus.PENDING,
                        listOf(user("line1\nline2"), assistant("a: b"), tool("{\"k\":1}")),
                    ),
                ),
            )
        assertTrue(rendered.contains("user: line1\nline2"), rendered)
        assertTrue(rendered.contains("assistant: a: b"), rendered)
        assertTrue(rendered.contains("tool: {\"k\":1}"), rendered)
        assertFalse(rendered.contains("http://"), rendered)
        assertFalse(rendered.contains("apiKey", ignoreCase = true), rendered)
    }

    @Test
    fun `rendering is stable across repeated calls`() {
        val turns = listOf(turn(1, ExtractionStatus.PENDING, listOf(user("hello"))))
        assertEquals(ExtractionPrompt.renderTurns(turns), ExtractionPrompt.renderTurns(turns))
        assertEquals(ExtractionPrompt.messages(turns), ExtractionPrompt.messages(turns))
    }

    @Test
    fun `a missing resource fails explicitly instead of returning a blank string`() {
        val method = ExtractionPrompt::class.java.getDeclaredMethod("readResource", String::class.java)
        method.isAccessible = true
        val failure =
            assertFailsWith<InvocationTargetException> {
                method.invoke(ExtractionPrompt, "/prompts/does-not-exist-v1.md")
            }
        assertTrue(failure.cause is IllegalStateException, "缺失资源必须明确失败: ${failure.cause}")
    }

    // ------------------------------------------------------------------ 工具

    private fun user(content: String): ChatMessage = ChatMessage(ChatRole.USER, content)

    private fun assistant(content: String): ChatMessage = ChatMessage(ChatRole.ASSISTANT, content)

    private fun tool(content: String): ChatMessage = ChatMessage(ChatRole.TOOL, content)

    private fun turn(
        id: Long,
        status: ExtractionStatus,
        messages: List<ChatMessage>,
    ): StoredConversationTurn =
        StoredConversationTurn(
            id = id,
            runtimeId = "rt",
            agentId = "agent",
            sessionId = "session",
            sessionTurnId = "turn-$id",
            timestamp = base,
            createdAt = base,
            messages = messages,
            extractionStatus = status,
        )
}
