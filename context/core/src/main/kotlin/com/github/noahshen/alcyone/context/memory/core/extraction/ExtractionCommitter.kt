package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import java.time.Clock

/** 提交结果：本次写入的 Raw Memory 条数（零记忆 = 0）。 */
data class ExtractionCommitResult(
    val rawCount: Int,
)

/**
 * 提取结果提交失败（状态冲突/行数不符/ID 碰撞重试耗尽）。
 *
 * 消息只含安全原因（不含 content/来源文本/Key/URL）；事务已回滚，不留部分写入。
 */
class ExtractionCommitException(
    message: String,
) : IllegalStateException(message)

/**
 * CT11 原子提交（Core public，非 `context/api` SDK；D7/D8/D12）。
 *
 * 在**单个** `database.write` 事务内完成：
 * 1. 预检每个 Raw ID 不存在（命中即视为 ID 碰撞 → 整笔回滚后重新生成整批 ID 重试，最多 [maxIdAttempts] 次）；
 * 2. 非空记忆：`insertBatch` 行数必须等于 Raw 数；每条 Raw 的 `organization_pending.enqueue` 必须返回 1；
 * 3. 逐个 Target `markComplete`，汇总行数必须等于 Target 数（任一 0 → 状态冲突，整笔回滚）；
 * 4. 空记忆：只执行 Target complete（同样校验行数）。
 *
 * 约束：不通知 Organization（B2 才做）、不写 failed、不修改 SQL/索引；
 * `CancellationException` 与数据库错误原样传播；只有**预检 ID 碰撞**会重试。
 */
class ExtractionCommitter(
    private val database: ContextDatabase,
    private val idGenerator: MemoryIdGenerator,
    private val clock: Clock,
    private val maxIdAttempts: Int = 3,
) {
    init {
        require(maxIdAttempts >= 1) { "maxIdAttempts must be at least 1" }
    }

    suspend fun commit(
        context: ExtractionContext,
        memories: List<ValidatedExtractionMemory>,
    ): ExtractionCommitResult {
        val targets = context.turns.filter { turn -> turn.extractionStatus == ExtractionStatus.PENDING }
        if (targets.isEmpty()) {
            throw ExtractionCommitException("extraction context has no pending target turn to commit")
        }

        var attempt = 0
        while (true) {
            attempt++
            // 每次尝试生成整批 Raw（同批 createdAt 一致；重试时整批重新生成）。
            val createdAt = clock.instant()
            val raws =
                memories.map { memory ->
                    RawMemory(
                        id = idGenerator.nextRawMemoryId(),
                        content = memory.content,
                        generatedBy = MemoryOrigin.EXTRACTION,
                        sources = memory.sources,
                        createdAt = createdAt,
                    )
                }
            try {
                database.write { tx ->
                    // 1) 预检：ID 已存在 → 碰撞（整笔回滚后重试）。
                    raws.forEach { raw ->
                        if (tx.rawMemories.findById(raw.id) != null) throw RawIdCollision()
                    }
                    // 2) Raw + organization_pending。
                    if (raws.isNotEmpty()) {
                        val inserted = tx.rawMemories.insertBatch(raws)
                        if (inserted != raws.size) {
                            throw ExtractionCommitException("raw memory insert count mismatch: $inserted != ${raws.size}")
                        }
                        raws.forEach { raw ->
                            val enqueued = tx.pending.enqueue(raw.id)
                            if (enqueued != 1) {
                                throw ExtractionCommitException("organization pending enqueue count mismatch: $enqueued")
                            }
                        }
                    }
                    // 3) 条件完成 Target，汇总行数必须精确匹配。
                    var completed = 0
                    targets.forEach { target -> completed += tx.conversations.markComplete(target.id) }
                    if (completed != targets.size) {
                        throw ExtractionCommitException(
                            "extraction target completion count mismatch: $completed != ${targets.size}",
                        )
                    }
                }
                return ExtractionCommitResult(rawCount = raws.size)
            } catch (collision: RawIdCollision) {
                if (attempt >= maxIdAttempts) {
                    throw ExtractionCommitException("raw memory id collision persisted after $attempt attempts")
                }
                // 只有 ID 碰撞会重试：整批重新生成 ID 后重试整笔事务。
            }
        }
    }

    /** 内部信号：仅在预检命中已存在 ID 时抛出，用于触发整批重试。 */
    private class RawIdCollision : RuntimeException()
}
