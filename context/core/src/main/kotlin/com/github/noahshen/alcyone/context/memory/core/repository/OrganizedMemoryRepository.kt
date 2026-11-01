package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import java.time.Instant

/**
 * Organized Memory 普通 suspend 读取 Repository Port。
 * 支持 ID/批量查询、来源正反查、分页扫描与 MAX 更新时间。
 */
interface OrganizedMemoryRepository {
    suspend fun findById(id: String): OrganizedMemory?

    suspend fun findByIds(ids: Collection<String>): List<OrganizedMemory>

    suspend fun findSourcesByOrganized(organizedId: String): List<String>

    suspend fun findOrganizedByRaw(rawId: String): List<String>

    /** 查询当前库中 organized_memory.updated_at 的全局最大值（空表时返回 null）。 */
    suspend fun maxUpdatedAt(): Instant?

    suspend fun scanPage(
        offset: Int,
        limit: Int,
    ): List<OrganizedMemory>
}
