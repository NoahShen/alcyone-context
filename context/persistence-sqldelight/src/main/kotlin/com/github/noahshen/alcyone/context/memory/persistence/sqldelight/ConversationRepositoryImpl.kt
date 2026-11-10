package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.repository.ConversationRepository
import java.time.Instant

/**
 * Conversation 普通 suspend 读取 Repository（CT05-B1）。
 *
 * 每个方法都是**一次** `executor.read { }` 短作用域：作用域随回调结束立即失效，
 * 不在 Repository 字段里缓存 scope / 连接 / Ops，因此跨调用不保证同一快照
 * （与 Port 契约一致）。作用域内部一律走 [ConversationReadOpsImpl]，
 * 从而复用 CT04 的线程归属、逃逸与关闭保护。
 *
 * 本文件在 CT05-B1 只覆盖 Conversation；其余 Port 的 Repository 由 B2 追加。
 */
class ConversationRepositoryImpl(
    private val executor: ContextDatabaseExecutor,
) : ConversationRepository {
    override suspend fun getById(id: Long): StoredConversationTurn? = executor.read { scope -> ConversationReadOpsImpl(scope).getById(id) }

    override suspend fun findByBusinessKey(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
    ): StoredConversationTurn? =
        executor.read { scope ->
            ConversationReadOpsImpl(scope).findByBusinessKey(runtimeId, agentId, sessionId, sessionTurnId)
        }

    override suspend fun sessionWindow(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        beforeTimestamp: Instant?,
        beforeId: Long?,
        limit: Int,
    ): List<StoredConversationTurn> =
        executor.read { scope ->
            ConversationReadOpsImpl(scope).sessionWindow(runtimeId, agentId, sessionId, beforeTimestamp, beforeId, limit)
        }

    override suspend fun sessionWindowAfter(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        afterTimestamp: Instant,
        afterId: Long,
        limit: Int,
    ): List<StoredConversationTurn> =
        executor.read { scope ->
            ConversationReadOpsImpl(scope).sessionWindowAfter(runtimeId, agentId, sessionId, afterTimestamp, afterId, limit)
        }

    override suspend fun latestPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): StoredConversationTurn? = executor.read { scope -> ConversationReadOpsImpl(scope).latestPending(runtimeId, agentId, sessionId) }

    override suspend fun countPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): Int = executor.read { scope -> ConversationReadOpsImpl(scope).countPending(runtimeId, agentId, sessionId) }

    override suspend fun idleCheck(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        idleBefore: Instant,
    ): Boolean = executor.read { scope -> ConversationReadOpsImpl(scope).idleCheck(runtimeId, agentId, sessionId, idleBefore) }

    override suspend fun findIdleSessions(idleBefore: Instant): List<SessionKey> =
        executor.read { scope -> ConversationReadOpsImpl(scope).findIdleSessions(idleBefore) }

    override suspend fun maxTurnId(): Long? = executor.read { scope -> ConversationReadOpsImpl(scope).maxTurnId() }

    override suspend fun idRange(
        fromIdExclusive: Long,
        toIdInclusive: Long,
        limit: Int,
    ): List<StoredConversationTurn> =
        executor.read { scope -> ConversationReadOpsImpl(scope).idRange(fromIdExclusive, toIdInclusive, limit) }
}
