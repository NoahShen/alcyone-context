package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudgetDecision
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig

/** 一次提取请求的预算分类结果（internal，纯值对象）。 */
internal sealed interface ExtractionBudgetStatus {
    /** 落在 Task 输入预算与 Profile 上下文窗口之内。 */
    data object Fits : ExtractionBudgetStatus

    /** 超限；[requiredTotalTokens] 为 `输入 + 输出保留 + 安全余量` 的实际需求。 */
    data class Over(
        val requiredTotalTokens: Long,
    ) : ExtractionBudgetStatus
}

/**
 * CT10 提取预算的**纯分类逻辑**（D5/D6 的 B1 部分）。
 *
 * 只做算术判定：不调用 LLM、不访问数据库、不抛 `LOCAL_BUDGET_EXCEEDED`。
 * Builder（B2）用它决定「保持窗口 / 折半 / 单条 / 固定开销超限」。
 *
 * 边界口径（等于视为通过）：
 * - `estimatedInputTokens <= task.maxInputTokens`
 * - `requiredTotalTokens <= profile.contextWindowTokens`
 */
internal object ExtractionBudgetPolicy {
    /** 分类；两条条件同时满足才是 [ExtractionBudgetStatus.Fits]。 */
    fun classify(
        decision: LlmBudgetDecision,
        task: LlmTaskConfig,
        profile: LlmProfileConfig,
    ): ExtractionBudgetStatus =
        if (decision.estimatedInputTokens <= task.maxInputTokens.toLong() &&
            decision.requiredTotalTokens <= profile.contextWindowTokens.toLong()
        ) {
            ExtractionBudgetStatus.Fits
        } else {
            ExtractionBudgetStatus.Over(decision.requiredTotalTokens)
        }

    /** 折半：`max(1, current / 2)`（`0` 也返回 `1`，作为防御）。 */
    fun nextWindowSize(current: Int): Int = maxOf(1, current / 2)
}
