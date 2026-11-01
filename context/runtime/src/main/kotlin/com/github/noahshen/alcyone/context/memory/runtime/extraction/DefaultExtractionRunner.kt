package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionBuildResult
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitException
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitter
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import com.github.noahshen.alcyone.context.memory.core.extraction.PreExtractionContextBuilder
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Organization Immediate Trigger 的唤醒（Runtime internal；**不新增 Core Port**，D2）。
 *
 * B1/B2 阶段默认 no-op；B3/CT13 接线到真实 Trigger。
 */
internal fun interface OrganizationWakeup {
    suspend fun onRawCommitted()
}

/** 提取执行的技术错误（窗口迭代超限等）；消息只含安全原因。 */
class ExtractionExecutionException(
    message: String,
) : IllegalStateException(message)

/**
 * CT11-B2 Runtime 提取执行器（internal，实现 Core [ExtractionRunner]）。
 *
 * 单次 `execute(sessionKey)` 的循环：
 * 1. [PreExtractionContextBuilder.build] 选择窗口：
 *    - `NoPending` → 直接返回（不算失败）；
 *    - `FixedOverheadOverBudget` → `ContextConfigException(INVALID_CONFIG)`（技术/配置错误，**0 写**）；
 *    - `SingleTurnOverBudget` → 独立短事务 `markFailed(anchorTurnId)`，行数必须 1（0 → 状态冲突），继续下一条；
 *    - `Built(context)` → 进入 2。
 * 2. 事务外调用 LLM（`requestFor(context)`）；`LlmException`/`CancellationException` 原样传播，**0 写**。
 * 3. [JsonExtractionResultValidator] 严格校验（非法 → `INVALID_RESPONSE`，0 写）。
 * 4. [ExtractionCommitter] 原子提交；`rawCount > 0` 时唤醒 Organization。
 * 5. 唤醒失败（非 CE）**吞掉并脱敏记录**：提交已成功，不回滚、不重提取。
 *
 * 防御上限：窗口迭代超过 [maxWindowIterations] 抛 [ExtractionExecutionException]，避免无限循环。
 * 日志只含固定事件名，不含 Raw/来源/Key/URL/Prompt/异常文本。
 */
internal class DefaultExtractionRunner(
    private val builder: PreExtractionContextBuilder,
    private val requestFor: (ExtractionContext) -> LlmRequest,
    private val llmClient: LlmClient,
    private val validator: JsonExtractionResultValidator,
    private val committer: ExtractionCommitter,
    private val database: ContextDatabase,
    private val maxWindowIterations: Int,
    private val wakeup: OrganizationWakeup = OrganizationWakeup { },
    private val logger: Logger = LoggerFactory.getLogger(DefaultExtractionRunner::class.java),
) : ExtractionRunner {
    init {
        require(maxWindowIterations >= 1) { "maxWindowIterations must be at least 1" }
    }

    override suspend fun execute(sessionKey: SessionKey) {
        var iterations = 0
        while (true) {
            iterations++
            if (iterations > maxWindowIterations) {
                throw ExtractionExecutionException(
                    "extraction window iterations exceeded the configured limit: $maxWindowIterations",
                )
            }
            when (val result = builder.build(sessionKey)) {
                is ExtractionBuildResult.NoPending -> return

                is ExtractionBuildResult.FixedOverheadOverBudget ->
                    throw ContextConfigException(
                        ContextConfigErrorCode.INVALID_CONFIG,
                        "extraction prompt fixed overhead exceeds the configured budget",
                    )

                is ExtractionBuildResult.SingleTurnOverBudget -> {
                    val failed = markFailed(result.anchorTurnId)
                    if (failed != 1) {
                        throw ExtractionCommitException(
                            "single over budget turn state changed before it could be marked failed",
                        )
                    }
                    // 继续下一轮：该 Turn 已 failed，Builder 会选到更早的窗口或返回 NoPending。
                    continue
                }

                is ExtractionBuildResult.Built -> {
                    val context = result.context
                    val response = llmClient.complete(requestFor(context))
                    val validated = validator.validate(response.content, context)
                    val commit = committer.commit(context, validated)
                    if (commit.rawCount > 0) {
                        notifyRawCommitted()
                    }
                    return
                }
            }
        }
    }

    /** 单 Turn 超预算的失败标记：独立短事务，行数必须为 1。 */
    private suspend fun markFailed(turnId: Long): Int = database.write { tx -> tx.conversations.markFailed(turnId) }

    /** 唤醒失败不影响已提交结果：只脱敏记录固定事件名，不重试。 */
    private suspend fun notifyRawCommitted() {
        try {
            wakeup.onRawCommitted()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logger.warn("extraction.organization.wakeup.failed")
        }
    }
}
