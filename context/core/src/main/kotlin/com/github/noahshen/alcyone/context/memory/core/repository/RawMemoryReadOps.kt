package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.RawMemory

/** Raw Memory 只读操作（ReadContext 中可用，回调同步不可挂起）。 */
interface RawMemoryReadOps {
    fun findById(id: String): RawMemory?

    fun findByIds(ids: Collection<String>): List<RawMemory>

    /** 历史分页扫描，按 created_at ASC, id ASC 确定性选取。 */
    fun scanPage(
        offset: Int,
        limit: Int,
    ): List<RawMemory>
}
