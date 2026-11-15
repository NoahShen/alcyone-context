package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest

/**
 * CT31 evaluation-only view of one HTTP attempt.
 *
 * This type is deliberately internal. It is never installed by the production runtime and contains neither
 * credentials nor the endpoint URL. The evaluation adapter is the only consumer allowed to persist the request
 * and raw provider response as evidence.
 */
internal data class RawExchangeObservation(
    val request: LlmRequest,
    val profile: String,
    val model: String,
    val attempt: Int,
    val httpStatus: Int?,
    val rawResponse: String?,
    val failureKind: RawExchangeFailureKind?,
    val errorType: String?,
    /** Logical `complete` this attempt belongs to; null when no evaluation scope is active. */
    val callId: String? = null,
)

/**
 * Evaluation-only coroutine scope tag. The logical call establishes a stable callId before the delegate runs and
 * every HTTP attempt reads it back from the inherited coroutine context, so attribution never depends on timing.
 */
internal class RawExchangeCallId(
    val value: String,
) : kotlin.coroutines.AbstractCoroutineContextElement(RawExchangeCallId) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RawExchangeCallId>
}

internal enum class RawExchangeFailureKind {
    TRANSPORT,
    RESPONSE_READ,
    CANCELLATION,
    HTTP_STATUS,
    INVALID_RESPONSE,
}

internal fun interface RawExchangeObserver {
    fun onExchange(observation: RawExchangeObservation)
}
