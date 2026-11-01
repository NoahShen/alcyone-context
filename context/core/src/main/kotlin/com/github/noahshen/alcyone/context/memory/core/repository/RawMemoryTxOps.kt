package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.RawMemory

/** Raw Memory 写操作（TransactionContext 中可用，回调同步不可挂起）。 */
interface RawMemoryTxOps : RawMemoryReadOps {
    /** 批量插入（不可更新、不可物理删除）；返回受影响行数。 */
    fun insertBatch(memories: List<RawMemory>): Int
}
