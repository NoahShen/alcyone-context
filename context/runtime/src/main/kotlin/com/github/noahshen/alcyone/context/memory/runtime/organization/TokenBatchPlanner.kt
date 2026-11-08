package com.github.noahshen.alcyone.context.memory.runtime.organization

/** 分批预算超限（内部信号；由调用方映射为 `LOCAL_BUDGET_EXCEEDED`）。 */
internal class CandidateBudgetExceededException(
    val reason: Reason,
    val requiredTotalTokens: Long,
) : RuntimeException("candidate batch budget exceeded: $reason") {
    enum class Reason {
        /** 固定开销（Prompt + Raw + 输出契约 + Schema）即超限。 */
        FIXED_OVERHEAD,

        /** 单条候选连同固定开销放不进一个新批。 */
        SINGLE_ITEM,
    }
}

/** 分批预算检查：返回 `null` 表示该批在预算内，返回 `requiredTotalTokens` 表示超限。 */
internal fun interface CandidateBatchBudget<T> {
    suspend fun requiredTotalFor(batch: List<T>): Long?
}

/**
 * CT13 通用预算分批（Runtime internal，纯逻辑 + 可挂起检查）。
 *
 * 规则（与设计 §6.4 一致）：
 * 1. 先检查**固定开销**（空批）；超限 → [CandidateBudgetExceededException.Reason.FIXED_OVERHEAD]；
 * 2. 按顺序逐条加入当前批：仍通过 → 保留；超限且当前批非空 → 封批，并对**该条单独**重新检查
 *    （换批后必须重算，不得因上一批非空就默认可容纳）；
 * 3. 当前批为空且单条仍超限 → [CandidateBudgetExceededException.Reason.SINGLE_ITEM]；
 * 4. 不跳过、不截断、不返回部分：所有条目都被分配或整体抛错；等于预算边界视为通过（由调用方判定）。
 */
internal object TokenBatchPlanner {
    suspend fun <T> plan(
        items: List<T>,
        budget: CandidateBatchBudget<T>,
    ): List<List<T>> {
        budget.requiredTotalFor(emptyList())?.let { required ->
            throw CandidateBudgetExceededException(CandidateBudgetExceededException.Reason.FIXED_OVERHEAD, required)
        }
        if (items.isEmpty()) return emptyList()

        val batches = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        items.forEach { item ->
            val candidate = current + item
            val required = budget.requiredTotalFor(candidate)
            if (required == null) {
                current = candidate.toMutableList()
            } else if (current.isNotEmpty()) {
                batches += current
                // 换批后重新检查当前单条能否独立放入新批。
                val singleRequired = budget.requiredTotalFor(listOf(item))
                if (singleRequired != null) {
                    throw CandidateBudgetExceededException(
                        CandidateBudgetExceededException.Reason.SINGLE_ITEM,
                        singleRequired,
                    )
                }
                current = mutableListOf(item)
            } else {
                throw CandidateBudgetExceededException(CandidateBudgetExceededException.Reason.SINGLE_ITEM, required)
            }
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }
}
