package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingConversationNeed
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingConversationNeedValidationException
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingConversationNeedValidator
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingEvidencePrompt
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision
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

/** CT24 组合 Stage：CT23 Topic → B1 Evidence candidate → Conversation Need/trace → final Evidence. */
internal class LlmDreamingEvidenceStage(
    database: ContextDatabase,
    private val llmClient: LlmClient,
    llmConfig: LlmConfig,
    private val discoveryStage: DreamingTopicDiscoveryStage,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val collector: LlmDreamingEvidenceCollector =
        LlmDreamingEvidenceCollector(database, llmClient, llmConfig, estimator, pageSize),
    private val tracer: DreamingConversationEvidenceTracer = DreamingConversationEvidenceTracer(database),
    private val conversationNeedParser: JsonDreamingConversationNeedParser = JsonDreamingConversationNeedParser(),
) {
    private val binding = resolveDreamingEvidenceBinding(llmConfig)
    private val conversationSchema: String? =
        if (binding.evidenceProfile.jsonMode == LlmResponseFormat.JSON_SCHEMA) {
            DreamingEvidencePrompt.conversationSchema()
        } else {
            null
        }

    suspend fun collect(batch: List<StoredConversationTurn>): DreamingBatchEvidence {
        val topics = discoveryStage.discover(batch)
        val candidates = collector.collect(batch, topics)
        val evidence =
            candidates.map { candidate ->
                when (candidate.decision) {
                    DreamingNoveltyDecision.FULLY_COVERED ->
                        DreamingTopicEvidence(
                            topic = candidate.topic,
                            decision = DreamingEvidenceDecision.FULLY_COVERED,
                            newConversationTurns = candidate.newConversationTurns,
                            organizedMemories = candidate.organizedMemories,
                            rawMemories = emptyList(),
                            historicalConversationTurns = emptyList(),
                        )

                    DreamingNoveltyDecision.NEEDS_EVIDENCE ->
                        if (candidate.rawMemories.isEmpty()) {
                            DreamingTopicEvidence(
                                topic = candidate.topic,
                                decision = DreamingEvidenceDecision.NEEDS_REFLECTION,
                                newConversationTurns = candidate.newConversationTurns,
                                organizedMemories = candidate.organizedMemories,
                                rawMemories = emptyList(),
                                historicalConversationTurns = emptyList(),
                            )
                        } else {
                            val need = evaluateConversationNeed(candidate)
                            val validated =
                                try {
                                    DreamingConversationNeedValidator.validate(
                                        need,
                                        candidate.rawMemories.mapTo(hashSetOf()) { raw -> raw.id },
                                    )
                                } catch (_: DreamingConversationNeedValidationException) {
                                    throw invalidResponse()
                                }
                            val trace = tracer.trace(candidate, validated.memoryIds)
                            DreamingTopicEvidence(
                                topic = candidate.topic,
                                decision = DreamingEvidenceDecision.NEEDS_REFLECTION,
                                newConversationTurns = candidate.newConversationTurns,
                                organizedMemories = trace.organizedMemories,
                                rawMemories = trace.rawMemories,
                                historicalConversationTurns = trace.historicalConversationTurns,
                            )
                        }
                }
            }
        return DreamingBatchEvidence(evidence)
    }

    internal fun conversationNeedRequestFor(candidate: DreamingTopicEvidenceCandidate): LlmRequest =
        LlmRequest(
            task = LlmTask.DREAMING_EVIDENCE,
            messages = DreamingEvidencePrompt.conversationMessages(candidate.topic, candidate.newConversationTurns, candidate.rawMemories),
            responseFormat = binding.evidenceProfile.jsonMode,
            maxOutputTokens = binding.evidenceTask.maxOutputTokens,
            schema = conversationSchema,
        )

    internal fun effectiveConversationSchema(): String? = conversationSchema

    private suspend fun evaluateConversationNeed(candidate: DreamingTopicEvidenceCandidate): DreamingConversationNeed {
        currentCoroutineContext().ensureActive()
        val request = conversationNeedRequestFor(candidate)
        try {
            LlmBudget.requireWithinBudget(
                request = request,
                schema = conversationSchema,
                task = binding.evidenceTask,
                profile = binding.evidenceProfile,
                safetyMarginPercent = binding.safetyMarginPercent,
                estimator = estimator,
            )
        } catch (failure: LlmException) {
            if (failure.errorCode == LlmErrorCode.LOCAL_BUDGET_EXCEEDED) {
                throw evidenceFailure(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.message.orEmpty())
            }
            throw failure
        }
        val response = llmClient.complete(request)
        if (response.finishReason == FINISH_REASON_LENGTH) throw truncated()
        return try {
            conversationNeedParser.parse(response.content)
        } catch (failure: LlmException) {
            if (failure.errorCode == LlmErrorCode.INVALID_RESPONSE) throw invalidResponse()
            throw failure
        }
    }

    private fun truncated(): LlmException =
        evidenceFailure(LlmErrorCode.OUTPUT_TRUNCATED, "dreaming conversation need output was truncated")

    private fun invalidResponse(): LlmException =
        evidenceFailure(LlmErrorCode.INVALID_RESPONSE, "invalid dreaming conversation need response")

    private fun evidenceFailure(
        code: LlmErrorCode,
        message: String,
    ): LlmException =
        LlmException(
            errorCode = code,
            message = message,
            task = LlmTask.DREAMING_EVIDENCE,
            profile = binding.evidenceProfile.name,
            model = binding.evidenceProfile.model,
        )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
        const val FINISH_REASON_LENGTH = "length"
    }
}
