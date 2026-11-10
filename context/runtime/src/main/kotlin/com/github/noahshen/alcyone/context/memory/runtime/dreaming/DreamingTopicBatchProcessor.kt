package com.github.noahshen.alcyone.context.memory.runtime.dreaming

/** CT22 Processor seam 的 Topic 薄 adapter：只返回 final Topic 计数，不持久化 Topic。 */
internal class DreamingTopicBatchProcessor(
    private val stage: DreamingTopicDiscoveryStage,
) : DreamingBatchProcessor {
    override suspend fun process(context: DreamingBatchContext): DreamingBatchOutcome {
        context.requireValidRange()
        val topics = stage.discover(context.turns)
        return DreamingBatchOutcome(
            lastTurnId = context.turns.last().id,
            discoveredTopics = topics.size,
            generatedMemories = 0,
        )
    }
}
