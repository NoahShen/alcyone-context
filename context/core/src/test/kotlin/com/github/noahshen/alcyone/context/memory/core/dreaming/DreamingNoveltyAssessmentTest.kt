package com.github.noahshen.alcyone.context.memory.core.dreaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DreamingNoveltyAssessmentTest {
    @Test
    fun `both decisions and defensive id copies are preserved`() {
        val ids = mutableListOf("O-1", "O-1")
        val assessment = DreamingNoveltyAssessment(DreamingNoveltyDecision.FULLY_COVERED, ids)
        ids += "O-2"

        assertEquals(DreamingNoveltyDecision.FULLY_COVERED, assessment.decision)
        assertEquals(listOf("O-1", "O-1"), assessment.coveredOrganizedIds)
        assertFailsWith<UnsupportedOperationException> { (assessment.coveredOrganizedIds as MutableList).add("O-2") }
    }

    @Test
    fun `validator normalizes duplicate fully covered ids`() {
        val validated =
            DreamingNoveltyValidator.validate(
                DreamingNoveltyAssessment(DreamingNoveltyDecision.FULLY_COVERED, listOf("O-1", "O-2", "O-1")),
                setOf("O-1", "O-2"),
            )

        assertEquals(listOf("O-1", "O-2"), validated.coveredOrganizedIds)
    }

    @Test
    fun `needs evidence must not contain covered ids`() {
        assertInvalid(DreamingNoveltyDecision.NEEDS_EVIDENCE, listOf("O-1"))
    }

    @Test
    fun `fully covered requires nonempty relevant subset and rejects blank or unknown ids`() {
        assertInvalid(DreamingNoveltyDecision.FULLY_COVERED, emptyList())
        assertInvalid(DreamingNoveltyDecision.FULLY_COVERED, listOf("O-UNKNOWN"))
        assertInvalid(DreamingNoveltyDecision.FULLY_COVERED, listOf(" "))
        assertEquals(
            listOf("O-1"),
            DreamingNoveltyValidator
                .validate(DreamingNoveltyAssessment(DreamingNoveltyDecision.FULLY_COVERED, listOf("O-1")), setOf("O-1"))
                .coveredOrganizedIds,
        )
    }

    @Test
    fun `toString does not expose covered ids`() {
        val text = DreamingNoveltyAssessment(DreamingNoveltyDecision.FULLY_COVERED, listOf("SECRET-ID")).toString()
        assertFalse(text.contains("SECRET-ID"), text)
    }

    private fun assertInvalid(
        decision: DreamingNoveltyDecision,
        ids: List<String>,
    ) {
        assertFailsWith<DreamingNoveltyValidationException> {
            DreamingNoveltyValidator.validate(DreamingNoveltyAssessment(decision, ids), setOf("O-1", "O-2"))
        }
    }
}
