package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection

/**
 * 单连接 SQLite 驱动：状态库整个生命周期只持有一条 [Connection]。
 *
 * 不用 `JdbcSqliteDriver` 的原因：它对文件库**按线程**持有连接，而且非事务语句执行完会关闭该线程的连接
 * （见 `ThreadedConnectionManager.closeConnection`）。两个后果：
 * 1. 「BEGIN → 若干次挂起后的语句 → COMMIT」只要有一次线程切换就换了一条连接，事务静默失效；
 * 2. 语句级 BEGIN / COMMIT 根本不成立——BEGIN 执行完连接就被关掉了。
 * 事务只能走 `Transacter.transaction {}`，而那是阻塞 API，要用挂起回调驱动它就又回到 `runBlocking` 死锁。
 *
 * 一条连接就没有这些问题：**SQLite 事务属于连接，不属于线程**，事务内每条语句无论在哪个线程执行，
 * 用的都是同一条连接、同一个事务。[closeConnection] 不作为，正是 SQLDelight 自己的内存库
 * （`StaticConnectionManager`）的做法。
 */
internal class SingleConnectionJdbcDriver(
    private val connection: Connection,
) : JdbcDriver() {
    // 实现 SQLDelight 查询变化通知接口；当前 Repository 未注册查询监听者。
    // 裸 SQL 事务下该通知不代表事务已提交，不能用来分发 VFS 业务事件。
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()

    override fun addListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) {
        queryKeys.forEach { key -> listeners.getOrPut(key) { mutableSetOf() } += listener }
    }

    override fun removeListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) {
        queryKeys.forEach { key -> listeners[key]?.remove(listener) }
    }

    override fun notifyListeners(queryKeys: Array<out String>) {
        queryKeys.forEach { key -> listeners[key]?.toList()?.forEach { it.queryResultsChanged() } }
    }

    override fun close() {
        connection.close()
    }

    override fun getConnection(): Connection = connection

    /** 连接由本驱动持有到 [close]，不能在单条语句结束后关闭。 */
    override fun closeConnection(connection: Connection) = Unit
}
