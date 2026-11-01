package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT04-F3 rev4 正式反例测试：覆盖第17节 R1～R3 的阻断项。
 *
 * 每条用例都直接对应一个 Architect 复核事实：
 * - R1：`ScopedConnection` 仍是 `Connection by delegate`，`unwrap(Connection)` 透传真实连接，
 *   作用域外仍可更新数据库（探针 `unwrap escaped update accepted=true`）；
 * - R2：事务控制语句（`COMMIT` / `ROLLBACK` / `BEGIN` / `END` / `SAVEPOINT` / `RELEASE`
 *   及多语句）未被统一阻断，`/*c*/ COMMIT` 可让失败业务落库（探针
 *   `failed business persisted through SQL commit=true`）；
 * - R3：取消路径漏终结，回调直接抛 `CancellationException` 后连接停在 `IN_TRANSACTION`，
 *   后续写被拒（探针 `healthy write after cancellation admitted=false`）。
 *
 * 判据原则：
 * - 「被拒绝」= 抛具名 [ContextPersistenceException]（`PERSISTENCE_UNAVAILABLE`），
 *   且消息指明被拒的事务控制关键字（不允许用别的偶然失败冒充阻断）；
 * - 事务控制被拒后，业务写入必须对独立连接不可见；
 * - 失败/取消后连接若已安全回滚，后续请求必须仍可进入。
 */
class F3Rev4CounterExampleTest {
    // ---------------------------------------------------------------- R1 逃逸出口

    @Test
    fun `rev4 unwrap escape is rejected and never yields the physical connection`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                var escaped: Connection? = null
                var unwrapFailure: Throwable? = null
                var statementFailure: Throwable? = null
                var preparedFailure: Throwable? = null
                var resultSetFailure: Throwable? = null
                // R3 清理：执行器异常直接传播；只有「预期被拒」的 unwrap 做局部捕获；
                // 所有 Statement/PreparedStatement/ResultSet 都在 scope 内 use 关闭。
                runBlocking {
                    executor.write { tx ->
                        val connection = (tx.sqlDriver() as JdbcDriver).getConnection()
                        val unwrapped = runCatching { connection.unwrap(Connection::class.java) }
                        escaped = unwrapped.getOrNull()
                        unwrapFailure = unwrapped.exceptionOrNull()
                        connection.createStatement().use { statement ->
                            statementFailure = runCatching { statement.unwrap(Statement::class.java) }.exceptionOrNull()
                            statement.executeQuery("SELECT 1").use { rs ->
                                resultSetFailure = runCatching { rs.unwrap(ResultSet::class.java) }.exceptionOrNull()
                            }
                        }
                        connection.prepareStatement("SELECT 1").use { prepared ->
                            preparedFailure = runCatching { prepared.unwrap(PreparedStatement::class.java) }.exceptionOrNull()
                        }
                    }
                }
                assertTrue(
                    unwrapFailure is ContextPersistenceException,
                    "Connection.unwrap 必须被具名拒绝，实际: $unwrapFailure",
                )
                assertNull(escaped, "Connection.unwrap 不得返回真实连接")
                listOf(
                    "Statement.unwrap" to statementFailure,
                    "PreparedStatement.unwrap" to preparedFailure,
                    "ResultSet.unwrap" to resultSetFailure,
                ).forEach { (what, failure) ->
                    assertTrue(
                        failure is ContextPersistenceException,
                        "$what 必须被具名拒绝，实际: $failure",
                    )
                }
                assertEquals(0L, readWatermark(path), "逃逸出口不得修改数据库")
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- R2 事务控制绕过

