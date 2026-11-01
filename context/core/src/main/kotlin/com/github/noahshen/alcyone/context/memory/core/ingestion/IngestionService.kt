package com.github.noahshen.alcyone.context.memory.core.ingestion

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.repository.ConversationTxOps
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

/**
 * 会话轮次入库服务（CT07-B1，Core 内部）。
 *
 * 职责（技术实现设计专题 4 / CT07 方案 §2.2）：
 * 1. **校验与限额**：四个 ID 非空白且长度 ≤ [limits].maxIdLength；messages 非空且条数 ≤
 *    maxMessages；规范化有效载荷 UTF-8 字节 ≤ maxTurnBytes。超限 → `PAYLOAD_TOO_LARGE`，
 *    空白/空消息 → `INVALID_TURN`。
 * 2. **规范化**：`timestamp` 按 `TimeCodec` 统一为 `+08:00` 毫秒文本（等价 offset 判等）；
 *    messages 顺序与 content 原样保留（不 trim、不截断、不去重）。
 * 3. **幂等与单写事务**：一次 `database.write { }` 内按四元键查找 → 不存在则 INSERT
 *    （`pending`，`created_at = clock.instant()` 经 TimeCodec）→ `CREATED`；存在且规范化
 *    timestamp + 有序 `(role, content)` 相同 → `DUPLICATE`（原 id，不刷新任何字段）；
 *    存在但内容不同 → `TURN_CONFLICT`（不含 id/created_at/status）。
 * 4. **唯一约束竞争**：捕获持久化异常中的 SQLSTATE 23000（仅插入路径），重新读取权威记录并按
 *    内容返回 DUPLICATE / TURN_CONFLICT；其他异常原样上抛。
 * 5. **通知**：仅 CREATED 且 `database.write` 成功返回（COMMIT 成功边界）后，在事务外调用
 *    [IngestionNotifier.onCreated]；通知异常在此脱敏捕获，不回滚、不改变结果。
 *
 * 本类不依赖 Ktor/YAML/JDBC 实现：数据库访问只走 [com.github.noahshen.alcyone.context.memory.core.repository.ConversationTxOps]。
 *
 * **SDK 可信路径限制（如实记录）**：公开 `ConversationTurn.timestamp` 是 [java.time.Instant]，
 * 调用方原始文本的 offset 写法在进入 SDK 前已丢失；因此 SDK 只保证「按 Instant 幂等 + 用
 * `TimeCodec` 规范存储」与「拒绝非法 Instant」，**不声称在 SDK 层拒绝无 offset 文本**。
 * 需要校验原始时间文本的入口（HTTP/JSON）必须使用 [IngestionTimestampText.parseRequiringOffset]。
 */
