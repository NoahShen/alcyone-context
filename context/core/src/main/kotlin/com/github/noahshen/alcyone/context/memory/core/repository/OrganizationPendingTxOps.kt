package com.github.noahshen.alcyone.context.memory.core.repository

/** Organization Pending 写操作（TransactionContext 中可用，回调同步不可挂起）。 */
interface OrganizationPendingTxOps : OrganizationPendingReadOps {
    fun enqueue(rawId: String): Int

    fun dequeue(rawId: String): Int
}
