package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.time.Instant

/**
 * Conversation 只读操作（ReadContext 中可用，回调同步不可挂起）。
 */
interface ConversationReadOps {
    fun getById(id: Long): StoredConversationTurn?

    fun findByBusinessKey(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
    ): StoredConversationTurn?

    fun findByBusinessKey(
        sessionKey: SessionKey,
        sessionTurnId: String,
    ): StoredConversationTurn? = findByBusinessKey(sessionKey.runtimeId, sessionKey.agentId, sessionKey.sessionId, sessionTurnId)

    /**
     * 按会话发生时间倒序取连续窗口（含 Complete/Failed 背景）。
     *
     * 边界契约：包含锚点自身，查询条件为
     * `timestamp < anchorTimestamp OR (timestamp == anchorTimestamp AND id <= anchorId)`；
     * 底层按 `timestamp DESC, id DESC LIMIT limit` 检索，并由 Repository 反序为真实会话顺序返回。
     */
    fun sessionWindow(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        beforeTimestamp: Instant?,
        beforeId: Long?,
        limit: Int,
    ): List<StoredConversationTurn>

    fun sessionWindow(
        sessionKey: SessionKey,
        beforeTimestamp: Instant?,
        beforeId: Long?,
        limit: Int,
    ): List<StoredConversationTurn> =
        sessionWindow(sessionKey.runtimeId, sessionKey.agentId, sessionKey.sessionId, beforeTimestamp, beforeId, limit)

    fun latestPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): StoredConversationTurn?

    fun latestPending(sessionKey: SessionKey): StoredConversationTurn? =
        latestPending(sessionKey.runtimeId, sessionKey.agentId, sessionKey.sessionId)

    fun countPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): Int

    fun countPending(sessionKey: SessionKey): Int = countPending(sessionKey.runtimeId, sessionKey.agentId, sessionKey.sessionId)

    /**
     * 空闲判定：指定 Session 有 pending Turn 且其所有状态 Turn 的 created_at 均早于或等于 idleBefore。
     */
    fun idleCheck(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        idleBefore: Instant,
    ): Boolean

    fun idleCheck(
        sessionKey: SessionKey,
        idleBefore: Instant,
    ): Boolean = idleCheck(sessionKey.runtimeId, sessionKey.agentId, sessionKey.sessionId, idleBefore)

    /**
     * 枚举所有当前处于空闲状态的会话候选列表。
     *
     * 业务语义：从至少含有一条 pending Turn 的候选 Session 中，
     * 筛选出该 Session 所有状态 Turn 的最大 created_at <= idleBefore 的 Session 集合。
     */
    fun findIdleSessions(idleBefore: Instant): List<SessionKey>

    /**
     * 查询当前库中 conversation_turn.id 的最大值。
     * 用于 Dreaming 固定增量扫描上界 U = MAX(conversation_turn.id)；空表返回 null。
     */
    fun maxTurnId(): Long?

    /**
     * Dreaming 增量扫描：按内部主键 id 范围顺序读取。
     *
     * 边界契约：下界排除、上界包含（id > fromIdExclusive AND id <= toIdInclusive），
     * 底层按 `id ASC LIMIT limit` 排序返回。
     */
    fun idRange(
        fromIdExclusive: Long,
        toIdInclusive: Long,
        limit: Int,
    ): List<StoredConversationTurn>
}
