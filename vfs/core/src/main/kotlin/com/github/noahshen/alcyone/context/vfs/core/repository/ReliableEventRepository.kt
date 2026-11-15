package com.github.noahshen.alcyone.context.vfs.core.repository

/** 持久事件及其数据库顺序；sequence 只由 SQLite 分配，与 UUID 和 occurredAt 无关。 */
data class SequencedEventRecord(
    val sequence: Long,
    val event: EventRecord,
) {
    init {
        require(sequence > 0) { "event sequence must be positive: $sequence" }
    }
}

/** 新 consumer 的起点；已存在的 cursor 永远优先，重复 start 不重置进度。 */
enum class EventCursorStart {
    EARLIEST,
    LATEST,
}

/**
 * 可靠事件读取与 cursor 的原子推进 Port。每次调用必须自行使用短事务，不能把事务交给消费处理函数。
 */
interface ReliableEventRepository {
    /** 返回下一条待读 sequence；EARLIEST 为 1，LATEST 为当前最大 sequence + 1。 */
    suspend fun initializeCursor(
        consumerId: String,
        start: EventCursorStart,
    ): Long

    /** 读取 [afterExclusive, afterExclusive + limit] 范围内的持久事件，严格按 sequence 升序。 */
    suspend fun readAfter(
        afterExclusive: Long,
        limit: Int,
    ): List<SequencedEventRecord>

    /** 只有整批处理成功才调用；expectedNextSequence 防止同一 consumer 的旧批覆盖新进度。 */
    suspend fun advanceCursor(
        consumerId: String,
        expectedNextSequence: Long,
        nextSequence: Long,
    )
}
