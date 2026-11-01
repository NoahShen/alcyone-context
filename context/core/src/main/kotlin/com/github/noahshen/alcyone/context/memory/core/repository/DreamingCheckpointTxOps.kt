package com.github.noahshen.alcyone.context.memory.core.repository

/** Dreaming Checkpoint 写操作（TransactionContext 中可用，回调同步不可挂起）。 */
interface DreamingCheckpointTxOps : DreamingCheckpointReadOps {
    /**
     * 条件更新水位：仅在当前水位 == expected 且 next 严格向前时更新；
     * 返回受影响行数，原值变化或更新数量异常即整笔回滚。
     */
    fun casUpdateCheckpoint(
        expected: Long,
        next: Long,
    ): Int
}
