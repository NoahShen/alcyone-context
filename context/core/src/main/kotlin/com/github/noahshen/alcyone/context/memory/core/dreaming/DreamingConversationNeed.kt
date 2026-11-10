package com.github.noahshen.alcyone.context.memory.core.dreaming

import java.util.Collections

/** Conversation Need 模型输出；只保存 Raw candidate 的业务 ID。 */
class DreamingConversationNeed(
    memoryIds: List<String>,
) {
    val memoryIds: List<String> = Collections.unmodifiableList(memoryIds.toList())

    fun copy(memoryIds: List<String> = this.memoryIds): DreamingConversationNeed = DreamingConversationNeed(memoryIds)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DreamingConversationNeed) return false
        return memoryIds == other.memoryIds
    }

    override fun hashCode(): Int = memoryIds.hashCode()

    override fun toString(): String = "DreamingConversationNeed(memoryIdCount=${memoryIds.size})"
}

/** Conversation Need 选择超出本次 Raw candidate 权限时使用的安全异常。 */
class DreamingConversationNeedValidationException(
    message: String,
) : IllegalArgumentException(message)

/** 纯逻辑 Conversation Need 校验；不判断模型选择是否在语义上必要。 */
object DreamingConversationNeedValidator {
    fun validate(
        need: DreamingConversationNeed,
        rawCandidateIds: Set<String>,
    ): DreamingConversationNeed {
        val normalized = need.memoryIds.distinct()
        if (normalized.any { id -> id.isBlank() || id !in rawCandidateIds }) {
            throw DreamingConversationNeedValidationException("conversation need contains a raw id outside the current candidates")
        }
        return DreamingConversationNeed(normalized)
    }
}
