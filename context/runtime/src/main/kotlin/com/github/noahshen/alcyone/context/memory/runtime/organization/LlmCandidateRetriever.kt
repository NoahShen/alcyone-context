package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.organization.CandidateIdValidationException
import com.github.noahshen.alcyone.context.memory.core.organization.CandidateIdValidator
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidatePrompt
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidateRetriever
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudget
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * CT13 基于 LLM 的候选召回实现（Runtime internal，实现 Core [OrganizationCandidateRetriever]）。
 *
 * 流程：
 * 1. **单次** `database.read` 内分页读取全部 Organized Memory（页大小默认 256，偏移用 `Long` 受检），
 *    在内存按 `id ASC` 排序；读取结束后立即释放 ReadContext，之后才调用 LLM（不在事务内做网络）。
 * 2. 空快照 → 返回空列表，**不调用 LLM**。
 * 3. 用 [TokenBatchPlanner] 做固定开销 + 逐条预算分批（估算与发送共用 [requestFor]，Schema 仅
 *    `JSON_SCHEMA` 计入，`maxOutputTokens = task.maxOutputTokens`）。
 * 4. 串行调用每批；任一批失败/非法 ID/解析失败/`finish_reason == "length"`/取消 → 整条失败，
 *    不返回部分候选，后续批不再调用。
 * 5. 候选映射回**原快照对象**（不重查成不同版本），最终按快照 `id ASC` 排序。
 *
 * 构造期只读配置与 Prompt 资源：缺 `CANDIDATE_SCANNER` Task / Profile 缺失 / 非 `JSON_SCHEMA`·`JSON_OBJECT`
 * → `ContextConfigException(INVALID_CONFIG)`（消息只含路径，不含 Key/URL/正文）；不访问 DB/LLM。
 */
