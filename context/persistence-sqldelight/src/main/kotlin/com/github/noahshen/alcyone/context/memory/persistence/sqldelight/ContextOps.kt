package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.repository.ConversationReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.ConversationTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryTxOps
import java.time.Instant

/**
 * Conversation 的 ReadOps / TxOps 实现（CT05-B1）。
 *
 * 绑定规则（架构任务书「实现边界」）：
 * - Ops 只使用**调用方传入**的 [RawReadScope] / [RawTransactionScope]，不自己开连接、
 *   不调用 `executor.read/write`（防嵌套）、不缓存作用域；
 * - 每次查询都经 `queryPrepared` / `updatePrepared`，从而复用 CT04 的
 *   `ScopedJdbc` + `SqlBoundaryGuard` + 关闭协议（逃逸、跨线程、中毒、事务控制全部照旧）；
 * - 事务内读取与写入共享同一 scope / session / 物理连接；
 * - 业务错误（[ContextIngestionException]）不被基础设施映射吞掉，
 *   `CancellationException` 原样传播。
 *
 * 本文件在 CT05-B1 覆盖 Conversation；B2 追加 Raw / Organized / Pending / Checkpoint。
 *
 * [ConversationQuery] 是 Ops 层共享的作用域内参数化查询入口：B1 由 Conversation 首次引入，
 * B2 的其他 Ops 复用同一接口，确保都经 `queryPrepared` 走 CT04 的受控驱动与线程/逃逸校验。
 */
internal interface ConversationQuery {
    fun <T> queryPrepared(
        sql: String,
        bind: (SqlPreparedStatement) -> Unit,
        mapper: (SqlCursor) -> T,
    ): T
}

private class ReadScopeConversationQuery(
    private val scope: RawReadScope,
) : ConversationQuery {
    override fun <T> queryPrepared(
        sql: String,
        bind: (SqlPreparedStatement) -> Unit,
        mapper: (SqlCursor) -> T,
    ): T = scope.queryPrepared(sql, bind, mapper)
}

private class TransactionScopeConversationQuery(
    private val scope: RawTransactionScope,
) : ConversationQuery {
    override fun <T> queryPrepared(
        sql: String,
        bind: (SqlPreparedStatement) -> Unit,
        mapper: (SqlCursor) -> T,
    ): T = scope.queryPrepared(sql, bind, mapper)
}

/**
 * `conversation_turn` 行 → [StoredConversationTurn] 的唯一映射。
 *
 * 列顺序与 [ContextSqlQueries] 的 `TURN_COLUMNS` 严格一致；时间 / JSON / 状态
 * 的非法内容统一映射为 `PERSISTENCE_UNAVAILABLE`，不泄露库内原文。
 */
internal object ConversationTurnMapper {
    fun map(cursor: SqlCursor): StoredConversationTurn {
        val id = cursor.getLong(0) ?: throw malformedRow()
        val runtimeId = cursor.getString(1) ?: throw malformedRow()
        val agentId = cursor.getString(2) ?: throw malformedRow()
        val sessionId = cursor.getString(3) ?: throw malformedRow()
        val sessionTurnId = cursor.getString(4) ?: throw malformedRow()
        val timestamp = decodeTime(cursor.getString(5))
        val createdAt = decodeTime(cursor.getString(6))
        val messages = ContextJsonCodecs.decodeMessages(cursor.getString(7) ?: throw malformedRow())
        val status = decodeStatus(cursor.getString(8))
        return try {
            StoredConversationTurn(
                id = id,
                runtimeId = runtimeId,
                agentId = agentId,
                sessionId = sessionId,
                sessionTurnId = sessionTurnId,
                timestamp = timestamp,
                createdAt = createdAt,
                messages = messages,
                extractionStatus = status,
            )
        } catch (failure: IllegalArgumentException) {
            // 例如 messages_json 是合法 JSON 数组但为空：StoredConversationTurn 拒绝空消息。
            throw malformedRow()
        }
    }

    fun decodeTime(text: String?): Instant {
        val value = text ?: throw malformedRow()
        return try {
            ContextDbTime.decode(value)
        } catch (failure: IllegalArgumentException) {
            throw malformedRow()
        }
    }

    private fun decodeStatus(text: String?): ExtractionStatus =
        when (text) {
            "pending" -> ExtractionStatus.PENDING
            "complete" -> ExtractionStatus.COMPLETE
            "failed" -> ExtractionStatus.FAILED
            else -> throw malformedRow()
        }

    private fun malformedRow(): ContextPersistenceException = persistenceUnavailable("conversation row is not a valid stored turn")
}

