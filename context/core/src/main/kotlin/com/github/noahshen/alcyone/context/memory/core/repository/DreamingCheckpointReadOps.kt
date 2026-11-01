package com.github.noahshen.alcyone.context.memory.core.repository

/** Dreaming Checkpoint 只读操作（ReadContext 中可用，回调同步不可挂起）。 */
interface DreamingCheckpointReadOps {
    /** 读取单行水位（固定 id=1），返回 last_processed_turn_id。 */
    fun readCheckpoint(): Long
}