    @Test
    fun `rev4 transaction control statements are rejected inside a write callback`() {
        val cases =
            listOf(
                "COMMIT" to "COMMIT",
                "/*c*/ COMMIT" to "COMMIT",
                "-- leading comment" + "\n" + "COMMIT" to "COMMIT",
                "ROLLBACK" to "ROLLBACK",
                "BEGIN" to "BEGIN",
                "END" to "END",
                "SAVEPOINT sp_rev4" to "SAVEPOINT",
                "/*c*/ SAVEPOINT sp_rev4" to "SAVEPOINT",
                "RELEASE sp_rev4" to "RELEASE",
                "UPDATE dreaming_checkpoint SET last_processed_turn_id = 900 WHERE id = 1; COMMIT" to "COMMIT",
            )
        val outcomes = mutableListOf<String>()
        val leaks = mutableListOf<String>()
        cases.forEachIndexed { index, (statement, keyword) ->
            val label = """[$index] $keyword <${statement.replace("\n", "\\n")}>"""
            val directory = TestDatabases.temporaryDirectory()
            try {
                val path = TestDatabases.initializedDatabase(directory)
                val watermark = 100 + index
                ContextDatabaseFactory.open(path).use { executor ->
                    val outcome =
                        runCatching {
                            runBlocking {
                                executor.write { tx ->
                                    tx.update(
                                        "UPDATE dreaming_checkpoint SET last_processed_turn_id = $watermark WHERE id = 1",
                                    )
                                    tx.update(statement)
                                }
                            }
                        }
                    val failure = outcome.exceptionOrNull()
                    // 仅当失败是具名 ContextPersistenceException、消息点名被拒语句本身
                    // （含关键字且说明“blocked”/“not supported”）时才算“被拒绝”；
                    // 其它通用基础设施失败（如连接/提交故障）必须算红，防止冒充阻断。
                    val text = failure?.message.orEmpty().lowercase()
                    val rejected =
                        (failure is ContextPersistenceException) &&
                            text.contains(keyword.lowercase()) &&
                            (text.contains("blocked") || text.contains("not supported"))
                    outcomes +=
                        "$label -> " +
                        if (rejected) {
                            "rejected"
                        } else {
                            "NOT rejected (${failure?.javaClass?.simpleName}: ${failure?.message})"
                        }
                }
                val observed = readWatermark(path)
                if (observed != 0L) {
                    leaks += "$label -> 独立连接可见水位 $observed"
                }
            } finally {
                TestDatabases.deleteRecursively(directory)
            }
        }
        assertEquals(
            emptyList(),
            outcomes.filter { it.contains("NOT rejected") },
            "全部事务控制语句必须被具名拒绝并指明关键字: $outcomes",
        )
        assertEquals(
            emptyList(),
            leaks,
            "被拒绝的事务控制不得让失败业务写入落库: $outcomes",
        )
    }

    // ---------------------------------------------------------------- R3 取消后复用

    @Test
    fun `rev4 cancelled write rolls back once and keeps the slot reusable`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                val failure =
                    runCatching {
                        runBlocking {
                            executor.write { tx ->
                                tx.update(
                                    "UPDATE dreaming_checkpoint SET last_processed_turn_id = 55 WHERE id = 1",
                                )
                                throw CancellationException("rev4 user cancelled inside write callback")
                            }
                        }
                    }
                val error = failure.exceptionOrNull()
                assertTrue(
                    error is CancellationException,
                    "取消必须原样传播，实际: $error",
                )
                assertEquals(0L, readWatermark(path), "被取消的写入不得落库")
                val reused =
                    runCatching {
                        runBlocking {
                            executor.write { tx ->
                                tx.update("UPDATE dreaming_checkpoint SET last_processed_turn_id = 7 WHERE id = 1")
                                7
                            }
                        }
                    }
                assertTrue(
                    reused.isSuccess,
                    "取消后写连接必须仍可复用，实际: ${reused.exceptionOrNull()}",
                )
                assertEquals(7L, readWatermark(path))
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `rev4 business failure rolls back and keeps the slot reusable`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                val failure =
                    runCatching {
                        runBlocking {
                            executor.write { tx ->
                                tx.update(
                                    "UPDATE dreaming_checkpoint SET last_processed_turn_id = 66 WHERE id = 1",
                                )
                                throw IllegalStateException("rev4 business boom")
                            }
                        }
                    }
                val error = failure.exceptionOrNull()
                assertTrue(error is IllegalStateException, "业务异常必须原样传播，实际: $error")
                assertEquals("rev4 business boom", error?.message)
                assertEquals(0L, readWatermark(path), "回滚后失败写入不得落库")
                val reused = runCatching { runBlocking { executor.write { 42 } } }
                assertTrue(
                    reused.isSuccess,
                    "回滚成功后写连接必须仍可复用，实际: ${reused.exceptionOrNull()}",
                )
                assertEquals(42, reused.getOrNull())
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- R6 异常链

