package com.github.noahshen.alcyone.context.integration.evaluation

import kotlinx.coroutines.CancellationException

/** Model/profile identity written into every report and run artifact. */
internal data class EvaluationIdentity(
    val model: String,
    val profileName: String,
    val responseFormat: String,
    val temperature: Double?,
)

/**
 * Cancellation that still carries the logical calls captured before it. The raw exchange evidence has
 * already been written to disk, so a cancelled sample can never appear as "zero calls" next to CANCELLED
 * raw artifacts.
 */
internal class EvaluationStageCancelled(
    val calls: List<CallRecord>,
    cause: CancellationException?,
) : CancellationException("evaluation cancelled during sample") {
    init {
        if (cause != null) initCause(cause)
    }
}

/**
 * Non-cancellation stage failure that carries the calls captured before it, for the same reason as
 * [EvaluationStageCancelled]: a failed provider call must stay countable in the report.
 */
internal class EvaluationStageFailed(
    val calls: List<CallRecord>,
    cause: Exception,
) : Exception("evaluation stage failed", cause)
