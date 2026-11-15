package com.github.noahshen.alcyone.context.memory.runtime.evaluation

import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitContext
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitResult
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitter
import com.github.noahshen.alcyone.context.memory.core.dreaming.ValidatedDreamingInsight
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGeneratorImpl
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.LlmDreamingEvidenceStage
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.LlmDreamingReflectionStage
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.LlmDreamingTopicDiscoveryStage
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import java.time.Clock

/** A call captured at the real production LlmClient boundary, not a replayed fixture. */
data class OfflineEvaluationObservedCall(
    val task: String,
    val messages: List<LlmMessage>,
    val responseFormat: String,
    val maxOutputTokens: Int?,
    val schema: String?,
    val content: String,
    val usage: TokenUsage?,
    val finishReason: String?,
    val elapsedMs: Long,
)

/** Evaluation-only observation; production DreamingRuntime does not depend on these DTOs. */
data class OfflineDreamingEvaluationResult(
    val topics: List<DiscoveredTopic>,
    val insights: List<ValidatedDreamingInsight>,
    val evidenceLinks: List<Pair<String, String>>,
    val commit: DreamingCommitResult,
    val calls: List<OfflineEvaluationObservedCall>,
)

/**
 * Explicit offline-evaluation bridge over production Topic/Evidence/Reflection stages and
 * DreamingCommitter. It has no scheduler, retries, HTTP client, or fixture responses.
 */
class ProductionDreamingEvaluationRunner(
    client: LlmClient,
    private val llmConfig: LlmConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val recorder = RecordingLlmClient(client)
    private val discovery = LlmDreamingTopicDiscoveryStage(recorder, llmConfig)
    private val reflection = LlmDreamingReflectionStage(recorder, llmConfig)
    private val profile = llmConfig.profiles.values.single()

    suspend fun run(
        database: ContextDatabase,
        turns: List<StoredConversationTurn>,
        expectedCheckpoint: Long = 0,
    ): OfflineDreamingEvaluationResult {
        require(
            turns.isNotEmpty() &&
                turns.zipWithNext().all { (a, b) ->
                    a.id < b.id
                },
        ) { "evaluation turns must have strictly increasing ids" }
        val upperBound = turns.last().id
        val evidenceStage = LlmDreamingEvidenceStage(database, recorder, llmConfig, discovery)
        val batchEvidence = evidenceStage.collect(turns)
        val estimator = ConservativeTokenEstimator()
        batchEvidence.topics.forEach { topic ->
            val request = reflection.reflectionRequestFor(topic)
            check(estimator.estimatePrompt(request.messages) + estimator.estimateSchema(request.schema) <= profile.contextWindowTokens) {
                "dreaming evidence exceeds the configured model context window"
            }
        }
        val insights = reflection.reflect(batchEvidence)
        val commit =
            DreamingCommitter(database, MemoryIdGeneratorImpl(clock), clock).commit(
                DreamingCommitContext(expectedCheckpoint, upperBound, turns.last().id),
                insights,
            )
        val links =
            database.read { read ->
                batchEvidence.topics
                    .flatMap { topic ->
                        topic.organizedMemories.flatMap { organized ->
                            read.organizedMemories.findSourcesByOrganized(organized.id).map { raw -> raw to organized.id }
                        }
                    }.distinct()
            }
        return OfflineDreamingEvaluationResult(
            topics = batchEvidence.topics.map { it.topic },
            insights = insights,
            evidenceLinks = links,
            commit = commit,
            calls = recorder.calls(),
        )
    }
}

private class RecordingLlmClient(
    private val delegate: LlmClient,
) : LlmClient {
    private val observed = mutableListOf<OfflineEvaluationObservedCall>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val start = System.nanoTime()
        val response = delegate.complete(request)
        synchronized(observed) {
            observed +=
                OfflineEvaluationObservedCall(
                    task = request.task.name,
                    messages = request.messages,
                    responseFormat = request.responseFormat.name,
                    maxOutputTokens = request.maxOutputTokens,
                    schema = request.schema,
                    content = response.content,
                    usage = response.usage,
                    finishReason = response.finishReason,
                    elapsedMs = (System.nanoTime() - start) / 1_000_000,
                )
        }
        return response
    }

    fun calls(): List<OfflineEvaluationObservedCall> = synchronized(observed) { observed.toList() }
}
