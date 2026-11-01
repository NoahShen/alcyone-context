package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只读上下文的作用域（CT04 底层执行入口）。
 *
 * 三条硬约束，违反即抛具名异常：
 * 1. **线程绑定**：只有创建它的 Slot 固定线程可以使用；
 * 2. **生命周期绑定**：回调返回后自动失效，不能把作用域带出去后再用（禁止上下文逃逸）；
 * 3. **只读**：不暴露任何写入口，且底层连接本身 `query_only=ON`。
 *
 * 这不是业务 Repository：只有 `query` / `driver` 这类底层入口，业务 ReadOps 属 CT05。
 *
 * R3：`query`/`queryPrepared`/`sqlDriver` 均通过 `slot.scopedConnection(session)` /
 * `slot.scopedDriver(session)` 获取受 [ScopeSession] 保护的连接/驱动，每次真实操作
 * 前都会校验作用域活跃、线程归属、未废弃；会话失效后所有操作均被拒绝。
 */
class RawReadScope internal constructor(
    private val slot: ContextSlot,
    private val ownerThread: Thread,
) : RawScopeHandle {
    private val active = AtomicBoolean(true)

    /** 当前作用域会话：由执行器在 [withScope] 处创建并传入。 */
    internal var session: ScopeSession? = null

    /** 该 Slot 的连接所绑定的 SQLDelight 驱动，供 CT05 组装生成查询。
     * 仅供内部/测试使用；CT05 应通过 query/queryPrepared 执行查询，以确保每次调用都校验作用域和线程。
     * 返回前会将本驱动绑定到当前作用域（线程 + 活跃态），驱动本身会自保护逃逸与跨线程。 */
    internal fun sqlDriver(): SqlDriver {
        ensureUsable("sqlDriver")
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("read", "no active scope session")
        return slot.scopedDriver(s)
    }

    /** 一次同步查询，映射由调用方给出。 */
    fun <T> query(
        sql: String,
        mapper: (ResultSet) -> T,
    ): T {
        ensureUsable("query")
        SqlBoundaryGuard.check(sql)
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("read", "no active scope session")
        return ContextSqliteErrors.map("raw read query") {
            slot.scopedConnection(s).createStatement().use { statement -> statement.executeQuery(sql).use(mapper) }
        }
    }

    /** 参数化查询，复用 SQLDelight 的绑定与游标映射。 */
    fun <T> queryPrepared(
        sql: String,
        bind: (SqlPreparedStatement) -> Unit = {},
        mapper: (SqlCursor) -> T,
    ): T {
        ensureUsable("queryPrepared")
        SqlBoundaryGuard.check(sql)
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("read", "no active scope session")
        return ContextSqliteErrors.map("raw read prepared query") {
            slot
                .scopedDriver(s)
                .executeQuery(null, sql, { cursor ->
                    app.cash.sqldelight.db.QueryResult
                        .Value(mapper(cursor))
                }, 0, bind)
                .value
        }
    }

    override fun invalidate() {
        active.set(false)
        session?.invalidate()
        session = null
    }

    internal fun ensureUsable(what: String) {
        if (!active.get()) {
            throw ContextDatabaseExecutor.escapedScope("read", what)
        }
        if (Thread.currentThread() !== ownerThread) {
            throw ContextDatabaseExecutor.crossThread("read", what)
        }
    }
}

/**
 * 写事务作用域（CT04 底层执行入口）：在唯一写 Slot 的物理连接上、`autoCommit=false` 期间使用。
 *
 * 除同样受线程/生命周期约束外，所有 SQL 入口统一经 [SqlBoundaryGuard]：事务控制语句
 * （`BEGIN`/`COMMIT`/`END`/`ROLLBACK`/`SAVEPOINT`/`RELEASE`）与多语句一律具名拒绝，
 * 事务只能由执行器整体提交或回滚；执行器同时拒绝嵌套。
 *
 * R3：`query`/`update`/`queryPrepared`/`updatePrepared`/`sqlDriver` 均通过
 * `slot.scopedConnection(session)` / `slot.scopedDriver(session)` 获取受
 * [ScopeSession] 保护的连接/驱动，每次真实操作前都会校验作用域活跃、线程归属、
 * 未废弃；会话失效后所有操作均被拒绝。
 */
class RawTransactionScope internal constructor(
    private val slot: ContextSlot,
    private val ownerThread: Thread,
) : RawScopeHandle {
    private val active = AtomicBoolean(true)

    /** 当前作用域会话：由执行器在 [withScope] 处创建并传入。 */
    internal var session: ScopeSession? = null

    /** 该写连接的 SQLDelight 驱动，供 CT05 组装生成的 TxOps 查询。
     * 仅供内部/测试使用；CT05 应通过 query/update/queryPrepared/updatePrepared 执行，
     * 以确保每次调用都校验作用域和线程。
     * 返回前会将本驱动绑定到当前作用域（线程 + 活跃态），驱动本身会自保护逃逸与跨线程。 */
    internal fun sqlDriver(): SqlDriver {
        ensureUsable("sqlDriver")
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("write", "no active scope session")
        return slot.scopedDriver(s)
    }

    fun <T> query(
        sql: String,
        mapper: (ResultSet) -> T,
    ): T {
        ensureUsable("query")
        SqlBoundaryGuard.check(sql)
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("write", "no active scope session")
        return ContextSqliteErrors.map("raw transaction query") {
            slot.scopedConnection(s).createStatement().use { statement -> statement.executeQuery(sql).use(mapper) }
        }
    }

    fun <T> queryPrepared(
        sql: String,
        bind: (SqlPreparedStatement) -> Unit = {},
        mapper: (SqlCursor) -> T,
    ): T {
        ensureUsable("queryPrepared")
        SqlBoundaryGuard.check(sql)
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("write", "no active scope session")
        return ContextSqliteErrors.map("raw transaction prepared query") {
            slot
                .scopedDriver(s)
                .executeQuery(null, sql, { cursor ->
                    app.cash.sqldelight.db.QueryResult
                        .Value(mapper(cursor))
                }, 0, bind)
                .value
        }
    }

    /** 执行一条更新语句，返回受影响行数。事务控制语句在此被统一阻断。 */
    fun update(sql: String): Int {
        ensureUsable("update")
        SqlBoundaryGuard.check(sql)
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("write", "no active scope session")
        return ContextSqliteErrors.map("raw transaction update") {
            slot.scopedConnection(s).createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    /** 参数化更新，复用 SQLDelight 的绑定。 */
    fun updatePrepared(
        sql: String,
        bind: (SqlPreparedStatement) -> Unit,
    ): Long {
        ensureUsable("updatePrepared")
        SqlBoundaryGuard.check(sql)
        val s = session ?: throw ContextDatabaseExecutor.escapedScope("write", "no active scope session")
        return ContextSqliteErrors.map("raw transaction prepared update") { slot.scopedDriver(s).execute(null, sql, 0, bind).value }
    }

    override fun invalidate() {
        active.set(false)
        session?.invalidate()
        session = null
    }

    internal fun ensureUsable(what: String) {
        if (!active.get()) {
            throw ContextDatabaseExecutor.escapedScope("write", what)
        }
        if (Thread.currentThread() !== ownerThread) {
            throw ContextDatabaseExecutor.crossThread("write", what)
        }
    }
}