/**
 * 分页参数前置校验（P2）。
 *
 * 必须在**执行任何 SQL 之前**调用：SQLite 的 `LIMIT -1` 等于无上界、`OFFSET -1` 行为不确定，
 * 不能把非法值直接绑进语句。`limit = 0` / `offset = 0` 是合法值（limit=0 返回空页）。
 *
 * 错误用 Kotlin `require` 抛 `IllegalArgumentException`（与本模块既有参数校验口径一致），
 * 消息只含字段名与非法值，不回显 SQL 原文。这是**调用方参数错误**，不应伪装成
 * `PERSISTENCE_UNAVAILABLE` 而误导诊断或触发重试。Core KDoc 的分页契约语义与之一致。
 */
internal fun requirePagination(
    limit: Int,
    offset: Int? = null,
) {
    require(limit >= 0) { "pagination limit must be >= 0, got $limit" }
    if (offset != null) require(offset >= 0) { "pagination offset must be >= 0, got $offset" }
}

/** 只读 Ops：绑定调用方传入的 [RawReadScope]。 */
class ConversationReadOpsImpl(
    scope: RawReadScope,
) : ConversationReadOps {
    private val reader = ConversationTurnReader(ReadScopeConversationQuery(scope))

    override fun getById(id: Long): StoredConversationTurn? = reader.getById(id)

    override fun findByBusinessKey(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
    ): StoredConversationTurn? = reader.findByBusinessKey(runtimeId, agentId, sessionId, sessionTurnId)

    override fun sessionWindow(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        beforeTimestamp: Instant?,
        beforeId: Long?,
        limit: Int,
    ): List<StoredConversationTurn> = reader.sessionWindow(runtimeId, agentId, sessionId, beforeTimestamp, beforeId, limit)

    override fun sessionWindowAfter(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        afterTimestamp: Instant,
        afterId: Long,
        limit: Int,
    ): List<StoredConversationTurn> = reader.sessionWindowAfter(runtimeId, agentId, sessionId, afterTimestamp, afterId, limit)

    override fun latestPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): StoredConversationTurn? = reader.latestPending(runtimeId, agentId, sessionId)

    override fun countPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): Int = reader.countPending(runtimeId, agentId, sessionId)

    override fun idleCheck(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        idleBefore: Instant,
    ): Boolean = reader.idleCheck(runtimeId, agentId, sessionId, idleBefore)

    override fun findIdleSessions(idleBefore: Instant): List<SessionKey> = reader.findIdleSessions(idleBefore)

    override fun maxTurnId(): Long? = reader.maxTurnId()

    override fun idRange(
        fromIdExclusive: Long,
        toIdInclusive: Long,
        limit: Int,
    ): List<StoredConversationTurn> = reader.idRange(fromIdExclusive, toIdInclusive, limit)
}

