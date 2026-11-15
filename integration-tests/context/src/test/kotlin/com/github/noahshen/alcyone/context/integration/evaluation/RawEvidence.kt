package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionPrompt
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapPrompt
import com.github.noahshen.alcyone.context.memory.core.organization.GovernancePrompt
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidatePrompt
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchPrompt
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.RecordedLlmCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Result of an evidence write; `failure` is set when the evidence directory could not be written. */
internal data class LogicalEvidenceWrite(
    val refs: Map<String, RawEvidenceRef>,
    val failure: String? = null,
)

/**
 * Write evidence without ever replacing a production outcome. A real cancellation still propagates; any other
 * writer failure is reported through [LogicalEvidenceWrite.failure] so the caller can surface it explicitly.
 */
internal fun writeLogicalEvidenceSafely(
    directory: Path,
    logical: List<RecordedLlmCall>,
): LogicalEvidenceWrite =
    try {
        LogicalEvidenceWrite(writeLogicalEvidence(directory, logical))
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        LogicalEvidenceWrite(emptyMap(), failure::class.simpleName ?: "EvidenceWriteFailure")
    }

/**
 * Persist one raw exchange artifact per logical call. The file name uses the callId, never an attempt index,
 * so retries remain distinguishable. The returned reference is the only production evidence embedded in
 * `result.json`.
 */
internal fun writeLogicalEvidence(
    directory: Path,
    logical: List<RecordedLlmCall>,
): Map<String, RawEvidenceRef> {
    if (logical.isEmpty()) return emptyMap()
    val rawDirectory = directory.resolve("raw")
    Files.createDirectories(rawDirectory)
    return logical.associate { call ->
        val name = "production-call-${call.callId}.json"
        val path = rawDirectory.resolve(name)
        writeJson(
            path,
            buildJsonObject {
                put("callId", call.callId)
                put("kind", "PRODUCTION")
                put("state", call.state.name)
                put("startedAt", call.startedAt.toString())
                put("elapsedMs", call.elapsedMs)
                put("request", call.requestJson())
                putJsonArray("exchanges") {
                    call.attempts.forEach { attempt ->
                        add(
                            buildJsonObject {
                                put("attempt", attempt.attempt)
                                put("httpStatus", attempt.httpStatus ?: -1)
                                put("rawResponse", attempt.rawResponse ?: "")
                                put("rawResponsePresent", attempt.rawResponse != null)
                                put("failureKind", attempt.failureKind ?: "")
                                put("errorType", attempt.errorType ?: "")
                            },
                        )
                    }
                }
                put("content", call.content ?: "")
                call.usage?.let {
                    putJsonObject("usage") {
                        it.inputTokens?.let { value -> put("inputTokens", value) }
                        it.outputTokens?.let { value -> put("outputTokens", value) }
                        it.totalTokens?.let { value -> put("totalTokens", value) }
                    }
                }
                put("finishReason", call.finishReason ?: "")
                putJsonObject("safeError") {
                    put("type", call.errorType ?: "")
                    put("code", call.errorCode ?: "")
                }
            },
        )
        call.callId to
            RawEvidenceRef(
                path = "raw/$name",
                sha256 = sha256File(path),
                state = call.state.name,
                attempts = call.attempts.size,
                missing = call.rawEvidenceMissing,
            )
    }
}

internal fun RecordedLlmCall.toCallRecord(
    model: String,
    productionOutput: JsonObject?,
    evidence: RawEvidenceRef?,
): CallRecord {
    val promptVersion =
        when (task) {
            LlmTask.EXTRACTION.name -> ExtractionPrompt.VERSION
            LlmTask.MEMORY_SEARCH.name -> MemorySearchPrompt.VERSION
            LlmTask.CANDIDATE_SCANNER.name -> OrganizationCandidatePrompt.VERSION
            LlmTask.GOVERNANCE.name -> GovernancePrompt.VERSION
            LlmTask.MEMORY_MAP.name -> MemoryMapPrompt.VERSION
            else -> "v1"
        }
    val parsed =
        content?.let { raw ->
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(raw)
                    .obj()
            }.getOrElse { buildJsonObject { put("rawContent", raw) } }
        } ?: JsonObject(emptyMap())
    val output =
        productionOutput?.let { result ->
            buildJsonObject {
                parsed.forEach { (k, v) -> put(k, v) }
                put("persistedProductionResult", result)
            }
        }
            ?: parsed
    val requestText = messages.joinToString("\n") { "${it.role}\n${it.content}" }
    return CallRecord(
        task,
        "production-$task",
        model,
        promptVersion,
        "v1",
        sha256(requestText),
        sha256(requestText + responseFormat + (schema ?: "")),
        messages.sumOf { (it.content.length + 3) / 4 },
        usage,
        elapsedMs,
        maxOf(1, attempts.size),
        finishReason,
        finishReason == "length",
        output,
        evidence,
        redactOutput = true,
    )
}

internal fun sha256File(path: Path): String = sha256Bytes(Files.readAllBytes(path))

internal fun sha256Bytes(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
