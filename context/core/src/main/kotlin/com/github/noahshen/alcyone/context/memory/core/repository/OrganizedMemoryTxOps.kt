package com.github.noahshen.alcyone.context.memory.core.repository

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import java.time.Instant

/** Organized Memory 写操作（TransactionContext 中可用，回调同步不可挂起）。 */
interface OrganizedMemoryTxOps : OrganizedMemoryReadOps {
    fun insertNew(memory: OrganizedMemory): Int

    fun update(memory: OrganizedMemory): Int

    /** 插入来源关联 `(organized_memory_id, raw_memory_id)`；返回受影响行数。 */
    fun insertSourceLink(
        organizedId: String,
        rawId: String,
    ): Int

    /**
     * 推进单调水位：更新指定 Organized Memory 的 updated_at。
     *
     * 事务契约与严格单调分配规则：
     * - 在同一个 WriteSlot 写事务内部，调用方必须先通过 [maxUpdatedAt] 读取当前全局最大更新时间 `previousGlobalMax`；
     * - 将当前时钟截断到毫秒后分配严格更大的新值：`next = max(clock.instant().truncatedTo(ChronoUnit.MILLIS), (previousGlobalMax ?: Instant.MIN).plusMillis(1))`；
     * - 同一写事务若修改多条 Organized Memory，或仅做来源关系的 MERGE，亦须依次推进分配严格更大的毫秒时间；
     * - 最终写入时验证影响行数为 1，并作为 Memory Map 刷新的全局权威水位。
     *
     * @param organizedId 目标 Organized Memory 标识
     * @param next 经全局水位计算后严格递增的毫秒时间
     * @return 实际影响行数，期望为 1
     */
    fun advanceUpdatedAt(
        organizedId: String,
        next: Instant,
    ): Int
}