/** 事务 Ops：读取与写入共享调用方传入的同一个 [RawTransactionScope]。 */
class ConversationTxOpsImpl(
    private val scope: RawTransactionScope,
) : ConversationTxOps {
    private val reader = ConversationTurnReader(TransactionScopeConversationQuery(scope))

    override fun getById(id: Long): StoredConversationTurn? = reader.getById(id)

    override fun findByBusinessKey(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
    ): StoredConversationTurn? = reader.findByBusinessKey(runtimeId, agentId, sessionId, sessionTurnId)

    override fun sessionWindow(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        beforeTimestamp: Instant?,
        beforeId: Long?,
        limit: Int,
    ): List<StoredConversationTurn> = reader.sessionWindow(runtimeId, agentId, sessionId, beforeTimestamp, beforeId, limit)

    override fun sessionWindowAfter(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        afterTimestamp: Instant,
        afterId: Long,
        limit: Int,
    ): List<StoredConversationTurn> = reader.sessionWindowAfter(runtimeId, agentId, sessionId, afterTimestamp, afterId, limit)

    override fun latestPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): StoredConversationTurn? = reader.latestPending(runtimeId, agentId, sessionId)

    override fun countPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): Int = reader.countPending(runtimeId, agentId, sessionId)

    override fun idleCheck(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        idleBefore: Instant,
    ): Boolean = reader.idleCheck(runtimeId, agentId, sessionId, idleBefore)

    override fun findIdleSessions(idleBefore: Instant): List<SessionKey> = reader.findIdleSessions(idleBefore)

    override fun maxTurnId(): Long? = reader.maxTurnId()

    override fun idRange(
        fromIdExclusive: Long,
        toIdInclusive: Long,
        limit: Int,
    ): List<StoredConversationTurn> = reader.idRange(fromIdExclusive, toIdInclusive, limit)

    /**
     * 幂等插入（CT05 方案 §2.1）：
     * 四元键不存在 → 插入 pending 行并返回 `CREATED`；
     * 存在且**规范化时间文本 + 有序消息**完全一致 → 返回原 id 的 `DUPLICATE`，不刷新 created_at；
     * 存在但内容不同 → 抛 `ContextIngestionException(TURN_CONFLICT)`，拒绝静默覆盖。
     *
     * 时间比较使用库内规范文本（[ContextDbTime]），因此同一毫秒的等价 Instant 视为相同；
     * 消息比较使用解码后的有序列表，JSON 转义差异不会误判冲突。
     */
    override fun insertTurn(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
        timestamp: Instant,
        createdAt: Instant,
        messages: List<ChatMessage>,
    ): IngestResult {
        if (messages.isEmpty()) {
            throw ContextIngestionException(
                ContextIngestionErrorCode.INVALID_TURN,
                "conversation turn must contain at least one message",
            )
        }
        val encodedTimestamp = ContextDbTime.encode(timestamp)
        val existing = reader.findByBusinessKey(runtimeId, agentId, sessionId, sessionTurnId)
        if (existing != null) {
            val sameTimestamp = ContextDbTime.encode(existing.timestamp) == encodedTimestamp
            val sameMessages = existing.messages == messages
            if (sameTimestamp && sameMessages) {
                return IngestResult(existing.id, IngestStatus.DUPLICATE)
            }
            throw ContextIngestionException(
                ContextIngestionErrorCode.TURN_CONFLICT,
                "conversation turn already exists with different content in this session",
            )
        }
        val encodedMessages = ContextJsonCodecs.encodeMessages(messages)
        scope.updatePrepared(ContextSqlQueries.INSERT_TURN) { bind ->
            bind.bindString(0, runtimeId)
            bind.bindString(1, agentId)
            bind.bindString(2, sessionId)
            bind.bindString(3, sessionTurnId)
            bind.bindString(4, encodedTimestamp)
            bind.bindString(5, ContextDbTime.encode(createdAt))
            bind.bindString(6, encodedMessages)
        }
        val id =
            scope.queryPrepared(ContextSqlQueries.SELECT_LAST_INSERT_ROW_ID, {}, { cursor ->
                if (cursor.next().value) cursor.getLong(0) else null
            }) ?: throw persistenceUnavailable("conversation turn insert did not report a row id")
        return IngestResult(id, IngestStatus.CREATED)
    }

    /** 条件更新，返回真实 `executeUpdate` 行数；已非 pending 时为 0。 */
    override fun markComplete(id: Long): Int = scope.updatePrepared(ContextSqlQueries.MARK_COMPLETE) { it.bindLong(0, id) }.toInt()

    override fun markFailed(id: Long): Int = scope.updatePrepared(ContextSqlQueries.MARK_FAILED) { it.bindLong(0, id) }.toInt()
}

/**
 * Conversation 查询逻辑的唯一实现，ReadOps / TxOps 共用；只依赖 [ConversationQuery]，
 * 因此事务内外语义一致。
 */
