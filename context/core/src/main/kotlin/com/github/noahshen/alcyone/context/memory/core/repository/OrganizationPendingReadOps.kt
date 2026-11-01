package com.github.noahshen.alcyone.context.memory.core.repository

/** Organization Pending 只读操作（ReadContext 中可用，回调同步不可挂起）。 */
interface OrganizationPendingReadOps {
    /** 检查特定 Raw Memory 是否仍在治理待处理队列中。 */
    fun exists(rawId: String): Boolean

    /**
     * 全局存在性检查：检查当前待治理队列是否非空。
     * 底层执行 `SELECT EXISTS(SELECT 1 FROM organization_pending)`，用于 Trigger 快速轮询。
     */
    fun hasAnyPending(): Boolean

    /** 按 Raw 创建时间 ASC, id ASC 取下一条待治理的 raw_memory_id。 */
    fun nextByRawCreatedAt(): String?
}
