package com.github.noahshen.alcyone.context.memory.core.repository

/**
 * 读写事务上下文：继承 ReadContext 并提供 TxOps，写只能经 TransactionContext。
 *
 * 事务内所有读取均使用当前写连接；回调同步且不可挂起，禁止在事务内跨 LLM/HTTP/VFS
 * 或自行调度新的 database.read/write。
 */
interface TransactionContext : ReadContext {
    override val conversations: ConversationTxOps
    override val rawMemories: RawMemoryTxOps
    override val organizedMemories: OrganizedMemoryTxOps
    override val pending: OrganizationPendingTxOps
    override val dreaming: DreamingCheckpointTxOps
}
