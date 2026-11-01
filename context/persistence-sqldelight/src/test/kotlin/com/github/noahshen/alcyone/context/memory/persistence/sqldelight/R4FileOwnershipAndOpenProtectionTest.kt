package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.sql.Statement
import java.util.Comparator
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CT04-R4 文件所有权与打开保护正式反例测试。
 *
 * 覆盖架构复核第 4 节 R4 与第 5 节 F1-T1 / F1-T2 / F1-T3 的决定性断言：
 * - 全部使用真实文件型 SQLite + JDBC Driver 包装制造受控交错，不依赖概率 sleep；
 * - 故障注入落在真实的语句执行点上（`executeQuery` 按 SQL 文本匹配），并记录阶段证据，
 *   不用用例名称掩盖分支缺失；
 * - 注入动作、被包装连接与临时目录的清理失败都必须可见，禁止静默吞掉造成伪通过。
 */
class R4FileOwnershipAndOpenProtectionTest {
    private interface ConnectHook {
        fun connect(
            url: String,
            props: Properties,
        ): Connection
    }

    /** 包装真实驱动，注入受控连接行为，用完在 finally 恢复。 */
    private fun <T> interceptDriver(
        delegate: java.sql.Driver,
        hook: ConnectHook,
        work: () -> T,
    ): T {
        val wrapper =
            object : java.sql.Driver {
                override fun connect(
                    url: String,
                    info: Properties?,
                ): Connection? =
                    try {
                        hook.connect(url, info ?: Properties())
                    } catch (e: SQLException) {
                        // 原样上抛：保持注入异常的 SQLSTATE 与 message，便于断言阶段与脱敏结果。
                        throw e
                    } catch (e: Exception) {
                        throw SQLException(e)
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
        return try {
            work()
        } finally {
            DriverManager.deregisterDriver(wrapper)
            DriverManager.registerDriver(delegate)
        }
    }

    private fun urlOf(
        path: Path,
        mode: String,
    ): String = "jdbc:sqlite:${path.toUri()}?mode=$mode"

    private fun userVersionOf(
        delegate: java.sql.Driver,
        path: Path,
    ): Long {
        val conn = delegate.connect(urlOf(path, "ro"), Properties())
        return try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        } finally {
            conn.close()
        }
    }

    private fun delegateFor(path: Path): java.sql.Driver = DriverManager.getDriver(urlOf(path, "ro"))

    private fun assertNoFile(path: Path) {
        assertFalse(Files.exists(path), "不得产生任何新文件: $path")
    }

    /** 完整异常链（message / toString / cause / suppressed / printStackTrace 全文）不得含敏感标记。 */
    private fun assertChainHasNoMarker(
        failure: Throwable,
        marker: String,
    ) {
        val writer = java.io.StringWriter()
        failure.printStackTrace(java.io.PrintWriter(writer))
        val fullChain = writer.toString()
        assertFalse(fullChain.contains(marker), "完整异常链不得泄露敏感标记，实际：$fullChain")
        assertFalse(failure.message.orEmpty().contains(marker), "异常 message 不得泄露敏感标记")
        assertFalse(failure.toString().contains(marker), "异常 toString 不得泄露敏感标记")
        var cursor: Throwable? = failure.cause
        while (cursor != null) {
            assertFalse(cursor.message.orEmpty().contains(marker), "cause 链不得泄露敏感标记")
            cursor.suppressed.forEach { suppressed ->
                assertFalse(suppressed.message.orEmpty().contains(marker), "suppressed 不得泄露敏感标记")
            }
            cursor = cursor.cause
        }
    }

    /**
     * 期望 `Factory.open` 拒绝；若意外成功必须立即关闭执行器，
     * 避免连接/线程泄漏，并让断言失败而不是伪通过。
     */
    private fun openExpectingFailure(
        path: Path,
        readSlotCount: Int = 1,
    ): ContextPersistenceException {
        var executor: ContextDatabaseExecutor? = null
        val failure =
            try {
                executor = ContextDatabaseFactory.open(path, readSlotCount)
                null
            } catch (e: ContextPersistenceException) {
                e
            } finally {
                executor?.close()
            }
        return failure ?: fail("Factory.open 必须拒绝打开，但实际成功")
    }

    /**
     * 捕获 `Factory.open` 的拒绝结果；意外成功时立即关闭执行器并返回 null，
     * 由调用方 `?: fail(...)` 报错：连接/线程不泄漏、不伪通过。
     */
    private fun openAndCapture(
        path: Path,
        readSlotCount: Int = 1,
    ): Throwable? {
        var executor: ContextDatabaseExecutor? = null
        return try {
            executor = ContextDatabaseFactory.open(path, readSlotCount)
            null
        } catch (t: Throwable) {
            t
        } finally {
            executor?.close()
        }
    }

    /** 临时目录封装：清理失败必须可见，不能静默吞掉。 */
    private class TempDir(
        val path: Path,
    ) {
        val cleanupFailures: MutableList<String> = mutableListOf()

        fun cleanup() {
            if (!Files.exists(path)) return
            val stream = Files.walk(path)
            try {
                stream.sorted(Comparator.reverseOrder()).forEach { target ->
                    try {
                        Files.deleteIfExists(target)
                    } catch (e: Exception) {
                        cleanupFailures += "删除失败 $target：${e::class.simpleName}: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                cleanupFailures += "遍历失败 $path：${e::class.simpleName}: ${e.message}"
            } finally {
                stream.close()
            }
        }
    }

    private fun assertCleanupSucceeded(tmp: TempDir) {
        assertTrue(tmp.cleanupFailures.isEmpty(), "临时目录清理必须成功，失败项：${tmp.cleanupFailures}")
    }

    /** Statement 代理：按 SQL 文本匹配注入行为。 */
    private class StatementProxy(
        private val delegate: Statement,
        private val onQuery: (String) -> Unit,
        private val onExecute: (String) -> Unit,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any?,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            val invokeArgs = if (args == null) arrayOf() else args
            if (invokeArgs.isNotEmpty() && invokeArgs[0] is String) {
                when (method.name) {
                    "executeQuery" -> onQuery(invokeArgs[0] as String)
                    "execute" -> onExecute(invokeArgs[0] as String)
                }
            }
            return try {
                method.invoke(delegate, *invokeArgs)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }

    /** Connection 代理：把 `createStatement()` 换成被代理的 Statement。 */
    private class ConnectionProxy(
        private val delegate: Connection,
        private val statementFactory: (Statement) -> Statement,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any?,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            val invokeArgs = if (args == null) arrayOf() else args
            if (method.name == "createStatement" && invokeArgs.isEmpty()) {
                return statementFactory(delegate.createStatement())
            }
            return try {
                method.invoke(delegate, *invokeArgs)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }

    private fun proxiedConnection(
        connection: Connection,
        onQuery: (String) -> Unit = {},
        onExecute: (String) -> Unit = {},
    ): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            ConnectionProxy(connection) { statement ->
                Proxy.newProxyInstance(
                    Statement::class.java.classLoader,
                    arrayOf(Statement::class.java),
                    StatementProxy(statement, onQuery, onExecute),
                ) as Statement
            },
        ) as Connection

    @Test
    fun `特殊路径初始化后打开 user_version 为 1`() {
        val tmp = TempDir(Files.createTempDirectory("r4-special-"))
        try {
            for (name in listOf("space name.db", "literal%41.db", "question?.db", "hash#.db", "mix %?#中文.db")) {
                val path = tmp.path.resolve(name)
                ContextDatabaseInitializer.initialize(path)
                assertEquals(1L, userVersionOf(delegateFor(path), path), "特殊路径 $name schema 版本必须为 1")
                ContextDatabaseFactory.open(path).use { executor ->
                    assertEquals("wal", executor.pragma(SlotKind.WRITE, 0, "journal_mode"))
                }
            }
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `竞争初始化胜者唯一且库有效`() {
        val tmp = TempDir(Files.createTempDirectory("r4-race-"))
        try {
            val path = tmp.path.resolve("race.db")
            val entered = AtomicBoolean(false)
            val wins = AtomicInteger(0)

            val delegate = delegateFor(path)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        if (url.contains("race.db") && !entered.get()) {
                            entered.set(true)
                            try {
                                ContextDatabaseInitializer.initialize(path)
                                wins.incrementAndGet()
                            } catch (_: ContextPersistenceException) {
                                // 他方已胜，本方放弃
                            }
                        }
                        return delegate.connect(url, props)
                    }
                },
            ) {
                try {
                    ContextDatabaseInitializer.initialize(path)
                    wins.incrementAndGet()
                } catch (_: ContextPersistenceException) {
                    // 预期：只有一方能成功 CREATE_NEW
                }
            }

            assertTrue(entered.get(), "钩子必须被触达")
            assertEquals(1, wins.get(), "恰好一个初始化必须成功")
            assertTrue(Files.exists(path), "胜者文件必须保留")
            assertEquals(1L, userVersionOf(delegate, path), "胜者库 schema 必须有效")
            ContextDatabaseFactory.open(path).use { }
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `初始化失败时不删除他方替换文件`() {
        val tmp = TempDir(Files.createTempDirectory("r4-preserve-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)

            val target = tmp.path.resolve("target.db")
            val delegate = delegateFor(source)

            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        if (url.contains("target.db")) {
                            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                        return delegate.connect(url, props)
                    }
                },
            ) {
                assertFailsWith<ContextPersistenceException> {
                    ContextDatabaseInitializer.initialize(target)
                }
            }

            assertTrue(Files.exists(target), "被替换的文件不得被本次失败清理")
            assertEquals(1L, userVersionOf(delegate, target), "他方替换后的库 schema 必须有效")
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `认领后文件被删除不重建`() {
        val tmp = TempDir(Files.createTempDirectory("r4-delete-claim-"))
        try {
            val target = tmp.path.resolve("deleted.db")
            val delegate = DriverManager.getDriver("jdbc:sqlite:${target.toUri()}")

            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        if (url.contains("deleted.db")) {
                            Files.deleteIfExists(target)
                        }
                        return delegate.connect(url, props)
                    }
                },
            ) {
                assertFailsWith<ContextPersistenceException> {
                    ContextDatabaseInitializer.initialize(target)
                }
            }

            assertFalse(Files.exists(target), "认领后丢失文件不得被重建")
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `Factory验证后文件被删除时拒绝且不重建`() {
        val tmp = TempDir(Files.createTempDirectory("r4-open-delete-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)

            val target = tmp.path.resolve("deleted.db")
            Files.copy(source, target)
            val fired = AtomicBoolean(false)
            val actionFailure = AtomicReference<Throwable?>(null)

            val delegate = delegateFor(source)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        val connection = delegate.connect(url, props)
                        if (!url.endsWith("?mode=ro")) return connection
                        return Proxy.newProxyInstance(
                            Connection::class.java.classLoader,
                            arrayOf(Connection::class.java),
                            CloseDeleteHandler(fired, target, actionFailure, connection),
                        ) as Connection
                    }
                },
            ) {
                val failure = openExpectingFailure(target, 1)
                // 真实生产语义：只读预检通过后文件消失 → mode=rw 打开写 Slot 时被拒（绝不自动建库）。
                assertTrue(
                    failure.message.orEmpty().contains("write slot"),
                    "拒绝必须发生在写 Slot 打开阶段，实际：${failure.message}",
                )
                assertEquals(
                    ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                    failure.errorCode,
                    "拒绝错误码必须是 PERSISTENCE_UNAVAILABLE，实际：${failure.errorCode}",
                )
            }

            assertTrue(fired.get(), "只读验证连接必须被关闭")
            assertNull(actionFailure.get(), "注入删除动作不得失败：${actionFailure.get()}")
            assertFalse(Files.exists(target), "拒绝打开不得重建缺失文件")
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `每个 Slot 物理连接使用 mode=rw 且不自动建库`() {
        val tmp = TempDir(Files.createTempDirectory("r4-rw-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)
            var rwCount = 0

            val delegate = delegateFor(source)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        if (!url.endsWith("?mode=ro")) {
                            assertTrue(url.endsWith("?mode=rw"), "非只读 URL 必须使用 mode=rw，实际：$url")
                            rwCount++
                        }
                        return delegate.connect(url, props)
                    }
                },
            ) {
                ContextDatabaseFactory.open(source, 2).use { }
            }

            // 写 Slot 1 + 读 Slot 2 = 3 条读写连接
            assertEquals(3, rwCount, "必须打开 3 条 mode=rw 连接，实际：$rwCount")
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `实际写连接 PRAGMA 前重验 user_version，错版被拒`() {
        val tmp = TempDir(Files.createTempDirectory("r4-write-version-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)

            val target = tmp.path.resolve("replace.db")
            Files.copy(source, target)
            val fired = AtomicBoolean(false)
            val actionFailure = AtomicReference<Throwable?>(null)

            val delegate = delegateFor(source)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        val connection = delegate.connect(url, props)
                        if (!url.endsWith("?mode=ro")) return connection
                        return Proxy.newProxyInstance(
                            Connection::class.java.classLoader,
                            arrayOf(Connection::class.java),
                            CloseSetVersionHandler(fired, target, delegate, urlOf(target, "rw"), 999L, actionFailure, connection),
                        ) as Connection
                    }
                },
            ) {
                val failure = openExpectingFailure(target, 1)
                assertTrue(
                    failure.message.orEmpty().contains("999") || failure.message.orEmpty().contains("version"),
                    "错版拒绝原因必须指向版本，实际：${failure.message}",
                )
            }

            assertTrue(fired.get(), "只读验证连接必须被关闭")
            assertNull(actionFailure.get(), "注入改版本动作不得失败：${actionFailure.get()}")
            assertEquals(999L, userVersionOf(delegate, target), "拒绝后文件内容必须保留（被替换为 999）")
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    /**
     * F1-T2：读 Slot 结构校验阶段定点注入。
     *
     * 判据收窄：不能在写 Slot 阶段就让库缺索引（那只能证明写阶段失败），
     * 必须在**第 2 条 mode=rw 连接**（读 Slot 连接）真正执行结构查询时才删索引，
     * 从而证明「读 Slot 在应用配置 PRAGMA 之前先重验结构」。
     */
    @Test
    fun `实际读连接结构校验阶段命中缺索引并被拒`() {
        val tmp = TempDir(Files.createTempDirectory("r4-read-structure-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)

            val target = tmp.path.resolve("read-structure.db")
            Files.copy(source, target)

            val rwConnections = AtomicInteger(0)
            val injected = AtomicBoolean(false)
            val actionFailure = AtomicReference<Throwable?>(null)
            // 写阶段（write slot）的 sqlite_master 查询成功次数
            val writeStructureSuccesses = AtomicInteger(0)
            // 读阶段（read slot）的 sqlite_master 查询命中次数
            val readStructureHits = AtomicInteger(0)
            // 读 Slot 的配置 PRAGMA 执行次数：拒绝若发生在 applyPragmas 之前，这里必须为 0
            val readPragmaExecutions = AtomicInteger(0)
            val missingIndex = "idx_source_raw"

            val delegate = delegateFor(source)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        if (url.endsWith("?mode=ro")) return delegate.connect(url, props)
                        val slotIndex = rwConnections.incrementAndGet()
                        val connection = delegate.connect(url, props)
                        if (slotIndex != 2) {
                            // 写 Slot：只统计结构查询成功次数，不做注入
                            return proxiedConnection(connection, onQuery = { sql ->
                                if (sql.contains("sqlite_master")) writeStructureSuccesses.incrementAndGet()
                            })
                        }
                        // 读 Slot：结构查询命中时，先用独立 rw 短连接真实删除索引，再放行原查询
                        return proxiedConnection(
                            connection,
                            onQuery = { sql ->
                                if (sql.contains("sqlite_master")) {
                                    readStructureHits.incrementAndGet()
                                    if (injected.compareAndSet(false, true)) {
                                        val alt = delegate.connect(urlOf(target, "rw"), Properties())
                                        try {
                                            alt.createStatement().use { statement ->
                                                statement.execute("DROP INDEX $missingIndex")
                                            }
                                        } catch (e: Exception) {
                                            actionFailure.set(e)
                                        } finally {
                                            // 短连接关闭失败也必须可见，记入 actionFailure 供用例断言。
                                            try {
                                                alt.close()
                                            } catch (e: Exception) {
                                                actionFailure.set(e)
                                            }
                                        }
                                    }
                                }
                            },
                            onExecute = { sql ->
                                if (sql.contains("query_only")) readPragmaExecutions.incrementAndGet()
                            },
                        )
                    }
                },
            ) {
                val failure = openExpectingFailure(target, 1)
                assertTrue(
                    failure.message.orEmpty().contains(missingIndex),
                    "读阶段拒绝必须点名缺失索引 $missingIndex，实际：${failure.message}",
                )
                assertTrue(
                    failure.message.orEmpty().contains("read"),
                    "拒绝必须发生在读 Slot 上，实际：${failure.message}",
                )
            }

            assertEquals(2, rwConnections.get(), "必须建立写 Slot + 读 Slot 两条 mode=rw 连接")
            assertTrue(writeStructureSuccesses.get() >= 1, "写 Slot 的结构校验必须已成功完成")
            assertEquals(1, readStructureHits.get(), "读 Slot 的结构查询必须命中注入点")
            assertEquals(0, readPragmaExecutions.get(), "拒绝必须发生在读 Slot 的配置 PRAGMA 生效之前")
            assertTrue(injected.get(), "索引删除注入必须发生")
            assertNull(actionFailure.get(), "注入删除索引动作不得失败：${actionFailure.get()}")
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    /**
     * F1-T1 场景一：只读预检的**建立连接阶段**失败。
     *
     * 注入点在 `connect(url, props)`：任何查询都还没发生，异常必须被安全映射。
     */
    @Test
    fun `Factory 建立只读预检连接失败时异常链不携带敏感标记`() {
        val tmp = TempDir(Files.createTempDirectory("r4-factory-connect-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)
            val marker = "SECRET_F1_CONNECT_BODY"
            val captured = AtomicReference<Throwable?>(null)
            val roConnectAttempts = AtomicInteger(0)

            val delegate = delegateFor(source)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        if (url.endsWith("?mode=ro")) {
                            roConnectAttempts.incrementAndGet()
                            val error = SQLException(marker, "HY000")
                            error.addSuppressed(RuntimeException(marker))
                            throw error
                        }
                        return delegate.connect(url, props)
                    }
                },
            ) {
                captured.set(openAndCapture(source, 1))
            }

            val failure = captured.get() ?: fail("建立连接阶段注入必须导致失败")
            assertTrue(failure is ContextPersistenceException, "必须是 ContextPersistenceException，实际：${failure::class.simpleName}")
            assertEquals(1, roConnectAttempts.get(), "只读预检连接必须尝试建立且仅一次")
            assertChainHasNoMarker(failure, marker)
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    /**
     * F1-T1 场景二：只读预检的 **PRAGMA user_version 查询**阶段失败。
     *
     * 注入点在 `Statement.executeQuery` 且 SQL 文本命中 `PRAGMA user_version`：
     * 真实只读连接必须已成功建立，且注入精确命中 version 查询。
     */
    @Test
    fun `Factory 预检 user_version 查询失败时异常链不携带敏感标记`() {
        val tmp = TempDir(Files.createTempDirectory("r4-factory-version-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)
            val marker = "SECRET_F1_VERSION_BODY"
            val captured = AtomicReference<Throwable?>(null)
            val roConnected = AtomicBoolean(false)
            val firedQuery = AtomicBoolean(false)
            val otherQueries = AtomicInteger(0)

            val delegate = delegateFor(source)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        val connection = delegate.connect(url, props)
                        if (!url.endsWith("?mode=ro")) return connection
                        roConnected.set(true)
                        return proxiedConnection(
                            connection,
                            onQuery = { sql ->
                                if (sql.contains("PRAGMA user_version")) {
                                    firedQuery.set(true)
                                    val error = SQLException(marker, "HY000")
                                    error.addSuppressed(RuntimeException(marker))
                                    throw error
                                }
                                otherQueries.incrementAndGet()
                            },
                        )
                    }
                },
            ) {
                captured.set(openAndCapture(source, 1))
            }

            val failure = captured.get() ?: fail("user_version 查询阶段注入必须导致失败")
            assertTrue(failure is ContextPersistenceException, "必须是 ContextPersistenceException，实际：${failure::class.simpleName}")
            assertTrue(roConnected.get(), "真实只读预检连接必须已成功建立")
            assertTrue(firedQuery.get(), "注入必须精确命中 PRAGMA user_version 查询")
            assertEquals(0, otherQueries.get(), "版本校验失败后不得继续执行其它查询")
            assertChainHasNoMarker(failure, marker)
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    /**
     * F1-T1 场景三：只读预检的 **sqlite_master 结构查询**阶段失败。
     *
     * 注入点在 `Statement.executeQuery` 且 SQL 文本命中 `sqlite_master`；
     * 必须证明前序的 user_version 查询已成功完成。
     */
    @Test
    fun `Factory 预检 sqlite_master 查询失败时异常链不携带敏感标记`() {
        val tmp = TempDir(Files.createTempDirectory("r4-factory-structure-"))
        try {
            val source = tmp.path.resolve("source.db")
            ContextDatabaseInitializer.initialize(source)
            val marker = "SECRET_F1_STRUCTURE_BODY"
            val captured = AtomicReference<Throwable?>(null)
            val versionSuccesses = AtomicInteger(0)
            val firedQuery = AtomicBoolean(false)

            val delegate = delegateFor(source)
            interceptDriver(
                delegate,
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        val connection = delegate.connect(url, props)
                        if (!url.endsWith("?mode=ro")) return connection
                        return proxiedConnection(
                            connection,
                            onQuery = { sql ->
                                if (sql.contains("sqlite_master")) {
                                    firedQuery.set(true)
                                    val error = SQLException(marker, "HY000")
                                    error.addSuppressed(RuntimeException(marker))
                                    throw error
                                }
                                if (sql.contains("PRAGMA user_version")) versionSuccesses.incrementAndGet()
                            },
                        )
                    }
                },
            ) {
                captured.set(openAndCapture(source, 1))
            }

            val failure = captured.get() ?: fail("sqlite_master 查询阶段注入必须导致失败")
            assertTrue(failure is ContextPersistenceException, "必须是 ContextPersistenceException，实际：${failure::class.simpleName}")
            assertTrue(versionSuccesses.get() >= 1, "注入前 user_version 查询必须已成功完成")
            assertTrue(firedQuery.get(), "注入必须精确命中 sqlite_master 查询")
            assertChainHasNoMarker(failure, marker)
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `缺库打开不创建任何文件`() {
        val tmp = TempDir(Files.createTempDirectory("r4-missing-"))
        try {
            val path = tmp.path.resolve("missing.db")
            assertNoFile(path)
            // open 必须拒绝；意外成功时由 openExpectingFailure 立即 close 并 fail。
            openExpectingFailure(path)
            assertNoFile(path)
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    @Test
    fun `错版文件字节不变`() {
        val tmp = TempDir(Files.createTempDirectory("r4-bytes-"))
        try {
            val path = TestDatabases.initializedDatabase(tmp.path)
            TestDatabases.setUserVersion(path, 0)
            val before = TestDatabases.sha256(path)

            // open 必须拒绝（ContextPersistenceException 由 openExpectingFailure 保证）；
            // 意外成功时立即 close 并 fail。
            openExpectingFailure(path)
            assertEquals(before, TestDatabases.sha256(path), "版本不符时文件字节必须不变")
        } finally {
            tmp.cleanup()
            assertCleanupSucceeded(tmp)
        }
    }

    /**
     * 关闭连接时删除目标文件的 InvocationHandler（F1-T3）。
     *
     * 首次 `close` 拦截：执行注入动作后**继续委托真实 `connection.close()`**，
     * 不再直接 return；注入动作失败记录到 [actionFailure]，由用例断言，不静默吞掉。
     */
    private class CloseDeleteHandler(
        private val fired: AtomicBoolean,
        private val target: Path,
        private val actionFailure: AtomicReference<Throwable?>,
        private val connection: Connection,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any?,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            if (method.name == "close" && fired.compareAndSet(false, true)) {
                try {
                    Files.deleteIfExists(target)
                } catch (e: Exception) {
                    actionFailure.set(e)
                }
            }
            val invokeArgs = if (args == null) arrayOf() else args
            return try {
                method.invoke(connection, *invokeArgs)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }

    /**
     * 关闭连接时把版本设为指定值的 InvocationHandler（F1-T3）。
     *
     * 注入动作经独立 rw 短连接执行并在 finally 真实关闭；随后继续委托真实 `connection.close()`。
     */
    private class CloseSetVersionHandler(
        private val fired: AtomicBoolean,
        private val target: Path,
        private val delegate: java.sql.Driver,
        private val rwUrl: String,
        private val version: Long,
        private val actionFailure: AtomicReference<Throwable?>,
        private val connection: Connection,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any?,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            if (method.name == "close" && fired.compareAndSet(false, true)) {
                var alt: Connection? = null
                try {
                    alt = delegate.connect(rwUrl, Properties())
                    alt.createStatement().use { stmt -> stmt.execute("PRAGMA user_version = $version") }
                } catch (e: Exception) {
                    actionFailure.set(e)
                } finally {
                    // 短连接关闭失败也必须可见，记入 actionFailure 供用例断言；
                    // 无论成败，下面仍继续委托关闭被包装的原 connection（原连接必关不变）。
                    try {
                        alt?.close()
                    } catch (e: Exception) {
                        actionFailure.set(e)
                    }
                }
            }
            val invokeArgs = if (args == null) arrayOf() else args
            return try {
                method.invoke(connection, *invokeArgs)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }
}
