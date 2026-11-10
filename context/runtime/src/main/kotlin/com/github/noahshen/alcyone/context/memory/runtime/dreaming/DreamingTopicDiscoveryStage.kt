package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic

/** CT23 Topic Discovery seam；返回完整 Topic 列表供 CT24 消费。 */
internal fun interface DreamingTopicDiscoveryStage {
    suspend fun discover(batch: List<StoredConversationTurn>): List<DiscoveredTopic>
}
