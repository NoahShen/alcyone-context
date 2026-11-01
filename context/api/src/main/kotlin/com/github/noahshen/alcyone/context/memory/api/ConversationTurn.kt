package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant
import java.util.Collections

/**
 * 公开 SDK 输入契约：一条会话轮次。
 *
 * 严格仅含六字段，绝不包含内部主键 `id`、入库时间 `createdAt` 或提取状态 `extractionStatus`；
 * 后三者由服务端在持久化边界统一分配，外部调用方不得提供。
 *
 * `messages` 在所有构造器与 [copy] 路径均执行防御性浅拷贝并包装为不可修改 List，
 * 元素 [ChatMessage] 本身不可变。
 */
class ConversationTurn(
    val runtimeId: String,
    val agentId: String,
    val sessionId: String,
    val sessionTurnId: String,
    val timestamp: Instant,
    messages: List<ChatMessage>,
) {
    val messages: List<ChatMessage> = Collections.unmodifiableList(messages.toList())

    init {
        require(runtimeId.isNotBlank()) { "runtimeId must not be blank" }
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(sessionTurnId.isNotBlank()) { "sessionTurnId must not be blank" }
        require(this.messages.isNotEmpty()) { "messages must not be empty" }
    }

    val sessionKey: SessionKey
        get() = SessionKey(runtimeId, agentId, sessionId)

    fun copy(
        runtimeId: String = this.runtimeId,
        agentId: String = this.agentId,
        sessionId: String = this.sessionId,
        sessionTurnId: String = this.sessionTurnId,
        timestamp: Instant = this.timestamp,
        messages: List<ChatMessage> = this.messages,
    ): ConversationTurn =
        ConversationTurn(
            runtimeId = runtimeId,
            agentId = agentId,
            sessionId = sessionId,
            sessionTurnId = sessionTurnId,
            timestamp = timestamp,
            messages = messages,
        )

    operator fun component1(): String = runtimeId

    operator fun component2(): String = agentId

    operator fun component3(): String = sessionId

    operator fun component4(): String = sessionTurnId

    operator fun component5(): Instant = timestamp

    operator fun component6(): List<ChatMessage> = messages

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConversationTurn) return false
        return runtimeId == other.runtimeId &&
            agentId == other.agentId &&
            sessionId == other.sessionId &&
            sessionTurnId == other.sessionTurnId &&
            timestamp == other.timestamp &&
            messages == other.messages
    }

    override fun hashCode(): Int {
        var result = runtimeId.hashCode()
        result = 31 * result + agentId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + sessionTurnId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + messages.hashCode()
        return result
    }

    override fun toString(): String =
        "ConversationTurn(runtimeId=$runtimeId, agentId=$agentId, sessionId=$sessionId, sessionTurnId=$sessionTurnId, timestamp=$timestamp, messages=$messages)"

    companion object {
        fun create(
            runtimeId: String,
            agentId: String,
            sessionId: String,
            sessionTurnId: String,
            timestamp: Instant,
            messages: List<ChatMessage>,
        ): ConversationTurn =
            ConversationTurn(
                runtimeId = runtimeId,
                agentId = agentId,
                sessionId = sessionId,
                sessionTurnId = sessionTurnId,
                timestamp = timestamp,
                messages = messages,
            )
    }
}
