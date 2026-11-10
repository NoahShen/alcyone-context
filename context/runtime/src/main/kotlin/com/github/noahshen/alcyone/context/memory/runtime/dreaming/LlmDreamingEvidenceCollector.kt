package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingEvidencePrompt
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyAssessment
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyValidationException
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyValidator
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchIdValidationException
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchIdValidator
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchPrompt
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudget
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.CandidateBatchBudget
import com.github.noahshen.alcyone.context.memory.runtime.organization.CandidateBudgetExceededException
import com.github.noahshen.alcyone.context.memory.runtime.organization.TokenBatchPlanner
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.JsonMemorySearchResponseParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** CT24 B1 Organized/Raw/Novelty collector；所有 LLM 调用发生在只读快照之外。 */
internal class LlmDreamingEvidenceCollector(
    private val database: ContextDatabase,
    private val llmClient: LlmClient,
    llmConfig: LlmConfig,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val organizedParser: JsonMemorySearchResponseParser = JsonMemorySearchResponseParser(),
    private val noveltyParser: JsonDreamingNoveltyResponseParser = JsonDreamingNoveltyResponseParser(),
    private val rawParser: JsonDreamingEvidenceCandidateParser = JsonDreamingEvidenceCandidateParser(),
) {
    private val binding = resolveDreamingEvidenceBinding(llmConfig)

    init {
        require(pageSize >= 1) { "pageSize must be at least 1" }
    }

    /** 每个 Topic 独立扫描；任一 Topic 失败立即抛出，不返回部分 Candidate。 */
    suspend fun collect(
        batch: List<StoredConversationTurn>,
        topics: List<DiscoveredTopic>,
    ): List<DreamingTopicEvidenceCandidate> {
        if (topics.isEmpty()) return emptyList()
        val snapshots = readSnapshots()
        return topics.map { topic -> collectTopic(topic, batch, snapshots) }
    }

    private suspend fun collectTopic(
        topic: DiscoveredTopic,
        batch: List<StoredConversationTurn>,
        snapshots: Snapshots,
    ): DreamingTopicEvidenceCandidate {
        val topicTurns = mapTopicTurns(topic, batch)
        val relevantOrganized = scanOrganized(topic, topicTurns, snapshots.organized)
        val novelty =
            if (relevantOrganized.isEmpty()) {
                DreamingNoveltyAssessment(DreamingNoveltyDecision.NEEDS_EVIDENCE, emptyList())
            } else {
                evaluateNovelty(topic, topicTurns, relevantOrganized)
            }
        if (novelty.decision == DreamingNoveltyDecision.FULLY_COVERED) {
            return DreamingTopicEvidenceCandidate(
                topic = topic,
                decision = novelty.decision,
                newConversationTurns = topicTurns,
                organizedMemories = relevantOrganized,
                rawMemories = emptyList(),
            )
        }

        val rawHits = scanRaw(topic, topicTurns, snapshots.raw)
        val reverseOrganized = rawHits.flatMap { raw -> reverseOrganizedForRaw(raw.id) }
        val mergedOrganized = (relevantOrganized + reverseOrganized).distinctBy { memory -> memory.id }.sortedBy { memory -> memory.id }
        return DreamingTopicEvidenceCandidate(
            topic = topic,
            decision = DreamingNoveltyDecision.NEEDS_EVIDENCE,
            newConversationTurns = topicTurns,
            organizedMemories = mergedOrganized,
            rawMemories = rawHits,
        )
    }

    private suspend fun readSnapshots(): Snapshots =
        database.read { read ->
            Snapshots(
                organized = readOrganizedSnapshot(read.organizedMemories),
                raw = readRawSnapshot(read.rawMemories),
            )
        }

    private fun readOrganizedSnapshot(
        read: com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryReadOps,
    ): List<OrganizedMemory> {
        val result = mutableListOf<OrganizedMemory>()
        var offset = 0
        while (true) {
            val page = read.scanPage(offset, pageSize)
            if (page.isEmpty()) break
            result += page
            if (page.size < pageSize) break
            offset = Math.addExact(offset, page.size)
        }
        return result.sortedBy { memory -> memory.id }
    }

    private fun readRawSnapshot(read: com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryReadOps): List<RawMemory> {
        val result = mutableListOf<RawMemory>()
        var offset = 0
        while (true) {
            val page = read.scanPage(offset, pageSize)
            if (page.isEmpty()) break
            result += page
            if (page.size < pageSize) break
            offset = Math.addExact(offset, page.size)
        }
        return result.sortedWith(compareBy<RawMemory> { memory -> memory.createdAt }.thenBy { memory -> memory.id })
    }

    private suspend fun scanOrganized(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        snapshot: List<OrganizedMemory>,
    ): List<OrganizedMemory> {
        if (snapshot.isEmpty()) return emptyList()
        val schema =
            binding.memorySearchProfile.jsonMode
                .takeIf { mode ->
                    mode == LlmResponseFormat.JSON_SCHEMA
                }?.let { MemorySearchPrompt.scannerSchema() }
        val batches =
            planBatches(snapshot) { batch ->
                budgetOverrun(
                    request = organizedRequest(topic, batch),
                    schema = schema,
                    task = binding.memorySearchTask,
                    profile = binding.memorySearchProfile,
                )
            }
        val selected = LinkedHashSet<String>()
        batches.forEach { batch ->
            currentCoroutineContext().ensureActive()
            val request = organizedRequest(topic, batch)
            requireBudget(request, schema, binding.memorySearchTask, binding.memorySearchProfile)
            val response = llmClient.complete(request)
            if (response.finishReason == FINISH_REASON_LENGTH) {
                throw truncated("dreaming organized evidence output was truncated", LlmTask.MEMORY_SEARCH, binding.memorySearchProfile)
            }
            val returned =
                try {
                    organizedParser.parse(response.content)
                } catch (failure: LlmException) {
                    if (failure.errorCode == LlmErrorCode.INVALID_RESPONSE) {
                        throw invalidResponse(LlmTask.MEMORY_SEARCH, binding.memorySearchProfile)
                    }
                    throw failure
                }
            val validated =
                try {
                    MemorySearchIdValidator.validateScannerBatch(batch.map { memory -> memory.id }.toSet(), returned)
                } catch (_: MemorySearchIdValidationException) {
                    throw invalidResponse(LlmTask.MEMORY_SEARCH, binding.memorySearchProfile)
                }
            selected += validated
        }
        return snapshot.filter { memory -> memory.id in selected }.sortedBy { memory -> memory.id }
    }

    private suspend fun evaluateNovelty(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        relevantOrganized: List<OrganizedMemory>,
    ): DreamingNoveltyAssessment {
        val schema =
            binding.evidenceProfile.jsonMode
                .takeIf { mode ->
                    mode == LlmResponseFormat.JSON_SCHEMA
                }?.let { DreamingEvidencePrompt.noveltySchema() }
        val request = noveltyRequest(topic, turns, relevantOrganized)
        requireBudget(request, schema, binding.evidenceTask, binding.evidenceProfile)
        val response = llmClient.complete(request)
        if (response.finishReason == FINISH_REASON_LENGTH) {
            throw truncated("dreaming novelty output was truncated", LlmTask.DREAMING_EVIDENCE, binding.evidenceProfile)
        }
        val assessment =
            try {
                noveltyParser.parse(response.content)
            } catch (failure: LlmException) {
                if (failure.errorCode == LlmErrorCode.INVALID_RESPONSE) {
                    throw invalidResponse(LlmTask.DREAMING_EVIDENCE, binding.evidenceProfile)
                }
                throw failure
            }
        return try {
            DreamingNoveltyValidator.validate(assessment, relevantOrganized.map { memory -> memory.id }.toSet())
        } catch (_: DreamingNoveltyValidationException) {
            throw invalidResponse(LlmTask.DREAMING_EVIDENCE, binding.evidenceProfile)
        }
    }

    private suspend fun scanRaw(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        snapshot: List<RawMemory>,
    ): List<RawMemory> {
        if (snapshot.isEmpty()) return emptyList()
        val schema =
            binding.evidenceProfile.jsonMode
                .takeIf { mode ->
                    mode == LlmResponseFormat.JSON_SCHEMA
                }?.let { DreamingEvidencePrompt.rawSchema() }
        val batches =
            planBatches(snapshot) { batch ->
                budgetOverrun(
                    request = rawRequest(topic, turns, batch),
                    schema = schema,
                    task = binding.evidenceTask,
                    profile = binding.evidenceProfile,
                )
            }
        val selected = LinkedHashSet<String>()
        batches.forEach { batch ->
            currentCoroutineContext().ensureActive()
            val request = rawRequest(topic, turns, batch)
            requireBudget(request, schema, binding.evidenceTask, binding.evidenceProfile)
            val response = llmClient.complete(request)
            if (response.finishReason == FINISH_REASON_LENGTH) {
                throw truncated("dreaming raw evidence output was truncated", LlmTask.DREAMING_EVIDENCE, binding.evidenceProfile)
            }
            val returned =
                try {
                    rawParser.parse(response.content)
                } catch (failure: LlmException) {
                    if (failure.errorCode == LlmErrorCode.INVALID_RESPONSE) {
                        throw invalidResponse(LlmTask.DREAMING_EVIDENCE, binding.evidenceProfile)
                    }
                    throw failure
                }
            val validated =
                try {
                    MemorySearchIdValidator.validateScannerBatch(batch.map { memory -> memory.id }.toSet(), returned)
                } catch (_: MemorySearchIdValidationException) {
                    throw invalidResponse(LlmTask.DREAMING_EVIDENCE, binding.evidenceProfile)
                }
            selected += validated
        }
        return snapshot
            .filter { memory ->
                memory.id in selected
            }.sortedWith(compareBy<RawMemory> { memory -> memory.createdAt }.thenBy { memory -> memory.id })
    }

    private suspend fun reverseOrganizedForRaw(rawId: String): List<OrganizedMemory> =
        database.read { read ->
            val ids = read.organizedMemories.findOrganizedByRaw(rawId).distinct()
            if (ids.isEmpty()) return@read emptyList()
            val rows = read.organizedMemories.findByIds(ids)
            if (rows.map { memory -> memory.id }.toSet() != ids.toSet()) {
                throw invalidResponse(LlmTask.DREAMING_EVIDENCE, binding.evidenceProfile)
            }
            rows
        }

    private suspend fun <T> planBatches(
        items: List<T>,
        budget: suspend (List<T>) -> Long?,
    ): List<List<T>> =
        try {
            TokenBatchPlanner.plan(items, CandidateBatchBudget { batch -> budget(batch) })
        } catch (failure: CandidateBudgetExceededException) {
            throw LlmException(
                errorCode = LlmErrorCode.LOCAL_BUDGET_EXCEEDED,
                message = "dreaming evidence budget exceeded",
            )
        }

    private fun budgetOverrun(
        request: LlmRequest,
        schema: String?,
        task: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig,
        profile: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig,
    ): Long? {
        val decision =
            LlmBudget.estimate(
                request = request,
                schema = schema,
                task = task,
                profile = profile,
                safetyMarginPercent = binding.safetyMarginPercent,
                estimator = estimator,
            )
        val fitsInput = decision.estimatedInputTokens <= task.maxInputTokens.toLong()
        val fitsContext = decision.requiredTotalTokens <= profile.contextWindowTokens.toLong()
        return if (fitsInput && fitsContext) null else decision.requiredTotalTokens
    }

    private fun requireBudget(
        request: LlmRequest,
        schema: String?,
        task: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig,
        profile: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig,
    ) {
        LlmBudget.requireWithinBudget(
            request = request,
            schema = schema,
            task = task,
            profile = profile,
            safetyMarginPercent = binding.safetyMarginPercent,
            estimator = estimator,
        )
    }

    internal fun organizedRequestFor(
        topic: DiscoveredTopic,
        batch: List<OrganizedMemory>,
    ): LlmRequest = organizedRequest(topic, batch)

    internal fun rawRequestFor(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        batch: List<RawMemory>,
    ): LlmRequest = rawRequest(topic, turns, batch)

    internal fun noveltyRequestFor(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        candidates: List<OrganizedMemory>,
    ): LlmRequest = noveltyRequest(topic, turns, candidates)

    private fun organizedRequest(
        topic: DiscoveredTopic,
        batch: List<OrganizedMemory>,
    ): LlmRequest =
        LlmRequest(
            task = LlmTask.MEMORY_SEARCH,
            messages = MemorySearchPrompt.scannerMessages(topicQuery(topic), batch),
            responseFormat = binding.memorySearchProfile.jsonMode,
            maxOutputTokens = binding.memorySearchTask.maxOutputTokens,
            schema =
                if (binding.memorySearchProfile.jsonMode ==
                    LlmResponseFormat.JSON_SCHEMA
                ) {
                    MemorySearchPrompt.scannerSchema()
                } else {
                    null
                },
        )

    private fun noveltyRequest(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        candidates: List<OrganizedMemory>,
    ): LlmRequest =
        LlmRequest(
            task = LlmTask.DREAMING_EVIDENCE,
            messages = DreamingEvidencePrompt.noveltyMessages(topic, turns, candidates),
            responseFormat = binding.evidenceProfile.jsonMode,
            maxOutputTokens = binding.evidenceTask.maxOutputTokens,
            schema =
                if (binding.evidenceProfile.jsonMode ==
                    LlmResponseFormat.JSON_SCHEMA
                ) {
                    DreamingEvidencePrompt.noveltySchema()
                } else {
                    null
                },
        )

    private fun rawRequest(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        batch: List<RawMemory>,
    ): LlmRequest =
        LlmRequest(
            task = LlmTask.DREAMING_EVIDENCE,
            messages = DreamingEvidencePrompt.rawMessages(topic, turns, batch),
            responseFormat = binding.evidenceProfile.jsonMode,
            maxOutputTokens = binding.evidenceTask.maxOutputTokens,
            schema = if (binding.evidenceProfile.jsonMode == LlmResponseFormat.JSON_SCHEMA) DreamingEvidencePrompt.rawSchema() else null,
        )

    private fun topicQuery(topic: DiscoveredTopic): String =
        buildString {
            append(topic.title).append('\n').append(topic.description)
            if (topic.keywords.isNotEmpty()) append("\nkeywords: ").append(topic.keywords.joinToString(", "))
        }

    private fun mapTopicTurns(
        topic: DiscoveredTopic,
        batch: List<StoredConversationTurn>,
    ): List<StoredConversationTurn> {
        val batchById = batch.associateBy { turn -> turn.id }
        val sources = topic.sourceTurnIds.toSet()
        if (sources.any { id -> id !in batchById }) throw invalidRequest("dreaming topic source is not in the current batch")
        return batch.filter { turn -> turn.id in sources }
    }

    private fun truncated(
        message: String,
        task: LlmTask,
        profile: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig,
    ): LlmException =
        LlmException(
            errorCode = LlmErrorCode.OUTPUT_TRUNCATED,
            message = message,
            task = task,
            profile = profile.name,
            model = profile.model,
        )

    private fun invalidResponse(
        task: LlmTask,
        profile: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig,
    ): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid dreaming evidence response",
            task = task,
            profile = profile.name,
            model = profile.model,
        )

    private fun invalidRequest(message: String): LlmException = LlmException(errorCode = LlmErrorCode.INVALID_REQUEST, message = message)

    private data class Snapshots(
        val organized: List<OrganizedMemory>,
        val raw: List<RawMemory>,
    )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
        const val FINISH_REASON_LENGTH = "length"
    }
}
