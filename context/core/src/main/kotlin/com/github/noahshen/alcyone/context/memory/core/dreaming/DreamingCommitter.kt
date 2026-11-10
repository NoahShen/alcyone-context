package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Dreaming 原子提交：Raw(DREAMING) + Pending + Checkpoint CAS 在同一个写事务内完成。
 *
 * 零 Insight 仍执行短写事务并推进 Checkpoint。Raw 的 Clock 与 ID 均在事务外生成；
 * 只有事务内预检发现确定的主键碰撞时，整批重新生成 ID 与时间后重试，其他异常不重试。
 */
class DreamingCommitter(
    private val database: ContextDatabase,
    private val idGenerator: MemoryIdGenerator,
    private val clock: Clock,
    private val maxIdAttempts: Int = 3,
) {
    init {
        require(maxIdAttempts >= 1) { "maxIdAttempts must be at least 1" }
    }

    suspend fun commit(
        context: DreamingCommitContext,
        insights: List<ValidatedDreamingInsight>,
    ): DreamingCommitResult {
        validateRange(context)

        var attempt = 0
        while (true) {
            attempt++
            val createdAt = clock.instant()
            val raws =
                try {
                    insights.map { insight ->
                        RawMemory(
                            id = idGenerator.nextRawMemoryId(),
                            content = insight.content,
                            generatedBy = MemoryOrigin.DREAMING,
                            sources = insight.sources,
                            createdAt = createdAt,
                        )
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    throw DreamingCommitException("dreaming raw memory id generation failed")
                }
            if (raws.map { it.id }.toSet().size != raws.size) {
                throw DreamingCommitException("dreaming raw memory id generation produced duplicate ids")
            }

            try {
                database.write { tx ->
                    validateRange(context)
                    val checkpoint = tx.dreaming.readCheckpoint()
                    if (checkpoint != context.expectedCheckpoint) throw DreamingCommitException("dreaming checkpoint changed")

                    raws.forEach { raw ->
                        if (tx.rawMemories.findById(raw.id) != null) throw RawIdCollision()
                    }

                    if (raws.isNotEmpty()) {
                        val inserted = tx.rawMemories.insertBatch(raws)
                        if (inserted != raws.size) {
                            throw DreamingCommitException("raw memory insert count mismatch: $inserted != ${raws.size}")
                        }
                        raws.forEach { raw ->
                            val enqueued = tx.pending.enqueue(raw.id)
                            if (enqueued != 1) {
                                throw DreamingCommitException("organization pending enqueue count mismatch: $enqueued")
                            }
                        }
                    }

                    val advanced = tx.dreaming.casUpdateCheckpoint(context.expectedCheckpoint, context.lastTurnId)
                    if (advanced != 1) {
                        throw DreamingCommitException("dreaming checkpoint CAS count mismatch: $advanced != 1")
                    }
                }
                return DreamingCommitResult(rawCount = raws.size, lastTurnId = context.lastTurnId)
            } catch (collision: RawIdCollision) {
                if (attempt >= maxIdAttempts) {
                    throw DreamingCommitException("raw memory id collision persisted after $attempt attempts")
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: DreamingCommitException) {
                throw failure
            } catch (failure: Throwable) {
                throw DreamingCommitException("dreaming commit persistence failed")
            }
        }
    }

    private fun validateRange(context: DreamingCommitContext) {
        if (context.expectedCheckpoint < 0 ||
            context.upperBound < 0 ||
            context.lastTurnId <= context.expectedCheckpoint ||
            context.lastTurnId > context.upperBound
        ) {
            throw DreamingCommitException("dreaming commit range is invalid")
        }
    }

    private class RawIdCollision : RuntimeException()
}
