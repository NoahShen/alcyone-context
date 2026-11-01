package com.github.noahshen.alcyone.context.memory.runtime

/**
 * 只读暴露当前 Context 实例实际生效的 `context.ingestion.maxTurnBytes`（CT07-B2）。
 *
 * 供同进程的 Server 路由组装在**不读取 YAML、不访问 Persistence/内部配置类型**的前提下，
 * 取得实例的真实限额；它不是公开 API 冻结契约的一部分，也不改变 [AlcyoneContext] 接口。
 * 宿主可用 `context as? IngestionLimitAware` 探测；实现保证返回值与配置快照一致。
 */
interface IngestionLimitAware {
    val ingestionMaxTurnBytes: Int
}
