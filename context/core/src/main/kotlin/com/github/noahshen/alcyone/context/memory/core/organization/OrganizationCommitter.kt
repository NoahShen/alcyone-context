package com.github.noahshen.alcyone.context.memory.core.organization

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * CT15 Organization 单事务提交器。
 *
 * 调用方必须在 LLM/候选检索事务之外准备好本次实际提供给 Governance 的候选快照与合法决策。
 * 本组件只做单次短写事务、事务内确定性复验、来源幂等与全局毫秒水位写入；不调用 LLM/HTTP/VFS，
 * 不触发通知，也不接入 Factory/Impl/Scheduler。
 *
 * 每次尝试都以新的 [ContextDatabase.write] 事务开始。Source 关联是幂等权威事实：已关联时优先
 * 清理残留 Pending，不校验本次 decision，也不推进水位。只有未关联且仍 Pending 的 Raw 才执行
 * CT14 Validator 复验、目标版本检查和 NEW/MERGE/UPDATE 原子写入。
 */
class OrganizationCommitter(
    private val database: ContextDatabase,
    private val idGenerator: MemoryIdGenerator,
    private val clock: Clock,
    private val maxIdAttempts: Int = 3,
) {
    init {
        require(maxIdAttempts in 1..3) { "maxIdAttempts must be between 1 and 3" }
    }

    suspend fun commit(
        rawId: String,
        candidates: List<OrganizedMemory>,
        decision: GovernanceDecision,
    ): OrganizationCommitResult {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return database.write { tx -> commitAttempt(tx, rawId, candidates, decision) }
            } catch (_: OrganizedIdCollision) {
                if (attempt >= maxIdAttempts) {
                    throw OrganizationCommitException(OrganizationCommitReason.ID_COLLISION_EXHAUSTED)
                }
                // 仅显式主键预检命中会走到这里；整笔事务已回滚，下一次重跑全部检查并生成新 ID。
            }
        }
    }

    private fun commitAttempt(
        tx: TransactionContext,
        rawId: String,
        candidates: List<OrganizedMemory>,
        decision: GovernanceDecision,
    ): OrganizationCommitResult {
        // Source/Pending 必须先于 decision 校验，保证已完成 Raw 的残留 Pending 可幂等清理。
        val associatedIds = tx.organizedMemories.findOrganizedByRaw(rawId)
        val pendingExists = tx.pending.exists(rawId)

        if (associatedIds.isNotEmpty()) {
            if (!pendingExists) return OrganizationCommitResult.AlreadyAssociated(pendingRemoved = false)
            requireSingleRow(tx.pending.dequeue(rawId))
            return OrganizationCommitResult.AlreadyAssociated(pendingRemoved = true)
        }
        if (!pendingExists) {
            throw OrganizationCommitException(OrganizationCommitReason.STALE_DECISION)
        }

        val validatedDecision =
            try {
                GovernanceDecisionValidator.validate(decision, candidates)
            } catch (_: GovernanceValidationException) {
                throw OrganizationCommitException(OrganizationCommitReason.INVALID_DECISION)
            }

        return when (validatedDecision.action) {
            GovernanceAction.NEW -> commitNew(tx, rawId, validatedDecision)
            GovernanceAction.MERGE -> commitMerge(tx, rawId, candidates, validatedDecision)
            GovernanceAction.UPDATE -> commitUpdate(tx, rawId, candidates, validatedDecision)
        }
    }

    private fun commitNew(
        tx: TransactionContext,
        rawId: String,
        decision: GovernanceDecision,
    ): OrganizationCommitResult {
        val newId = idGenerator.nextOrganizedMemoryId()
        if (tx.organizedMemories.findById(newId) != null) throw OrganizedIdCollision()

        val watermark = allocateWatermark(tx)
        val memory =
            OrganizedMemory(
                id = newId,
                content = requireNotNull(decision.content),
                domains = requireNotNull(decision.domains),
                rawMemoryIds = emptyList(),
                createdAt = watermark.nowMs,
                updatedAt = watermark.next,
            )
        requireSingleRow(tx.organizedMemories.insertNew(memory))
        requireSingleRow(tx.organizedMemories.insertSourceLink(newId, rawId))
        requireSingleRow(tx.pending.dequeue(rawId))
        return OrganizationCommitResult.Committed(GovernanceAction.NEW, newId)
    }

    private fun commitMerge(
        tx: TransactionContext,
        rawId: String,
        candidates: List<OrganizedMemory>,
        decision: GovernanceDecision,
    ): OrganizationCommitResult {
        val target = requireFreshTarget(tx, candidates, decision)
        val next = allocateWatermark(tx).next
        val updatedRows =
            if (decision.content == null) {
                tx.organizedMemories.advanceUpdatedAt(target.id, next)
            } else {
                tx.organizedMemories.update(
                    target.copy(
                        content = decision.content,
                        domains = target.domains,
                        updatedAt = next,
                    ),
                )
            }
        requireSingleRow(updatedRows)
        requireSingleRow(tx.organizedMemories.insertSourceLink(target.id, rawId))
        requireSingleRow(tx.pending.dequeue(rawId))
        return OrganizationCommitResult.Committed(GovernanceAction.MERGE, target.id)
    }

    private fun commitUpdate(
        tx: TransactionContext,
        rawId: String,
        candidates: List<OrganizedMemory>,
        decision: GovernanceDecision,
    ): OrganizationCommitResult {
        val target = requireFreshTarget(tx, candidates, decision)
        val next = allocateWatermark(tx).next
        val updated =
            target.copy(
                content = requireNotNull(decision.content),
                domains = decision.domains ?: target.domains,
                updatedAt = next,
            )
        requireSingleRow(tx.organizedMemories.update(updated))
        requireSingleRow(tx.organizedMemories.insertSourceLink(target.id, rawId))
        requireSingleRow(tx.pending.dequeue(rawId))
        return OrganizationCommitResult.Committed(GovernanceAction.UPDATE, target.id)
    }

    private fun requireFreshTarget(
        tx: TransactionContext,
        candidates: List<OrganizedMemory>,
        decision: GovernanceDecision,
    ): OrganizedMemory {
        val targetId = requireNotNull(decision.targetMemoryId)
        val candidate = candidates.first { memory -> memory.id == targetId }
        val stored = tx.organizedMemories.findById(targetId)
        if (stored == null || stored.updatedAt != candidate.updatedAt) {
            throw OrganizationCommitException(OrganizationCommitReason.STALE_TARGET)
        }
        return stored
    }

    private fun allocateWatermark(tx: TransactionContext): Watermark {
        val nowMs = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val previous = tx.organizedMemories.maxUpdatedAt()
        val next =
            when (previous) {
                null -> nowMs
                Instant.MAX -> throw OrganizationCommitException(OrganizationCommitReason.WATERMARK_EXHAUSTED)
                else -> maxOf(nowMs, previous.plusMillis(1))
            }
        return Watermark(nowMs, next)
    }

    private fun requireSingleRow(actual: Int) {
        if (actual != 1) throw OrganizationCommitException(OrganizationCommitReason.ROW_COUNT_MISMATCH)
    }

    private data class Watermark(
        val nowMs: Instant,
        val next: Instant,
    )

    /** 仅由当前事务内的显式 Organized ID 预检命中触发，用于整笔回滚后的有限重试。 */
    private class OrganizedIdCollision : RuntimeException()
}
