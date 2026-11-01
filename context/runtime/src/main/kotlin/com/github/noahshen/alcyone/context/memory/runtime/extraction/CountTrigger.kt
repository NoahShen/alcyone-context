package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import kotlinx.coroutines.CancellationException

/**
 * CT09-B2 Count Trigger（Runtime internal，实现既有 [IngestionNotifier]，签名不改）。
 *
 * 语义（D7）：
 * - 只在**已提交的 CREATED** 之后被调用（DUPLICATE 不通知，沿用 CT07 契约）；
 * - 通过 `ContextDatabase.read` 查询该会话的 **pending** 轮次数，`>= threshold` 才向下游
 *   `request(sessionKey)` 提交一次提取请求；不新增独立 Turn Counter；
 * - 查询失败交给既有 [IngestionNotificationReporter]（只传异常对象），**不发 Trigger**、
 *   不影响已提交的 Ingestion；
 * - `CancellationException` 原样传播（不吞、不转普通失败）。
 *
 * 日志/错误口径：本类**不写日志**、不输出 SessionKey/消息/SQL/异常文本；失败只经既有 reporter。
 */
internal class CountTrigger(
    private val threshold: Int,
    private val databaseProvider: () -> ContextDatabase,
    private val request: (SessionKey) -> Boolean,
    private val reporter: IngestionNotificationReporter,
) : IngestionNotifier {
    init {
        require(threshold >= 1) { "count threshold must be at least 1" }
    }

    override suspend fun onCreated(sessionKey: SessionKey) {
        val pending =
            try {
                databaseProvider().read { read -> read.conversations.countPending(sessionKey) }
            } catch (cancellation: CancellationException) {
                // 取消沿既有链传播，不当作查询失败。
                throw cancellation
            } catch (failure: Throwable) {
                // 查询失败：交既有 reporter、不发 Trigger、不改动已提交的 Ingestion。
                reporter.reportFailure(failure)
                return
            }
        if (pending >= threshold) {
            request(sessionKey)
        }
    }
}
