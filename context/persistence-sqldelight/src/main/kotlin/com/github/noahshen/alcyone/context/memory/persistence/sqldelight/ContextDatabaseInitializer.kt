package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/**
 * 显式首次初始化：只有这一个入口可以创建 `context.db`。
 *
 * 依据技术实现设计 §2.8 与 CT01 §h2：普通启动遇到缺库必须拒绝，
 * 首次建库必须由调用方明确授权。CM1 阶段该方法直接由测试与 Runtime 调用，
 * 正式 CLI 入口在 CM6（CT29）落地。
 *
 * 语义：
 * - 目标文件已存在（含 0 字节）→ 抛 [com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException]
 *   （`PERSISTENCE_UNAVAILABLE`），**绝不覆盖、不截断**既有文件；
 * - 目标文件不存在 → 通过 [FileChannel] 的 `CREATE_NEW` 原子认领文件所有权，
 *   仅认领者继续建库；建库失败时仅清理本次自有失败产物，**不删除他方已存在的 sidecar
 *   或他方已替换的文件**；
 * - 建库失败 → 仅在清理前确认文件仍是本次认领的同一文件（fileKey 一致），
 *   无法确认身份时宁可保留，绝不删除他方文件，也绝不重建。
 *
 * 版本号来源唯一：`ContextDatabaseSqlite.Schema.version`（= 1）。CT04 不做任何迁移。
 *
 * 并发安全边界：`CREATE_NEW` 只保证「谁先认领文件」的唯一性，不能抵御文件被认领后
 * 外部替换的场景；建库后的版本/结构验证由 [ContextDatabaseFactory] 和 [ContextSlot]
 * 在实际连接上逐条复查，不信任前一次检查。
 */
object ContextDatabaseInitializer {
    /** 当前 Schema 版本，与 `PRAGMA user_version` 一一对应。 */
    val SCHEMA_VERSION: Long = ContextDatabaseSqlite.Schema.version

    /**
     * 在 [databasePath] 处创建全新的 Context 状态库。
     *
     * @throws com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
     *   目标已存在或建库过程中发生 SQLite 故障。
     */
    fun initialize(databasePath: Path) {
        val target = databasePath.toAbsolutePath().normalize()
        target.parent?.let { parent ->
            ContextSqliteErrors.map("creating parent directory for context database") { Files.createDirectories(parent) }
        }

        // 原子认领：CREATE_NEW 只在文件不存在时成功，失败时文件已由他方创建，绝不动他方文件。
        var claimed = false
        try {
            FileChannel.open(target, CREATE_NEW, WRITE).use { claimed = true }
        } catch (failure: java.nio.file.FileAlreadyExistsException) {
            throw persistenceUnavailable("context database already exists, refusing to overwrite: $target")
        } catch (failure: Throwable) {
            throw persistenceUnavailable("cannot claim context database file: $target")
        }

        if (!claimed) {
            throw persistenceUnavailable("context database already exists, refusing to overwrite: $target")
        }

        // 记录认领时的文件标识，供清理前核对身份。
        val claimKey: Any? =
            Files
                .readAttributes(
                    target,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey()

        try {
            createSchema(target)
        } catch (failure: Throwable) {
            // 半成品不留在磁盘上：仅在确认文件仍是本次认领的同一文件时删除。
            // 文件被他方替换或删除后，绝不删除他方文件，也绝不重建。
            removeOwnProduct(target, claimKey)
            throw failure
        }
    }

    private fun removeOwnProduct(
        target: Path,
        claimKey: Any?,
    ) {
        if (claimKey == null) return
        try {
            val currentKey =
                Files
                    .readAttributes(
                        target,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                    ).fileKey()
            if (currentKey == claimKey) {
                // 文件仍是本次认领的同一文件，可安全删除本次产物。
                Files.deleteIfExists(target)
            }
            // 否则：文件已被外部替换或删除，不删除他方文件，也不重建。
        } catch (_: java.nio.file.NoSuchFileException) {
            // 文件已不存在：外部已删除，不重建。
        } catch (_: Throwable) {
            // 无法读取属性，身份无法确认：宁可保留，不删除他方文件。
        }
    }

    private fun createSchema(target: Path) {
        // 读写连接：mode=rw，库缺失时拒绝而不是自动建空库。
        // 整个建库过程的底层 SQLException 统一由 ContextSqliteErrors.map 安全映射，
        // 不裸漏原 cause / message（R6）。
        ContextSqliteErrors.map("creating context database $target") {
            val connection =
                java.sql.DriverManager.getConnection(SqliteUrls.openCreateUrl(target), java.util.Properties())
            try {
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("PRAGMA foreign_keys=ON")
                    statement.execute("PRAGMA busy_timeout=5000")
                }
                val driver = SlotConnectionJdbcDriver(connection)
                ContextDatabaseSqlite.Schema.create(driver).value
                connection.createStatement().use { it.execute("PRAGMA user_version = $SCHEMA_VERSION") }
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    /** 供测试断言初始化后的版本号；生产路径同样使用该值。 */
    internal fun schemaVersionOf(driver: JdbcDriver): Long = ContextSqliteErrors.map("reading user_version") { driver.schemaVersion() }
}

/** 读取 `PRAGMA user_version`：0 表示未初始化，非 0 表示已建库。 */
internal fun JdbcDriver.schemaVersion(): Long =
    executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
            )
        },
        0,
        null,
    ).value
