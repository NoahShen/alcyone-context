package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator

/**
 * 发送前预算检查的判定结果（纯值对象，便于精确断言）。
 *
 * 这些数都是**估算**，不是 Provider 的真实计费值；真实 Usage 由响应里的
 * `TokenUsage` 单独承载（缺失 = 未知，不填 0）。
 */
data class LlmBudgetDecision(
    /** `estimatePrompt(messages) + estimateSchema(schema)`。 */
    val estimatedInputTokens: Long,
    /** 实际将作为 `max_tokens` 发送的保留量。 */
    val effectiveMaxOutputTokens: Int,
    /** 安全余量：只按输入估算加**一次**。 */
    val safetyMarginTokens: Long,
    /** `estimatedInput + effectiveMaxOutputTokens + safetyMarginTokens`。 */
    val requiredTotalTokens: Long,
)

/**
 * 发送前 Token 预算校验（技术实现设计 §3.3；纯逻辑，不做 HTTP、不选数据）。
 *
 * 两条条件缺一不可：
 * 1. `estimateInput ≤ task.maxInputTokens`；
 * 2. `estimateInput + 输出保留 + 安全余量 ≤ profile.contextWindowTokens`。
 *
 * 任一不满足 → [LlmErrorCode.LOCAL_BUDGET_EXCEEDED]，**不发起请求**。
 * 所有加法/乘法用 `Long` 并做饱和保护：估算值接近 `Int.MAX_VALUE` 时也不会溢出成负数而绕过限额。
 * [TokenEstimator] 的返回值必须非负：负值属实现错误，在**任何预算算术之前**抛
 * [LlmErrorCode.INVALID_REQUEST]（fail closed，不 clamp 为 0），否则负数会抵消余量/输出保留
 * 而静默绕过预算，或让饱和加法失真。
 * 输出保留优先级：`min(request.maxOutputTokens ?: task.maxOutputTokens, task.maxOutputTokens)`
 * ——业务只能下调、不能超过 Task 预算；`request.maxOutputTokens < 1` 属请求错误。
 */
object LlmBudget {
    /**
     * 计算实际发送的 `max_tokens`。
     *
     * @throws LlmException `INVALID_REQUEST`：显式请求值 `< 1`。
     */
    fun effectiveMaxOutputTokens(
        requestedMaxOutputTokens: Int?,
        task: LlmTaskConfig,
    ): Int {
        if (requestedMaxOutputTokens != null && requestedMaxOutputTokens < 1) {
            throw LlmException(
                errorCode = LlmErrorCode.INVALID_REQUEST,
                message = "request.maxOutputTokens must be at least 1",
            )
        }
        return if (requestedMaxOutputTokens == null) {
            task.maxOutputTokens
        } else {
            minOf(requestedMaxOutputTokens, task.maxOutputTokens)
        }
    }

    /**
     * 只计算预算判定，不做预算额度拒绝（供诊断与单测使用；**不发起请求**）。
     *
     * 入参非法仍会拒绝：估算器返回负数、输出覆盖非法 → `INVALID_REQUEST`；
     * 余量百分比越界 → `CONFIGURATION`。
     *
     * @throws LlmException `INVALID_REQUEST`（输出覆盖非法 / 估算器返回负数）
     *   或 `CONFIGURATION`（余量百分比越界）。
     */
    fun estimate(
        request: LlmRequest,
        // B1 无 `LlmRequest.schema`（B2 追加）；调用方显式传入实际将发送的 schema 或 null。
        schema: String?,
        task: LlmTaskConfig,
        profile: LlmProfileConfig,
        safetyMarginPercent: Int,
        estimator: TokenEstimator,
    ): LlmBudgetDecision {
        if (safetyMarginPercent !in 0..100) {
            throw LlmException(
                errorCode = LlmErrorCode.CONFIGURATION,
                message = "llm.safetyMarginPercent must be in 0..100",
                profile = profile.name,
                model = profile.model,
            )
        }
        val effectiveOutput = effectiveMaxOutputTokens(request.maxOutputTokens, task)
        // 前置非负校验：必须在任何加法/乘法之前，且不 clamp（fail closed）。
        val promptTokens =
            nonNegativeTokenEstimate(
                source = "estimator.estimatePrompt",
                tokens = estimator.estimatePrompt(request.messages),
                profile = profile,
            )
        val schemaTokens =
            nonNegativeTokenEstimate(
                source = "estimator.estimateSchema",
                tokens = estimator.estimateSchema(schema),
                profile = profile,
            )
        val estimatedInput = saturatedAdd(promptTokens, schemaTokens)
        val margin = marginTokens(estimatedInput, safetyMarginPercent)
        val required =
            saturatedAdd(
                saturatedAdd(estimatedInput, effectiveOutput.toLong()),
                margin,
            )
        return LlmBudgetDecision(
            estimatedInputTokens = estimatedInput,
            effectiveMaxOutputTokens = effectiveOutput,
            safetyMarginTokens = margin,
            requiredTotalTokens = required,
        )
    }

