package com.github.noahshen.alcyone.context.memory.core.organization

/**
 * Organization 提交的安全业务结果。
 *
 * 结果不携带正文、domains 或来源原文；调用方只据此判断本次动作或来源幂等收敛状态。
 */
sealed interface OrganizationCommitResult {
    /** 本次事务完成了一次 Organized Memory 写入。 */
    data class Committed(
        val action: GovernanceAction,
        val organizedMemoryId: String,
    ) : OrganizationCommitResult

    /** Source 已关联该 Raw；仅报告是否顺手清理了残留 Pending。 */
    data class AlreadyAssociated(
        val pendingRemoved: Boolean,
    ) : OrganizationCommitResult
}
