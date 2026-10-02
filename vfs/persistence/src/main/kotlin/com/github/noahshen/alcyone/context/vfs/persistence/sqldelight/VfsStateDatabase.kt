package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * VFS 状态库的打开与关闭入口（T03 §8 版本化升级、§9 释放资源）。
 *
 * **版本管理**（A05）：
 * - 库版本号就是 SQLite 的 `PRAGMA user_version`，与 [VfsDatabase.Schema.version] 一一对应；
 *   本模块是基线 schema，版本为 1。
 * - 打开时读 `user_version`：`0` = 空库，执行 [app.cash.sqldelight.db.SqlSchema.create]；
 *   `> 0` = 已有库，执行 [app.cash.sqldelight.db.SqlSchema.migrate] 升到当前版本。两条路径都**不删除重建**。
 * - 版本号相等时什么也不做，只重开连接。
 * - 将来改表结构时，在 `src/main/sqldelight/<包路径>/` 下与对应 `.sq` 同名新增 `.sqm` 迁移文件，
 *   SQLDelight 生成的 [VfsDatabase.Schema.migrate] 就会多出从旧版本到新版本的一步，
 *   [VfsDatabase.Schema.version] 随迁移文件数递增；`user_version` 由本文件的 `open` 在迁移成功后写回。
 *   版本号只增不减，不要手改已有 `.sqm`。
 *
 * **资源**：[close] 释放整库唯一那条 JDBC 连接，之后本对象的任何数据库调用都会以 STATE_ERROR 失败
 * （A04 / A06）。首版一个状态库只由一个 Runtime 管理，不做跨进程独占检测（T03 §7）。
 *
 * 整库共用一条连接，理由见 [SingleConnectionJdbcDriver]——这是本模块所有事务语义的基石。
 * 对外只暴露本模块类型，SQLDelight / JDBC 类型留在模块内部（A06）。
 */
class VfsStateDatabase
    internal constructor(
        internal val database: VfsDatabase,
        internal val driver: JdbcDriver,
    ) : AutoCloseable {
        override fun close() {
            driver.close()
        }

        companion object {
            /** 打开内存库，用于测试与临时状态；关闭即丢失。`jdbc:sqlite:` 空路径 = 私有内存库。 */
            fun inMemory(): VfsStateDatabase = open("jdbc:sqlite:")

            /** 打开文件库，路径由调用方（Runtime）决定。 */
            fun file(path: Path): VfsStateDatabase = open("jdbc:sqlite:$path")

            private fun open(url: String): VfsStateDatabase =
                mapStateErrors {
                    val driver = SingleConnectionJdbcDriver(DriverManager.getConnection(url, Properties()))
                    try {
                        val schema = VfsDatabase.Schema
                        val currentVersion = driver.schemaVersion()
                        if (currentVersion != schema.version) {
                            // 迁移入口：空库建表，已有库按版本迁移，不删除重建。版本号与 DDL 在同一事务里提交。
                            driver.inRawTransaction {
                                if (currentVersion == 0L) {
                                    schema.create(driver).value
                                } else {
                                    schema.migrate(driver, currentVersion, schema.version).value
                                }
                                driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                            }
                        }
                        VfsStateDatabase(VfsDatabase(driver), driver)
                    } catch (failure: Throwable) {
                        // 建表 / 迁移失败也要释放连接，否则这次打开留下的句柄没人管。
                        runCatching { driver.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                        throw failure
                    }
                }
        }
    }

/** 读 `PRAGMA user_version`：0 表示空库，非 0 表示已建库的当前版本。 */
internal fun JdbcDriver.schemaVersion(): Long =
    executeQuery(
        null,
        "PRAGMA user_version",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        0,
        null,
    ).value
