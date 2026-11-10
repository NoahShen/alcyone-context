package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitContext
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitter
import com.github.noahshen.alcyone.context.memory.core.dreaming.ValidatedDreamingInsight
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * CT25 完整执行链：Topic/Evidence -> Reflection -> B1 原子 Commit -> Organization 通知。
 *
 * LLM 调用不位于数据库事务内；提交使用本批固定的 checkpoint 与上界。通知失败只降级为
 * 脱敏 warning，因为 Raw、Pending 与 checkpoint 已在同一事务中持久化。
 */
internal class LlmDreamingBatchProcessor(
    private val database: ContextDatabase,
    private val collectEvidence: suspend (List<StoredConversationTurn>) -> DreamingBatchEvidence,
    private val reflectEvidence: suspend (DreamingBatchEvidence) -> List<ValidatedDreamingInsight>,
    private val committer: DreamingCommitter,
    private val organizationWakeup: OrganizationWakeup = OrganizationWakeup { },
    private val logger: Logger = LoggerFactory.getLogger(LlmDreamingBatchProcessor::class.java),
) : DreamingBatchProcessor {
    constructor(
        database: ContextDatabase,
        evidenceStage: LlmDreamingEvidenceStage,
        reflectionStage: LlmDreamingReflectionStage,
        committer: DreamingCommitter,
        organizationWakeup: OrganizationWakeup = OrganizationWakeup { },
    ) : this(
        database = database,
        collectEvidence = evidenceStage::collect,
        reflectEvidence = reflectionStage::reflect,
        committer = committer,
        organizationWakeup = organizationWakeup,
    )

    constructor(
        database: ContextDatabase,
        llmClient: LlmClient,
        llmConfig: LlmConfig,
        committer: DreamingCommitter,
        organizationWakeup: OrganizationWakeup = OrganizationWakeup { },
        estimator: ConservativeTokenEstimator = ConservativeTokenEstimator(),
    ) : this(
        database = database,
        evidenceStage =
            LlmDreamingEvidenceStage(
                database,
                llmClient,
                llmConfig,
                LlmDreamingTopicDiscoveryStage(llmClient, llmConfig, estimator),
                estimator,
            ),
        reflectionStage = LlmDreamingReflectionStage(llmClient, llmConfig, estimator),
        committer = committer,
        organizationWakeup = organizationWakeup,
    )

    override suspend fun process(context: DreamingBatchContext): DreamingBatchOutcome {
        context.requireValidRange()
        val batch = context.turns
        val evidence = collectEvidence(batch)
        val insights = reflectEvidence(evidence)
        val commit =
            committer.commit(
                DreamingCommitContext(
                    expectedCheckpoint = context.expectedCheckpoint,
                    upperBound = context.upperBound,
                    lastTurnId = batch.last().id,
                ),
                insights,
            )
        if (commit.rawCount > 0) {
            try {
                organizationWakeup.onRawCommitted()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                logger.warn("dreaming.organization.wakeup.failed batchLastTurnId={}", commit.lastTurnId)
            }
        }
        return DreamingBatchOutcome(
            lastTurnId = commit.lastTurnId,
            discoveredTopics = evidence.topics.size,
            generatedMemories = commit.rawCount,
        )
    }
}
