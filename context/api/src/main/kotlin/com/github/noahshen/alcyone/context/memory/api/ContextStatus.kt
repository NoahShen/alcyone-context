package com.github.noahshen.alcyone.context.memory.api

/** Context 运行状态汇总：生命周期、Worker 执行态与数据库关键指标。 */
data class ContextStatus(
    val lifecycle: ContextLifecycleState,
    val workers: Map<String, WorkerStatus>,
    val storage: StorageStatus,
)
