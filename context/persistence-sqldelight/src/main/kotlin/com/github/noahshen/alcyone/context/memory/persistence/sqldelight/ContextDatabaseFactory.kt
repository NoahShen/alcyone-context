package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * 普通打开：**绝不建库、绝不迁移**。
 *
 * 依据技术实现设计 §2.8 与 CT01 §h2 / §h6，检查顺序严格如下：
 * 1. 文件不存在 → `DATABASE_NOT_INITIALIZED`，且磁盘上不产生任何新文件；
 * 2. 文件为 0 字节 → `DATABASE_NOT_INITIALIZED`（空文件不是有效库）；
 * 3. 以只读模式（`file:<abs>?mode=ro`）打开，读 `PRAGMA user_version`：
 *    - 不等于 [ContextDatabaseInitializer.SCHEMA_VERSION]（低于或高于都一样）
 *      → `SCHEMA_VERSION_MISMATCH`，**不写任何 PRAGMA**，原文件字节不变；
 *    - 六表或五索引缺失 → `PERSISTENCE_UNAVAILABLE`（不是本库或被损坏）；
 *    - SQLite 打开/查询异常 → `PERSISTENCE_UNAVAILABLE`；
 * 4. 校验全部通过后才打开读写 Slot（写 1 + 读 N）。每个 Slot 的实际连接在应用持久化
 *    PRAGMA 前，由 [ContextSlot.validateSchemaVersion] 再次读取 `user_version`，
 *    防止验证后文件被替换；版本不符则关闭已打开的 Slot 并拒绝。
 *
 * 因此「先验证版本、后改持久 PRAGMA」的顺序是可断言的：错版文件连 `journal_mode` 都不会被碰。
 *
 * 异常安全（R6 Factory）：底层 `SQLException` 的消息可能携带 SQL 原文、业务正文或凭据，
 * 其 cause / suppressed 链还会把驱动内部异常一路带出去。本边界对外只保留操作名与
 * SQLSTATE/驱动类型名，**绝不将原 SQLException 作为 cause 传递**。
 */
object ContextDatabaseFactory {
    /** 默认读 Slot 数（技术实现设计 §2.1 建议 2，可配置）。 */
    const val DEFAULT_READ_SLOTS: Int = 2

    /** 六张业务表（与 `.sq` 一致）。 */
    internal val REQUIRED_TABLES: Set<String> =
        setOf(
            "conversation_turn",
            "raw_memory",
            "organization_pending",
            "organized_memory",
            "organized_memory_source",
            "dreaming_checkpoint",
        )

    /** 五个显式业务索引（与 `.sq` 一致）。 */
    internal val REQUIRED_INDEXES: Set<String> =
        setOf(
            "idx_turn_session_time",
            "idx_turn_pending_session_time",
            "idx_turn_session_created_at",
            "idx_organized_updated_at",
            "idx_source_raw",
        )

    /** 校验六表 + 五索引是否齐备，返回缺失项。 */
    internal fun missingObjects(connection: Connection): Set<String> {
        val present = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table','index')").use { rs ->
                while (rs.next()) present += rs.getString(1)
            }
        }
        return (REQUIRED_TABLES + REQUIRED_INDEXES) - present
    }

    /**
     * 打开已初始化的 Context 状态库。
     *
     * @param databasePath 已由 [ContextDatabaseInitializer.initialize] 显式创建过的文件
     * @param readSlotCount 只读 Slot 数量，默认 [DEFAULT_READ_SLOTS]
     * @throws com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
     *   缺库、0 字节、版本不符、结构缺失或打开失败
     */
    fun open(
        databasePath: Path,
        readSlotCount: Int = DEFAULT_READ_SLOTS,
    ): ContextDatabaseExecutor {
        require(readSlotCount >= 1) { "readSlotCount must be at least 1" }
        val target = databasePath.toAbsolutePath().normalize()

        // 1. 存在性：缺失时绝不创建文件。
        if (!Files.exists(target)) {
            throw databaseNotInitialized("context database is missing, run the explicit initializer first: $target")
        }
        // 2. 0 字节不是有效库。
        if (Files.size(target) == 0L) {
            throw databaseNotInitialized("context database file is empty (0 bytes): $target")
        }
        // 3. 只读校验：先版本，再结构；此阶段不写任何 PRAGMA。
        verifyReadOnly(target)

        // 4. 校验通过后才打开读写 Slot。ContextDatabaseExecutor 内部会在每个实际连接上
        //    再次验证 user_version（防止验证后文件被替换），然后才应用持久化 PRAGMA。
        return ContextDatabaseExecutor(target, readSlotCount)
    }

    private fun verifyReadOnly(target: Path) {
        // SqliteUrls.openReadUrl 使用 Path.toUri() 生成编码正确的文件 URI，
        // 空格、%、?、# 等字符不会破坏 URL 解析。
        val url = SqliteUrls.openReadUrl(target)
        val connection =
            try {
                DriverManager.getConnection(url, Properties())
            } catch (failure: java.sql.SQLException) {
                // 不把原 SQLException 当 cause：其 message / stackTrace 可能包含敏感 SQL 原文。
                throw persistenceUnavailable("context database cannot be opened read-only (corrupt or not a database): $target")
            }
        try {
            val version =
                try {
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                    }
                } catch (failure: java.sql.SQLException) {
                    throw persistenceUnavailable("context database integrity check failed: $target")
                }
            if (version != ContextDatabaseInitializer.SCHEMA_VERSION) {
                throw schemaVersionMismatch(
                    "context database schema version $version does not match required " +
                        "${ContextDatabaseInitializer.SCHEMA_VERSION} (no automatic migration): $target",
                )
            }
            val missing =
                try {
                    missingObjects(connection)
                } catch (failure: java.sql.SQLException) {
                    throw persistenceUnavailable("context database structure check failed: $target")
                }
            if (missing.isNotEmpty()) {
                throw persistenceUnavailable(
                    "context database is missing expected objects ${missing.sorted()}: $target",
                )
            }
        } finally {
            runCatching { connection.close() }
        }
    }
}
