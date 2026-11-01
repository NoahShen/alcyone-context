package com.github.noahshen.alcyone.context.memory.core.ingestion

/**
 * 入库通知失败诊断 Port（CT07，Core 内部）。
 *
 * 只接收异常对象，**不接收** SessionKey、消息正文或任何业务值，避免诊断日志泄露数据。
 * 默认实现为 no-op；Runtime 生产实现用 SLF4J 只记录异常类名与固定上下文。
 * 本 Port 不进入公开 API 契约。
 */
fun interface IngestionNotificationReporter {
    fun reportFailure(cause: Throwable)
}
