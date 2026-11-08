package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** CT20 B1 纯 Memory Search 链：全量快照 → Scanner 分批 → 全候选 Rerank → 权威回读。 */
internal class MemorySearchService(
    private val database: ContextDatabase,
    private val llmClient: LlmClient?,
    private val llmConfig: LlmConfig?,
    maxLimit: Int = MemorySearchDefaults.MAX_LIMIT,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val scannerParser: JsonMemorySearchResponseParser = JsonMemorySearchResponseParser(),
    private val rerankParser: JsonMemorySearchRerankResponseParser = JsonMemorySearchRerankResponseParser(),
) {
    private val maxLimit: Int = requireValidMaxLimit(maxLimit)

    internal var betweenPagesForTesting: (() -> Unit)? = null

    init {
        require(pageSize >= 1) { "pageSize must be at least 1" }
    }

    suspend fun search(
        query: String,
        domains: List<String>? = null,
        limit: Int = DEFAULT_API_LIMIT,
    ): List<OrganizedMemory> {
        val normalizedQuery = normalizeQuery(query)
        val normalizedDomains = normalizeDomains(domains)
        requireValidSearchLimit(limit, maxLimit)
        val binding = resolveMemorySearchBinding(llmConfig)
        val client = llmClient ?: throw configuration("llm client is required for memory search")

        val snapshot = readSnapshot().filter { memory -> matchesDomains(memory, normalizedDomains) }
        if (snapshot.isEmpty()) return emptyList()

        val scannerSchema = if (binding.profile.jsonMode == LlmResponseFormat.JSON_SCHEMA) MemorySearchPrompt.scannerSchema() else null
        val rerankSchema = if (binding.profile.jsonMode == LlmResponseFormat.JSON_SCHEMA) MemorySearchPrompt.rerankSchema() else null
        val selectedIds = scan(client, binding, scannerSchema, normalizedQuery, snapshot)
        if (selectedIds.isEmpty()) return emptyList()

        val selectedSet = selectedIds.toSet()
        val candidates =
            snapshot
                .filter { memory ->
                    memory.id in selectedSet
                }.distinctBy { memory -> memory.id }
                .sortedBy { memory -> memory.id }
        val rerankIds = rerank(client, binding, rerankSchema, normalizedQuery, candidates, limit).take(limit)
        if (rerankIds.isEmpty()) return emptyList()
        return readAuthoritative(rerankIds)
    }

    private suspend fun scan(
        client: LlmClient,
        binding: MemorySearchBinding,
        schema: String?,
        query: String,
        candidates: List<OrganizedMemory>,
    ): List<String> {
        val budget =
            CandidateBatchBudget<OrganizedMemory> { batch ->
                currentCoroutineContext().ensureActive()
                budgetOverrun(scannerRequest(binding, schema, query, batch), binding, schema)
            }
        val batches =
            try {
                TokenBatchPlanner.plan(candidates, budget)
            } catch (exceeded: CandidateBudgetExceededException) {
                throw budgetFailure(exceeded)
            }

        val selected = LinkedHashSet<String>()
        batches.forEach { batch ->
            currentCoroutineContext().ensureActive()
            val request = scannerRequest(binding, schema, query, batch)
            LlmBudget.requireWithinBudget(
                request = request,
                schema = schema,
                task = binding.task,
                profile = binding.profile,
                safetyMarginPercent = binding.safetyMarginPercent,
                estimator = estimator,
            )
            val response = client.complete(request)
            if (response.finishReason == FINISH_REASON_LENGTH) throw truncated("memory search scan output was truncated")
            val returned = scannerParser.parse(response.content)
            val validated =
                try {
                    MemorySearchIdValidator.validateScannerBatch(batch.map { memory -> memory.id }.toSet(), returned)
                } catch (_: MemorySearchIdValidationException) {
                    throw invalidResponse("invalid memory search response")
                }
            selected += validated
        }
        return selected.toList()
    }

    private suspend fun rerank(
        client: LlmClient,
        binding: MemorySearchBinding,
        schema: String?,
        query: String,
        candidates: List<OrganizedMemory>,
        limit: Int,
    ): List<String> {
        currentCoroutineContext().ensureActive()
        val request = rerankRequest(binding, schema, query, candidates, limit)
        LlmBudget.requireWithinBudget(
            request = request,
            schema = schema,
            task = binding.task,
            profile = binding.profile,
            safetyMarginPercent = binding.safetyMarginPercent,
            estimator = estimator,
        )
        val response = client.complete(request)
        if (response.finishReason == FINISH_REASON_LENGTH) throw truncated("memory search rerank output was truncated")
        val returned = rerankParser.parse(response.content)
        return try {
            MemorySearchIdValidator.validateRerank(candidates.map { memory -> memory.id }.toSet(), returned)
        } catch (_: MemorySearchIdValidationException) {
            throw invalidResponse("invalid memory search rerank response")
        }
    }

    private suspend fun readSnapshot(): List<OrganizedMemory> {
        val context = currentCoroutineContext()
        return database.read { read ->
            val all = mutableListOf<OrganizedMemory>()
            var offset = 0L
            while (true) {
                context.ensureActive()
                betweenPagesForTesting?.invoke()
                if (offset > Int.MAX_VALUE.toLong()) throw IllegalStateException("organized memory page offset exceeds the supported range")
                val page = read.organizedMemories.scanPage(offset.toInt(), pageSize)
                if (page.isEmpty()) break
                all += page
                if (page.size < pageSize) break
                offset += page.size.toLong()
            }
            all.sortedBy { memory -> memory.id }
        }
    }

    private suspend fun readAuthoritative(ids: List<String>): List<OrganizedMemory> {
        val rows = database.read { read -> read.organizedMemories.findByIds(ids) }
        val byId = rows.associateBy { memory -> memory.id }
        if (ids.any { id -> id !in byId }) {
            throw ContextRetrievalException(ContextRetrievalErrorCode.UNAVAILABLE, "one or more search results are no longer available")
        }
        return ids.map { id -> byId.getValue(id) }
    }

    private fun scannerRequest(
        binding: MemorySearchBinding,
        schema: String?,
        query: String,
        batch: List<OrganizedMemory>,
    ): LlmRequest =
        LlmRequest(
            task = LlmTask.MEMORY_SEARCH,
            messages = MemorySearchPrompt.scannerMessages(query, batch),
            responseFormat = binding.profile.jsonMode,
            maxOutputTokens = binding.task.maxOutputTokens,
            schema = schema,
        )

    private fun rerankRequest(
        binding: MemorySearchBinding,
        schema: String?,
        query: String,
        candidates: List<OrganizedMemory>,
        limit: Int,
    ): LlmRequest =
        LlmRequest(
            task = LlmTask.MEMORY_SEARCH,
            messages = MemorySearchPrompt.rerankMessages(query, candidates, limit),
            responseFormat = binding.profile.jsonMode,
            maxOutputTokens = binding.task.maxOutputTokens,
            schema = schema,
        )

    private fun budgetOverrun(
        request: LlmRequest,
        binding: MemorySearchBinding,
        schema: String?,
    ): Long? {
        val decision =
            LlmBudget.estimate(
                request = request,
                schema = schema,
                task = binding.task,
                profile = binding.profile,
                safetyMarginPercent = binding.safetyMarginPercent,
                estimator = estimator,
            )
        val fits =
            decision.estimatedInputTokens <= binding.task.maxInputTokens.toLong() &&
                decision.requiredTotalTokens <= binding.profile.contextWindowTokens.toLong()
        return if (fits) null else decision.requiredTotalTokens
    }

    private fun budgetFailure(exceeded: CandidateBudgetExceededException): LlmException =
        LlmException(
            errorCode = LlmErrorCode.LOCAL_BUDGET_EXCEEDED,
            message =
                "memory search budget exceeded " +
                    "(reason=${exceeded.reason.name.lowercase()}, requiredTotalTokens=${exceeded.requiredTotalTokens})",
            task = LlmTask.MEMORY_SEARCH,
        )

    private fun truncated(message: String): LlmException =
        LlmException(
            errorCode = LlmErrorCode.OUTPUT_TRUNCATED,
            message = message,
            task = LlmTask.MEMORY_SEARCH,
        )

    private fun invalidResponse(message: String): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = message,
            task = LlmTask.MEMORY_SEARCH,
        )

    private fun normalizeQuery(query: String): String {
        val normalized = query.trim()
        if (normalized.isEmpty()) throw invalidRequest("query must not be blank")
        return normalized
    }

    private fun normalizeDomains(domains: List<String>?): Set<String>? {
        if (domains.isNullOrEmpty()) return null
        val normalized = LinkedHashSet<String>(domains.size)
        domains.forEach { domain ->
            val value = domain.trim()
            if (value.isEmpty()) throw invalidRequest("domain must not be blank")
            normalized += value
        }
        return normalized
    }

    private fun matchesDomains(
        memory: OrganizedMemory,
        domains: Set<String>?,
    ): Boolean = domains == null || memory.domains.any { domain -> domain in domains }

    private fun invalidRequest(message: String): ContextRetrievalException =
        ContextRetrievalException(ContextRetrievalErrorCode.INVALID_REQUEST, message)

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
        const val DEFAULT_API_LIMIT = 10
        const val FINISH_REASON_LENGTH = "length"
    }
}
