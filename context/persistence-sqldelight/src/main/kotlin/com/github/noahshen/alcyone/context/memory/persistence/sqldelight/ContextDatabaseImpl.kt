package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.repository.ConversationReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.ConversationTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext

/**
 * [ContextDatabase] 的持久化装配（CT05-B3）。
 *
 * 装配规则：
 * - `read` 只开**一个** [RawReadScope]，五个 ReadOps 全部绑定同一 scope / session /
 *   只读事务，回调内多次查询共享同一 WAL 快照；
 * - `write` 只开**一个** [RawTransactionScope]，五个 TxOps 全部绑定同一写连接与事务，
 *   TxOps 同时提供对应 ReadOps，因此事务内读写共享同一 scope；
 * - 回调同步且不可挂起；Ops 内不得再调用本类的 `read` / `write`（执行器会具名拒绝嵌套）；
 * - 不缓存 scope、不裸透 `Connection` / `Driver`；回调返回后 ReadContext / TransactionContext
 *   及其 Ops 立即失效（CT04 作用域校验）；
 * - 失败 / 取消沿用 CT04 契约：异常回滚、`CancellationException` 原样传播、中毒连接拒绝复用。
 *
 * 生命周期（打开 / 关闭）仍由调用方持有 [ContextDatabaseExecutor] 决定；本类不新增连接管理。
 */
class ContextDatabaseImpl(
    private val executor: ContextDatabaseExecutor,
) : ContextDatabase {
    override suspend fun <T> read(block: (ReadContext) -> T): T = executor.read { scope -> block(ReadContextImpl(scope)) }

    override suspend fun <T> write(block: (TransactionContext) -> T): T = executor.write { scope -> block(TransactionContextImpl(scope)) }
}

/** 五个只读 Ops 共享同一个 [RawReadScope]。 */
internal class ReadContextImpl(
    scope: RawReadScope,
) : ReadContext {
    override val conversations: ConversationReadOps = ConversationReadOpsImpl(scope)
    override val rawMemories: RawMemoryReadOps = RawMemoryReadOpsImpl(scope)
    override val organizedMemories: OrganizedMemoryReadOps = OrganizedMemoryReadOpsImpl(scope)
    override val pending: OrganizationPendingReadOps = OrganizationPendingReadOpsImpl(scope)
    override val dreaming: DreamingCheckpointReadOps = DreamingCheckpointReadOpsImpl(scope)
}

/** 五个 TxOps（同时继承对应 ReadOps）共享同一个 [RawTransactionScope]。 */
internal class TransactionContextImpl(
    scope: RawTransactionScope,
) : TransactionContext {
    override val conversations: ConversationTxOps = ConversationTxOpsImpl(scope)
    override val rawMemories: RawMemoryTxOps = RawMemoryTxOpsImpl(scope)
    override val organizedMemories: OrganizedMemoryTxOps = OrganizedMemoryTxOpsImpl(scope)
    override val pending: OrganizationPendingTxOps = OrganizationPendingTxOpsImpl(scope)
    override val dreaming: DreamingCheckpointTxOps = DreamingCheckpointTxOpsImpl(scope)
}