    @Test
    fun `rev4 sql failure chain does not contain the raw sql body`() {
        val marker = "REV4_SECRET_BODY_MARKER"
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                val failures = mutableListOf<Throwable>()
                failures +=
                    runCatching {
                        runBlocking {
                            executor.write { tx ->
                                tx.update("UPDATE dreaming_checkpoint SET $marker = 1 WHERE id = 1")
                            }
                        }
                    }.exceptionOrNull()!!
                failures +=
                    runCatching {
                        runBlocking {
                            executor.write { tx ->
                                tx.updatePrepared("UPDATE dreaming_checkpoint SET $marker = 1 WHERE id = 1") { }
                            }
                        }
                    }.exceptionOrNull()!!
                failures +=
                    runCatching {
                        runBlocking {
                            executor.queryRaw("SELECT $marker FROM dreaming_checkpoint") { rs -> rs }
                        }
                    }.exceptionOrNull()!!
                failures.forEach { failure ->
                    assertTrue(
                        failure is ContextPersistenceException,
                        "SQL 失败必须映射为具名持久化异常，实际: $failure",
                    )
                    assertEquals(
                        ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                        (failure as ContextPersistenceException).errorCode,
                    )
                    val chain =
                        failure.message.orEmpty() +
                            "|" +
                            failure.cause?.message.orEmpty() +
                            "|" +
                            failure.suppressed.joinToString { it.message.orEmpty() }
                    val trace = StringWriter()
                    failure.printStackTrace(PrintWriter(trace))
                    assertFalse(
                        chain.contains(marker) || trace.toString().contains(marker),
                        "异常链不得包含原始 SQL 正文: $chain",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- rev5 R1 BOM 词法边界

    @Test
    fun `rev5 bom prefixed transaction control is rejected on every sql entry`() {
        val bom = '\uFEFF'
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val failures = mutableListOf<String>()
                    val statements =
                        listOf(
                            "${bom}COMMIT" to "COMMIT",
                            "$bom/*c*/ COMMIT" to "COMMIT",
                            "/*c*/${bom}COMMIT" to "COMMIT",
                        )
                    statements.forEach { (sql, keyword) ->
                        val outcome =
                            runCatching {
                                executor.write { tx ->
                                    tx.update("UPDATE dreaming_checkpoint SET last_processed_turn_id = 88 WHERE id = 1")
                                    tx.update(sql)
                                }
                            }.exceptionOrNull()
                        if (!isTransactionControlRejection(outcome, keyword)) {
                            failures += "update(${sql.describe()}) -> ${outcome?.javaClass?.simpleName}: ${outcome?.message}"
                        }
                        val watermark = readWatermark(path)
                        if (watermark != 0L) failures += "update(${sql.describe()}) 失败写入落库 watermark=$watermark"
                    }
                    statements.forEach { (sql, keyword) ->
                        val outcome =
                            runCatching {
                                executor.write { tx ->
                                    tx.update("UPDATE dreaming_checkpoint SET last_processed_turn_id = 88 WHERE id = 1")
                                    tx.updatePrepared(sql) { }
                                }
                            }.exceptionOrNull()
                        if (!isTransactionControlRejection(outcome, keyword)) {
                            failures += "updatePrepared(${sql.describe()}) -> ${outcome?.javaClass?.simpleName}: ${outcome?.message}"
                        }
                        val watermark = readWatermark(path)
                        if (watermark != 0L) failures += "updatePrepared(${sql.describe()}) 失败写入落库 watermark=$watermark"
                    }
                    statements.forEach { (sql, keyword) ->
                        val executeOutcome =
                            runCatching {
                                executor.write { tx -> tx.sqlDriver().execute(null, sql, 0) { } }
                            }.exceptionOrNull()
                        if (!isTransactionControlRejection(executeOutcome, keyword)) {
                            failures +=
                                "driver.execute(${sql.describe()}) -> ${executeOutcome?.javaClass?.simpleName}: ${executeOutcome?.message}"
                        }
                        val queryOutcome =
                            runCatching {
                                executor.write { tx ->
                                    tx.sqlDriver().executeQuery(null, sql, { QueryResult.Value(0L) }, 0) { }
                                }
                            }.exceptionOrNull()
                        if (!isTransactionControlRejection(queryOutcome, keyword)) {
                            failures +=
                                "driver.executeQuery(${sql.describe()}) -> ${queryOutcome?.javaClass?.simpleName}: ${queryOutcome?.message}"
                        }
                    }
                    // 多语句仍必须阻断（含 BOM 前缀）。
                    val multiOutcome =
                        runCatching {
                            executor.write { tx -> tx.update("${bom}SELECT 1; COMMIT") }
                        }.exceptionOrNull()
                    if (multiOutcome !is ContextPersistenceException) {
                        failures += "多语句未被阻断 -> ${multiOutcome?.javaClass?.simpleName}: ${multiOutcome?.message}"
                    }
                    assertEquals(emptyList(), failures, "BOM 前缀事务控制必须在所有入口被具名拒绝: $failures")

                    // 正常 DML / 查询 / 字符串内分号 / 参数绑定不受影响。
                    val content =
                        executor.write { tx ->
                            tx.update("UPDATE dreaming_checkpoint SET last_processed_turn_id = 7 WHERE id = 1")
                            tx.update(
                                "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
                                    "VALUES ('R-BOM-1', 'a;b', 'EXTRACTION', '[]', '2026-10-23T18:30:25.123+08:00')",
                            )
                            tx.query("SELECT content FROM raw_memory WHERE id = 'R-BOM-1'") { rs ->
                                rs.next()
                                rs.getString(1)
                            }
                        }
                    assertEquals("a;b", content, "字符串内分号不得被误判为多语句")
                    val preparedCount =
                        executor.write { tx ->
                            tx.queryPrepared("SELECT count(*) FROM raw_memory WHERE content = 'a;b'") { cursor ->
                                if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                            }
                        }
                    assertEquals(1L, preparedCount, "正常 prepared 绑定/查询不得被边界检查误伤")
                    assertEquals(7L, readWatermark(path), "正常 DML 必须照常提交；被拒绝的 BOM 事务控制写入不得残留")
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- rev5 R2 中毒连接所有权

    @Test
    fun `rev5 poisoned connection close failure keeps ownership for the global close`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val delegate = DriverManager.getDriver("jdbc:sqlite:${path.toAbsolutePath()}")
            val wrapper = CloseFaultDriver(delegate, closeFailures = 1)
            DriverManager.deregisterDriver(delegate)
            DriverManager.registerDriver(wrapper)
            try {
                val executor = ContextDatabaseFactory.open(path)
                try {
                    val writeFailure = runCatching { runBlocking { executor.write { } } }.exceptionOrNull()
                    assertTrue(
                        writeFailure is ContextPersistenceException,
                        "注入 commit 失败必须可观察，实际: $writeFailure",
                    )
                    val closeFailure = runCatching { executor.close() }.exceptionOrNull()
                    assertNull(
                        closeFailure,
                        "首次物理 close 失败后全局 close 必须重试并成功，实际: $closeFailure",
                    )
                    assertEquals(
                        0,
                        wrapper.physicalConnections.count { !it.isClosed },
                        "executor.close() 之后不得残留未关闭的物理连接（所有权必须保留到 close 成功）",
                    )
                } finally {
                    runCatching { executor.close() }
                }
            } finally {
                DriverManager.deregisterDriver(wrapper)
                DriverManager.registerDriver(delegate)
                wrapper.physicalConnections.forEach { runCatching { it.close() } }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `rev5 persistent close failure is reported by the shared close result`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val delegate = DriverManager.getDriver("jdbc:sqlite:${path.toAbsolutePath()}")
            val wrapper = CloseFaultDriver(delegate, closeFailures = Int.MAX_VALUE)
            DriverManager.deregisterDriver(delegate)
            DriverManager.registerDriver(wrapper)
            try {
                val executor = ContextDatabaseFactory.open(path)
                try {
                    runCatching { runBlocking { executor.write { } } }
                    val first = runCatching { executor.close() }.exceptionOrNull()
                    assertTrue(
                        first is ContextPersistenceException,
                        "持续清理失败必须报告失败而不是静默成功，实际: $first",
                    )
                    val repeated = runCatching { executor.close() }.exceptionOrNull()
                    assertTrue(
                        repeated is ContextPersistenceException,
                        "共享关闭结果必须对后续调用者可见，实际: $repeated",
                    )
                    assertTrue(
                        wrapper.physicalConnections.any { !it.isClosed },
                        "持续注入 close 失败时连接确实未能释放（证明失败不是静默成功）",
                    )
                } finally {
                    runCatching { executor.close() }
                }
            } finally {
                DriverManager.deregisterDriver(wrapper)
                DriverManager.registerDriver(delegate)
                wrapper.physicalConnections.forEach { runCatching { it.close() } }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun isTransactionControlRejection(
        failure: Throwable?,
        keyword: String,
    ): Boolean {
        if (failure !is ContextPersistenceException) return false
        val text = failure.message.orEmpty().lowercase()
        return text.contains(keyword.lowercase()) && (text.contains("blocked") || text.contains("not supported"))
    }

    private fun String.describe(): String = replace("\uFEFF", "<BOM>").replace("\n", "\\n")

    /** 独立只读语义连接读取水位；不经执行器，用于判断业务写入是否真的落库。 */
    private fun readWatermark(path: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT last_processed_turn_id FROM dreaming_checkpoint").use { rs ->
                    if (rs.next()) rs.getLong(1) else -1L
                }
            }
        }
}

/**
 * 测试用 JDBC Driver（rev5 R2）：对写 Slot（首个非 `mode=ro` 连接）注入一次 commit 失败，
 * 并按配置注入 N 次 close 失败。close 失败时**不委托**真实 close，用于验证
 * 「首次清理失败后物理连接所有权仍保留、全局 close 可以再次尝试」。
 */
private class CloseFaultDriver(
    val delegate: Driver,
    closeFailures: Int,
) : Driver {
    val physicalConnections = CopyOnWriteArrayList<Connection>()
    private val remainingCloseFailures = AtomicInteger(closeFailures)
    private val remainingCommitFailures = AtomicInteger(1)
    private val slotIndex = AtomicInteger(0)

    override fun connect(
        url: String,
        info: Properties?,
    ): Connection? {
        val real = delegate.connect(url, info ?: Properties()) ?: return null
        if (url.endsWith("?mode=ro")) return real
        val slot = slotIndex.getAndIncrement()
        physicalConnections += real
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (slot == 0 && method.name == "close" && remainingCloseFailures.getAndDecrement() > 0) {
                throw SQLException("injected close failure")
            }
            if (slot == 0 && method.name == "commit" && remainingCommitFailures.getAndDecrement() > 0) {
                throw SQLException("injected commit failure")
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw failure.cause ?: failure
            }
        } as Connection
    }

    override fun acceptsURL(url: String): Boolean = delegate.acceptsURL(url)

    override fun getPropertyInfo(
        url: String,
        info: Properties?,
    ): Array<DriverPropertyInfo> = delegate.getPropertyInfo(url, info ?: Properties())

    override fun getMajorVersion(): Int = delegate.majorVersion

    override fun getMinorVersion(): Int = delegate.minorVersion

    override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

    override fun getParentLogger(): Logger = delegate.parentLogger
}
