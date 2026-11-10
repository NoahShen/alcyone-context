package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingReflectionPrompt
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingReflectionValidationException
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingReflectionValidator
import com.github.noahshen.alcyone.context.memory.core.dreaming.ValidatedDreamingInsight
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudget
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Collections

/** CT25 Reflection Stage：FULLY_COVERED 跳过，NEEDS_REFLECTION 每 Topic 恰调用一次。 */
internal class LlmDreamingReflectionStage(
    private val llmClient: LlmClient,
    llmConfig: LlmConfig,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val parser: JsonDreamingReflectionParser = JsonDreamingReflectionParser(),
) {
    private val binding = resolveDreamingReflectionBinding(llmConfig)
    private val reflectionSchema: String? =
        if (binding.reflectionProfile.jsonMode == LlmResponseFormat.JSON_SCHEMA) {
            DreamingReflectionPrompt.reflectionSchema()
        } else {
            null
        }

    suspend fun reflect(evidence: DreamingBatchEvidence): List<ValidatedDreamingInsight> {
        val result = mutableListOf<ValidatedDreamingInsight>()
        evidence.topics.forEach { topic ->
            when (topic.decision) {
                DreamingEvidenceDecision.FULLY_COVERED -> Unit
                DreamingEvidenceDecision.NEEDS_REFLECTION -> result += reflectTopic(topic)
            }
        }
        return Collections.unmodifiableList(result)
    }

    internal fun reflectionRequestFor(topic: DreamingTopicEvidence): LlmRequest =
        LlmRequest(
            task = LlmTask.DREAMING_REFLECTION,
            messages =
                DreamingReflectionPrompt.reflectionMessages(
                    topic = topic.topic,
                    newConversationTurns = topic.newConversationTurns,
                    organizedMemories = topic.organizedMemories,
                    rawMemories = topic.rawMemories,
                    historicalConversationTurns = topic.historicalConversationTurns,
                ),
            responseFormat = binding.reflectionProfile.jsonMode,
            maxOutputTokens = binding.reflectionTask.maxOutputTokens,
            schema = reflectionSchema,
        )

    internal fun effectiveReflectionSchema(): String? = reflectionSchema

    private suspend fun reflectTopic(topic: DreamingTopicEvidence): List<ValidatedDreamingInsight> {
        currentCoroutineContext().ensureActive()
        val request = reflectionRequestFor(topic)
        try {
            LlmBudget.requireWithinBudget(
                request = request,
                schema = reflectionSchema,
                task = binding.reflectionTask,
                profile = binding.reflectionProfile,
                safetyMarginPercent = binding.safetyMarginPercent,
                estimator = estimator,
            )
        } catch (failure: LlmException) {
            if (failure.errorCode == LlmErrorCode.LOCAL_BUDGET_EXCEEDED) {
                throw reflectionFailure(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.message.orEmpty())
            }
            throw failure
        }

        // Adapter/Client failures already carry their own immutable classification; never re-home them.
        val response = llmClient.complete(request)
        if (response.finishReason == FINISH_REASON_LENGTH) {
            throw reflectionFailure(LlmErrorCode.OUTPUT_TRUNCATED, "dreaming reflection output was truncated")
        }
        val candidates =
            try {
                parser.parse(response.content)
            } catch (failure: LlmException) {
                if (failure.errorCode == LlmErrorCode.INVALID_RESPONSE) throw invalidResponse()
                throw failure
            }
        val topicsTurns = topic.newConversationTurns + topic.historicalConversationTurns
        return try {
            DreamingReflectionValidator.validate(candidates, topicsTurns)
        } catch (_: DreamingReflectionValidationException) {
            throw invalidResponse()
        }
    }

    private fun invalidResponse(): LlmException = reflectionFailure(LlmErrorCode.INVALID_RESPONSE, "invalid dreaming reflection response")

    private fun reflectionFailure(
        code: LlmErrorCode,
        message: String,
    ): LlmException =
        LlmException(
            errorCode = code,
            message = message,
            task = LlmTask.DREAMING_REFLECTION,
            profile = binding.reflectionProfile.name,
            model = binding.reflectionProfile.model,
        )

    private companion object {
        const val FINISH_REASON_LENGTH = "length"
    }
}