internal class ConversationTurnReader(
    private val queries: ConversationQuery,
) {
    fun getById(id: Long): StoredConversationTurn? =
        queries.queryPrepared(ContextSqlQueries.SELECT_TURN_BY_ID, { it.bindLong(0, id) }) { cursor ->
            if (cursor.next().value) ConversationTurnMapper.map(cursor) else null
        }

    fun findByBusinessKey(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
    ): StoredConversationTurn? =
        queries.queryPrepared(ContextSqlQueries.SELECT_TURN_BY_BUSINESS_KEY, { bind ->
            bind.bindString(0, runtimeId)
            bind.bindString(1, agentId)
            bind.bindString(2, sessionId)
            bind.bindString(3, sessionTurnId)
        }) { cursor ->
            if (cursor.next().value) ConversationTurnMapper.map(cursor) else null
        }

    /**
     * 窗口边界：`beforeTimestamp == null` 表示无锚点（取最新 limit 条）；
     * 有锚点时包含锚点自身，`beforeId == null` 视为该时刻 id 不设上界。
     *
     * 无锚点 / 有锚点使用两条独立参数化 SQL：CT04 的受控绑定白名单不支持 NULL，
     * 这里不绕过它，也不把值拼进 SQL。
     */
    fun sessionWindow(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        beforeTimestamp: Instant?,
        beforeId: Long?,
        limit: Int,
    ): List<StoredConversationTurn> {
        requirePagination(limit)
        if (beforeTimestamp == null) {
            return queries.queryPrepared(ContextSqlQueries.SELECT_SESSION_WINDOW_LATEST, { bind ->
                bind.bindString(0, runtimeId)
                bind.bindString(1, agentId)
                bind.bindString(2, sessionId)
                bind.bindLong(3, limit.toLong())
            }) { cursor -> readNewestPage(cursor) }
        }
        val anchor = ContextDbTime.encode(beforeTimestamp)
        val anchorId = beforeId ?: Long.MAX_VALUE
        return queries.queryPrepared(ContextSqlQueries.SELECT_SESSION_WINDOW, { bind ->
            bind.bindString(0, runtimeId)
            bind.bindString(1, agentId)
            bind.bindString(2, sessionId)
            bind.bindString(3, anchor)
            bind.bindString(4, anchor)
            bind.bindLong(5, anchorId)
            bind.bindLong(6, limit.toLong())
        }) { cursor -> readNewestPage(cursor) }
    }

    /** 严格前向窗口，直接按 `timestamp ASC, id ASC` 返回。 */
    fun sessionWindowAfter(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        afterTimestamp: Instant,
        afterId: Long,
        limit: Int,
    ): List<StoredConversationTurn> {
        requirePagination(limit)
        if (limit == 0) return emptyList()
        val anchor = ContextDbTime.encode(afterTimestamp)
        return queries.queryPrepared(ContextSqlQueries.SELECT_SESSION_WINDOW_AFTER, { bind ->
            bind.bindString(0, runtimeId)
            bind.bindString(1, agentId)
            bind.bindString(2, sessionId)
            bind.bindString(3, anchor)
            bind.bindString(4, anchor)
            bind.bindLong(5, afterId)
            bind.bindLong(6, limit.toLong())
        }) { cursor ->
            val rows = mutableListOf<StoredConversationTurn>()
            while (cursor.next().value) rows += ConversationTurnMapper.map(cursor)
            rows
        }
    }

    /** 按 `timestamp DESC, id DESC` 读出的页，反序为真实会话顺序。 */
    private fun readNewestPage(cursor: SqlCursor): List<StoredConversationTurn> {
        val rows = mutableListOf<StoredConversationTurn>()
        while (cursor.next().value) rows += ConversationTurnMapper.map(cursor)
        return rows.asReversed()
    }

    fun latestPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): StoredConversationTurn? =
        queries.queryPrepared(ContextSqlQueries.SELECT_LATEST_PENDING, { bind ->
            bind.bindString(0, runtimeId)
            bind.bindString(1, agentId)
            bind.bindString(2, sessionId)
        }) { cursor ->
            if (cursor.next().value) ConversationTurnMapper.map(cursor) else null
        }

    fun countPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): Int =
        queries.queryPrepared(ContextSqlQueries.COUNT_PENDING, { bind ->
            bind.bindString(0, runtimeId)
            bind.bindString(1, agentId)
            bind.bindString(2, sessionId)
        }) { cursor ->
            if (cursor.next().value) cursor.getLong(0)?.toInt() ?: 0 else 0
        }

    fun idleCheck(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        idleBefore: Instant,
    ): Boolean {
        val hasPending =
            queries.queryPrepared(ContextSqlQueries.EXISTS_PENDING, { bind ->
                bind.bindString(0, runtimeId)
                bind.bindString(1, agentId)
                bind.bindString(2, sessionId)
            }) { cursor ->
                if (cursor.next().value) cursor.getLong(0) == 1L else false
            }
        if (!hasPending) return false
        val maxCreatedAt =
            queries.queryPrepared(ContextSqlQueries.MAX_CREATED_AT, { bind ->
                bind.bindString(0, runtimeId)
                bind.bindString(1, agentId)
                bind.bindString(2, sessionId)
            }) { cursor ->
                if (cursor.next().value) cursor.getString(0) else null
            } ?: return false
        return ConversationTurnMapper.decodeTime(maxCreatedAt) <= idleBefore
    }

    fun findIdleSessions(idleBefore: Instant): List<SessionKey> =
        queries.queryPrepared(ContextSqlQueries.SELECT_IDLE_SESSIONS, { it.bindString(0, ContextDbTime.encode(idleBefore)) }) { cursor ->
            val sessions = mutableListOf<SessionKey>()
            while (cursor.next().value) {
                sessions +=
                    SessionKey(
                        runtimeId = cursor.getString(0) ?: throw malformedSession(),
                        agentId = cursor.getString(1) ?: throw malformedSession(),
                        sessionId = cursor.getString(2) ?: throw malformedSession(),
                    )
            }
            sessions
        }

    fun maxTurnId(): Long? =
        queries.queryPrepared(ContextSqlQueries.SELECT_MAX_TURN_ID, {}) { cursor ->
            if (cursor.next().value) cursor.getLong(0) else null
        }

    fun idRange(
        fromIdExclusive: Long,
        toIdInclusive: Long,
        limit: Int,
    ): List<StoredConversationTurn> {
        requirePagination(limit)
        return queries.queryPrepared(ContextSqlQueries.SELECT_TURN_ID_RANGE, { bind ->
            bind.bindLong(0, fromIdExclusive)
            bind.bindLong(1, toIdInclusive)
            bind.bindLong(2, limit.toLong())
        }) { cursor ->
            val rows = mutableListOf<StoredConversationTurn>()
            while (cursor.next().value) rows += ConversationTurnMapper.map(cursor)
            rows
        }
    }

    private fun malformedSession() = persistenceUnavailable("conversation session row is not a valid session key")
}

