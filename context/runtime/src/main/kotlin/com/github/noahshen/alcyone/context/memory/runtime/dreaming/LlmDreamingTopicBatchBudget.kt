package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn

/** 将 CT22 Budget seam 委托给同一个 Discovery Stage 的预算路径。 */
internal class LlmDreamingTopicBatchBudget(
    private val stage: LlmDreamingTopicDiscoveryStage,
) : DreamingBatchBudget {
    override suspend fun requiredTotalFor(batch: List<StoredConversationTurn>): Long? = stage.discoveryBudgetFor(batch)
}
