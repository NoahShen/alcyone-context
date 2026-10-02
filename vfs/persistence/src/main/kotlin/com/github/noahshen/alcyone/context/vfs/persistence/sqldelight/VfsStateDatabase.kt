package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * VFS 状态库的打开与关闭入口（T03 §8 版本化升级、§9 释放资源）。
 *
 * **版本管理**（A05）：
 * - 库版本号就是 SQLite 的 `PRAGMA user_version`，与 [VfsDatabase.Schema.version] 一一对应；
 *   本模块是基线 schema，版本为 1。
 * - 打开时读 `user_version`，四种情况分得很清楚：
 *   `0` = 空库，执行 [app.cash.sqldelight.db.SqlSchema.create]；等于当前版本 = 只重开连接；
 *   `0 < v < 当前` = 执行 [app.cash.sqldelight.db.SqlSchema.migrate] 升到当前版本；**高于当前版本 = 拒绝打开**
 *   （`STATE_ERROR`，不改原库，连接随之释放）。前两条路径都**不删除重建**。
 * - 迁移文件**从 `1.sqm` 起连续编号**，编号 N 表示“从版本 N 升到 N+1”：1 → 2 写 `1.sqm`，2 → 3 写 `2.sqm`，
 *   以此类推；`.sq` 始终表示最新结构。版本号只增不减，不要手改已发布的 `.sqm`。
 * - **程序逻辑变更不会自动提高 Schema 版本**：只有持久化结构或数据格式变化才需要新增 `.sqm`。
 *   `user_version` 由本文件的 `open` 在升级成功后写回（DDL 与版本号同一事务提交）。
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
            // 与其他状态库失败一致：JDBC 关闭异常也映射为 STATE_ERROR，cause 保留原始异常。
            mapStateErrors { driver.close() }
        }

        companion object {
            /**
             * 打开临时库，用于测试与临时状态；关闭即删除。
             *
             * URL 是 `jdbc:sqlite:`（空路径）：SQLite 侧是**私有临时库**（落临时文件，连接关闭即删除），
             * 不是纯内存库。纯内存写法是 `jdbc:sqlite::memory:`，本轮不用，以免测试与真实使用行为不一致。
             */
            fun inMemory(): VfsStateDatabase = open("jdbc:sqlite:")

            /** 打开文件库，路径由调用方（Runtime）决定。 */
            fun file(path: Path): VfsStateDatabase = open("jdbc:sqlite:$path")

            private fun open(url: String): VfsStateDatabase =
                mapStateErrors {
                    val driver = SingleConnectionJdbcDriver(DriverManager.getConnection(url, Properties()))
                    try {
                        val schema = VfsDatabase.Schema
                        val currentVersion = driver.schemaVersion()
                        when {
                            // 同版本：既不建表也不迁移，只重开连接。
                            currentVersion == schema.version -> Unit
                            currentVersion == 0L || currentVersion < schema.version -> upgrade(driver, currentVersion)
                            // 比代码新：旧代码不猜它的结构，也不能把自己的版本号写回去。
                            else ->
                                throw VfsException(
                                    VfsErrorCode.STATE_ERROR,
                                    "state database schema version $currentVersion is newer than " +
                                        "supported version ${schema.version}",
                                )
                        }
                        VfsStateDatabase(VfsDatabase(driver), driver)
                    } catch (failure: Throwable) {
                        // 建表 / 迁移失败也要释放连接，否则这次打开留下的句柄没人管。
                        runCatching { driver.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                        throw failure
                    }
                }

            /** 升级入口：DDL 与版本号在同一事务里提交，中途失败整体回滚，不留下改了一半的库。 */
            private fun upgrade(
                driver: JdbcDriver,
                fromVersion: Long,
            ) {
                val schema = VfsDatabase.Schema
                driver.inRawTransaction {
                    if (fromVersion == 0L) {
                        schema.create(driver).value
                    } else {
                        schema.migrate(driver, fromVersion, schema.version).value
                    }
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
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
