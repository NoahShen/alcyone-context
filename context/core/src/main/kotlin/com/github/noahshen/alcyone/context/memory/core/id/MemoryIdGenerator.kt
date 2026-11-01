package com.github.noahshen.alcyone.context.memory.core.id

/**
 * Memory ID 生成器（Port）：线程安全，单实例 MVP 内由 Runtime 提供共用实例。
 *
 * Raw ID：`R-MEM-yyyyMMdd-{8位当天毫秒}-{2位序列}`
 * Organized ID：`O-MEM-yyyyMMdd-{8位当天毫秒}-{2位序列}`
 *
 * 日期/当天毫秒/序列使用上海日历日与当天毫秒；时钟回拨不得导致进程内重复。
 * 不承诺跨重启无碰撞，由 SQLite 主键兜底。
 */
interface MemoryIdGenerator {
    fun nextRawMemoryId(): String

    fun nextOrganizedMemoryId(): String
}
