package com.github.noahshen.alcyone.context.memory.api

/** Context 实例生命周期状态。 */
enum class ContextLifecycleState {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    CLOSED,
}
