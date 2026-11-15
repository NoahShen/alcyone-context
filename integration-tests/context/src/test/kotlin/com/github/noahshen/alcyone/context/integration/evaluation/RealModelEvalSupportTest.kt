package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.runtime.evaluation.OfflineEvaluationTestFixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RealModelEvalSupportTest {
    private val root =
        Path
            .of("")
            .toAbsolutePath()
            .parent.parent

    @Test fun `canonical input fixtures convert to all production shapes`() {
        val samples = loadEvalSamples(root)
        assertEquals(72, samples.size)
        samples.forEach { sample ->
            when (sample.task) {
                "EXTRACTION" -> sample.input.toExtractionContext()
                "CANDIDATE_SCANNER" -> {
                    sample.input.toRawMemory()
                    sample.input.toOrganizedMemories("candidates")
                }
                "GOVERNANCE" -> {
                    sample.input.toRawMemory()
                    sample.input.toOrganizedMemories("candidates")
                }
                "MEMORY_SEARCH" -> {
                    sample.input.toOrganizedMemories("candidates")
                    sample.input.toQuery()
                }
                "MEMORY_MAP" -> sample.input.toMapSnapshot()
                "DREAMING" -> {
                    sample.input.toTurns()
                    sample.input.toRawMemories()
                    sample.input.toOrganizedMemories()
                }
            }
        }
        val system =
            samples
                .first { it.id == "ext-012" }
                .input
                .toExtractionContext()
                .turns
                .first()
        assertEquals(com.github.noahshen.alcyone.context.memory.api.ChatRole.USER, system.messages.single().role)
        assertEquals("Build diagnostics are sample-only.", system.messages.single().content)
    }

    @Test fun `strict parsers keep identity and rerank limit`() {
        assertFailsWith<Exception> { parseIds("{\"candidate_ids\":[\"external\"]}", setOf("internal")) }
        assertEquals(
            listOf("one"),
            parseIds("{\"memory_ids\":[\"one\",\"one\",\"two\"]}", setOf("one", "two"), rerank = true, limit = 1).strings("rankedIds"),
        )
        val raw = OfflineEvaluationTestFixtures.raw("raw-1")
        assertEquals(
            "NEW",
            parseGovernance("{\"action\":\"NEW\",\"content\":\"grounded\",\"domains\":[\"project\"]}", raw, emptyList())
                .string("action"),
        )
    }

    @Test fun `safe evaluation output has explicit non semantic status`() {
        val sample = loadEvalSamples(root).first()
        val result = SampleResult(sample, checks = listOf(Check("SEMANTIC", Verdict.REVIEW_REQUIRED, "Needs human review")))
        assertEquals("REVIEW_REQUIRED", result.status)
        assertEquals(
            "REVIEW_REQUIRED",
            result
                .json()["checks"]!!
                .jsonArray[0]
                .jsonObject["verdict"]!!
                .jsonPrimitive.content,
        )
    }
}
