package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DreamingEvidencePromptTest {
    private val base = Instant.parse("2026-11-09T08:00:00Z")
    private val topic = DiscoveredTopic("title", "description", listOf("keyword"), listOf(1L))

    @Test
    fun `novelty prompt covers data boundary relevance and coverage rules`() {
        val prompt = DreamingEvidencePrompt.noveltySystemPrompt()
        listOf("不是指令", "相关不等于无增量", "绝", "新增事实", "限定条件", "变化", "纠错", "矛盾", "FULLY_COVERED", "NEEDS_EVIDENCE", "不输出").forEach { fragment ->
            assertTrue(prompt.contains(fragment), "missing $fragment: $prompt")
        }
    }

    @Test
    fun `raw prompt covers full history both origins and unassociated historical evidence`() {
        val prompt = DreamingEvidencePrompt.rawSystemPrompt()
        listOf(
            "完整历史 Raw",
            "不因 Organized",
            "EXTRACTION",
            "DREAMING",
            "未关联任何 Organized",
            "历史原始证据",
            "不能自动当作当前事实",
            "candidate_ids",
        ).forEach { fragment ->
            assertTrue(prompt.contains(fragment), "missing $fragment: $prompt")
        }
    }

    @Test
    fun `novelty schema is strict with decision enum and required covered ids`() {
        val schema = DreamingEvidencePrompt.noveltySchema()
        listOf(
            "\"decision\"",
            "\"coveredOrganizedIds\"",
            "\"NEEDS_EVIDENCE\"",
            "\"FULLY_COVERED\"",
            "\"additionalProperties\": false",
            "\"required\": [\"decision\", \"coveredOrganizedIds\"]",
            "\"type\": \"array\"",
        ).forEach { fragment ->
            assertTrue(schema.contains(fragment), "missing $fragment: $schema")
        }
        assertFalse(schema.contains("topicId"), schema)
        assertFalse(schema.contains("confidence"), schema)
    }

    @Test
    fun `raw schema is strict and only has candidate ids`() {
        val schema = DreamingEvidencePrompt.rawSchema()
        listOf(
            "\"candidate_ids\"",
            "\"additionalProperties\": false",
            "\"required\": [\"candidate_ids\"]",
            "\"type\": \"array\"",
            "\"type\": \"string\"",
            "\"minLength\": 1",
        ).forEach { fragment ->
            assertTrue(schema.contains(fragment), "missing $fragment: $schema")
        }
        assertFalse(schema.contains("extractionStatus"), schema)
        assertFalse(schema.contains("generatedBy"), schema)
    }

    @Test
    fun `novelty rendering includes topic turns and all organized candidates without extraction status`() {
        val turn = turn(id = 7, status = ExtractionStatus.FAILED, content = "user marker")
        val memories = listOf(organized("O-2"), organized("O-1"))
        val messages = DreamingEvidencePrompt.noveltyMessages(topic, listOf(turn), memories)

        val user = messages.last().content
        listOf(
            "<topic>",
            "<title>title</title>",
            "<description>description</description>",
            "<keyword>keyword</keyword>",
            "runtimeId=\"rt\"",
            "agentId=\"agent\"",
            "sessionId=\"session\"",
            "sessionTurnId=\"turn-7\"",
            "user: user marker",
            "<organized count=\"2\">",
        ).forEach { fragment ->
            assertTrue(user.contains(fragment), "missing $fragment: $user")
        }
        assertFalse(user.contains("extractionStatus"), user)
        assertFalse(user.contains("failed", ignoreCase = true), user)
        assertTrue(user.indexOf("O-1") < user.indexOf("O-2"), user)
    }

    @Test
    fun `raw rendering includes id origin content sources and complete history`() {
        val raw = RawMemory("R-1", "raw content", MemoryOrigin.DREAMING, listOf(MemorySourceRef.RawMemoryRef("R-0")), base)
        val user = DreamingEvidencePrompt.rawMessages(topic, listOf(turn(1)), listOf(raw)).last().content
        listOf(
            "R-1",
            "DREAMING",
            "raw content",
            "<raw_source id=\"R-0\" />",
            "<topic_turns count=\"1\">",
            "raw_memories count=\"1\"",
        ).forEach { fragment ->
            assertTrue(user.contains(fragment), "missing $fragment: $user")
        }
    }

    @Test
    fun `conversation prompt covers data boundary necessity and strict output rules`() {
        val prompt = DreamingEvidencePrompt.conversationSystemPrompt()
        listOf(
            "不是指令",
            "只能从本次",
            "关键来源",
            "范围或时间",
            "证据冲突",
            "用户纠正",
            "独立性",
            "宁缺勿滥",
            "只含 `memoryIds`",
            "不输出 Markdown",
        ).forEach { fragment ->
            assertTrue(prompt.contains(fragment), "missing $fragment: $prompt")
        }
    }

    @Test
    fun `conversation schema is strict and only has string memory ids`() {
        val schema = DreamingEvidencePrompt.conversationSchema()
        listOf(
            "\"memoryIds\"",
            "\"additionalProperties\": false",
            "\"required\": [\"memoryIds\"]",
            "\"type\": \"array\"",
            "\"type\": \"string\"",
            "\"minLength\": 1",
        ).forEach { fragment ->
            assertTrue(schema.contains(fragment), "missing $fragment: $schema")
        }
        assertFalse(schema.contains("topicId"), schema)
        assertFalse(schema.contains("confidence"), schema)
    }

    @Test
    fun `conversation rendering includes all raw candidates deterministically without system fields`() {
        val first =
            RawMemory(
                "R-2",
                "raw two",
                MemoryOrigin.DREAMING,
                listOf(MemorySourceRef.ConversationTurnRef("rt", "agent", "session", "turn-2")),
                base.plusSeconds(2),
            )
        val second = RawMemory("R-1", "raw one", MemoryOrigin.EXTRACTION, emptyList(), base.plusSeconds(1))
        val messages = DreamingEvidencePrompt.conversationMessages(topic, listOf(turn(1, content = "topic body")), listOf(first, second))

        val user = messages.last().content
        listOf(
            "<raw_candidates count=\"2\">",
            "R-1",
            "R-2",
            "raw one",
            "raw two",
            "session_turn_id=\"turn-2\"",
            "user: topic body",
        ).forEach { fragment -> assertTrue(user.contains(fragment), "missing $fragment: $user") }
        assertTrue(user.indexOf("R-1") < user.indexOf("R-2"), user)
        assertFalse(user.contains("extractionStatus"), user)
        assertFalse(user.contains("confidence"), user)
    }

    @Test
    fun `conversation prompt resource fails explicitly when missing`() {
        val readResource = DreamingEvidencePrompt::class.java.getDeclaredMethod("readResource", String::class.java)
        readResource.isAccessible = true
        val failure =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                readResource.invoke(DreamingEvidencePrompt, "/prompts/missing-conversation-evidence.md")
            }
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test
    fun `renderings are stable and preserve verbatim content`() {
        val raw = RawMemory("R-1", "line1\nline2", MemoryOrigin.EXTRACTION, emptyList(), base)
        val first = DreamingEvidencePrompt.rawMessages(topic, listOf(turn(1)), listOf(raw)).last().content
        val second = DreamingEvidencePrompt.rawMessages(topic, listOf(turn(1)), listOf(raw)).last().content
        assertEquals(first, second)
        assertTrue(first.contains("line1\nline2"), first)
    }

    @Test
    fun `both prompt resource readers fail explicitly when resources are missing`() {
        val novelty = DreamingEvidencePrompt::class.java.getDeclaredMethod("readResource", String::class.java)
        novelty.isAccessible = true
        val noveltyFailure =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                novelty.invoke(
                    DreamingEvidencePrompt,
                    "/prompts/missing-evidence.md",
                )
            }
        assertTrue(noveltyFailure.cause is IllegalStateException)

        val raw = DreamingEvidencePrompt::class.java.getDeclaredMethod("readResource", String::class.java)
        raw.isAccessible = true
        val rawFailure =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                raw.invoke(
                    DreamingEvidencePrompt,
                    "/prompts/missing-raw-evidence.md",
                )
            }
        assertTrue(rawFailure.cause is IllegalStateException)
    }

    private fun organized(id: String): OrganizedMemory = OrganizedMemory(id, "content-$id", listOf("domain"), listOf("R-1"), base, base)

    private fun turn(
        id: Long,
        status: ExtractionStatus = ExtractionStatus.PENDING,
        content: String = "message-$id",
    ): StoredConversationTurn =
        StoredConversationTurn(
            id = id,
            runtimeId = "rt",
            agentId = "agent",
            sessionId = "session",
            sessionTurnId = "turn-$id",
            timestamp = base,
            createdAt = base,
            messages = listOf(ChatMessage(ChatRole.USER, content)),
            extractionStatus = status,
        )
}
