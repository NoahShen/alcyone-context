package com.github.noahshen.alcyone.context.memory.runtime.evaluation

import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfflineEvaluationTaskAdaptersTest {
    @Test
    fun `extraction maps internal turn ids to stable sources`() {
        val turns = OfflineEvaluationTestFixtures.turns(1L, 2L)
        val result =
            OfflineEvaluationTaskAdapters.parseExtraction(
                """{"memories":[{"content":"fact","source_turn_ids":[2]}]}""",
                OfflineEvaluationTestFixtures.context(turns),
            )
        val memory =
            result
                .getValue("memories")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("fact", memory.getValue("content").jsonPrimitive.content)
        assertEquals(
            "turn-2",
            memory
                .getValue("sources")
                .jsonArray
                .single()
                .jsonObject
                .getValue("sessionTurnId")
                .jsonPrimitive.content,
        )
        assertFailsWith<LlmException> {
            OfflineEvaluationTaskAdapters.parseExtraction(
                """{"memories":[{"content":"fact","source_turn_ids":[3]}]}""",
                OfflineEvaluationTestFixtures.context(turns),
            )
        }.assertInvalid()
    }

    @Test
    fun `selection ids are bounded and deduplicated and malformed json is rejected`() {
        val allowed = setOf("a", "b")
        assertEquals(
            listOf("b", "a"),
            OfflineEvaluationTaskAdapters
                .parseScanner("""{"candidate_ids":["b","b","a"]}""", allowed)
                .getValue("candidate_ids")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("a"),
            OfflineEvaluationTaskAdapters
                .parseRerank("""{"memory_ids":["a","a"]}""", allowed)
                .getValue("memory_ids")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertFailsWith<LlmException> {
            OfflineEvaluationTaskAdapters.parseCandidates("""{"candidate_ids":["c"]}""", allowed)
        }.assertInvalid()
        assertFailsWith<LlmException> {
            OfflineEvaluationTaskAdapters.parseScanner("```json\n{\"candidate_ids\":[]}\n```", allowed)
        }.assertInvalid()
    }

    @Test
    fun `governance and map return deterministic evaluation json`() {
        val candidate = OfflineEvaluationTestFixtures.organized("candidate-1")
        val decision =
            OfflineEvaluationTaskAdapters
                .parseGovernance(
                    """{"action":"UPDATE","targetMemoryId":"candidate-1","content":"new fact","domains":["work","work"]}""",
                    OfflineEvaluationTestFixtures.raw(),
                    listOf(candidate),
                ).getValue("decision")
                .jsonObject
        assertEquals("UPDATE", decision.getValue("action").jsonPrimitive.content)
        assertEquals(1, decision.getValue("domains").jsonArray.size)

        val map =
            OfflineEvaluationTaskAdapters.parseMap(
                """{"overview":"overview","navigation":"navigation","domains":["work"]}""",
                OfflineEvaluationTestFixtures.mapSnapshot(listOf(candidate)),
            )
        assertEquals("overview", map.getValue("overview").jsonPrimitive.content)
        assertFailsWith<LlmException> {
            OfflineEvaluationTaskAdapters.parseGovernance(
                """{"action":"UPDATE","targetMemoryId":"unknown","content":"fact"}""",
                OfflineEvaluationTestFixtures.raw(),
                listOf(candidate),
            )
        }.assertInvalid()
    }

    @Test
    fun `dreaming returns core objects and enforces evidence bounds`() {
        val topics =
            OfflineEvaluationTaskAdapters.parseTopic(
                """{"topics":[{"title":"topic","description":"description","keywords":["key"],"sourceTurnIds":[1,1]}]}""",
                setOf(1L),
            )
        assertEquals(listOf(1L), topics.single().sourceTurnIds)
        assertFailsWith<LlmException> {
            OfflineEvaluationTaskAdapters.parseTopic(
                """{"topics":[{"title":"topic","description":"description","keywords":[],"sourceTurnIds":[2]}]}""",
                setOf(1L),
            )
        }.assertInvalid()

        val novelty =
            OfflineEvaluationTaskAdapters.parseNovelty(
                """{"decision":"FULLY_COVERED","coveredOrganizedIds":["m1"]}""",
                setOf("m1"),
            )
        assertEquals(DreamingNoveltyDecision.FULLY_COVERED, novelty.decision)
        assertEquals(listOf("m1"), OfflineEvaluationTaskAdapters.parseDreamingCandidates("""{"candidate_ids":["m1","m1"]}""", setOf("m1")))
        assertEquals(listOf("r1"), OfflineEvaluationTaskAdapters.parseConversationNeed("""{"memoryIds":["r1","r1"]}""", setOf("r1")))

        val insights =
            OfflineEvaluationTaskAdapters.parseReflection(
                """{"insights":[{"content":"insight","sourceTurnIds":[1,1]}]}""",
                OfflineEvaluationTestFixtures.turns(1L),
            )
        assertEquals(listOf(1L), insights.single().sourceTurnIds)
        assertFailsWith<LlmException> {
            OfflineEvaluationTaskAdapters.parseReflection(
                """{"insights":[{"content":"insight","sourceTurnIds":[2]}]}""",
                OfflineEvaluationTestFixtures.turns(1L),
            )
        }.assertInvalid()
    }

    @Test
    fun `eval output encodes json without exposing a new protocol`() {
        val output = EvalOutput.of(buildJsonObject { put("ok", true) })
        assertEquals("""{"ok":true}""", output.toJsonString())
        assertEquals("""{"ok":true}""", EvalOutput.encode(output))
        assertEquals("""{"memory_ids":["m1"]}""", EvalOutput.encode(mapOf("memory_ids" to listOf("m1"))))
    }

    private fun LlmException.assertInvalid() = assertEquals(LlmErrorCode.INVALID_RESPONSE, errorCode)
}
