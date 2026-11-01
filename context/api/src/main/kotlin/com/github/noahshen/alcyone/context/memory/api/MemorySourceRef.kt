package com.github.noahshen.alcyone.context.memory.api

/**
 * 可追溯的证据引用：引用一个 Conversation Turn 或已有的 Raw Memory。
 *
 * Conversation 证据使用完整稳定业务键 `(runtimeId, agentId, sessionId, sessionTurnId)`；
 * 单独 `sessionTurnId` 可能跨 Runtime / Agent / Session 重复，不能独立引用。
 */
sealed interface MemorySourceRef {
    /** 引用某条会话轮次。 */
    data class ConversationTurnRef(
        val runtimeId: String,
        val agentId: String,
        val sessionId: String,
        val sessionTurnId: String,
    ) : MemorySourceRef

    /** 引用某条已有 Raw Memory。 */
    data class RawMemoryRef(
        val memoryId: String,
    ) : MemorySourceRef
}
