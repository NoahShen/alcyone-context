package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.RawMemory

/**
 * Raw Memory 普通 suspend 读取 Repository Port。
 * 仅支持 ID/批量精确查询与分页扫描，不可更新不可删除。
 */
interface RawMemoryRepository {
    suspend fun findById(id: String): RawMemory?

    suspend fun findByIds(ids: Collection<String>): List<RawMemory>

    suspend fun scanPage(
        offset: Int,
        limit: Int,
    ): List<RawMemory>
}
