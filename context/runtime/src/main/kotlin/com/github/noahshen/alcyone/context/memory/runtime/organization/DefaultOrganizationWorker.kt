package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecision
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidateRetriever
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Governance 决策 seam；B2 用 LlmGovernanceService::decide 适配。 */
internal fun interface OrganizationGovernanceDecider {
    suspend fun decide(
        rawMemory: RawMemory,
        candidates: List<OrganizedMemory>,
    ): GovernanceDecision
}

/** CT15 Committer 调用 seam；B2 用 OrganizationCommitter::commit 适配。 */
internal fun interface OrganizationCommitOperation {
    suspend fun commit(
        rawId: String,
        candidates: List<OrganizedMemory>,
        decision: GovernanceDecision,
    ): OrganizationCommitResult
}

/** Map 刷新接缝；B1 默认 no-op，CT17 才实现 Map。 */
internal fun interface OrganizationMapWakeup {
    suspend fun onOrganizedCommitted()
}

/**
 * 单轮固定 Pending 列表的 Organization Worker。
 *
 * 列表在单个 database.read 内一次取得；轮中新入队不会混入。逐条先 SourceGuard，未关联才读取 Raw、
 * 调 CandidateRetriever/Governance/Commit。单条普通失败安全跳过，Pending 保留；CancellationException
 * 原样传播。Committed 后调用 Map 接缝，普通失败吞掉，CE 传播。
 */
internal class DefaultOrganizationWorker(
    private val database: ContextDatabase,
    private val sourceGuard: OrganizationSourceGuard,
    private val candidateRetriever: OrganizationCandidateRetriever,
    private val governanceDecider: OrganizationGovernanceDecider,
    private val commit: OrganizationCommitOperation,
    private val mapWakeup: OrganizationMapWakeup = OrganizationMapWakeup { },
    private val logger: Logger = LoggerFactory.getLogger(DefaultOrganizationWorker::class.java),
) : OrganizationRoundRunner {
    override suspend fun runRound() {
        val rawIds = database.read { read -> read.pending.listAllByRawCreatedAt() }
        for (rawId in rawIds) {
            currentCoroutineContext().ensureActive()
            try {
                process(rawId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                logger.warn("organization.item.failed")
            }
        }
    }

    private suspend fun process(rawId: String) {
        if (sourceGuard.isAlreadyAssociated(rawId)) return
        val raw = database.read { read -> read.rawMemories.findById(rawId) } ?: throw MissingRawMemory()
        val candidates = candidateRetriever.search(raw)
        val decision = governanceDecider.decide(raw, candidates)
        when (commit.commit(rawId, candidates, decision)) {
            is OrganizationCommitResult.Committed -> notifyMap()
            is OrganizationCommitResult.AlreadyAssociated -> Unit
        }
    }

    private suspend fun notifyMap() {
        try {
            mapWakeup.onOrganizedCommitted()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            logger.warn("organization.map.wakeup.failed")
        }
    }

    private class MissingRawMemory : IllegalStateException("organization pending raw is missing")
}
