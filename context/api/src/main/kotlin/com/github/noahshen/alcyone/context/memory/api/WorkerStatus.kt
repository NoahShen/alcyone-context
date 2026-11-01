package com.github.noahshen.alcyone.context.memory.api

/** Worker 简单执行态。 */
enum class WorkerState {
    IDLE,
    RUNNING,
    STOPPED,
    FAILED,
}

/** Worker 运行状态快照（进程内，不持久化）。 */
data class WorkerStatus(
    val state: WorkerState,
    val lastError: String? = null,
)
