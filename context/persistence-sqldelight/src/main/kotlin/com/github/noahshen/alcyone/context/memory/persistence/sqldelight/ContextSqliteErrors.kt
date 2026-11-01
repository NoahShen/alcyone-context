package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import java.sql.SQLException

/**
 * 持久化层错误映射（R6 边界）。
 *
 * 底层 `SQLException.getMessage()` 会携带本次 SQL 原文——而 SQL 原文可能内嵌业务正文、
 * 参数值乃至凭据；其 cause / suppressed 链还会把驱动内部异常一路带出去。因此本边界
 * 对外只保留三类安全信息：**操作名、SQLSTATE 码、驱动异常类型名**。
 *
 * 规则：
 * - 底层 `SQLException` 一律映射为不带 cause 的具名 [ContextPersistenceException]，
 *   原始异常（含 message / stackTrace / cause / suppressed 全文）不进对外异常链；
 * - 本模块自己构造的具名异常原样透传；
 * - `CancellationException` 绝不在这里被包装（协作取消必须原样向外传播）。
 */
internal object ContextSqliteErrors {
    /**
     * 执行一段 JDBC 操作并把底层 SQL 失败收敛为 `PERSISTENCE_UNAVAILABLE`。
     *
     * @param what 进入消息的操作名（不得包含 SQL 原文、业务正文或凭据）。
     */
    fun <T> map(
        what: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: ContextPersistenceException) {
            throw failure
        } catch (failure: SQLException) {
            val sqlState = failure.sqlState?.trim().orEmpty()
            val detail = if (sqlState.isNotEmpty()) "SQLSTATE $sqlState" else failure.javaClass.simpleName
            throw persistenceUnavailable("$what failed: $detail")
        }
}

/** 具名持久化异常（[PERSISTENCE_UNAVAILABLE]）。cause 仅用于本模块内部诊断，生产路径不传底层异常。 */
internal fun persistenceUnavailable(
    message: String,
    cause: Throwable? = null,
): ContextPersistenceException = ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, message, cause)

/** 「库未初始化」：文件缺失或 0 字节，普通启动不得自动建库。 */
internal fun databaseNotInitialized(message: String): ContextPersistenceException =
    ContextPersistenceException(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, message)

/** 「版本不符」：不自动迁移，不动原文件。 */
internal fun schemaVersionMismatch(message: String): ContextPersistenceException =
    ContextPersistenceException(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, message)
