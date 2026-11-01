package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CT04-F2 资源所有权与关闭协议正式反例测试（方案 §4 的 F2-A01～A09）。
 *
 * 手段（Architect §R1 / §R2 要求）：
 * - 用真实 `java.sql.Driver` 包装 + 记录**每一条真实连接**，直接断言 `Connection.isClosed`，
 *   不用「再次打开成功」或「无残留线程」这类间接证据；
 * - 用 `CountDownLatch` 受控屏障制造确定性交错，不使用 sleep 概率；
 * - 单资源 close 失败、等待者取消、关闭发起者被中断、queryRaw mapper 回调等分支都要求
 *   失败可见、真实清理仍完成；
 * - **构造、故障关闭、所有真实连接断言均在兜底清理之前**（断言全部位于 `interceptDriver`
 *   工作块内），兜底清理只做测试自身的最后一道防线，不参与被测结论；
 * - 所有屏障与线程在 `finally` 释放，失败分支也不挂住。
 */
class F2ResourceOwnershipAndCloseProtocolTest {
    private interface ConnectHook {
        fun connect(
            url: String,
            props: Properties,
        ): Connection
    }

    /** 包装真实驱动，注入受控连接行为；把每条真实连接记入 [recorded]。用完 finally 恢复。 */
    private fun <T> interceptDriver(
        delegate: java.sql.Driver,
        recorded: MutableList<Connection>,
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
            // 测试自身兜底释放：确保任何未关闭的捕获连接不泄漏（不参与被测断言）。
            recorded.forEach { runCatching { it.close() } }
        }
    }

    private fun delegateFor(path: Path): java.sql.Driver = DriverManager.getDriver("jdbc:sqlite:${path.toUri()}?mode=ro")

    /** 记录真实连接的包装 hook（原样返回 delegate 的连接）。 */
    private fun recordingHook(
        delegate: java.sql.Driver,
        recorded: MutableList<Connection>,
    ): ConnectHook =
        object : ConnectHook {
            override fun connect(
                url: String,
                props: Properties,
            ): Connection = delegate.connect(url, props).also { recorded += it }
        }

    /** 在包装块内断言全部捕获连接已关闭（不能放到包装块之外：兜底清理会抢先关闭它们）。 */
    private fun assertAllRecordedClosed(recorded: List<Connection>) {
        assertTrue(recorded.isNotEmpty(), "必须至少捕获到一条真实连接")
        assertTrue(recorded.all { it.isClosed }, "全部已取得连接都必须关闭，实际: ${recorded.map { it.isClosed }}")
    }

    /** 读取内部 `closed` 标志，用于确定性地等到关闭发起（与 Architect 探针同技术）。 */
    private fun closedFlagOf(executor: ContextDatabaseExecutor): AtomicBoolean =
        ContextDatabaseExecutor::class.java
            .getDeclaredField("closed")
            .apply { isAccessible = true }
            .get(executor) as AtomicBoolean

    /** 受控自旋等待条件成立；超时返回 false。不使用 sleep 概率。 */
    private fun spinUntil(
        timeoutSeconds: Long = 10,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.onSpinWait()
        }
        return condition()
    }

    // ---------------------------------------------------------------- A01

    @Test
    fun `A01 read slot open failure closes the already-opened real write connection`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            interceptDriver(delegateFor(path), recorded, recordingHook(delegateFor(path), recorded)) {
                val failure =
                    assertFailsWith<IllegalStateException> {
                        ContextDatabaseExecutor(path, 1) { throw IllegalStateException("injected read open failure") }
                    }
                assertEquals("injected read open failure", failure.message)
                // 在包装块内直接断言真实写连接已关闭（不能等到块外：兜底清理会抢先关闭）。
                assertAllRecordedClosed(recorded)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A02

    @Test
    fun `A02 version validation failure after connection acquired closes that real connection`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            val connectCount = AtomicInteger(0)
            // connect #1 写 Slot（真实连接）；connect #2 读 Slot：连接取得成功，但版本/结构校验的
            // createStatement 阶段失败（区别于 A02b 的 applyPragmas 执行阶段）。
            val hook =
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        val real = delegate.connect(url, props)
                        val index = connectCount.incrementAndGet()
                        if (index == 2) {
                            val proxy =
                                Proxy.newProxyInstance(
                                    this.javaClass.classLoader,
                                    arrayOf(Connection::class.java),
                                ) { _, method, args ->
                                    if (method.name == "createStatement") {
                                        throw SQLException("injected createStatement failure on read slot")
                                    }
                                    method.invoke(real, *(args ?: emptyArray()))
                                } as Connection
                            recorded += proxy
                            return proxy
                        }
                        recorded += real
                        return real
                    }
                }
            interceptDriver(delegate, recorded, hook) {
                assertFailsWith<ContextPersistenceException> {
                    ContextDatabaseExecutor(path, 1)
                }
                assertTrue(recorded.size >= 2, "写与读 Slot 的真实连接都应被捕获，实际: ${recorded.size}")
                assertAllRecordedClosed(recorded)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `A02b PRAGMA application failure after connection acquired closes that real connection`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            // 每条连接：createStatement 成功（版本/结构校验走 executeQuery 正常通过），
            // 但 applyPragmas 的 `execute("PRAGMA name=value")` 阶段抛错，与 A02 的版本校验阶段区分。
            val hook =
                object : ConnectHook {
                    override fun connect(
                        url: String,
                        props: Properties,
                    ): Connection {
                        val real = delegate.connect(url, props)
                        val proxy =
                            Proxy.newProxyInstance(
                                this.javaClass.classLoader,
                                arrayOf(Connection::class.java),
                            ) { _, method, args ->
                                if (method.name == "createStatement") {
                                    val statement = method.invoke(real, *(args ?: emptyArray())) as Statement
                                    Proxy.newProxyInstance(
                                        this.javaClass.classLoader,
                                        arrayOf(Statement::class.java),
                                    ) { _, stmtMethod, stmtArgs ->
                                        if (stmtMethod.name == "execute" && stmtArgs != null && stmtArgs.isNotEmpty()) {
                                            val sql = stmtArgs[0] as? String
                                            if (sql != null && sql.startsWith("PRAGMA ") && sql.contains("=")) {
                                                throw SQLException("injected applyPragmas failure")
                                            }
                                        }
                                        stmtMethod.invoke(statement, *(stmtArgs ?: emptyArray()))
                                    } as Statement
                                } else {
                                    method.invoke(real, *(args ?: emptyArray()))
                                }
                            } as Connection
                        recorded += proxy
                        return proxy
                    }
                }
            interceptDriver(delegate, recorded, hook) {
                val failure =
                    assertFailsWith<ContextPersistenceException> {
                        ContextDatabaseExecutor(path, 1)
                    }
                assertTrue(
                    failure.message.orEmpty().contains("applying PRAGMA"),
                    "失败必须发生在 applyPragmas 阶段，实际: ${failure.message}",
                )
                assertTrue(recorded.size >= 2, "写与读 Slot 的真实连接都应被捕获，实际: ${recorded.size}")
                assertAllRecordedClosed(recorded)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A03

    @Test
    fun `A03 one slot close failure still attempts every other slot and stays visible to all waiters`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            val closeAttempts = CopyOnWriteArrayList<Int>()
            interceptDriver(delegate, recorded, recordingHook(delegate, recorded)) {
                // 构造、故障关闭与全部真实连接断言都在此工作块内完成，兜底清理不参与结论。
                val executor =
                    ContextDatabaseExecutor(path, 2) { index ->
                        object : ContextSlot(SlotKind.READ, index, path, READ_PRAGMAS) {
                            override fun close() {
                                closeAttempts += index
                                super.close()
                                throw IllegalStateException("injected close failure on read slot $index")
                            }
                        }
                    }
                val failure = assertFailsWith<ContextPersistenceException> { executor.close() }
                // 失败必须可见（不伪报释放成功）。P1：同一轮次最多两次尝试，只重试失败 Slot，顺序逆序 1 → 0。
                assertTrue(failure.message.orEmpty().contains("releasing"), "清理失败必须对外可见，实际: ${failure.message}")
                assertEquals(
                    listOf(1, 0, 1, 0),
                    closeAttempts.toList(),
                    "两个读 Slot 都失败时，同一轮次内各重试一次（逆序 1 → 0，再 1 → 0），实际: $closeAttempts",
                )
                val primary = failure.cause as? IllegalStateException
                assertTrue(primary != null, "发起者的清理失败必须把首个读 Slot 失败作为 cause，实际: $failure")
                // 首次尝试：读 Slot 1 失败（primary）；读 Slot 0 首次失败 → suppressed(1)。
                // 第二次尝试：两 Slot 再次失败 → 各自 suppressed(1)，共 2 条（去重后不与 primary 自身重复）。
                assertEquals(
                    2,
                    primary!!.suppressed.size,
                    "其余尝试失败都以 suppressed 附加（另一 Slot 首次 + 第二轮两次），实际: ${primary.suppressed.size}",
                )
                assertTrue(
                    primary.suppressed.none { it === primary },
                    "不得出现同一 Throwable 自我 suppressed，实际: ${primary.suppressed}",
                )
                // 写连接与两个读连接（共 3 条真实连接）都必须已释放。
                assertEquals(3, recorded.size, "应捕获写 + 2 读共 3 条真实连接，实际: ${recorded.size}")
                assertAllRecordedClosed(recorded)

                // 多个关闭等待者必须看到同一失败结果（同步与异步各一）。
                val asyncWaiter =
                    assertFailsWith<ContextPersistenceException> {
                        runBlocking { executor.closeAsync() }
                    }
                assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, asyncWaiter.errorCode)
                val syncWaiter = assertFailsWith<ContextPersistenceException> { executor.close() }
                assertSame(failure, syncWaiter, "同步等待者必须观察到发起者发布的同一失败实例")
                // 等待者不重复释放：真实连接保持已关闭终态。
                assertAllRecordedClosed(recorded)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A04

    @Test
    fun `A04 concurrent sync and async close wait for the same result until in-flight write exits`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            val pool: ExecutorService = Executors.newFixedThreadPool(3)
            try {
                interceptDriver(delegate, recorded, recordingHook(delegate, recorded)) {
                    ContextDatabaseFactory.open(path, 1).use { executor ->
                        val closedFlag = closedFlagOf(executor)
                        val enteredWrite = CountDownLatch(1)
                        val releaseWrite = CountDownLatch(1)
                        val asyncReturned = CountDownLatch(1)
                        val syncStarted = CountDownLatch(1)
                        val syncReturned = CountDownLatch(1)
                        val outcomes = CopyOnWriteArrayList<Throwable>()

                        /** 在途写任务；启动即受保护，失败分支也必须放行它。 */
                        @Suppress("UNCHECKED_CAST")
                        var writer: Future<Unit?>? = null

                        /** 异步关闭发起者；启动即受保护。 */
                        @Suppress("UNCHECKED_CAST")
                        var asyncCloser: Future<Unit?>? = null

                        /** 同步关闭等待者；启动即受保护。 */
                        val syncThread = AtomicReference<Thread?>()

                        @Suppress("UNCHECKED_CAST")
                        var syncCloser: Future<Unit?>? = null

                        try {
                            writer =
                                pool.submit {
                                    runCatching {
                                        runBlocking {
                                            executor.write { _ ->
                                                enteredWrite.countDown()
                                                assertTrue(releaseWrite.await(30, TimeUnit.SECONDS), "释放屏障超时")
                                            }
                                        }
                                    }.exceptionOrNull()?.let { outcomes += it }
                                } as Future<Unit?>
                            assertTrue(enteredWrite.await(30, TimeUnit.SECONDS), "写事务必须已进入回调")

                            // 先启动异步关闭作为发起者；确定性等到它已封闭新请求（closed=true）再启动同步关闭，
                            // 从而确保同步关闭必然走「非发起者等待同一结果」这条路径。
                            asyncCloser =
                                pool.submit {
                                    runCatching { runBlocking { executor.closeAsync() } }.exceptionOrNull()?.let { outcomes += it }
                                    asyncReturned.countDown()
                                } as Future<Unit?>
                            assertTrue(spinUntil(10) { closedFlag.get() }, "异步关闭必须已发起")

                            // 同步等待者：记录自身线程，受控确认它确实进入等待分支（WAITING）后才做断言。
                            syncCloser =
                                pool.submit {
                                    syncThread.set(Thread.currentThread())
                                    syncStarted.countDown()
                                    runCatching { executor.close() }.exceptionOrNull()?.let { outcomes += it }
                                    syncReturned.countDown()
                                } as Future<Unit?>

                            assertTrue(syncStarted.await(30, TimeUnit.SECONDS), "同步关闭必须已进入调用")
                            // 受控确认同步 close 已进入等待分支（WAITING），或它已返回；仅靠 countDown 计数不可靠，
                            // 因为 syncReturned 起始计数为 1 会导致 spinUntil 立即退出。
                            val syncWaiting =
                                spinUntil(10) {
                                    syncThread.get()?.state == Thread.State.WAITING || syncReturned.count == 0L
                                }
                            assertTrue(
                                syncWaiting && syncThread.get()?.state == Thread.State.WAITING,
                                "同步 close 必须受控确认已进入等待分支，实际状态: ${syncThread.get()?.state}",
                            )
                            // 在途写未退出前，同步与异步关闭都不得返回（等待同一真实完成结果）。
                            assertEquals(1L, asyncReturned.count, "在途写未退出前 closeAsync 不得返回")
                            assertEquals(1L, syncReturned.count, "在途写未退出前同步 close 必须等待同一结果，不得提前返回")

                            releaseWrite.countDown()
                            writer?.let { requireNotNull(it).get(30, TimeUnit.SECONDS) }
                            asyncCloser?.let { requireNotNull(it).get(30, TimeUnit.SECONDS) }
                            syncCloser?.let { requireNotNull(it).get(30, TimeUnit.SECONDS) }
                            assertTrue(outcomes.isEmpty(), "并发关闭不应抛错: $outcomes")
                            assertAllRecordedClosed(recorded)
                        } finally {
                            // 失败分支也不得挂住：先放行写回调（让在途写退出），再收齐已启动任务，
                            // 确保 executor.use -> close() 在断言失败后不会在写回调上等到 30 秒超时。
                            releaseWrite.countDown()
                            runCatching { writer?.get(30, TimeUnit.SECONDS) }
                            runCatching { asyncCloser?.get(30, TimeUnit.SECONDS) }
                            runCatching { syncCloser?.get(30, TimeUnit.SECONDS) }
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
                pool.awaitTermination(5, TimeUnit.SECONDS)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A05

    @Test
    fun `A05 requests after close are rejected with CLOSED and never admitted`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val executor = ContextDatabaseFactory.open(path, 1)
            runBlocking { executor.closeAsync() }

            // 重复请求（包含并发重复）始终得到 CLOSED，不新增在途请求。
            repeat(5) {
                val read = assertFailsWith<ContextLifecycleException> { runBlocking { executor.read { } } }
                assertEquals(ContextLifecycleErrorCode.CLOSED, read.errorCode)
                val write = assertFailsWith<ContextLifecycleException> { runBlocking { executor.write { } } }
                assertEquals(ContextLifecycleErrorCode.CLOSED, write.errorCode)
                val raw = assertFailsWith<ContextLifecycleException> { runBlocking { executor.queryRaw("SELECT 1") { rs -> rs } } }
                assertEquals(ContextLifecycleErrorCode.CLOSED, raw.errorCode)
            }
            // 关闭后 close 幂等、不再有资源可释放，也不应抛错。
            executor.close()
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A06

    @Test
    fun `A06 close inside a callback is rejected by name without deadlock`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            interceptDriver(delegate, recorded, recordingHook(delegate, recorded)) {
                val executor = ContextDatabaseFactory.open(path, 1)
                // 回调在 Slot 固定线程上同步执行，阻塞式 close 无法被协程超时打断；
                // 故用外部守护线程 + join 超时做真正的死锁护栏。
                val outcome = AtomicReference<Throwable?>()
                val syncCode = AtomicReference<ContextLifecycleErrorCode?>()
                val asyncCode = AtomicReference<ContextLifecycleErrorCode?>()
                val runner =
                    Thread {
                        try {
                            runBlocking {
                                executor.write { _ ->
                                    // 回调内（Slot 固定线程上）关闭自己：必须具名拒绝而不是等待自身退出。
                                    val sync = assertFailsWith<ContextLifecycleException> { executor.close() }
                                    syncCode.set(sync.errorCode)
                                    if (!sync.message.orEmpty().contains("inside a read/write callback")) {
                                        error("回调内 close 必须是具名拒绝，实际: ${sync.message}")
                                    }
                                    val async =
                                        assertFailsWith<ContextLifecycleException> {
                                            runBlocking { executor.closeAsync() }
                                        }
                                    asyncCode.set(async.errorCode)
                                }
                            }
                        } catch (failure: Throwable) {
                            outcome.set(failure)
                        }
                    }.apply {
                        name = "f2-a06-callback-close"
                        isDaemon = true
                    }
                runner.start()
                runner.join(TimeUnit.SECONDS.toMillis(15))
                val deadlocked = runner.isAlive

                if (deadlocked) {
                    // 死锁：直接失败，且不在测试线程上再调 close（那也会挂住），让用例尽快 FAILED。
                    fail("回调内 close 不得死锁：守护线程在 15s 内未返回")
                }
                outcome.get()?.let { throw AssertionError("回调内 close 不应抛出意外异常", it) }
                assertEquals(ContextLifecycleErrorCode.CLOSED, syncCode.get(), "同步回调内 close 必须返回 CLOSED")
                assertEquals(ContextLifecycleErrorCode.CLOSED, asyncCode.get(), "异步回调内 close 必须返回 CLOSED")

                // 在途写未被回调内 close 抢先关闭：正常关闭仍能释放全部连接。
                executor.close()
                assertAllRecordedClosed(recorded)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A07

    @Test
    fun `A07 cancelling one close waiter does not abort real cleanup or other waiters`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            val pool: ExecutorService = Executors.newFixedThreadPool(1)
            try {
                interceptDriver(delegate, recorded, recordingHook(delegate, recorded)) {
                    ContextDatabaseFactory.open(path, 1).use { executor ->
                        val closedFlag = closedFlagOf(executor)
                        runBlocking {
                            withTimeout(15_000) {
                                val enteredWrite = CountDownLatch(1)
                                val releaseWrite = CountDownLatch(1)
                                val writer =
                                    async(Dispatchers.IO) {
                                        executor.write { _ ->
                                            enteredWrite.countDown()
                                            assertTrue(releaseWrite.await(30, TimeUnit.SECONDS), "释放屏障超时")
                                        }
                                    }
                                assertTrue(enteredWrite.await(30, TimeUnit.SECONDS))

                                // 发起者：在途写未结束前一直阻塞在 drain 等待，真实清理由它完成。
                                val owner = async { executor.closeAsync() }
                                // 等发起者推进到「已封闭新请求」：确定性地保证后续 waiter 只可能是等待者。
                                withTimeout(10_000) {
                                    while (!closedFlag.get()) yield()
                                }

                                // 第二个关闭等待者：开始后立即取消它，必须只影响它自己。
                                val waiterStarted = CountDownLatch(1)
                                val waiter =
                                    async(Dispatchers.IO) {
                                        waiterStarted.countDown()
                                        executor.closeAsync()
                                    }
                                assertTrue(waiterStarted.await(30, TimeUnit.SECONDS))
                                waiter.cancel(CancellationException("waiter cancelled"))

                                val cancelFailure = assertFailsWith<CancellationException> { waiter.await() }
                                assertEquals("waiter cancelled", cancelFailure.message)

                                // 被取消的等待者不影响真实清理：释放写事务后发起者与资源都正常收尾。
                                releaseWrite.countDown()
                                writer.await()
                                owner.await()
                            }
                        }
                        assertAllRecordedClosed(recorded)
                    }
                }
            } finally {
                pool.shutdownNow()
                pool.awaitTermination(5, TimeUnit.SECONDS)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A08（F2-R1 正式反例）

    @Test
    fun `A08 interrupted sync close initiator still completes unique cleanup and matches real final state`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            val pool: ExecutorService = Executors.newSingleThreadExecutor()
            try {
                interceptDriver(delegate, recorded, recordingHook(delegate, recorded)) {
                    ContextDatabaseFactory.open(path, 1).use { executor ->
                        val closedFlag = closedFlagOf(executor)
                        val enteredWrite = CountDownLatch(1)
                        val releaseWrite = CountDownLatch(1)
                        // 打开阶段有一条只读校验连接已被合法关闭；以打开后的已关数作为基线，
                        // 只要求关闭流程不得在在途 JDBC 退出前再关掉任何新的真实连接。
                        val closedBaseline = recorded.count { it.isClosed }
                        val writer =
                            pool.submit {
                                runCatching {
                                    runBlocking {
                                        executor.write { _ ->
                                            enteredWrite.countDown()
                                            assertTrue(releaseWrite.await(30, TimeUnit.SECONDS), "释放屏障超时")
                                        }
                                    }
                                }
                            }
                        try {
                            assertTrue(enteredWrite.await(30, TimeUnit.SECONDS), "写事务必须已进入回调")

                            val closerOutcome = AtomicReference<Throwable?>()
                            val interruptFlagRestored = AtomicBoolean(false)
                            val closerThread = AtomicReference<Thread?>()
                            val closer =
                                Thread {
                                    closerThread.set(Thread.currentThread())
                                    try {
                                        executor.close()
                                    } catch (failure: Throwable) {
                                        closerOutcome.set(failure)
                                    }
                                    interruptFlagRestored.set(Thread.currentThread().isInterrupted)
                                }.apply {
                                    name = "f2-a08-close-initiator"
                                    isDaemon = true
                                }
                            closer.start()

                            // 受控确认：关闭已发起，且发起者已进入 drain 等待（WAITING）。
                            assertTrue(spinUntil(10) { closedFlag.get() }, "同步关闭必须已发起")
                            assertTrue(
                                spinUntil(10) { closerThread.get()?.state == Thread.State.WAITING },
                                "同步关闭发起者必须已进入等待在途归零的分支",
                            )
                            // 在途 JDBC 未退出前，绝不允许抢关真实连接（相比打开阶段的已关数不得新增）。
                            assertEquals(closedBaseline, recorded.count { it.isClosed }, "在途写未退出前不得新增关闭真实连接")

                            closer.interrupt()
                            releaseWrite.countDown()
                            writer.get(30, TimeUnit.SECONDS)
                            closer.join(TimeUnit.SECONDS.toMillis(15))
                            assertFalse(closer.isAlive, "被中断的同步关闭发起者必须完成清理并返回，不得挂住")

                            // 中断不改变唯一清理责任：close 正常返回，真实资源全部释放。
                            assertNull(closerOutcome.get(), "被中断的 close 不得抛出 InterruptedException，实际: ${closerOutcome.get()}")
                            assertTrue(interruptFlagRestored.get(), "清理完成后必须恢复中断标志")
                            assertAllRecordedClosed(recorded)

                            // 重复 close 观察到同一成功结果，不重抛历史失败、也不残留连接。
                            executor.close()
                            assertAllRecordedClosed(recorded)
                        } finally {
                            releaseWrite.countDown()
                            runCatching { writer.get(30, TimeUnit.SECONDS) }
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
                pool.awaitTermination(5, TimeUnit.SECONDS)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    // ---------------------------------------------------------------- A09（F2-R2 正式反例）

    @Test
    fun `A09 close inside queryRaw mapper is rejected by name without deadlock`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val recorded = CopyOnWriteArrayList<Connection>()
            val delegate = delegateFor(path)
            interceptDriver(delegate, recorded, recordingHook(delegate, recorded)) {
                val executor = ContextDatabaseFactory.open(path, 1)
                val syncCode = AtomicReference<ContextLifecycleErrorCode?>()
                val asyncCode = AtomicReference<ContextLifecycleErrorCode?>()
                val outcome = AtomicReference<Throwable?>()
                // mapper 在 Slot 固定线程上同步执行，阻塞式 close 无法被协程超时打断；
                // 用外部守护线程 + join 超时做真正的死锁护栏。
                val runner =
                    Thread {
                        try {
                            runBlocking {
                                executor.queryRaw("SELECT 1") { rs ->
                                    val sync = assertFailsWith<ContextLifecycleException> { executor.close() }
                                    syncCode.set(sync.errorCode)
                                    if (!sync.message.orEmpty().contains("inside a read/write callback")) {
                                        error("mapper 内 close 必须是具名拒绝，实际: ${sync.message}")
                                    }
                                    val async =
                                        assertFailsWith<ContextLifecycleException> {
                                            runBlocking { executor.closeAsync() }
                                        }
                                    asyncCode.set(async.errorCode)
                                    rs
                                }
                            }
                        } catch (failure: Throwable) {
                            outcome.set(failure)
                        }
                    }.apply {
                        name = "f2-a09-queryraw-mapper-close"
                        isDaemon = true
                    }
                runner.start()
                runner.join(TimeUnit.SECONDS.toMillis(15))
                if (runner.isAlive) {
                    fail("queryRaw mapper 内 close 不得死锁：守护线程在 15s 内未返回")
                }
                outcome.get()?.let { throw AssertionError("mapper 内 close 不应抛出意外异常", it) }
                assertEquals(ContextLifecycleErrorCode.CLOSED, syncCode.get(), "mapper 内同步 close 必须返回 CLOSED")
                assertEquals(ContextLifecycleErrorCode.CLOSED, asyncCode.get(), "mapper 内异步 close 必须返回 CLOSED")

                // mapper 内 close 被拒后，正常关闭仍能释放全部真实连接。
                executor.close()
                assertAllRecordedClosed(recorded)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
