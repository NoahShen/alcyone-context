package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
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
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapArtifact
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapDocument
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapPrompt
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapRenderer
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapSnapshot
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapValidationException
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapValidator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudget
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock

/** 固定快照 Memory Map 生成器；读取事务结束后才构造 Prompt 并调用 LLM。 */
internal class MemoryMapGenerator(
    private val database: ContextDatabase,
    private val llmClient: LlmClient,
    llmConfig: LlmConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val parser: JsonMemoryMapResponseParser = JsonMemoryMapResponseParser(),
) : MemoryMapGeneratorPort {
    private val task: LlmTaskConfig
    private val profile: LlmProfileConfig
    private val safetyMarginPercent: Int = llmConfig.safetyMarginPercent
    private val schema: String?

    /** 测试接缝：每次分页读取前调用；生产为 null。 */
    internal var betweenPagesForTesting: (() -> Unit)? = null

    init {
        require(pageSize >= 1) { "pageSize must be at least 1" }
        MemoryMapPrompt.systemPrompt()
        val loadedSchema = MemoryMapPrompt.schema()
        val taskConfig =
            llmConfig.tasks[LlmTask.MEMORY_MAP]
                ?: throw invalidConfig("context.llm.tasks.MEMORY_MAP is required for memory map generation")
        val profileConfig =
            llmConfig.profiles[taskConfig.profile]
                ?: throw invalidConfig(
                    "context.llm.tasks.MEMORY_MAP.profile refers to an unknown profile: ${taskConfig.profile}",
                )
        if (profileConfig.jsonMode != LlmResponseFormat.JSON_SCHEMA &&
            profileConfig.jsonMode != LlmResponseFormat.JSON_OBJECT
        ) {
            throw invalidConfig(
                "context.llm.profiles.${profileConfig.name}.jsonMode must be JSON_SCHEMA or JSON_OBJECT for memory map generation",
            )
        }
        task = taskConfig
        profile = profileConfig
        schema = if (profileConfig.jsonMode == LlmResponseFormat.JSON_SCHEMA) loadedSchema else null
    }

    override suspend fun generate(): MemoryMapArtifact {
        val snapshot = readSnapshot()
        currentCoroutineContext().ensureActive()
        if (snapshot.memories.isEmpty()) return renderEmptyMap()

        val request = requestFor(snapshot)
        LlmBudget.requireWithinBudget(
            request = request,
            schema = schema,
            task = task,
            profile = profile,
            safetyMarginPercent = safetyMarginPercent,
            estimator = estimator,
        )
        currentCoroutineContext().ensureActive()
        val response = llmClient.complete(request)
        if (response.finishReason == FINISH_REASON_LENGTH) {
            throw LlmException(
                errorCode = LlmErrorCode.OUTPUT_TRUNCATED,
                message = "memory map output was truncated (finish_reason=length)",
                task = LlmTask.MEMORY_MAP,
                profile = profile.name,
                model = profile.model,
            )
        }
        val draft = parser.parse(response.content)
        val validated =
            try {
                MemoryMapValidator.validate(draft, snapshot)
            } catch (failure: MemoryMapValidationException) {
                throw invalidResponse()
            }
        currentCoroutineContext().ensureActive()
        val document =
            MemoryMapDocument(
                overview = validated.overview,
                navigation = validated.navigation,
                domains = snapshot.domains,
                generatedAt = clock.instant(),
                sourceUpdatedAt = snapshot.sourceUpdatedAt,
            )
        return MemoryMapArtifact(document = document, renderedBytes = MemoryMapRenderer.render(document))
    }

    internal fun requestFor(snapshot: MemoryMapSnapshot): LlmRequest =
        LlmRequest(
            task = LlmTask.MEMORY_MAP,
            messages = MemoryMapPrompt.messages(snapshot),
            responseFormat = profile.jsonMode,
            maxOutputTokens = task.maxOutputTokens,
            schema = schema,
        )

    private suspend fun readSnapshot(): MemoryMapSnapshot {
        val coroutine = currentCoroutineContext()
        val captured =
            database.read { read ->
                val memories = mutableListOf<OrganizedMemory>()
                var offset = 0L
                while (true) {
                    coroutine.ensureActive()
                    if (offset > Int.MAX_VALUE.toLong()) {
                        throw IllegalStateException("organized memory page offset exceeds the supported range")
                    }
                    betweenPagesForTesting?.invoke()
                    val page = read.organizedMemories.scanPage(offset.toInt(), pageSize)
                    if (page.isEmpty()) break
                    memories += page
                    if (page.size < pageSize) break
                    offset += page.size.toLong()
                }
                coroutine.ensureActive()
                SnapshotCapture(memories.toList(), read.organizedMemories.maxUpdatedAt())
            }
        coroutine.ensureActive()
        val sorted = captured.memories.sortedBy { memory -> memory.id }
        return MemoryMapSnapshot(sorted, captured.sourceUpdatedAt)
    }

    private fun renderEmptyMap(): MemoryMapArtifact {
        val document =
            MemoryMapDocument(
                overview = EMPTY_OVERVIEW,
                navigation = EMPTY_NAVIGATION,
                domains = emptyList(),
                generatedAt = clock.instant(),
                sourceUpdatedAt = null,
            )
        return MemoryMapArtifact(document = document, renderedBytes = MemoryMapRenderer.render(document))
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid memory map response",
            task = LlmTask.MEMORY_MAP,
            profile = profile.name,
            model = profile.model,
        )

    private data class SnapshotCapture(
        val memories: List<OrganizedMemory>,
        val sourceUpdatedAt: java.time.Instant?,
    )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
        const val FINISH_REASON_LENGTH = "length"
        const val EMPTY_OVERVIEW = "No organized memories have been recorded yet."
        const val EMPTY_NAVIGATION = "The memory map will appear after organized memories are created."

        fun invalidConfig(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
    }
}
