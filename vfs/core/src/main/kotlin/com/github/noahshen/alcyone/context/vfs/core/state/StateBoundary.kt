package com.github.noahshen.alcyone.context.vfs.core.state

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 状态库串行边界：一把可以被多个组件共用的锁（T11 §2.3、T13 §2.5）。
 *
 * 为什么需要它：整个状态库只有一条 JDBC 连接，也不支持并发或嵌套事务（[com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork]
 * 的 Kdoc 里有实测记录）。同一个挂载里如果一个协程正在 `inTransaction` 写、另一个协程同时用自动提交的 Repository 读，
 * 读会**看见对方还没提交的记录**——对方一回滚，这条记录就是假的。所以「读状态」和「写状态」必须排在同一条队伍里。
 *
 * 它就是一把 [Mutex] 加一个名字，不做调度、不排队管理、不区分读写：调用方自己决定在锁里做什么。
 *
 * **锁不可重入。** 已经在 [withLock] 里就别再拿同一把锁调另一个会加锁的方法，否则自己等自己，永远出不来。
 * 需要组合时（例如「写文件 + 懒注册 + 记事件」），由最外层拿一次锁，里面调不加锁的版本：
 *
 * ```
 * val boundary = StateBoundary()                      // 整个 Runtime 共用这一个
 * val registry = NodeRegistry(router, nodes, { storages[it] }, boundary)
 * val unitOfWork = SqliteUnitOfWork(state)            // T15 组装
 *
 * boundary.withLock {                                 // ← 整个写操作只取一次锁
 *     val result = unitOfWork.inTransaction { scope ->
 *         scope.metadata.put(id, metadata)             // 事务内的状态写入
 *         registry.resolveInsideBoundary(path)        // ← 不加锁版本：里面不会再取锁
 *     }
 *     storage.write(...)                              // 后端 I/O 也在同一条队伍里
 *     result
 * }
 * ```
 *
 * 等待锁时被取消是正常传播（[kotlinx.coroutines.sync.Mutex] 自己保证），不会把锁留在别人手里。
 */
class StateBoundary(
    mutex: Mutex = Mutex(),
) {
    private val mutex = mutex

    /**
     * 排队等这把锁，然后在锁里执行 [block]；[block] 结束（含抛异常）就放行。
     *
     * 取消在等锁和执行期间都会照常抛出；[block] 已经产生的效果不由这里回滚，调用方按实际 effect 报告。
     */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
