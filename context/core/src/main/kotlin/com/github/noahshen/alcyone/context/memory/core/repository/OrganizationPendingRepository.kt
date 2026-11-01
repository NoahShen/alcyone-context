package com.github.noahshen.alcyone.context.memory.core.repository

/**
 * Organization Pending 普通 suspend 读取 Repository Port。
 * 支持单项与全局 exists 检查与按 Raw 创建时间取下一条。
 */
interface OrganizationPendingRepository {
    /** 检查特定 Raw Memory 是否仍在治理待处理队列中。 */
    suspend fun exists(rawId: String): Boolean

    /** 全局存在性检查：检查当前待治理队列是否非空。 */
    suspend fun hasAnyPending(): Boolean

    suspend fun nextByRawCreatedAt(): String?
}
