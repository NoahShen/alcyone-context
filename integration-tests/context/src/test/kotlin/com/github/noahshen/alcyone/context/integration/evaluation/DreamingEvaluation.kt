package com.github.noahshen.alcyone.context.integration.evaluation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun evaluateDreaming(
    sample: EvalSample,
    observed: DreamingObservation,
    model: String,
): SampleResult {
    val calls = observed.calls
    val topics = observed.topics.map { it.turnIds }
    val insightSources = observed.insights.map { it.sourceTurnIds }
    val insightText = observed.insights.joinToString("\n") { it.content }
    val requiredTurns = sample.oracle.longs("requiredEvidenceTurnIds")
    val windowUpper =
        sample.input
            .toDreamingWindow()
            .last()
            .id
    val rawSources =
        observed.rawMemories.map { raw ->
            raw.sources
                .filterIsInstance<com.github.noahshen.alcyone.context.memory.api.MemorySourceRef.ConversationTurnRef>()
                .mapNotNull { it.sessionTurnId.toLongOrNull() }
        }
    val checks =
        mutableListOf(
            Check(
                "topic_protocol",
                if (topics.all {
                        it.isNotEmpty() &&
                            it.all { id ->
                                id in sample.input.longs("turnIds")
                            }
                    }
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "topics=$topics",
            ),
            Check(
                "insight_sources",
                if (insightSources.all {
                        it.isNotEmpty() &&
                            it.all { id ->
                                id in sample.input.longs("turnIds")
                            }
                    }
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "sources=$insightSources",
            ),
            Check(
                "evidence_coverage",
                if (requiredTurns.all { id ->
                        (topics + insightSources).any { id in it }
                    }
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "required=$requiredTurns topics=$topics insights=$insightSources",
            ),
            Check(
                "insight_persisted",
                if (observed.insights.size == observed.rawMemories.size) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "insights=${observed.insights.size} raw=${observed.rawMemories.size}",
            ),
            Check(
                "checkpoint_advanced",
                if (observed.commitCompleted &&
                    observed.persistedCheckpoint == windowUpper
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "checkpoint=${observed.persistedCheckpoint} expected=$windowUpper maxPersistedTurnId=${observed.maxPersistedTurnId}",
            ),
            Check(
                "pending_conserved",
                if (observed.pendingCount ==
                    observed.inputPendingCount + observed.rawMemories.size
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "initial=${observed.inputPendingCount} newDreaming=${observed.rawMemories.size} persistedPending=${observed.pendingCount}",
            ),
            Check(
                "raw_sources_attributed",
                if (rawSources.all { sources ->
                        sources.isNotEmpty() &&
                            sources.all { id -> id in sample.input.longs("turnIds") }
                    }
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "sources=$rawSources window=${sample.input.longs("turnIds")}",
            ),
            Check(
                "insight_sources_persisted",
                if (rawSources.map { it.toSet() }.sortedBy { it.toString() } ==
                    insightSources.map { it.toSet() }.sortedBy { it.toString() }
                ) {
                    Verdict.PASS
                } else {
                    Verdict.FAIL
                },
                "rawSources=$rawSources insightSources=$insightSources",
            ),
            Check("no_truncation", if (calls.none { it.truncated }) Verdict.PASS else Verdict.FAIL, "calls=${calls.size}"),
        )
    val forbidden =
        sample.oracle.strings("forbiddenInsightPatterns").flatMap { pattern ->
            Regex(pattern).findAll(insightText).map { it.value }.toList()
        }
    checks += Check("forbidden_patterns", if (forbidden.isEmpty()) Verdict.PASS else Verdict.FAIL, "matched=$forbidden")
    if (sample.oracle.bool("expectedEmpty")) {
        checks +=
            Check("expected_empty", if (observed.insights.isEmpty()) Verdict.PASS else Verdict.FAIL, "insights=${observed.insights.size}")
    } else {
        checks +=
            Check(
                "expected_empty",
                if (observed.insights.isNotEmpty()) Verdict.PASS else Verdict.FAIL,
                "insights=${observed.insights.size}",
            )
    }
    if (observed.insights.isNotEmpty() &&
        !sample.oracle.bool("expectedEmpty")
    ) {
        checks +=
            Check("insight_semantics", Verdict.REVIEW_REQUIRED, "Expected=${sample.oracle.strings("expectedInsights")}")
    }
    if ("H2_CRITICAL_CONFLICT_MISS" in sample.riskTags ||
        "H3_CORRECTION_REGRESSION" in sample.riskTags
    ) {
        checks +=
            Check(
                "conflict_and_correction_review",
                Verdict.REVIEW_REQUIRED,
                "Review conflict discovery, corrected content and replacement evidence",
            )
    }
    if ("DUPLICATE_REGRESSION" in
        sample.riskTags
    ) {
        checks +=
            Check(
                "duplicate_review",
                Verdict.REVIEW_REQUIRED,
                "Review repeated output against duplicateOf=${sample.oracle.strings("duplicateOf")}",
            )
    }
    return SampleResult(sample, calls, checks)
}

internal fun dreamingArtifact(observed: DreamingObservation): JsonObject =
    buildJsonObject {
        put("generatedMemories", observed.rawMemories.size)
        put("commitCompleted", observed.commitCompleted)
        put("persistedCheckpoint", observed.persistedCheckpoint)
        put("pendingCount", observed.pendingCount)
        put("conversationTurnCount", observed.conversationTurnCount)
        putJsonArray("topics") {
            observed.topics.forEach { topic ->
                add(
                    buildJsonObject {
                        put("title", topic.title)
                        putJsonArray("sourceTurnIds") { topic.turnIds.forEach { add(JsonPrimitive(it)) } }
                    },
                )
            }
        }
        putJsonArray("insights") {
            observed.insights.forEach { insight ->
                add(
                    buildJsonObject {
                        put("content", insight.content)
                        putJsonArray("sourceTurnIds") { insight.sourceTurnIds.forEach { add(JsonPrimitive(it)) } }
                    },
                )
            }
        }
        putJsonArray("rawMemories") {
            observed.rawMemories.forEach { raw ->
                add(
                    buildJsonObject {
                        put("id", raw.id)
                        put("content", raw.content)
                        put("createdAt", raw.createdAt.toString())
                        put(
                            "sourceTurnIds",
                            raw.sources
                                .filterIsInstance<com.github.noahshen.alcyone.context.memory.api.MemorySourceRef.ConversationTurnRef>()
                                .map { it.sessionTurnId }
                                .toString(),
                        )
                    },
                )
            }
        }
        putJsonArray("inputEvidenceLinks") {
            observed.inputEvidenceLinks.forEach { (raw, organized) ->
                organized.forEach {
                    add(
                        buildJsonObject {
                            put("rawId", raw)
                            put("organizedId", it)
                        },
                    )
                }
            }
        }
        putJsonArray("evidenceLinks") {
            observed.evidenceLinks.forEach { (raw, organized) ->
                organized.forEach {
                    add(
                        buildJsonObject {
                            put("rawId", raw)
                            put("organizedId", it)
                        },
                    )
                }
            }
        }
    }
