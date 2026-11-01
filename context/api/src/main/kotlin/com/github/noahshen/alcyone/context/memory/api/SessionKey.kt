package com.github.noahshen.alcyone.context.memory.api

/**
 * 会话三元稳定业务标识：定位一个具体的 Agent 会话。
 */
data class SessionKey(
    val runtimeId: String,
    val agentId: String,
    val sessionId: String,
) {
    init {
        require(runtimeId.isNotBlank()) { "runtimeId must not be blank" }
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }
}