    /**
     * 计算并校验预算；通过则返回判定结果，未通过则抛 `LOCAL_BUDGET_EXCEEDED`。
     *
     * @throws LlmException `LOCAL_BUDGET_EXCEEDED`（输入超 Task 预算 / 输入+输出+余量超 Profile 容量）
     *   或 `INVALID_REQUEST`/`CONFIGURATION`（见 [estimate]）。
     */
    fun requireWithinBudget(
        request: LlmRequest,
        schema: String?,
        task: LlmTaskConfig,
        profile: LlmProfileConfig,
        safetyMarginPercent: Int,
        estimator: TokenEstimator,
    ): LlmBudgetDecision {
        val decision = estimate(request, schema, task, profile, safetyMarginPercent, estimator)
        if (decision.estimatedInputTokens > task.maxInputTokens.toLong()) {
            throw LlmException(
                errorCode = LlmErrorCode.LOCAL_BUDGET_EXCEEDED,
                message =
                    "estimated input tokens exceed the task input budget " +
                        "(estimated=${decision.estimatedInputTokens}, maxInputTokens=${task.maxInputTokens})",
                profile = profile.name,
                model = profile.model,
            )
        }
        if (decision.requiredTotalTokens > profile.contextWindowTokens.toLong()) {
            throw LlmException(
                errorCode = LlmErrorCode.LOCAL_BUDGET_EXCEEDED,
                message =
                    "estimated input, output reserve and safety margin exceed the profile context window " +
                        "(required=${decision.requiredTotalTokens}, contextWindowTokens=${profile.contextWindowTokens})",
                profile = profile.name,
                model = profile.model,
            )
        }
        return decision
    }

    /**
     * 校验估算器返回值非负并转 `Long`：非法估值是调用方/实现错误，不是预算超限。
     *
     * 消息只带来源名与数值，**不回显** messages/schema 原文、Key 或 URL。
     */
    private fun nonNegativeTokenEstimate(
        source: String,
        tokens: Int,
        profile: LlmProfileConfig,
    ): Long {
        if (tokens < 0) {
            throw LlmException(
                errorCode = LlmErrorCode.INVALID_REQUEST,
                message = "$source must return a non-negative token count (actual=$tokens)",
                profile = profile.name,
                model = profile.model,
            )
        }
        return tokens.toLong()
    }

    /** 余量 = `ceil(input * percent / 100)`；只按输入估算计算，不叠加到输出保留之外。 */
    internal fun marginTokens(
        estimatedInputTokens: Long,
        safetyMarginPercent: Int,
    ): Long {
        val product = saturatedMultiply(estimatedInputTokens, safetyMarginPercent.toLong())
        return saturatedAdd(product, 99L) / 100L
    }

    private fun saturatedAdd(
        left: Long,
        right: Long,
    ): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatedMultiply(
        left: Long,
        right: Long,
    ): Long {
        if (left == 0L || right == 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }
}
