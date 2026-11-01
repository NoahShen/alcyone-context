package com.github.noahshen.alcyone.context.memory.core.repository

/**
 * 只读事务上下文：聚合五个 Repository 的 ReadOps，回调同步且不可挂起。
 *
 * 多次普通查询之间不保证是同一快照；需要跨多次读取的一致性快照，应使用
 * {@link ContextDatabase.read} 绑定单个 ReadSlot / 只读事务。
 */
interface ReadContext {
    val conversations: ConversationReadOps
    val rawMemories: RawMemoryReadOps
    val organizedMemories: OrganizedMemoryReadOps
    val pending: OrganizationPendingReadOps
    val dreaming: DreamingCheckpointReadOps
}