// =====================================================================
// B2：Raw Memory
// =====================================================================

/**
 * `raw_memory` 行 → [RawMemory] 的唯一映射。
 *
 * `generated_by` 必须精确等于 [MemoryOrigin] 名字，`sources_json` 严格按
 * [MemorySourceRef] 两个变体解码；任何非法内容映射为 `PERSISTENCE_UNAVAILABLE`。
 */
internal object RawMemoryMapper {
    fun map(cursor: SqlCursor): RawMemory {
        val id = cursor.getString(0) ?: throw malformedRaw()
        val content = cursor.getString(1) ?: throw malformedRaw()
        val generatedBy = decodeOrigin(cursor.getString(2))
        val sources = ContextJsonCodecs.decodeSources(cursor.getString(3) ?: throw malformedRaw())
        val createdAt = decodeStoredInstant(cursor.getString(4), ::malformedRaw)
        return try {
            RawMemory(
                id = id,
                content = content,
                generatedBy = generatedBy,
                sources = sources,
                createdAt = createdAt,
            )
        } catch (failure: IllegalArgumentException) {
            throw malformedRaw()
        }
    }

    fun decodeOrigin(text: String?): MemoryOrigin = MemoryOrigin.entries.firstOrNull { it.name == text } ?: throw malformedRaw()

    private fun malformedRaw(): ContextPersistenceException = persistenceUnavailable("raw memory row is not a valid stored record")
}

/** Raw Memory 只读 Ops：绑定调用方传入的 [RawReadScope]。 */
class RawMemoryReadOpsImpl(
    scope: RawReadScope,
) : RawMemoryReadOps {
    private val reader = RawMemoryReader(ReadScopeConversationQuery(scope))

    override fun findById(id: String): RawMemory? = reader.findById(id)

    override fun findByIds(ids: Collection<String>): List<RawMemory> = reader.findByIds(ids)

    override fun scanPage(
        offset: Int,
        limit: Int,
    ): List<RawMemory> = reader.scanPage(offset, limit)
}

/** Raw Memory 写 Ops：只有插入（不可变），读取共享同一事务 scope。 */
class RawMemoryTxOpsImpl(
    private val scope: RawTransactionScope,
) : RawMemoryTxOps {
    private val reader = RawMemoryReader(TransactionScopeConversationQuery(scope))

    override fun findById(id: String): RawMemory? = reader.findById(id)

    override fun findByIds(ids: Collection<String>): List<RawMemory> = reader.findByIds(ids)

    override fun scanPage(
        offset: Int,
        limit: Int,
    ): List<RawMemory> = reader.scanPage(offset, limit)

    /** 逐条插入并累计真实 `executeUpdate` 行数；重复 id 由 UNIQUE 约束安全失败。 */
    override fun insertBatch(memories: List<RawMemory>): Int {
        var total = 0
        memories.forEach { memory ->
            total +=
                scope
                    .updatePrepared(ContextSqlQueries.INSERT_RAW) { bind ->
                        bind.bindString(0, memory.id)
                        bind.bindString(1, memory.content)
                        bind.bindString(2, memory.generatedBy.name)
                        bind.bindString(3, ContextJsonCodecs.encodeSources(memory.sources))
                        bind.bindString(4, ContextDbTime.encode(memory.createdAt))
                    }.toInt()
        }
        return total
    }
}

internal class RawMemoryReader(
    private val queries: ConversationQuery,
) {
    fun findById(id: String): RawMemory? =
        queries.queryPrepared(ContextSqlQueries.SELECT_RAW_BY_ID, { it.bindString(0, id) }) { cursor ->
            if (cursor.next().value) RawMemoryMapper.map(cursor) else null
        }

    /** 空集合直接返回空列表，不执行 SQL（避免 `IN ()` 语法错误）。 */
    fun findByIds(ids: Collection<String>): List<RawMemory> {
        if (ids.isEmpty()) return emptyList()
        val ordered = ids.toList()
        return queries.queryPrepared(ContextSqlQueries.selectRawByIds(ordered.size), { bind ->
            ordered.forEachIndexed { index, id -> bind.bindString(index, id) }
        }) { cursor ->
            val rows = mutableListOf<RawMemory>()
            while (cursor.next().value) rows += RawMemoryMapper.map(cursor)
            rows
        }
    }

    fun scanPage(
        offset: Int,
        limit: Int,
    ): List<RawMemory> {
        requirePagination(limit, offset)
        return queries.queryPrepared(ContextSqlQueries.SELECT_RAW_PAGE, { bind ->
            bind.bindLong(0, limit.toLong())
            bind.bindLong(1, offset.toLong())
        }) { cursor ->
            val rows = mutableListOf<RawMemory>()
            while (cursor.next().value) rows += RawMemoryMapper.map(cursor)
            rows
        }
    }
}

