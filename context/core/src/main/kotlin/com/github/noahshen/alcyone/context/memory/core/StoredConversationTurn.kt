package com.github.noahshen.alcyone.context.memory.core

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import java.time.Instant
import java.util.Collections

/**
 * 内部持久化会话记录，由 Core/Persistence 内部持有，不进公开 API。
 *
 * 与公开 ConversationTurn 严格分离：多出 SQLite 自增主键 id、服务端分配的
 * createdAt 以及内部提取状态 extractionStatus；四元业务键用于幂等与定位。
 * messages 在所有构造与复制路径均执行防御性拷贝并包装为不可修改 List。
 */
class StoredConversationTurn(
    val id: Long,
    val runtimeId: String,
    val agentId: String,
    val sessionId: String,
    val sessionTurnId: String,
    val timestamp: Instant,
    val createdAt: Instant,
    messages: List<ChatMessage>,
    val extractionStatus: ExtractionStatus,
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
        id: Long = this.id,
        runtimeId: String = this.runtimeId,
        agentId: String = this.agentId,
        sessionId: String = this.sessionId,
        sessionTurnId: String = this.sessionTurnId,
        timestamp: Instant = this.timestamp,
        createdAt: Instant = this.createdAt,
        messages: List<ChatMessage> = this.messages,
        extractionStatus: ExtractionStatus = this.extractionStatus,
    ): StoredConversationTurn =
        StoredConversationTurn(
            id = id,
            runtimeId = runtimeId,
            agentId = agentId,
            sessionId = sessionId,
            sessionTurnId = sessionTurnId,
            timestamp = timestamp,
            createdAt = createdAt,
            messages = messages,
            extractionStatus = extractionStatus,
        )

    operator fun component1(): Long = id

    operator fun component2(): String = runtimeId

    operator fun component3(): String = agentId

    operator fun component4(): String = sessionId

    operator fun component5(): String = sessionTurnId

    operator fun component6(): Instant = timestamp

    operator fun component7(): Instant = createdAt

    operator fun component8(): List<ChatMessage> = messages

    operator fun component9(): ExtractionStatus = extractionStatus

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredConversationTurn) return false
        return id == other.id &&
            runtimeId == other.runtimeId &&
            agentId == other.agentId &&
            sessionId == other.sessionId &&
            sessionTurnId == other.sessionTurnId &&
            timestamp == other.timestamp &&
            createdAt == other.createdAt &&
            messages == other.messages &&
            extractionStatus == other.extractionStatus
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + runtimeId.hashCode()
        result = 31 * result + agentId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + sessionTurnId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + messages.hashCode()
        result = 31 * result + extractionStatus.hashCode()
        return result
    }

    override fun toString(): String =
        "StoredConversationTurn(id=$id, runtimeId=$runtimeId, agentId=$agentId, sessionId=$sessionId, sessionTurnId=$sessionTurnId, timestamp=$timestamp, createdAt=$createdAt, messages=$messages, extractionStatus=$extractionStatus)"
}
