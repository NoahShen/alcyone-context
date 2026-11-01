package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionBuildResult
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionPrompt
import com.github.noahshen.alcyone.context.memory.core.extraction.PreExtractionContextBuilder
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudget
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig

/**
 * CT10-B2 默认预提取上下文 Builder（Runtime internal；D1/D5～D7/D10/D11）。
 *
 * **构造期 fail-fast**（只读配置，不访问 DB/LLM）：
 * - 缺 `context.llm.tasks.EXTRACTION` → `INVALID_CONFIG`（消息含该路径）；
 * - Task 引用的 Profile 不存在 → `INVALID_CONFIG`（消息含 `context.llm.tasks.EXTRACTION.profile`）；
 * - Profile `jsonMode == PROMPT_ONLY` → `INVALID_CONFIG`（消息含 `context.llm.profiles.<name>.jsonMode`，
 *   只允许 `JSON_SCHEMA` / `JSON_OBJECT`）。
 * 消息只含字段路径，不回显 Key、URL 或正文。
 *
 * **build**：整个流程在**一个** `database.read` 回调内完成（同一 SQLite WAL 快照，回调内无挂起/无 LLM/无 I/O）：
 * 1. `latestPending` 为空 → [ExtractionBuildResult.NoPending]（不渲染、不估算）；
 * 2. 先算**基础请求**（空轮次 `system + user("")` + JSON_SCHEMA 时的 schema）；超限 →
 *    [ExtractionBuildResult.FixedOverheadOverBudget]；
 * 3. 以 `initialWindowTurns` 为初始 LIMIT 取连续窗口；完整请求估算 Fits → [ExtractionBuildResult.Built]；
 *    非 Fits 且只剩 1 条 → [ExtractionBuildResult.SingleTurnOverBudget]（锚点 id）；
 *    否则按**实际读取条数**折半（`max(1, K / 2)`）继续，首次满足即停止。
 *
 * 不写库、不修改 `extractionStatus`、不调用 LLM；`SingleTurnOverBudget`/`FixedOverheadOverBudget`
 * 只是返回结果（CT11 才决定是否标记 failed）。
 */
internal class DefaultPreExtractionContextBuilder(
    private val database: ContextDatabase,
    llmConfig: LlmConfig,
    private val extractionConfig: ExtractionConfig,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
) : PreExtractionContextBuilder {
    private val task: LlmTaskConfig
    private val profile: LlmProfileConfig
    private val safetyMarginPercent: Int = llmConfig.safetyMarginPercent
    private val schema: String?

    /** 测试接缝：每次 `sessionWindow` 读取**之前**调用（生产为 null；非挂起，仅测试阻塞用）。 */
    internal var betweenReadsForTesting: (() -> Unit)? = null

    init {
        val taskConfig =
            llmConfig.tasks[LlmTask.EXTRACTION]
                ?: throw invalidConfig("context.llm.tasks.EXTRACTION is required for extraction")
        val profileConfig =
            llmConfig.profiles[taskConfig.profile]
                ?: throw invalidConfig(
                    "context.llm.tasks.EXTRACTION.profile refers to an unknown profile: ${taskConfig.profile}",
                )
        if (profileConfig.jsonMode == LlmResponseFormat.PROMPT_ONLY) {
            throw invalidConfig(
                "context.llm.profiles.${profileConfig.name}.jsonMode must be JSON_SCHEMA or JSON_OBJECT for extraction",
            )
        }
        task = taskConfig
        profile = profileConfig
        // 资源在构造期读取一次：缺失/空白立即失败（不进入 build 的空转路径）。
        schema = if (profileConfig.jsonMode == LlmResponseFormat.JSON_SCHEMA) ExtractionPrompt.schema() else null
    }

    override suspend fun build(sessionKey: SessionKey): ExtractionBuildResult =
        database.read { read ->
            val anchor =
                read.conversations.latestPending(sessionKey)
                    ?: return@read ExtractionBuildResult.NoPending

            // 2) 基础请求（空轮次）：固定开销超限时不再读窗口。
            when (val overhead = estimate(emptyList())) {
                is ExtractionBudgetStatus.Fits -> Unit
                is ExtractionBudgetStatus.Over ->
                    return@read ExtractionBuildResult.FixedOverheadOverBudget(overhead.requiredTotalTokens)
            }

            // 3) 连续窗口 + 按实际读取条数折半。
            var limit = extractionConfig.initialWindowTurns
            while (true) {
                betweenReadsForTesting?.invoke()
                val turns =
                    read.conversations.sessionWindow(sessionKey, anchor.timestamp, anchor.id, limit)
                if (turns.isEmpty()) return@read ExtractionBuildResult.NoPending

                when (val status = estimate(turns)) {
                    is ExtractionBudgetStatus.Fits ->
                        return@read ExtractionBuildResult.Built(ExtractionContext(sessionKey, turns))

                    is ExtractionBudgetStatus.Over ->
                        if (turns.size == 1) {
                            return@read ExtractionBuildResult.SingleTurnOverBudget(
                                anchorTurnId = anchor.id,
                                requiredTotalTokens = status.requiredTotalTokens,
                            )
                        } else {
                            limit = ExtractionBudgetPolicy.nextWindowSize(turns.size)
                        }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ExtractionBuildResult.NoPending
        }

    /**
     * 用与 [build] **完全相同**的 task/profile/schema/Prompt.messages 构造请求（CT11-B2 Runner 复用）。
     *
     * 只读、不访问数据库、不改变 [build] 的行为。
     */
    internal fun requestFor(context: ExtractionContext): LlmRequest = request(ExtractionPrompt.messages(context.turns))

    /** 用同一 schema/格式估算一组轮次；只做纯计算，不写库、不调 LLM。 */
    private fun estimate(turns: List<StoredConversationTurn>): ExtractionBudgetStatus {
        val request = request(ExtractionPrompt.messages(turns))
        val decision =
            LlmBudget.estimate(
                request = request,
                schema = schema,
                task = task,
                profile = profile,
                safetyMarginPercent = safetyMarginPercent,
                estimator = estimator,
            )
        return ExtractionBudgetPolicy.classify(decision, task, profile)
    }

    private fun request(messages: List<LlmMessage>): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = messages,
            responseFormat = profile.jsonMode,
            maxOutputTokens = null,
            schema = schema,
        )

    private companion object {
        fun invalidConfig(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
    }
}
