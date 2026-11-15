package com.github.noahshen.alcyone.context.integration.evaluation

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

internal suspend fun semanticReview(
    sample: EvalSample,
    result: SampleResult,
    judge: SemanticJudge,
    artifact: JsonObject? = null,
): List<Check> {
    if (result.error !=
        null
    ) {
        return listOf(Check("semantic_review", Verdict.FAIL, "Protocol failure cannot receive semantic PASS; human review required"))
    }
    if (result.calls.isEmpty() &&
        result.checks.isEmpty()
    ) {
        return listOf(Check("semantic_review", Verdict.NOT_RUN, "No production or protocol execution was captured"))
    }
    if (sample.task in setOf("CANDIDATE_SCANNER", "MEMORY_SEARCH")) return emptyList()
    val output = result.calls.lastOrNull()?.output ?: JsonObject(emptyMap())
    val actual = artifact?.toString() ?: output.toString()
    val judgments =
        when (sample.task) {
            "EXTRACTION" ->
                listOf(
                    Triple(
                        "required_facts",
                        "Does actual preserve every required fact and attribution without fabrication?",
                        sample.oracle.strings("requiredFacts").joinToString("\n"),
                    ),
                    Triple(
                        "forbidden_facts",
                        "Does actual violate any forbidden fact, including by implication?",
                        sample.oracle.strings("forbiddenFacts").joinToString("\n"),
                    ),
                )
            "GOVERNANCE" ->
                listOf(
                    Triple(
                        "governance_content",
                        "Does the decision preserve required content, correction, ownership and temporal scope?",
                        sample.oracle.strings("requiredContentPoints").joinToString("\n"),
                    ),
                    Triple(
                        "governance_regressions",
                        "Does the output commit any forbidden regression?",
                        sample.oracle.strings("forbiddenRegressions").joinToString("\n"),
                    ),
                )
            "MEMORY_MAP" ->
                listOf(
                    Triple(
                        "map_navigation",
                        "Does the Map faithfully summarize all required facts and domain navigation without fabrication?",
                        sample.oracle.strings("requiredFacts").joinToString("\n"),
                    ),
                )
            "DREAMING" ->
                listOf(
                    Triple(
                        "dreaming_insights",
                        "Are the committed insights grounded, faithful and specific to the expected evidence?",
                        sample.oracle.strings("expectedInsights").joinToString("\n"),
                    ),
                )
            else -> emptyList()
        }
    return judgments.map { (name, question, expected) ->
        try {
            val verdict = judge.judge(name, question, actual, expected, result.json().getValue("checks").toString())
            Check("judge_$name", if (verdict.pass) Verdict.PASS else Verdict.FAIL, verdict.reason)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Check("judge_$name", Verdict.REVIEW_REQUIRED, "JUDGE_UNAVAILABLE: ${failure::class.simpleName}; human review required")
        }
    }
}
