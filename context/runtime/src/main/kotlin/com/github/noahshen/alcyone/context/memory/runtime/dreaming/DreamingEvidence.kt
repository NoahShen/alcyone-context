package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import java.util.Collections

/** CT25 Reflection 前的最终 Topic 决策。 */
internal enum class DreamingEvidenceDecision {
    FULLY_COVERED,
    NEEDS_REFLECTION,
}

/** 单个 Topic 的最终证据包；所有列表均为防御性不可修改快照。 */
internal class DreamingTopicEvidence(
    val topic: DiscoveredTopic,
    val decision: DreamingEvidenceDecision,
    newConversationTurns: List<StoredConversationTurn>,
    organizedMemories: List<OrganizedMemory>,
    rawMemories: List<RawMemory>,
    historicalConversationTurns: List<StoredConversationTurn>,
) {
    val newConversationTurns: List<StoredConversationTurn> =
        Collections.unmodifiableList(newConversationTurns.toList())
    val organizedMemories: List<OrganizedMemory> = Collections.unmodifiableList(organizedMemories.toList())
    val rawMemories: List<RawMemory> = Collections.unmodifiableList(rawMemories.toList())
    val historicalConversationTurns: List<StoredConversationTurn> =
        Collections.unmodifiableList(historicalConversationTurns.toList())

    override fun toString(): String =
        "DreamingTopicEvidence(decision=$decision, newConversationTurnCount=${newConversationTurns.size}, " +
            "organizedMemoryCount=${organizedMemories.size}, rawMemoryCount=${rawMemories.size}, " +
            "historicalConversationTurnCount=${historicalConversationTurns.size})"
}

/** 一次 Dreaming Batch 的最终证据包。 */
internal class DreamingBatchEvidence(
    topics: List<DreamingTopicEvidence>,
) {
    val topics: List<DreamingTopicEvidence> = Collections.unmodifiableList(topics.toList())

    override fun toString(): String = "DreamingBatchEvidence(topicCount=${topics.size})"
}

/** Source trace 的中间结果；最终 Stage 会将 B1 Raw 与 trace Raw 合并。 */
internal class DreamingConversationTrace(
    rawMemories: List<RawMemory>,
    organizedMemories: List<OrganizedMemory>,
    historicalConversationTurns: List<StoredConversationTurn>,
) {
    val rawMemories: List<RawMemory> = Collections.unmodifiableList(rawMemories.toList())
    val organizedMemories: List<OrganizedMemory> = Collections.unmodifiableList(organizedMemories.toList())
    val historicalConversationTurns: List<StoredConversationTurn> =
        Collections.unmodifiableList(historicalConversationTurns.toList())
}
