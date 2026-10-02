package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.JdbcDriver

/**
 * 状态库的裸事务控制。
 *
 * 为什么不用 `Transacter.transaction {}`：它是阻塞 API，要用挂起回调驱动就只能套 `runBlocking`，
 * 而 `runBlocking` 在被取消的协程里会死锁（已实际发生）。SQLDelight 2.1 的驱动级事务
 * 也没有公开的 commit / rollback，只有阻塞入口。
 *
 * 为什么裸 SQL 就够：整库共用一条连接，SQLite 事务属于连接不属于线程（见 [SingleConnectionJdbcDriver]），
 * 所以 BEGIN / 中间语句 / COMMIT 不必落在同一个线程上。
 */
internal const val BEGIN_SQL = "BEGIN IMMEDIATE TRANSACTION"
internal const val COMMIT_SQL = "COMMIT"
internal const val ROLLBACK_SQL = "ROLLBACK"

/** 同步执行一个事务；`block` 抛出即回滚，回滚失败不掩盖原始异常。 */
internal fun JdbcDriver.inRawTransaction(block: () -> Unit) {
    execute(null, BEGIN_SQL, 0)
    try {
        block()
        execute(null, COMMIT_SQL, 0)
    } catch (failure: Throwable) {
        runCatching { execute(null, ROLLBACK_SQL, 0) }.exceptionOrNull()?.let { failure.addSuppressed(it) }
        throw failure
    }
}
