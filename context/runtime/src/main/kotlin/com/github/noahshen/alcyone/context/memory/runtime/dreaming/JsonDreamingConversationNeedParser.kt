package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingConversationNeed
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.MemorySearchJsonObjectReader

/** Conversation Need 响应严格解析；只接受 memoryIds 字符串数组。 */
internal class JsonDreamingConversationNeedParser {
    fun parse(content: String): DreamingConversationNeed =
        DreamingConversationNeed(
            MemorySearchJsonObjectReader.readStringArray(
                content = content,
                field = MEMORY_IDS,
                errorMessage = INVALID_RESPONSE_MESSAGE,
            ),
        )

    private companion object {
        const val MEMORY_IDS = "memoryIds"
        const val INVALID_RESPONSE_MESSAGE = "invalid dreaming conversation need response"
    }
}
