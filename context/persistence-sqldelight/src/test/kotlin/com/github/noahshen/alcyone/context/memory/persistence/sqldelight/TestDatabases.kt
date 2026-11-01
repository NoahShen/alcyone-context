package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Properties

/**
 * CT04 测试用的真实文件型 SQLite 工具。
 *
 * 所有用例都通过这里拿临时目录下的 `context.db`，不使用纯内存库：
 * 缺库/0 字节/损坏/字节不变这些判据只有在真实文件上才有意义。
 */
internal object TestDatabases {
    fun temporaryDirectory(): Path = Files.createTempDirectory("context-db-")

    fun databaseIn(directory: Path): Path = directory.resolve("context.db")

    /** 新建一个已初始化的库；调用方负责删除目录。 */
    fun initializedDatabase(directory: Path): Path {
        val path = databaseIn(directory)
        ContextDatabaseInitializer.initialize(path)
        return path
    }

    /** 用只读连接执行一次查询，返回首行首列文本（不触碰执行器）。 */
    fun <T> readOnly(
        path: Path,
        sql: String,
        mapper: (java.sql.ResultSet) -> T,
    ): T =
        DriverManager.getConnection("jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery(sql).use(mapper) }
        }

    /** 文件字节的 SHA-256，用于断言「错版文件未被改动」。 */
    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = Files.readAllBytes(path)
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** 直接读写 `PRAGMA user_version`，用于构造「版本不符」的库。 */
    fun setUserVersion(
        path: Path,
        version: Long,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", Properties()).use { connection ->
            connection.createStatement().use { statement -> statement.execute("PRAGMA user_version = $version") }
        }
    }

    fun userVersion(path: Path): Long = readOnly(path, "PRAGMA user_version") { rs -> if (rs.next()) rs.getLong(1) else -1L }

    /** 递归删除临时目录，忽略失败（测试清理）。 */
    fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }
}