class IngestionService internal constructor(
    private val limits: IngestionLimits,
    private val clock: Clock,
    private val notifier: IngestionNotifier,
    private val reporter: IngestionNotificationReporter = IngestionNotificationReporter { },
) {
    /**
     * 校验、规范化并幂等写入一个会话轮次。
     *
     * @param turn 公开 SDK 输入模型（六字段，无 id/createdAt/extractionStatus）
     * @param ops 调用方在 `database.write { }` 内提供的 ConversationTxOps（事务边界归调用方）
     */
    fun ingest(
        turn: ConversationTurn,
        ops: ConversationTxOps,
    ): IngestResult {
        val encodedTimestamp = validateAndEncode(turn)
        val existing = ops.findByBusinessKey(turn.runtimeId, turn.agentId, turn.sessionId, turn.sessionTurnId)
        if (existing != null) {
            val result = compareWithExisting(existing.id, existing.timestamp, existing.messages, encodedTimestamp, turn.messages)
            if (result.status == IngestStatus.CREATED) {
                // 比较函数对「不存在」返回 CREATED；这里 existing 非空，只能是 DUPLICATE/CONFLICT。
                throw IllegalStateException("conversation comparison unexpectedly reported CREATED for an existing turn")
            }
            return result
        }
        return try {
            val created =
                ops.insertTurn(
                    turn.runtimeId,
                    turn.agentId,
                    turn.sessionId,
                    turn.sessionTurnId,
                    turn.timestamp,
                    clock.instant(),
                    turn.messages,
                )
            if (created.status != IngestStatus.CREATED) {
                throw persistenceConflict()
            }
            created
        } catch (failure: ContextIngestionException) {
            if (failure.errorCode != ContextIngestionErrorCode.TURN_CONFLICT) throw failure
            // 唯一约束竞争：另一个写事务刚提交了同一业务键。重新读取权威记录并按内容判定。
            resolveConstraintRace(turn, encodedTimestamp, ops, failure)
        } catch (failure: ContextPersistenceException) {
            // CT04 的安全映射把底层 SQLSTATE 保留在消息文本里（不带 SQL 原文）。
            // 只有 SQLSTATE 23000（唯一约束）在本插入路径被视为可重读的竞争，其余原样上抛。
            if (!isUniqueConstraintViolation(failure)) throw failure
            resolveConstraintRace(turn, encodedTimestamp, ops, failure)
        }
    }

    /**
     * COMMIT 成功后的事务外通知；仅 CREATED 调用。
     *
     * 通知失败不影响已提交结果：异常只交给 [reporter]（不含 SessionKey/正文）。
     * 取消异常先上报再原样抛出，供关闭路径观察。
     */
    suspend fun notifyCreated(sessionKey: SessionKey) {
        try {
            notifier.onCreated(sessionKey)
        } catch (failure: CancellationException) {
            runCatching { reporter.reportFailure(failure) }
            throw failure
        } catch (failure: Exception) {
            runCatching { reporter.reportFailure(failure) }
        }
    }

    private fun resolveConstraintRace(
        turn: ConversationTurn,
        encodedTimestamp: String,
        ops: ConversationTxOps,
        original: Throwable,
    ): IngestResult {
        val winner =
            try {
                ops.findByBusinessKey(turn.runtimeId, turn.agentId, turn.sessionId, turn.sessionTurnId)
            } catch (failure: ContextPersistenceException) {
                failure.addSuppressed(original)
                throw failure
            } ?: throw persistenceConflict()
        val result = compareWithExisting(winner.id, winner.timestamp, winner.messages, encodedTimestamp, turn.messages)
        if (result.status == IngestStatus.CREATED) {
            throw persistenceConflict()
        }
        return result
    }

    private fun compareWithExisting(
        id: Long,
        storedTimestamp: Instant,
        storedMessages: List<ChatMessage>,
        encodedTimestamp: String,
        messages: List<ChatMessage>,
    ): IngestResult =
        if (TimeCodec.format(storedTimestamp) == encodedTimestamp && storedMessages == messages) {
            IngestResult(id, IngestStatus.DUPLICATE)
        } else {
            throw ContextIngestionException(
                ContextIngestionErrorCode.TURN_CONFLICT,
                "conversation turn already exists with different content in this session",
            )
        }

    private fun validateAndEncode(turn: ConversationTurn): String {
        validateId("runtimeId", turn.runtimeId)
        validateId("agentId", turn.agentId)
        validateId("sessionId", turn.sessionId)
        validateId("sessionTurnId", turn.sessionTurnId)
        if (turn.messages.isEmpty()) {
            throw invalidTurn("conversation turn must contain at least one message")
        }
        if (turn.messages.size > limits.maxMessages) {
            throw tooLarge("conversation turn exceeds the maximum number of messages")
        }
        val encodedTimestamp =
            try {
                TimeCodec.format(turn.timestamp)
            } catch (failure: Exception) {
                throw invalidTurn("conversation turn timestamp is not a valid instant")
            }
        val payloadBytes =
            turn.messages.sumOf { message ->
                message.content.toByteArray(Charsets.UTF_8).size +
                    message.role.name
                        .toByteArray(Charsets.UTF_8)
                        .size
            } + turn.runtimeId.toByteArray(Charsets.UTF_8).size +
                turn.agentId.toByteArray(Charsets.UTF_8).size +
                turn.sessionId.toByteArray(Charsets.UTF_8).size +
                turn.sessionTurnId.toByteArray(Charsets.UTF_8).size +
                encodedTimestamp.toByteArray(Charsets.UTF_8).size
        if (payloadBytes > limits.maxTurnBytes) {
            throw tooLarge("conversation turn exceeds the maximum payload size")
        }
        return encodedTimestamp
    }

    private fun validateId(
        field: String,
        value: String,
    ) {
        if (value.isBlank()) {
            throw invalidTurn("conversation turn $field must not be blank")
        }
        if (value.length > limits.maxIdLength) {
            throw tooLarge("conversation turn $field exceeds the maximum identifier length")
        }
    }

    private fun invalidTurn(detail: String): ContextIngestionException =
        ContextIngestionException(ContextIngestionErrorCode.INVALID_TURN, detail)

    private fun tooLarge(detail: String): ContextIngestionException =
        ContextIngestionException(ContextIngestionErrorCode.PAYLOAD_TOO_LARGE, detail)

    companion object {
        /**
         * 组装服务：校验限额、Clock 与通知 Port。
         *
         * Runtime 是唯一生产调用方（`database` 由 Runtime 在 start 后提供）；
         * 测试可直接构造。
         */
        fun create(
            limits: IngestionLimits,
            clock: Clock,
            notifier: IngestionNotifier,
            reporter: IngestionNotificationReporter = IngestionNotificationReporter { },
            databaseProvider: () -> com.github.noahshen.alcyone.context.memory.core.ContextDatabase,
        ): DelegatingIngestionService = DelegatingIngestionService(IngestionService(limits, clock, notifier, reporter), databaseProvider)
    }
}

/** CT04 把底层 SQLSTATE 保留在消息里；只有 23000（唯一约束）可进入重读路径。 */
private fun isUniqueConstraintViolation(failure: ContextPersistenceException): Boolean =
    failure.message.orEmpty().contains("SQLSTATE 23000", ignoreCase = true)

private fun persistenceConflict(): ContextIngestionException =
    ContextIngestionException(
        ContextIngestionErrorCode.TURN_CONFLICT,
        "conversation turn insert outcome is unknown; the record must be re-read before retrying",
    )

/**
 * 把「事务边界 + 服务调用」绑定的对外形态：Runtime/测试通过它调用 ingest，
 * 事务在 [com.github.noahshen.alcyone.context.memory.core.ContextDatabase.write] 内完成。
 */
class DelegatingIngestionService(
    private val service: IngestionService,
    private val databaseProvider: () -> com.github.noahshen.alcyone.context.memory.core.ContextDatabase,
) {
    suspend fun ingest(turn: ConversationTurn): IngestResult = databaseProvider().write { tx -> service.ingest(turn, tx.conversations) }

    suspend fun notifyCreated(sessionKey: SessionKey) = service.notifyCreated(sessionKey)
}
