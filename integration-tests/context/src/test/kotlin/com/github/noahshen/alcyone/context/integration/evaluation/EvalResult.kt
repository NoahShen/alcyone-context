package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.OfflineEvaluationTaskAdapters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

internal enum class Verdict { PASS, FAIL, REVIEW_REQUIRED, NOT_RUN }

internal data class Check(
    val id: String,
    val verdict: Verdict,
    val evidence: String,
)

internal data class DreamingObservation(
    val rawMemories: List<com.github.noahshen.alcyone.context.memory.api.RawMemory>,
    val commitCompleted: Boolean,
    val persistedCheckpoint: Long,
    val pendingCount: Int,
    val inputPendingCount: Int,
    val conversationTurnCount: Int,
    val topics: List<TopicObservation>,
    val insights: List<InsightObservation>,
    val evidenceLinks: Map<String, Set<String>>,
    val inputEvidenceLinks: Map<String, Set<String>>,
    val calls: List<CallRecord>,
    val maxPersistedTurnId: Long,
)

internal data class TopicObservation(
    val title: String,
    val turnIds: List<Long>,
)

internal data class InsightObservation(
    val content: String,
    val sourceTurnIds: List<Long>,
)

internal data class RawEvidenceRef(
    val path: String,
    val sha256: String,
    val state: String,
    val attempts: Int,
    val missing: Boolean,
)

internal data class CallRecord(
    val task: String,
    val profile: String,
    val model: String,
    val promptVersion: String,
    val schemaVersion: String,
    val promptSha256: String,
    val requestSha256: String? = null,
    val estimatedInputTokens: Int,
    val usage: TokenUsage?,
    val latencyMs: Long,
    val attempts: Int,
    val finishReason: String?,
    val truncated: Boolean,
    val output: JsonObject,
    val rawEvidence: RawEvidenceRef? = null,
    val redactOutput: Boolean = false,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("task", task)
            put("profile", profile)
            put("model", model)
            put("promptVersion", promptVersion)
            put("schemaVersion", schemaVersion)
            put("promptSha256", promptSha256)
            requestSha256?.let { put("requestSha256", it) }
            put("estimatedInputTokens", estimatedInputTokens)
            put("latencyMs", latencyMs)
            put("attempts", attempts)
            finishReason?.let { put("finishReason", it) }
            put("truncated", truncated)
            usage?.let {
                putJsonObject("usage") {
                    it.inputTokens?.let { value ->
                        put("inputTokens", value)
                    }
                    it.outputTokens?.let { value ->
                        put("outputTokens", value)
                    }
                    it.totalTokens?.let { value -> put("totalTokens", value) }
                }
            }
            rawEvidence?.let {
                putJsonObject("rawEvidence") {
                    put("path", it.path)
                    put("sha256", it.sha256)
                    put("state", it.state)
                    put("attempts", it.attempts)
                    put("missing", it.missing)
                }
            }
            if (redactOutput) {
                put("outputRedacted", true)
            } else {
                put("output", output)
            }
        }
}

