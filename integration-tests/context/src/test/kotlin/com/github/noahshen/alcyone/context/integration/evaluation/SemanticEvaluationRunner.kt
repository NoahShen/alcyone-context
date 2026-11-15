@file:Suppress("ktlint:standard:max-line-length")

package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionPrompt
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapPrompt
import com.github.noahshen.alcyone.context.memory.core.organization.GovernancePrompt
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidatePrompt
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.OfflineEvaluationTaskAdapters as OfflineTaskAdapters

/** Offline oracle checks, not an LLM judge. Free-text agreement remains explicitly reviewable. */
internal class SemanticEvaluationRunner(
    private val executor: EvalExecutor,
) {
    suspend fun run(sample: EvalSample): SampleResult =
        try {
            when (sample.task) {
                "EXTRACTION" -> extraction(sample)
                "MEMORY_SEARCH" -> retrieval(sample)
                "GOVERNANCE" -> governance(sample)
                "CANDIDATE_SCANNER" -> scanner(sample)
                "MEMORY_MAP" -> map(sample)
                else -> error("Unsupported semantic task")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            SampleResult(sample, error = safeError(failure))
        }

    private suspend fun extraction(sample: EvalSample): SampleResult {
        val context = sample.input.toExtractionContext()
        val call =
            executor.call(
                sample,
                LlmTask.EXTRACTION,
                evalRequest(LlmTask.EXTRACTION, ExtractionPrompt.messages(context.turns), ExtractionPrompt.schema()),
                ExtractionPrompt.VERSION,
                ExtractionPrompt.SCHEMA_VERSION,
            ) {
                parseExtraction(it, context)
            }
        val actual = call.output.array("memories").map { it.obj() }
        val checks = mutableListOf<Check>()
        val sources = actual.flatMap { it.array("sources") }.map { it.obj().string("sessionTurnId").toLong() }
        val targetIds = sample.oracle.longs("sourceTurnIds")
        checks +=
            Check(
                "protocol_nonempty",
                if (!sample.oracle.bool("expectedEmpty") &&
                    actual.isNotEmpty()
                ) {
                    Verdict.PASS
                } else if (actual.isEmpty()) {
                    Verdict.FAIL
                } else {
                    Verdict.PASS
                },
                "memories=${actual.size}",
            )
        checks +=
            Check(
                "source_turns_in_target",
                if (sources.all { it in targetIds }) Verdict.PASS else Verdict.FAIL,
                "sources=$sources target=$targetIds",
            )
        checks +=
            Check("source_coverage", Verdict.REVIEW_REQUIRED, "Expected target turns=$targetIds; per-fact coverage requires human review")
        if (sample.oracle.bool("expectedEmpty")) {
            checks +=
                Check("expected_empty", if (actual.isEmpty()) Verdict.PASS else Verdict.FAIL, "memories=${actual.size}")
        }
        val content = actual.joinToString("\n") { it.string("content") }
        if (sample.oracle.strings("requiredFacts").isNotEmpty()) {
            checks +=
                textReview("required_facts", sample.oracle.strings("requiredFacts"), content)
        }
        checks +=
            Check(
                "forbidden_fact_review",
                Verdict.REVIEW_REQUIRED,
                "Negations, scope and ownership require human review; substring alone is insufficient",
            )
        return SampleResult(sample, listOf(call), checks)
    }

    private suspend fun scanner(sample: EvalSample): SampleResult {
        val raw = sample.input.toRawMemory()
        val candidates = sample.input.toOrganizedMemories("candidates")
        val ids = candidates.mapTo(linkedSetOf()) { it.id }
        val call =
            executor.call(
                sample,
                LlmTask.CANDIDATE_SCANNER,
                evalRequest(
                    LlmTask.CANDIDATE_SCANNER,
                    OrganizationCandidatePrompt.messages(raw, candidates),
                    OrganizationCandidatePrompt.schema(),
                ),
                OrganizationCandidatePrompt.VERSION,
                OrganizationCandidatePrompt.SCHEMA_VERSION,
            ) {
                OfflineTaskAdapters.parseCandidates(it, ids)
            }
        val actual = call.output.strings("candidate_ids").toSet()
        val expected = sample.oracle.strings("relevantIds")
        val forbidden = sample.oracle.strings("forbiddenIds")
        val critical = sample.oracle.strings("criticalIds")
        return SampleResult(
            sample,
            listOf(call),
            listOf(
                Check(
                    "relevant_recall",
                    if (actual.containsAll(expected)) Verdict.PASS else Verdict.FAIL,
                    "expected=$expected actual=$actual",
                ),
                Check(
                    "forbidden_ids",
                    if (actual.none { it in forbidden }) Verdict.PASS else Verdict.FAIL,
                    "forbidden=$forbidden actual=$actual",
                ),
                Check(
                    "critical_recall",
                    if (actual.containsAll(critical)) Verdict.PASS else Verdict.FAIL,
                    "critical=$critical actual=$actual",
                ),
                Check(
                    "expected_empty",
                    if (sample.oracle.bool("expectedEmpty") ==
                        actual.isEmpty()
                    ) {
                        Verdict.PASS
                    } else {
                        Verdict.FAIL
                    },
                    "expectedEmpty=${sample.oracle.bool("expectedEmpty")}",
                ),
            ),
        )
    }

    private suspend fun retrieval(sample: EvalSample): SampleResult {
        val candidates = sample.input.toOrganizedMemories("candidates")
        val allowed = candidates.mapTo(linkedSetOf()) { it.id }
        val checks = mutableListOf<Check>()
        val calls = mutableListOf<CallRecord>()
        var scannerIds = emptyList<String>()
        if (candidates.isNotEmpty()) {
            val call =
                executor.call(
                    sample,
                    LlmTask.MEMORY_SEARCH,
                    evalRequest(
                        LlmTask.MEMORY_SEARCH,
                        MemorySearchPrompt.scannerMessages(sample.input.toQuery(), candidates),
                        MemorySearchPrompt.scannerSchema(),
                    ),
                    MemorySearchPrompt.VERSION,
                    MemorySearchPrompt.SCHEMA_VERSION,
                ) {
                    parseIds(it, allowed)
                }
            calls += call
            scannerIds = call.output.strings("candidateIds")
            val expected = sample.oracle.obj("scanner").strings("expectedIds")
            val forbidden = sample.oracle.obj("scanner").strings("forbiddenIds")
            checks +=
                Check(
                    "scanner_required",
                    if (scannerIds.containsAll(expected)) Verdict.PASS else Verdict.FAIL,
                    "expected=$expected actual=$scannerIds",
                )
            checks +=
                Check(
                    "scanner_forbidden",
                    if (scannerIds.none {
                            it in forbidden
                        }
                    ) {
                        Verdict.PASS
                    } else {
                        Verdict.FAIL
                    },
                    "forbidden=$forbidden actual=$scannerIds",
                )
        }
        val rerankCandidates = candidates.filter { it.id in scannerIds }
        var ranked = emptyList<String>()
        if (rerankCandidates.isNotEmpty()) {
            val k = sample.oracle.int("k")
            val call =
                executor.call(
                    sample,
                    LlmTask.MEMORY_SEARCH,
                    evalRequest(
                        LlmTask.MEMORY_SEARCH,
                        MemorySearchPrompt.rerankMessages(sample.input.toQuery(), rerankCandidates, k),
                        MemorySearchPrompt.rerankSchema(),
                    ),
                    MemorySearchPrompt.VERSION,
                    MemorySearchPrompt.SCHEMA_VERSION,
                ) {
                    parseIds(it, rerankCandidates.mapTo(linkedSetOf()) { c -> c.id }, rerank = true, limit = k)
                }
            calls += call
            ranked = call.output.strings("rankedIds")
            val expected = sample.oracle.obj("rerank").strings("expectedOrder")
            checks += Check("rerank_order", if (ranked == expected) Verdict.PASS else Verdict.FAIL, "expected=$expected actual=$ranked")
            checks +=
                Check(
                    "rerank_forbidden",
                    if (ranked.none {
                            it in sample.oracle.obj("rerank").strings("forbiddenIds")
                        }
                    ) {
                        Verdict.PASS
                    } else {
                        Verdict.FAIL
                    },
                    "ranked=$ranked",
                )
            val critical = sample.oracle.strings("criticalIds")
            checks +=
                Check(
                    "critical_recall",
                    if (ranked.containsAll(critical)) Verdict.PASS else Verdict.FAIL,
                    "critical=$critical ranked=$ranked",
                )
            checks +=
                Check(
                    "ndcg_at_k",
                    if (ndcg(ranked, sample.oracle) >=
                        0.999
                    ) {
                        Verdict.PASS
                    } else {
                        Verdict.REVIEW_REQUIRED
                    },
                    "nDCG=${"%.4f".format(ndcg(ranked, sample.oracle))}; partial credit requires configured policy review",
                )
        }
        checks +=
            Check(
                "expected_empty",
                if (sample.oracle.bool("expectedEmpty") ==
                    (scannerIds.isEmpty() && ranked.isEmpty())
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "scanner=$scannerIds rerank=$ranked",
            )
        return SampleResult(sample, calls, checks)
    }

    private suspend fun governance(sample: EvalSample): SampleResult {
        val raw = sample.input.toRawMemory()
        val candidates = sample.input.toOrganizedMemories("candidates")
        val ids = candidates.mapTo(linkedSetOf()) { it.id }
        val calls = mutableListOf<CallRecord>()
        var decision: JsonObject? = null
        if (candidates.isNotEmpty()) {
            val scan =
                executor.call(
                    sample,
                    LlmTask.CANDIDATE_SCANNER,
                    evalRequest(
                        LlmTask.CANDIDATE_SCANNER,
                        OrganizationCandidatePrompt.messages(raw, candidates),
                        OrganizationCandidatePrompt.schema(),
                    ),
                    OrganizationCandidatePrompt.VERSION,
                    OrganizationCandidatePrompt.SCHEMA_VERSION,
                ) {
                    OfflineTaskAdapters.parseCandidates(it, ids)
                }
            calls += scan
            decision = if (scan.output.strings("candidate_ids").isEmpty()) buildJsonObject { put("action", "NEW") } else null
            if (decision == null) {
                val selected = candidates.filter { it.id in scan.output.strings("candidate_ids") }
                val govern =
                    executor.call(
                        sample,
                        LlmTask.GOVERNANCE,
                        evalRequest(LlmTask.GOVERNANCE, GovernancePrompt.messages(raw, selected), GovernancePrompt.schema()),
                        GovernancePrompt.VERSION,
                        GovernancePrompt.SCHEMA_VERSION,
                    ) {
                        parseGovernance(it, raw, selected)
                    }
                calls += govern
                decision = govern.output
            }
        } else {
            decision = buildJsonObject { put("action", "NEW") }
        }
        val actual = requireNotNull(decision)
        val action = actual.string("action")
        val target = actual.nullableString("targetMemoryId")
        val checks =
            mutableListOf(
                Check(
                    "action",
                    if (action ==
                        sample.oracle.string("expectedAction")
                    ) {
                        Verdict.PASS
                    } else {
                        Verdict.FAIL
                    },
                    "expected=${sample.oracle.string("expectedAction")} actual=$action",
                ),
            )
        checks +=
            Check(
                "target",
                if (target == null &&
                    sample.oracle.array("allowedTargets").isEmpty() ||
                    target in sample.oracle.strings("allowedTargets")
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "target=$target allowed=${sample.oracle.strings("allowedTargets")}",
            )
        checks +=
            Check(
                "forbidden_target",
                if (target == null ||
                    target !in sample.oracle.strings("forbiddenTargets")
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "target=$target",
            )
        actual.nullableString("content")?.let { content ->
            checks +=
                textReview("content_points", sample.oracle.strings("requiredContentPoints"), content)
        }
        checks +=
            Check(
                "content_regression_review",
                Verdict.REVIEW_REQUIRED,
                "Raw preservation, correction scope and forbidden regression require human review",
            )
        return SampleResult(sample, calls, checks)
    }

    private suspend fun map(sample: EvalSample): SampleResult {
        val snapshot = sample.input.toMapSnapshot()
        val call =
            executor.call(
                sample,
                LlmTask.MEMORY_MAP,
                evalRequest(LlmTask.MEMORY_MAP, MemoryMapPrompt.messages(snapshot), MemoryMapPrompt.schema()),
                MemoryMapPrompt.VERSION,
                MemoryMapPrompt.SCHEMA_VERSION,
            ) {
                parseMap(it, snapshot)
            }
        val domains = call.output.strings("domains")
        val checks =
            mutableListOf(
                Check(
                    "domain_coverage",
                    if (domains.containsAll(sample.oracle.strings("requiredDomains"))) Verdict.PASS else Verdict.FAIL,
                    "domains=$domains",
                ),
            )
        val navigation = call.output.string("navigation")
        val required = sample.oracle.strings("requiredMemoryIds")
        checks +=
            // Only ID presence is checked; `#id`/`@domain` are a presentation choice, not a production contract.
            Check("navigation_memory_ids", if (required.all { it in navigation }) Verdict.PASS else Verdict.FAIL, "required=$required")
        checks +=
            Check(
                "navigation_input_ids_only",
                if (Regex("[A-Za-z]+-[A-Za-z]+-\\d+").findAll(navigation).all {
                        it.value in
                            snapshot.memories.map { m -> m.id }
                    }
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "All structured candidate IDs must come from the input",
            )
        checks += Check("navigation_semantics", Verdict.REVIEW_REQUIRED, "Domain navigation and uncertainty language require human review")
        return SampleResult(sample, listOf(call), checks)
    }

    private fun textReview(
        id: String,
        expected: List<String>,
        content: String,
    ): Check =
        if (expected.all {
                normalize(it) in
                    normalize(content)
            }
        ) {
            Check(id, Verdict.PASS, "All oracle phrases matched literally")
        } else {
            Check(id, Verdict.REVIEW_REQUIRED, "Paraphrase, negation and fact ownership require human review")
        }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun ndcg(
        ranked: List<String>,
        oracle: JsonObject,
    ): Double {
        val gains = oracle.array("qrels").associate { it.obj().string("id") to it.obj().int("gain") }

        fun score(ids: List<String>) =
            ids
                .take(oracle.int("k"))
                .mapIndexed { i, id ->
                    ((gains[id] ?: 0).toDouble()) /
                        (Math.log((i + 2).toDouble()) / Math.log(2.0))
                }.sum()
        val ideal = gains.entries.sortedByDescending { it.value }.map { it.key }
        return if (score(ideal) == 0.0) 1.0 else score(ranked) / score(ideal)
    }
}
