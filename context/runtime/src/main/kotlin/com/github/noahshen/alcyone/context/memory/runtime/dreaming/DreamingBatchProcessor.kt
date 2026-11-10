package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn

/** Coordinator 固定范围内的一批真实 Turn；所有处理组件共用同一范围契约。 */
internal data class DreamingBatchContext(
    val expectedCheckpoint: Long,
    val upperBound: Long,
    val turns: List<StoredConversationTurn>,
)

internal fun interface DreamingBatchProcessor {
    suspend fun process(context: DreamingBatchContext): DreamingBatchOutcome
}

/** 在任何 Discovery/Evidence 副作用前执行完整范围防御。 */
internal fun DreamingBatchContext.requireValidRange() {
    require(expectedCheckpoint >= 0L && upperBound >= 0L) { "dreaming batch checkpoint and upper bound must be non-negative" }
    require(turns.isNotEmpty()) { "dreaming batch turns must not be empty" }
    require(turns.first().id > expectedCheckpoint) { "dreaming batch starts at or before expected checkpoint" }
    require(turns.last().id <= upperBound) { "dreaming batch exceeds fixed upper bound" }
    require(turns.zipWithNext().all { (left, right) -> left.id < right.id }) { "dreaming batch turn ids must be strictly ascending" }
}

internal data class DreamingBatchOutcome(
    val lastTurnId: Long,
    val discoveredTopics: Int,
    val generatedMemories: Int,
)

/** 返回 null 表示预算内；否则 requiredTotalTokens。 */
internal fun interface DreamingBatchBudget {
    suspend fun requiredTotalFor(batch: List<StoredConversationTurn>): Long?
}
