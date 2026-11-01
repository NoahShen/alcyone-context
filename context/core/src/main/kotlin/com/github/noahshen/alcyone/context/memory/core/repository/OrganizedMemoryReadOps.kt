package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import java.time.Instant

/** Organized Memory 只读操作（ReadContext 中可用，回调同步不可挂起）。 */
interface OrganizedMemoryReadOps {
    fun findById(id: String): OrganizedMemory?

    fun findByIds(ids: Collection<String>): List<OrganizedMemory>

    /** 来源正查：Organized -> 关联的 Raw Memory id 列表。 */
    fun findSourcesByOrganized(organizedId: String): List<String>

    /** 来源反查：Raw Memory -> 关联的 Organized Memory id 列表。 */
    fun findOrganizedByRaw(rawId: String): List<String>

    /** 查询当前库中 organized_memory.updated_at 的全局最大值（空表时返回 null）。 */
    fun maxUpdatedAt(): Instant?

    fun scanPage(
        offset: Int,
        limit: Int,
    ): List<OrganizedMemory>
}
