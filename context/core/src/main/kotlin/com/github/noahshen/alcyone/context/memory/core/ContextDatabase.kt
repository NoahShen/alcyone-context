package com.github.noahshen.alcyone.context.memory.core

import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext

/**
 * 显式同步读写执行器。
 *
 * - `read`：绑定一个 ReadSlot / 只读事务，仅提供 ReadOps，回调同步不可挂起。
 * - `write`：绑定唯一 WriteSlot / 写事务，提供 TxOps 并继承对应 ReadOps，回调同步不可挂起。
 * - 回调同步不可挂起，事务内不跨 LLM/HTTP/VFS；禁止事务嵌套与 Savepoint。
 */
interface ContextDatabase {
    suspend fun <T> read(block: (ReadContext) -> T): T

    suspend fun <T> write(block: (TransactionContext) -> T): T
}