internal data class SampleResult(
    val sample: EvalSample,
    val calls: List<CallRecord> = emptyList(),
    val checks: List<Check> = emptyList(),
    val error: String? = null,
) {
    val status: String get() =
        when {
            calls.isEmpty() && checks.isEmpty() && error == null -> "NOT_RUN"
            error != null -> "PROTOCOL_FAILURE"
            checks.any { it.verdict == Verdict.FAIL } -> "FAIL"
            checks.any { it.verdict == Verdict.REVIEW_REQUIRED } -> "REVIEW_REQUIRED"
            checks.any { it.verdict == Verdict.NOT_RUN } -> "NOT_RUN"
            else -> "PASS"
        }

    fun json(): JsonObject =
        buildJsonObject {
            put("id", sample.id)
            put("task", sample.task)
            put("status", status)
            putJsonArray("riskTags") { sample.riskTags.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            error?.let { put("safeError", it) }
            putJsonArray("calls") { calls.forEach { add(it.json()) } }
            putJsonArray("checks") {
                checks.forEach { check ->
                    add(
                        buildJsonObject {
                            put("id", check.id)
                            put("verdict", check.verdict.name)
                            put("evidence", check.evidence)
                        },
                    )
                }
            }
        }
}

internal class EvalExecutor(
    private val config: RealModelConfig?,
) {
    suspend fun call(
        sample: EvalSample,
        task: com.github.noahshen.alcyone.context.memory.core.llm.LlmTask,
        request: LlmRequest,
        promptVersion: String,
        schemaVersion: String,
        parse: (String) -> JsonObject,
    ): CallRecord {
        val client = requireNotNull(config) { "Real model execution requires -PcontextEvalLive=true" }.llmClient
        val start = System.nanoTime()
        val responseFormat = requireNotNull(config).responseFormat
        val profileRequest =
            request.copy(
                responseFormat = responseFormat,
                schema =
                    if (responseFormat ==
                        LlmResponseFormat.JSON_SCHEMA
                    ) {
                        request.schema
                    } else {
                        null
                    },
            )
        val response = client.complete(profileRequest)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        check(response.finishReason != "length") { "Model output was truncated; do not score a partial answer" }
        val output = parse(response.content)
        return CallRecord(
            task.name,
            requireNotNull(config).profileName,
            requireNotNull(config).model,
            promptVersion,
            schemaVersion,
            sha256(request.messages.joinToString("\n") { "${it.role}\n${it.content}" }),
            sha256(request.toString()),
            request.messages.sumOf { (it.content.length + 3) / 4 },
            response.usage,
            elapsed,
            1,
            response.finishReason,
            response.finishReason == "length",
            output,
        )
    }
}

internal fun parseIds(
    content: String,
    allowed: Set<String>,
    rerank: Boolean = false,
): JsonObject =
    if (rerank) {
        val ids = OfflineEvaluationTaskAdapters.parseRerank(content, allowed)
        kotlinx.serialization.json.buildJsonObject { put("rankedIds", ids.getValue("memory_ids")) }
    } else {
        val ids = OfflineEvaluationTaskAdapters.parseScanner(content, allowed)
        kotlinx.serialization.json.buildJsonObject { put("candidateIds", ids.getValue("candidate_ids")) }
    }

internal fun parseIds(
    content: String,
    allowed: Set<String>,
    rerank: Boolean = false,
    limit: Int,
): JsonObject =
    if (rerank) {
        val ids = OfflineEvaluationTaskAdapters.parseRerank(content, allowed).strings("memory_ids").take(limit)
        kotlinx.serialization.json.buildJsonObject { put("rankedIds", kotlinx.serialization.json.JsonArray(ids.map(::JsonPrimitive))) }
    } else {
        parseIds(content, allowed)
    }

internal fun parseExtraction(
    content: String,
    context: com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext,
): JsonObject =
    OfflineEvaluationTaskAdapters.parseExtraction(content, context).let { output ->
        kotlinx.serialization.json.buildJsonObject {
            put("memories", output.getValue("memories"))
            put(
                "sourceTurnIds",
                kotlinx.serialization.json.JsonArray(
                    output
                        .array("memories")
                        .flatMap {
                            it.obj().array("sources")
                        }.map { JsonPrimitive(it.obj().string("sessionTurnId")) },
                ),
            )
        }
    }

internal fun parseGovernance(
    content: String,
    raw: com.github.noahshen.alcyone.context.memory.api.RawMemory,
    candidates: List<com.github.noahshen.alcyone.context.memory.api.OrganizedMemory>,
): JsonObject = OfflineEvaluationTaskAdapters.parseGovernance(content, raw, candidates).obj("decision")

internal fun parseMap(
    content: String,
    snapshot: com.github.noahshen.alcyone.context.memory.core.map.MemoryMapSnapshot,
): JsonObject = OfflineEvaluationTaskAdapters.parseMap(content, snapshot)

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
