package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision
import java.util.Collections

/** CT25 Reflection 前的 Topic 证据候选；不持久化，不暴露正文到 toString。 */
internal class DreamingTopicEvidenceCandidate(
    val topic: DiscoveredTopic,
    val decision: DreamingNoveltyDecision,
    newConversationTurns: List<StoredConversationTurn>,
    organizedMemories: List<OrganizedMemory>,
    rawMemories: List<RawMemory>,
) {
    val newConversationTurns: List<StoredConversationTurn> =
        Collections.unmodifiableList(newConversationTurns.toList())
    val organizedMemories: List<OrganizedMemory> = Collections.unmodifiableList(organizedMemories.toList())
    val rawMemories: List<RawMemory> = Collections.unmodifiableList(rawMemories.toList())

    override fun toString(): String =
        "DreamingTopicEvidenceCandidate(decision=$decision, topic=$topic, " +
            "newConversationTurnCount=${newConversationTurns.size}, organizedMemoryCount=${organizedMemories.size}, " +
            "rawMemoryCount=${rawMemories.size})"
}
