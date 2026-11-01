package com.github.noahshen.alcyone.context.memory.core.extraction

/**
 * CT10 预提取上下文构建结果（Core public，非 `context/api` SDK；D1 冻结四态）。
 *
 * 只表达「能否构建、为什么不能」，**不携带 Prompt、对话正文、Key 或 URL**：
 * - [Built]：成功构建的 [ExtractionContext]；
 * - [NoPending]：该会话没有 `PENDING` 轮次，无需提取；
 * - [SingleTurnOverBudget]：只剩锚点单条轮次仍超预算（[anchorTurnId] 为锚点轮次 id）；
 * - [FixedOverheadOverBudget]：连基础请求开销都超预算（与具体轮次无关）。
 *
 * 预算字段只给出 `requiredTotalTokens`（输入 + 输出保留 + 安全余量），供诊断与后续 CT11 决策使用。
 */
sealed interface ExtractionBuildResult {
    /** 成功构建的提取上下文。 */
    data class Built(
        val context: ExtractionContext,
    ) : ExtractionBuildResult

    /** 会话没有待提取轮次。 */
    data object NoPending : ExtractionBuildResult

    /** 单条锚点轮次仍超预算。 */
    data class SingleTurnOverBudget(
        val anchorTurnId: Long,
        val requiredTotalTokens: Long,
    ) : ExtractionBuildResult

    /** 基础请求（固定开销）即超预算。 */
    data class FixedOverheadOverBudget(
        val requiredTotalTokens: Long,
    ) : ExtractionBuildResult
}
