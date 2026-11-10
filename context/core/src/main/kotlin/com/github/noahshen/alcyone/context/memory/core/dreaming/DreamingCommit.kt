package com.github.noahshen.alcyone.context.memory.core.dreaming

/**
 * Dreaming 批次提交所需的固定范围元数据。
 *
 * [expectedCheckpoint] 是 Coordinator 读取的权威 Checkpoint，[upperBound] 是本轮固定扫描上界，
 * [lastTurnId] 是本批实际处理的最后一个内部 Turn ID（不假设 ID 连续）。
 */
data class DreamingCommitContext(
    val expectedCheckpoint: Long,
    val upperBound: Long,
    val lastTurnId: Long,
) {
    init {
        if (expectedCheckpoint < 0 || upperBound < 0 || lastTurnId <= expectedCheckpoint || lastTurnId > upperBound) {
            throw DreamingCommitException("dreaming commit range is invalid")
        }
    }
}

/** 一次 Dreaming 提交的结果；零 Insight 也返回一个已推进的 Checkpoint。 */
data class DreamingCommitResult(
    val rawCount: Int,
    val lastTurnId: Long,
)

/**
 * Dreaming 提交失败。消息只包含安全原因，不携带 SQL、正文、来源或底层 cause。
 *
 * 范围非法、Checkpoint 冲突、行数不符、ID 生成耗尽及普通数据库失败都收敛到该异常；
 * `CancellationException` 不在此处包装。
 */
class DreamingCommitException(
    message: String,
) : IllegalStateException(message)
