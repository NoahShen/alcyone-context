package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CT04-F3 正式反例测试：R3 作用域/driver逃逸、R5 事务失败状态转移、R6 异常链脱敏。 */
class F3TransactionFailureAndScopeBoundaryTest {
    private val insertRaw =
        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
            "VALUES (?, 'x', 'EXTRACTION', '[]', '2026-10-23T18:30:25.123+08:00')"

    // ---------------------------------------------------------------- R3 驱动逃逸与作用域边界

    @Test
    fun `R3 sqlDriver escape after write scope returns is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    var escapedDriver: Any? = null
                    executor.write { tx ->
                        escapedDriver = tx.sqlDriver()
                    }
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            val driver = escapedDriver as app.cash.sqldelight.db.SqlDriver
                            driver.execute(null, "UPDATE dreaming_checkpoint SET last_processed_turn_id=99 WHERE id=1", 0, { })
                        }
                    assertTrue(
                        failure.message.orEmpty().contains("after its callback") ||
                            failure.message.orEmpty().contains("driver"),
                        "逃逸驱动必须被拒绝，实际: ${failure.message}",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R3 sqlDriver escape after read scope returns is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    var escapedDriver: Any? = null
                    executor.read { scope ->
                        escapedDriver = scope.sqlDriver()
                    }
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            val driver = escapedDriver as app.cash.sqldelight.db.SqlDriver
                            driver.executeQuery(null, "SELECT 1", { cursor ->
                                app.cash.sqldelight.db.QueryResult
                                    .Value(Unit)
                            }, 0, { })
                        }
                    assertTrue(
                        failure.message.orEmpty().contains("after its callback") ||
                            failure.message.orEmpty().contains("driver"),
                        "逃逸驱动必须被拒绝，实际: ${failure.message}",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R3 driver used from foreign thread after scope returns is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                val pool = Executors.newSingleThreadExecutor()
                try {
                    var escapedDriver: Any? = null
                    runBlocking { executor.read { scope -> escapedDriver = scope.sqlDriver() } }
                    val message =
                        pool
                            .submit<String> {
                                runCatching {
                                    val driver = escapedDriver as app.cash.sqldelight.db.SqlDriver
                                    driver.executeQuery(null, "SELECT 1", { cursor ->
                                        app.cash.sqldelight.db.QueryResult
                                            .Value(Unit)
                                    }, 0, { })
                                }.exceptionOrNull()?.message.orEmpty()
                            }.get(10, TimeUnit.SECONDS)
                    assertTrue(
                        message.contains("thread") || message.contains("after its callback") || message.contains("driver"),
                        "跨线程驱动使用必须被拒绝，实际: $message",
                    )
                } finally {
                    pool.shutdownNow()
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R3 driver executeQuery rejected after scope returns`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    var escapedDriver: app.cash.sqldelight.db.SqlDriver? = null
                    executor.write { tx ->
                        escapedDriver = tx.sqlDriver()
                    }
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            escapedDriver?.executeQuery(null, "SELECT 1", { cursor ->
                                app.cash.sqldelight.db.QueryResult
                                    .Value(Unit)
                            }, 0, { })
                        }
                    assertTrue(
                        failure.message.orEmpty().contains("after its callback") ||
                            failure.message.orEmpty().contains("driver"),
                        "逃逸驱动的 executeQuery 必须被拒绝，实际: ${failure.message}",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R3 in-scope driver usage still works`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val count =
                        executor.read { scope ->
                            val driver = scope.sqlDriver()
                            driver
                                .executeQuery(null, "SELECT count(*) FROM dreaming_checkpoint", { cursor ->
                                    if (cursor.next().value) {
                                        app.cash.sqldelight.db.QueryResult
                                            .Value(cursor.getLong(0) ?: 0L)
                                    } else {
                                        app.cash.sqldelight.db.QueryResult
                                            .Value(0L)
                                    }
                                }, 0, { })
                                .value
                                .toInt()
                        }
                    assertEquals(1, count)
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R3 savepoint attempt through raw update is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            executor.write { tx -> tx.update("SAVEPOINT sp1") }
                        }
                    assertTrue(
                        failure.message!!.contains("savepoint", ignoreCase = true),
                        "SAVEPOINT 必须被拒绝，实际: ${failure.message}",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R3 raw update prepared savepoint is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            executor.write { tx -> tx.updatePrepared("SAVEPOINT sp2") { } }
                        }
                    assertTrue(
                        failure.message!!.contains("savepoint", ignoreCase = true),
                        "参数化 SAVEPOINT 必须被拒绝，实际: ${failure.message}",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- R5 事务失败状态转移

    private fun withRwConnectionFailure(
        path: Path,
        failingMethod: String,
        injectedMessage: String,
        work: (ContextDatabaseExecutor) -> Unit,
    ) {
        val delegate = DriverManager.getDriver("jdbc:sqlite:${path.toUri()}?mode=ro")
        val wrapper =
            object : Driver {
                override fun connect(
                    url: String,
                    info: Properties?,
                ): Connection? {
                    val real = delegate.connect(url, info ?: Properties())
                    if (!url.endsWith("?mode=rw")) return real
                    return Proxy.newProxyInstance(
                        this@F3TransactionFailureAndScopeBoundaryTest.javaClass.classLoader,
                        arrayOf(Connection::class.java),
                    ) { _, method, args ->
                        if (method.name == failingMethod) throw SQLException(injectedMessage)
                        method.invoke(real, *(args ?: emptyArray()))
                    } as Connection
                }

                override fun acceptsURL(url: String): Boolean = delegate.acceptsURL(url)

                override fun getPropertyInfo(
                    url: String,
                    info: Properties?,
                ): Array<DriverPropertyInfo> = delegate.getPropertyInfo(url, info ?: Properties())

                override fun getMajorVersion(): Int = delegate.getMajorVersion()

                override fun getMinorVersion(): Int = delegate.getMinorVersion()

                override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

                override fun getParentLogger(): Logger = Logger.getGlobal()
            }
        DriverManager.deregisterDriver(delegate)
        DriverManager.registerDriver(wrapper)
        try {
            ContextDatabaseFactory.open(path).use { executor -> work(executor) }
        } finally {
            DriverManager.deregisterDriver(wrapper)
            DriverManager.registerDriver(delegate)
        }
    }

    @Test
    fun `R5 rollback failure poisons connection and does not restore autoCommit`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            withRwConnectionFailure(path, "rollback", "injected rollback failure") { executor ->
                val failure =
                    assertFailsWith<IllegalStateException> {
                        runBlocking {
                            executor.write { tx ->
                                tx.updatePrepared(insertRaw.replace("?", "'R-MEM-1'")) { }
                                throw IllegalStateException("business failure")
                            }
                        }
                    }
                assertEquals("business failure", failure.message)
                // 回滚失败后连接中毒，后续写入必须被拒绝
                val reuseFailure =
                    assertFailsWith<ContextPersistenceException> {
                        runBlocking { executor.write { _ -> } }
                    }
                assertTrue(reuseFailure.message.orEmpty().contains("PERSISTENCE_UNAVAILABLE"))
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R5 commit failure is observable and does not hide as rollback`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            withRwConnectionFailure(path, "commit", "injected commit failure") { executor ->
                val failure =
                    assertFailsWith<ContextPersistenceException> {
                        runBlocking {
                            executor.write { tx ->
                                tx.updatePrepared(insertRaw.replace("?", "'R-MEM-2'")) { }
                            }
                        }
                    }
                assertTrue(failure.message.orEmpty().contains("PERSISTENCE_UNAVAILABLE"))
                // 连接中毒，后续操作被拒绝
                val reuseFailure =
                    assertFailsWith<ContextPersistenceException> {
                        runBlocking { executor.write { _ -> } }
                    }
                assertTrue(reuseFailure.message.orEmpty().contains("PERSISTENCE_UNAVAILABLE"))
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R5 read commit failure propagates and does not report success`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val delegate = DriverManager.getDriver("jdbc:sqlite:${path.toUri()}?mode=ro")
            val wrapper =
                object : Driver {
                    override fun connect(
                        url: String,
                        info: Properties?,
                    ): Connection? {
                        val real = delegate.connect(url, info ?: Properties())
                        if (!url.endsWith("?mode=rw")) return real
                        return Proxy.newProxyInstance(
                            this@F3TransactionFailureAndScopeBoundaryTest.javaClass.classLoader,
                            arrayOf(Connection::class.java),
                        ) { _, method, args ->
                            if (method.name == "commit") throw SQLException("injected read commit failure")
                            method.invoke(real, *(args ?: emptyArray()))
                        } as Connection
                    }

                    override fun acceptsURL(url: String): Boolean = delegate.acceptsURL(url)

                    override fun getPropertyInfo(
                        url: String,
                        info: Properties?,
                    ): Array<DriverPropertyInfo> = delegate.getPropertyInfo(url, info ?: Properties())

                    override fun getMajorVersion(): Int = delegate.getMajorVersion()

                    override fun getMinorVersion(): Int = delegate.getMinorVersion()

                    override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

                    override fun getParentLogger(): Logger = Logger.getGlobal()
                }
            DriverManager.deregisterDriver(delegate)
            DriverManager.registerDriver(wrapper)
            try {
                ContextDatabaseFactory.open(path).use { executor ->
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            runBlocking { executor.read { 10 } }
                        }
                    assertTrue(failure.message.orEmpty().contains("PERSISTENCE_UNAVAILABLE"))
                }
            } finally {
                DriverManager.deregisterDriver(wrapper)
                DriverManager.registerDriver(delegate)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R5 autoCommit restore failure does not replace original business exception`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val delegate = DriverManager.getDriver("jdbc:sqlite:${path.toUri()}?mode=ro")
            val wrapper =
                object : Driver {
                    override fun connect(
                        url: String,
                        info: Properties?,
                    ): Connection? {
                        val real = delegate.connect(url, info ?: Properties())
                        if (!url.endsWith("?mode=rw")) return real
                        return Proxy.newProxyInstance(
                            this@F3TransactionFailureAndScopeBoundaryTest.javaClass.classLoader,
                            arrayOf(Connection::class.java),
                        ) { _, method, args ->
                            if (method.name == "setAutoCommit" &&
                                args != null &&
                                args.size == 1 &&
                                args[0] == java.lang.Boolean.TRUE
                            ) {
                                throw SQLException("injected autoCommit restore failure")
                            }
                            method.invoke(real, *(args ?: emptyArray()))
                        } as Connection
                    }

                    override fun acceptsURL(url: String): Boolean = delegate.acceptsURL(url)

                    override fun getPropertyInfo(
                        url: String,
                        info: Properties?,
                    ): Array<DriverPropertyInfo> = delegate.getPropertyInfo(url, info ?: Properties())

                    override fun getMajorVersion(): Int = delegate.getMajorVersion()

                    override fun getMinorVersion(): Int = delegate.getMinorVersion()

                    override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

                    override fun getParentLogger(): Logger = Logger.getGlobal()
                }
            DriverManager.deregisterDriver(delegate)
            DriverManager.registerDriver(wrapper)
            try {
                ContextDatabaseFactory.open(path).use { executor ->
                    val failure =
                        assertFailsWith<IllegalStateException> {
                            runBlocking {
                                executor.write { tx ->
                                    tx.updatePrepared(insertRaw.replace("?", "'R-MEM-3'")) { }
                                    throw IllegalStateException("business boom")
                                }
                            }
                        }
                    assertEquals("business boom", failure.message)
                }
            } finally {
                DriverManager.deregisterDriver(wrapper)
                DriverManager.registerDriver(delegate)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `R5 cancelled write rolls back and keeps connection usable`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val cancelled =
                        async {
                            val callerJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                            executor.write { tx ->
                                tx.updatePrepared(insertRaw.replace("?", "'R-MEM-4'")) { }
                                callerJob.cancel(CancellationException("caller cancelled mid-transaction"))
                            }
                        }
                    assertFailsWith<CancellationException> { cancelled.await() }
                    // 取消不得抢关底层连接：后续请求仍能正常服务，且未提交数据不残留
                    assertEquals(
                        0L,
                        executor.queryRaw("SELECT count(*) FROM raw_memory") { rs ->
                            rs.next()
                            rs.getLong(1)
                        },
                        "提交前被取消的事务必须回滚",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- R6 异常链脱敏

    @Test
    fun `R6 SQLException chain does not leak sensitive message or cause`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                val sensitive = "SECRET_SESSION_BODY_MARKER"
                val failure =
                    assertFailsWith<ContextPersistenceException> {
                        runBlocking {
                            executor.queryRaw("SELECT $sensitive FROM sqlite_master WHERE type='table'") { rs -> rs }
                        }
                    }
                val fullChain =
                    failure.message.orEmpty() +
                        failure.cause?.message.orEmpty() +
                        failure.suppressed.joinToString { it.message.orEmpty() }
                val sw = java.io.StringWriter()
                failure.printStackTrace(java.io.PrintWriter(sw))
                val stackTrace = sw.toString()
                assertFalse(
                    fullChain.contains(sensitive) || stackTrace.contains(sensitive),
                    "异常链不得泄露敏感 SQL 原文：message=${failure.message}, cause=${failure.cause?.message}",
                )
                assertEquals(
                    ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                    failure.errorCode,
                )
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