// =====================================================================
// B2：Organized Memory 与来源关联
// =====================================================================

/**
 * `organized_memory` 行 → [OrganizedMemory] 的唯一映射。
 *
 * 表内不保存 `rawMemoryIds`；来源由 `organized_memory_source` 关联表提供，
 * 因此这里先映射为空列表，再由 Ops 通过来源正查补齐（保证与关联表一致）。
 */
internal object OrganizedMemoryMapper {
    fun map(cursor: SqlCursor): OrganizedMemory {
        val id = cursor.getString(0) ?: throw malformedOrganized()
        val content = cursor.getString(1) ?: throw malformedOrganized()
        val domains = ContextJsonCodecs.decodeDomains(cursor.getString(2) ?: throw malformedOrganized())
        val createdAt = decodeStoredInstant(cursor.getString(3), ::malformedOrganized)
        val updatedAt = decodeStoredInstant(cursor.getString(4), ::malformedOrganized)
        return try {
            OrganizedMemory(
                id = id,
                content = content,
                domains = domains,
                rawMemoryIds = emptyList(),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        } catch (failure: IllegalArgumentException) {
            throw malformedOrganized()
        }
    }

    private fun malformedOrganized(): ContextPersistenceException =
        persistenceUnavailable("organized memory row is not a valid stored record")
}

/** Organized Memory 只读 Ops：绑定调用方传入的 [RawReadScope]。 */
class OrganizedMemoryReadOpsImpl(
    scope: RawReadScope,
) : OrganizedMemoryReadOps {
    private val reader = OrganizedMemoryReader(ReadScopeConversationQuery(scope))

    override fun findById(id: String): OrganizedMemory? = reader.findById(id)

    override fun findByIds(ids: Collection<String>): List<OrganizedMemory> = reader.findByIds(ids)

    override fun findSourcesByOrganized(organizedId: String): List<String> = reader.findSourcesByOrganized(organizedId)

    override fun findOrganizedByRaw(rawId: String): List<String> = reader.findOrganizedByRaw(rawId)

    override fun maxUpdatedAt(): Instant? = reader.maxUpdatedAt()

    override fun scanPage(
        offset: Int,
        limit: Int,
    ): List<OrganizedMemory> = reader.scanPage(offset, limit)
}

/** Organized Memory 写 Ops：写入与读取共享同一事务 scope。 */
class OrganizedMemoryTxOpsImpl(
    private val scope: RawTransactionScope,
) : OrganizedMemoryTxOps {
    private val reader = OrganizedMemoryReader(TransactionScopeConversationQuery(scope))

    override fun findById(id: String): OrganizedMemory? = reader.findById(id)

    override fun findByIds(ids: Collection<String>): List<OrganizedMemory> = reader.findByIds(ids)

    override fun findSourcesByOrganized(organizedId: String): List<String> = reader.findSourcesByOrganized(organizedId)

    override fun findOrganizedByRaw(rawId: String): List<String> = reader.findOrganizedByRaw(rawId)

    override fun maxUpdatedAt(): Instant? = reader.maxUpdatedAt()

    override fun scanPage(
        offset: Int,
        limit: Int,
    ): List<OrganizedMemory> = reader.scanPage(offset, limit)

    /**
     * 只插入 `organized_memory` 行；来源关联必须由调用方另行 [insertSourceLink]
     * （与 Port 契约、CT05 方案 §2.3 一致）。
     */
    override fun insertNew(memory: OrganizedMemory): Int =
        scope
            .updatePrepared(ContextSqlQueries.INSERT_ORGANIZED) { bind ->
                bind.bindString(0, memory.id)
                bind.bindString(1, memory.content)
                bind.bindString(2, ContextJsonCodecs.encodeDomains(memory.domains))
                bind.bindString(3, ContextDbTime.encode(memory.createdAt))
                bind.bindString(4, ContextDbTime.encode(memory.updatedAt))
            }.toInt()

    /** 只更新正文 / 领域 / updated_at；来源关联不经此方法。 */
    override fun update(memory: OrganizedMemory): Int =
        scope
            .updatePrepared(ContextSqlQueries.UPDATE_ORGANIZED) { bind ->
                bind.bindString(0, memory.content)
                bind.bindString(1, ContextJsonCodecs.encodeDomains(memory.domains))
                bind.bindString(2, ContextDbTime.encode(memory.updatedAt))
                bind.bindString(3, memory.id)
            }.toInt()

    override fun insertSourceLink(
        organizedId: String,
        rawId: String,
    ): Int =
        scope
            .updatePrepared(ContextSqlQueries.INSERT_SOURCE_LINK) { bind ->
                bind.bindString(0, organizedId)
                bind.bindString(1, rawId)
            }.toInt()

    /**
     * 推进单调水位（P3：**全局分配是调用方契约，本层不加局部 CAS**）。
     *
     * 调用方必须在**同一个写事务**内完成：
     * `maxUpdatedAt()` 读取当前全局最大值 → 分配严格更大的毫秒值 `next > max` →
     * 本方法写回。多行 / MERGE 路径也要逐次重新读取并分配。
     *
     * 若调用方传入回退值（`next <= 当前全局 max`），属于**契约违规**，本层不提供防护，
     * 也不把它当成可接受行为；底层只做单行 `UPDATE` 并返回真实影响行数。
     */
    override fun advanceUpdatedAt(
        organizedId: String,
        next: Instant,
    ): Int =
        scope
            .updatePrepared(ContextSqlQueries.ADVANCE_UPDATED_AT) { bind ->
                bind.bindString(0, ContextDbTime.encode(next))
                bind.bindString(1, organizedId)
            }.toInt()
}

internal class OrganizedMemoryReader(
    private val queries: ConversationQuery,
) {
    fun findById(id: String): OrganizedMemory? {
        val row =
            queries.queryPrepared(ContextSqlQueries.SELECT_ORGANIZED_BY_ID, { it.bindString(0, id) }) { cursor ->
                if (cursor.next().value) OrganizedMemoryMapper.map(cursor) else null
            }
        return row?.let { attachSources(listOf(it)).single() }
    }

    fun findByIds(ids: Collection<String>): List<OrganizedMemory> {
        if (ids.isEmpty()) return emptyList()
        val ordered = ids.toList()
        val rows =
            queries.queryPrepared(ContextSqlQueries.selectOrganizedByIds(ordered.size), { bind ->
                ordered.forEachIndexed { index, id -> bind.bindString(index, id) }
            }) { cursor ->
                val mapped = mutableListOf<OrganizedMemory>()
                while (cursor.next().value) mapped += OrganizedMemoryMapper.map(cursor)
                mapped
            }
        return attachSources(rows)
    }

    fun findSourcesByOrganized(organizedId: String): List<String> =
        queries.queryPrepared(ContextSqlQueries.SELECT_SOURCES_BY_ORGANIZED, { it.bindString(0, organizedId) }) { cursor ->
            val ids = mutableListOf<String>()
            while (cursor.next().value) ids += cursor.getString(0) ?: throw malformedLink()
            ids
        }

    fun findOrganizedByRaw(rawId: String): List<String> =
        queries.queryPrepared(ContextSqlQueries.SELECT_ORGANIZED_BY_RAW, { it.bindString(0, rawId) }) { cursor ->
            val ids = mutableListOf<String>()
            while (cursor.next().value) ids += cursor.getString(0) ?: throw malformedLink()
            ids
        }

    fun maxUpdatedAt(): Instant? =
        queries
            .queryPrepared(ContextSqlQueries.SELECT_MAX_UPDATED_AT, {}) { cursor ->
                if (cursor.next().value) cursor.getString(0) else null
            }?.let { decodeStoredInstant(it, ::malformedOrganized) }

    fun scanPage(
        offset: Int,
        limit: Int,
    ): List<OrganizedMemory> {
        requirePagination(limit, offset)
        val rows =
            queries.queryPrepared(ContextSqlQueries.SELECT_ORGANIZED_PAGE, { bind ->
                bind.bindLong(0, limit.toLong())
                bind.bindLong(1, offset.toLong())
            }) { cursor ->
                val mapped = mutableListOf<OrganizedMemory>()
                while (cursor.next().value) mapped += OrganizedMemoryMapper.map(cursor)
                mapped
            }
        return attachSources(rows)
    }

    /** 用关联表补齐 `rawMemoryIds`，保证读取结果与权威关联表一致。 */
    private fun attachSources(rows: List<OrganizedMemory>): List<OrganizedMemory> =
        rows.map { row -> row.copy(rawMemoryIds = findSourcesByOrganized(row.id)) }

    private fun malformedOrganized(): ContextPersistenceException =
        persistenceUnavailable("organized memory row is not a valid stored record")

    private fun malformedLink(): ContextPersistenceException =
        persistenceUnavailable("organized memory source link is not a valid reference")
}

// =====================================================================
// B2：Organization Pending
// =====================================================================

/** Pending 只读 Ops：绑定调用方传入的 [RawReadScope]。 */
class OrganizationPendingReadOpsImpl(
    scope: RawReadScope,
) : OrganizationPendingReadOps {
    private val reader = OrganizationPendingReader(ReadScopeConversationQuery(scope))

    override fun exists(rawId: String): Boolean = reader.exists(rawId)

    override fun hasAnyPending(): Boolean = reader.hasAnyPending()

    override fun nextByRawCreatedAt(): String? = reader.nextByRawCreatedAt()

    override fun listAllByRawCreatedAt(): List<String> = reader.listAllByRawCreatedAt()
}

/** Pending 写 Ops：入队幂等、出队返回真实行数。 */
class OrganizationPendingTxOpsImpl(
    private val scope: RawTransactionScope,
) : OrganizationPendingTxOps {
    private val reader = OrganizationPendingReader(TransactionScopeConversationQuery(scope))

    override fun exists(rawId: String): Boolean = reader.exists(rawId)

    override fun hasAnyPending(): Boolean = reader.hasAnyPending()

    override fun nextByRawCreatedAt(): String? = reader.nextByRawCreatedAt()

    override fun listAllByRawCreatedAt(): List<String> = reader.listAllByRawCreatedAt()

    /** `INSERT OR IGNORE`：已存在时返回 0。 */
    override fun enqueue(rawId: String): Int = scope.updatePrepared(ContextSqlQueries.ENQUEUE_PENDING) { it.bindString(0, rawId) }.toInt()

    override fun dequeue(rawId: String): Int = scope.updatePrepared(ContextSqlQueries.DEQUEUE_PENDING) { it.bindString(0, rawId) }.toInt()
}

internal class OrganizationPendingReader(
    private val queries: ConversationQuery,
) {
    fun exists(rawId: String): Boolean =
        queries.queryPrepared(ContextSqlQueries.EXISTS_PENDING_RAW, { it.bindString(0, rawId) }) { cursor ->
            cursor.next().value && cursor.getLong(0) == 1L
        }

    fun hasAnyPending(): Boolean =
        queries.queryPrepared(ContextSqlQueries.HAS_ANY_PENDING, {}) { cursor ->
            cursor.next().value && cursor.getLong(0) == 1L
        }

    fun nextByRawCreatedAt(): String? =
        queries.queryPrepared(ContextSqlQueries.SELECT_NEXT_PENDING_BY_RAW_CREATED_AT, {}) { cursor ->
            if (cursor.next().value) cursor.getString(0) else null
        }

    fun listAllByRawCreatedAt(): List<String> =
        queries.queryPrepared(ContextSqlQueries.SELECT_ALL_PENDING_BY_RAW_CREATED_AT, {}) { cursor ->
            val ids = mutableListOf<String>()
            while (cursor.next().value) {
                ids += cursor.getString(0) ?: throw persistenceUnavailable("organization pending row is missing a raw id")
            }
            ids
        }
}

// =====================================================================
// B2：Dreaming Checkpoint
// =====================================================================

/** Checkpoint 只读 Ops：固定单行（id=1）。 */
class DreamingCheckpointReadOpsImpl(
    scope: RawReadScope,
) : DreamingCheckpointReadOps {
    private val reader = DreamingCheckpointReader(ReadScopeConversationQuery(scope))

    override fun readCheckpoint(): Long = reader.readCheckpoint()
}

/** Checkpoint 写 Ops：CAS 只在 expected 匹配且 next 严格向前时更新。 */
class DreamingCheckpointTxOpsImpl(
    private val scope: RawTransactionScope,
) : DreamingCheckpointTxOps {
    private val reader = DreamingCheckpointReader(TransactionScopeConversationQuery(scope))

    override fun readCheckpoint(): Long = reader.readCheckpoint()

    override fun casUpdateCheckpoint(
        expected: Long,
        next: Long,
    ): Int =
        scope
            .updatePrepared(ContextSqlQueries.CAS_UPDATE_CHECKPOINT) { bind ->
                bind.bindLong(0, next)
                bind.bindLong(1, expected)
                bind.bindLong(2, next)
            }.toInt()
}

internal class DreamingCheckpointReader(
    private val queries: ConversationQuery,
) {
    fun readCheckpoint(): Long =
        queries.queryPrepared(ContextSqlQueries.SELECT_CHECKPOINT, {}) { cursor ->
            if (cursor.next().value) {
                cursor.getLong(0) ?: throw malformedCheckpoint()
            } else {
                throw malformedCheckpoint()
            }
        }

    private fun malformedCheckpoint(): ContextPersistenceException =
        persistenceUnavailable("dreaming checkpoint row is missing or not a valid number")
}

/** 共享的时间列解码：非规范文本映射为调用方给定的安全异常。 */
internal fun decodeStoredInstant(
    text: String?,
    malformed: () -> ContextPersistenceException,
): Instant {
    val value = text ?: throw malformed()
    return try {
        ContextDbTime.decode(value)
    } catch (failure: IllegalArgumentException) {
        throw malformed()
    }
}
