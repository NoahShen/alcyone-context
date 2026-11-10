package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonDreamingEvidenceParserTest {
    private val novelty = JsonDreamingNoveltyResponseParser()
    private val candidates = JsonDreamingEvidenceCandidateParser()

    @Test
    fun `novelty accepts needs empty and fully covered arrays`() {
        val needs = novelty.parse("""{"decision":"NEEDS_EVIDENCE","coveredOrganizedIds":[]}""")
        val covered = novelty.parse("""{"decision":"FULLY_COVERED","coveredOrganizedIds":["O-1","O-1"]}""")

        assertEquals(DreamingNoveltyDecision.NEEDS_EVIDENCE, needs.decision)
        assertEquals(emptyList(), needs.coveredOrganizedIds)
        assertEquals(DreamingNoveltyDecision.FULLY_COVERED, covered.decision)
        assertEquals(listOf("O-1"), covered.coveredOrganizedIds)
    }

    @Test
    fun `novelty rejects unknown missing and wrong field types`() {
        listOf(
            """{"decision":"NEEDS_EVIDENCE"}""",
            """{"decision":"NEEDS_EVIDENCE","coveredOrganizedIds":[],"extra":1}""",
            """{"decision":"UNKNOWN","coveredOrganizedIds":[]}""",
            """{"decision":null,"coveredOrganizedIds":[]}""",
            """{"decision":"NEEDS_EVIDENCE","coveredOrganizedIds":"O-1"}""",
            """{"decision":"NEEDS_EVIDENCE","coveredOrganizedIds":[1]}""",
        ).forEach(::assertNoveltyInvalid)
    }

    @Test
    fun `novelty rejects duplicate keys fences and surrounding text`() {
        listOf(
            """{"decision":"NEEDS_EVIDENCE","decision":"FULLY_COVERED","coveredOrganizedIds":[]}""",
            "```json\n{\"decision\":\"NEEDS_EVIDENCE\",\"coveredOrganizedIds\":[]}\n```",
            "prefix {\"decision\":\"NEEDS_EVIDENCE\",\"coveredOrganizedIds\":[]}",
            "{\"decision\":\"NEEDS_EVIDENCE\",\"coveredOrganizedIds\":[]} suffix",
            "",
        ).forEach(::assertNoveltyInvalid)
    }

    @Test
    fun `candidate parser accepts empty and duplicate ids`() {
        assertEquals(emptyList(), candidates.parse("""{"candidate_ids":[]}"""))
        assertEquals(listOf("R-1", "R-1", "R-2"), candidates.parse("""{"candidate_ids":["R-1","R-1","R-2"]}"""))
    }

    @Test
    fun `candidate parser rejects unknown missing type blank duplicate and malformed json`() {
        listOf(
            """{"candidate_ids":[],"extra":1}""",
            """{"ids":[]}""",
            """{"candidate_ids":"R-1"}""",
            """{"candidate_ids":[1]}""",
            """{"candidate_ids":[""]}""",
            """{"candidate_ids":[" "]}""",
            """{"candidate_ids":[],"candidate_ids":["R-1"]}""",
            "```json\n{\"candidate_ids\":[]}\n```",
            "{\"candidate_ids\":[",
        ).forEach(::assertCandidateInvalid)
    }

    @Test
    fun `parser errors are safe and have no cause`() {
        val marker = "SECRET-EVIDENCE-BODY"
        val failure = assertFailsWith<LlmException> { novelty.parse("""{"decision":"$marker"}""") }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
        assertTrue(!failure.message.orEmpty().contains(marker), failure.message.orEmpty())
    }

    private fun assertNoveltyInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { novelty.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
    }

    private fun assertCandidateInvalid(payload: String) {
        val failure = assertFailsWith<LlmException>(payload) { candidates.parse(payload) }
        assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        assertNull(failure.cause)
    }
}
