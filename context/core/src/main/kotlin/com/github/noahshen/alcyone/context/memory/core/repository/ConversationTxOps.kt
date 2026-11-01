package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import java.time.Instant

/**
 * Conversation 写操作（TransactionContext 中可用，回调同步不可挂起）。
 */
interface ConversationTxOps : ConversationReadOps {
    /**
     * 幂等插入会话轮次。
     *
     * 语义与冲突契约：
     * - 四元业务键不存在：写入新行，分配自增主键，返回 `IngestResult(id, CREATED)`；
     * - 四元业务键已存在且规范化时间与有序消息内容完全相同：幂等返回原记录的 `IngestResult(id, DUPLICATE)`，不刷新 createdAt；
     * - 四元业务键已存在但规范化内容不一致：**抛出 [ContextIngestionException]，错误码为 [ContextIngestionErrorCode.TURN_CONFLICT]**，拒绝静默覆盖。
     *
     * 事务内必须检查影响行数，不能以忽略结果的 UPDATE 模拟状态已变更。
     *
     * @throws ContextIngestionException 当发生同键内容冲突时抛出 (TURN_CONFLICT)。
     */
    fun insertTurn(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
        timestamp: Instant,
        createdAt: Instant,
        messages: List<ChatMessage>,
    ): IngestResult

    fun insertTurn(
        sessionKey: SessionKey,
        sessionTurnId: String,
        timestamp: Instant,
        createdAt: Instant,
        messages: List<ChatMessage>,
    ): IngestResult =
        insertTurn(sessionKey.runtimeId, sessionKey.agentId, sessionKey.sessionId, sessionTurnId, timestamp, createdAt, messages)

    /** 条件标记为 Complete；返回受影响行数，期望为 1 且当前仍为 pending。 */
    fun markComplete(id: Long): Int

    /** 条件标记为 Failed；返回受影响行数。 */
    fun markFailed(id: Long): Int
}