internal class LlmCandidateRetriever(
    private val database: ContextDatabase,
    private val llmClient: LlmClient,
    llmConfig: LlmConfig,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : OrganizationCandidateRetriever {
    private val task: LlmTaskConfig
    private val profile: LlmProfileConfig
    private val safetyMarginPercent: Int = llmConfig.safetyMarginPercent
    private val schema: String?
    private val parser = JsonCandidateResponseParser()

    /** 测试接缝：每次 `scanPage` 之前调用（生产为 null；非挂起，仅用于快照/并发测试）。 */
    internal var betweenPagesForTesting: (() -> Unit)? = null

    init {
        require(pageSize >= 1) { "pageSize must be at least 1" }
        val taskConfig =
            llmConfig.tasks[LlmTask.CANDIDATE_SCANNER]
                ?: throw invalidConfig("context.llm.tasks.CANDIDATE_SCANNER is required for candidate scanning")
        val profileConfig =
            llmConfig.profiles[taskConfig.profile]
                ?: throw invalidConfig(
                    "context.llm.tasks.CANDIDATE_SCANNER.profile refers to an unknown profile: ${taskConfig.profile}",
                )
        if (profileConfig.jsonMode != LlmResponseFormat.JSON_SCHEMA &&
            profileConfig.jsonMode != LlmResponseFormat.JSON_OBJECT
        ) {
            throw invalidConfig(
                "context.llm.profiles.${profileConfig.name}.jsonMode must be JSON_SCHEMA or JSON_OBJECT for candidate scanning",
            )
        }
        task = taskConfig
        profile = profileConfig
        schema = if (profileConfig.jsonMode == LlmResponseFormat.JSON_SCHEMA) OrganizationCandidatePrompt.schema() else null
    }

    override suspend fun search(rawMemory: RawMemory): List<OrganizedMemory> {
        val snapshot = readSnapshot()
        if (snapshot.isEmpty()) return emptyList()

        val batches =
            try {
                TokenBatchPlanner.plan(snapshot) { batch ->
                    // 长预算循环可响应取消（不只依赖网络挂起点）。
                    currentCoroutineContext().ensureActive()
                    budgetOverrun(rawMemory, batch)
                }
            } catch (exceeded: CandidateBudgetExceededException) {
                throw budgetFailure(exceeded)
            }

        val selected = LinkedHashSet<String>()
        batches.forEach { batch ->
            currentCoroutineContext().ensureActive()
            val response = llmClient.complete(requestFor(rawMemory, batch))
            if (response.finishReason == FINISH_REASON_LENGTH) {
                throw LlmException(
                    errorCode = LlmErrorCode.OUTPUT_TRUNCATED,
                    message = "candidate scan output was truncated (finish_reason=length)",
                    task = LlmTask.CANDIDATE_SCANNER,
                    profile = profile.name,
                    model = profile.model,
                )
            }
            val returned = parser.parse(response.content)
            val validated =
                try {
                    CandidateIdValidator.validate(batch.map { memory -> memory.id }.toSet(), returned)
                } catch (failure: CandidateIdValidationException) {
                    throw LlmException(
                        errorCode = LlmErrorCode.INVALID_RESPONSE,
                        message = "invalid candidate response",
                        task = LlmTask.CANDIDATE_SCANNER,
                        profile = profile.name,
                        model = profile.model,
                    )
                }
            selected += validated
        }

        // 映射回原快照对象（不重查），并按快照 id ASC 输出。
        return snapshot.filter { memory -> memory.id in selected }
    }

    /**
     * 单次只读事务内分页读取全部 Organized Memory；分页偏移用 `Long` 受检，避免 Int 回绕。
     * 回调内不做网络；`ReadContext` 不逃逸。
     */
    private suspend fun readSnapshot(): List<OrganizedMemory> {
        val context = currentCoroutineContext()
        return database.read { read ->
            val all = mutableListOf<OrganizedMemory>()
            var offset = 0L
            while (true) {
                context.ensureActive()
                betweenPagesForTesting?.invoke()
                val page = read.organizedMemories.scanPage(offset.toInt(), pageSize)
                if (page.isEmpty()) break
                all += page
                if (page.size < pageSize) break
                offset += page.size.toLong()
                if (offset > Int.MAX_VALUE.toLong()) {
                    throw IllegalStateException("organized memory page offset exceeds the supported range")
                }
            }
            all.sortedBy { memory -> memory.id }
        }
    }

    /** 返回 `null` 表示在预算内；否则返回 `requiredTotalTokens`（估算与发送共用同一请求构造）。 */
    private fun budgetOverrun(
        rawMemory: RawMemory,
        batch: List<OrganizedMemory>,
    ): Long? {
        val decision =
            LlmBudget.estimate(
                request = requestFor(rawMemory, batch),
                schema = schema,
                task = task,
                profile = profile,
                safetyMarginPercent = safetyMarginPercent,
                estimator = estimator,
            )
        val fits =
            decision.estimatedInputTokens <= task.maxInputTokens.toLong() &&
                decision.requiredTotalTokens <= profile.contextWindowTokens.toLong()
        return if (fits) null else decision.requiredTotalTokens
    }

    private fun requestFor(
        rawMemory: RawMemory,
        batch: List<OrganizedMemory>,
    ): LlmRequest =
        LlmRequest(
            task = LlmTask.CANDIDATE_SCANNER,
            messages = OrganizationCandidatePrompt.messages(rawMemory, batch),
            responseFormat = profile.jsonMode,
            maxOutputTokens = task.maxOutputTokens,
            schema = schema,
        )

    private fun budgetFailure(exceeded: CandidateBudgetExceededException): LlmException =
        LlmException(
            errorCode = LlmErrorCode.LOCAL_BUDGET_EXCEEDED,
            message =
                "candidate scan budget exceeded " +
                    "(reason=${exceeded.reason.name.lowercase()}, requiredTotalTokens=${exceeded.requiredTotalTokens})",
            task = LlmTask.CANDIDATE_SCANNER,
            profile = profile.name,
            model = profile.model,
        )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
        const val FINISH_REASON_LENGTH = "length"

        fun invalidConfig(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
    }
}
