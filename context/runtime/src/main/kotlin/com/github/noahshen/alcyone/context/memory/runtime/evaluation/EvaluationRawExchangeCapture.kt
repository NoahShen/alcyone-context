package com.github.noahshen.alcyone.context.memory.runtime.evaluation

import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import com.github.noahshen.alcyone.context.memory.runtime.llm.KtorLlmClient
import com.github.noahshen.alcyone.context.memory.runtime.llm.RawExchangeObserver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * CT31 evaluation-only capture of every HTTP attempt made for one logical `LlmClient.complete` call.
 *
 * The capture installs the internal raw-exchange seam only while [start] is active and restores the default
 * no-op observer in [stop]. Production runtime never constructs this class, so default behavior, retries,
 * exceptions and cancellation are unchanged. It contains no credentials or endpoint URL.
 */
class EvaluationRawExchangeCapture(
    delegate: LlmClient,
    private val callId: String,
) {
    private val ktor = delegate as? KtorLlmClient
    private val lock = Any()
    private val buffered = mutableListOf<RecordedRawExchange>()
    private val observer =
        RawExchangeObserver { observation ->
            // Defensive: a sink only accepts attempts for its own logical call.
            if (observation.callId == callId) {
                synchronized(lock) {
                    buffered +=
                        RecordedRawExchange(
                            attempt = observation.attempt,
                            httpStatus = observation.httpStatus,
                            rawResponse = observation.rawResponse,
                            failureKind = observation.failureKind?.name,
                            errorType = observation.errorType,
                        )
                }
            }
        }

    /** Register this call's sink under its callId; a missing Ktor transport simply yields no raw attempts. */
    fun start() {
        ktor?.installRawExchangeObserver(callId, observer)
    }

    /** Remove only this call's sink, so a finishing call can never silence a concurrent one. */
    fun stop() {
        ktor?.removeRawExchangeObserver(callId)
    }

    /**
     * Run the delegate inside this call's scope, so every HTTP attempt is attributed by coroutine context and
     * never by timing. The context element stays internal; callers outside this module use this helper.
     */
    suspend fun <T> withScope(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(
            com.github.noahshen.alcyone.context.memory.runtime.llm
                .RawExchangeCallId(callId),
        ) { block() }

    /** Return and clear attempts captured for this logical call. */
    fun drain(): List<RecordedRawExchange> =
        synchronized(lock) {
            buffered.toList().also { buffered.clear() }
        }
}

/** One HTTP attempt; `rawResponse` is null when the body was never received. */
data class RecordedRawExchange(
    val attempt: Int,
    val httpStatus: Int?,
    val rawResponse: String?,
    val failureKind: String?,
    val errorType: String?,
)

enum class EvaluationCallState {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/** Terminal record for one logical `LlmClient.complete` call, including failed and cancelled calls. */
data class RecordedLlmCall(
    val callId: String,
    val task: String,
    val messages: List<LlmMessage>,
    val responseFormat: String,
    val maxOutputTokens: Int?,
    val schema: String?,
    val state: EvaluationCallState,
    val startedAt: Instant,
    val elapsedMs: Long,
    val attempts: List<RecordedRawExchange>,
    val content: String?,
    val usage: TokenUsage?,
    val finishReason: String?,
    val errorType: String?,
    val errorCode: String?,
) {
    val rawEvidenceMissing: Boolean get() = attempts.none { it.rawResponse != null || it.httpStatus != null }

    fun requestJson(): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("task", task)
            put(
                "messages",
                JsonArray(
                    messages.map { message ->
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        }
                    },
                ),
            )
            put("responseFormat", responseFormat)
            put("maxOutputTokens", maxOutputTokens ?: -1)
            put("schema", schema ?: "")
        }
}
