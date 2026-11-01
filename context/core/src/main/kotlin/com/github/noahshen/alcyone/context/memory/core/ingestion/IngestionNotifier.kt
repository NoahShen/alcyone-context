package com.github.noahshen.alcyone.context.memory.core.ingestion

import com.github.noahshen.alcyone.context.memory.api.SessionKey

/**
 * Count Trigger 通知 Port（CT07，Core 内部）。
 *
 * 仅在会话轮次**首次 CREATED 且 COMMIT 成功后**由 Runtime 在事务外异步调用；
 * DUPLICATE 不调用。实现方（Runtime 的 no-op 替身 / 测试记录替身 / CT09 实际接线）
 * 自行处理失败：通知失败不得影响接收结果、不得回滚已提交事务。
 * 本 Port 属 Core 内部扩展点，不进入公开 API 契约。
 */
fun interface IngestionNotifier {
    /** 通知指定会话有新入库轮次；实现应快速返回，重活交给自己的调度。 */
    suspend fun onCreated(sessionKey: SessionKey)
}
