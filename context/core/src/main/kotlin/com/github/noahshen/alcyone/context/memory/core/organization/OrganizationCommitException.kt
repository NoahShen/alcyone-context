package com.github.noahshen.alcyone.context.memory.core.organization

/** Organization 提交失败的脱敏原因分类。 */
enum class OrganizationCommitReason {
    INVALID_DECISION,
    STALE_TARGET,
    STALE_DECISION,
    ROW_COUNT_MISMATCH,
    ID_COLLISION_EXHAUSTED,
    WATERMARK_EXHAUSTED,
}

/**
 * Organization 提交失败的安全异常。
 *
 * 消息与公开状态只包含 [reason]；不链接底层 SQL 异常，不携带 Raw/Organized 正文、domains、
 * Key、URL 或完整业务 ID。`CancellationException` 与 `ContextPersistenceException` 不由此类包装。
 */
class OrganizationCommitException(
    val reason: OrganizationCommitReason,
) : IllegalStateException("organization commit failed: $reason")
