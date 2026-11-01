package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一个 Slot：独占一条物理 [Connection]，并独占一条固定执行线程。
 *
 * 约束（技术实现设计 §2.6 / CT01 §h7）：
 * 1. 物理连接在 Slot 整个生命周期常驻，逐语句执行绝不关闭；
 * 2. 同一 Slot 的所有阻塞 JDBC 都在它自己的单线程 [dispatcher] 上顺序执行，
 *    绝不把一条连接同时交给多个协程；
 * 3. 连接级 PRAGMA 在 [applyPragmas] 中显式设置，且必须在 [validateSchemaVersion] 之后；
 *    绝不信任前一次检查作为永久保证；
 * 4. [dispose] 只在执行器确认无在途操作后调用，不在阻塞 JDBC 访问期间关连接。
 *
 * 连接所有权：只有 Slot 线程（[dispatcher] 上）可以碰 [connection]；
 * [ContextDatabaseExecutor] 通过 Scope 的线程校验把这条规则变成可测的具名错误。
 *
 * 连接打开使用 [SqliteUrls.openReadWriteUrl]（`?mode=rw`），库缺失时拒绝而不是自动建空库；
 * 这是普通打开不得静默建库的最后防线。PRAGMA 不在打开时立即设置，由执行器在
 * 实际连接完成版本/结构验证后再调用 [applyPragmas]。
 *
 * R3 作用域绑定：[currentScope] 持有当前活跃的 [ScopeSession]，每次通过
 * [scopedConnection] / [scopedDriver] 获取的连接/驱动都绑定到该会话，会话失效后
 * 所有操作均被拒绝。
 *
 * R5 事务状态表：[txState] 为 IDLE / IN_TRANSACTION / COMMITTED / ROLLED_BACK / POISONED
 * 五态，commit/rollback 失败后进入 POISONED，禁止恢复 autoCommit、禁止继续复用。
 */
internal open class ContextSlot(
    val kind: SlotKind,
    val index: Int,
    private val databasePath: Path,
    private val pragmas: List<Pair<String, String>>,
) : AutoCloseable {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "context-db-${kind.label}-$index").apply { isDaemon = true }
        }

    /** 本 Slot 的固定执行线程；事务与查询都在它上面同步完成。 */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** 物理连接；构造完成前为 null，此时本 Slot 不可用。 */
    @Volatile
    private var physicalConnection: Connection? = null

    @Volatile
    private var physicalDriver: SlotConnectionJdbcDriver? = null

    /**
     * 固定物理连接：由 Xerial 打开（mode=rw），PRAGMA 由 [applyPragmas] 后续设置。
     *
     * 资源所有权（R1）：构造完成后连接必然已取得；构造中失败时 [physicalConnection]
     * 保持 null，任何使用都立即拒绝，不允许带病运行。中毒后物理连接由
     * [releasePoisonedConnection] 释放，此后访问得到具名持久化异常而不是 NPE。
     */
    val connection: Connection
        get() =
            physicalConnection ?: throw persistenceUnavailable(
                "${kind.label} slot $index connection is not available: " +
                    "it was not established or was released after a failed transaction",
            )

    /** 复用的 SQLDelight 驱动：绑定与游标映射走父类，连接供给走本 Slot。 */
    val driver: SlotConnectionJdbcDriver
        get() = requireNotNull(physicalDriver) { "${kind.label} slot $index driver is not established" }

    /**
     * 当前活跃的作用域会话：由 [RawReadScope] / [RawTransactionScope] 构造时设置，
     * [invalidate] 时清空。同一 Slot 同一时间只允许一个活跃作用域（执行器禁止嵌套）。
     */
    @Volatile
    var currentScope: ScopeSession? = null

    init {
        // 资源所有权（R1）：先开连接、再创建驱动绑定；连接取得后的任何失败必须关闭该
        // 连接并停止已启动的执行线程，不留泄漏；清理失败以 suppressed 附加到主失败。
        var opened: Connection? = null
        try {
            opened =
                ContextSqliteErrors.map("opening ${kind.label} slot $index") {
                    DriverManager.getConnection(SqliteUrls.openReadWriteUrl(databasePath), Properties())
                }
            physicalConnection = opened
            physicalDriver = SlotConnectionJdbcDriver(opened)
        } catch (failure: Throwable) {
            if (opened != null) {
                runCatching { opened.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            stopExecutorAfterConstructionFailure(failure)
            throw failure
        }
    }

    private val disposed = AtomicBoolean(false)

    val isDisposed: Boolean get() = disposed.get()

    // =====================================================================
    // R3 作用域守卫
    // =====================================================================

    /**
     * 校验 [session] 是否可用：会话未失效、调用线程为会话所属线程、会话仍拥有 Slot 当前
     * 作用域、连接未中毒/未废弃。违反任一条件均抛具名异常。
     *
     * R1/R3 rev4：中毒 Slot 的旧句柄同样在每次真实操作前被拒绝，不依赖“取句柄时检查一次”。
     */
    internal fun checkScope(session: ScopeSession) {
        if (!session.active.get()) {
            throw ContextDatabaseExecutor.escapedScope("scope", "session already invalidated")
        }
        if (Thread.currentThread() !== session.ownerThread) {
            throw ContextDatabaseExecutor.crossThread(
                "scope",
                "called from thread ${Thread.currentThread().name}, owner is ${session.ownerThread.name}",
            )
        }
        if (currentScope !== session) {
            throw ContextDatabaseExecutor.escapedScope(
                "scope",
                "session does not own the current scope of this slot",
            )
        }
        if (poisoned.get()) {
            throw ContextDatabaseExecutor.persistenceInvalid(
                "${kind.label} slot $index connection is poisoned after a failed transaction; the slot must not be reused",
            )
        }
        val drv = physicalDriver
        if (drv != null && drv.disposed) {
            throw ContextDatabaseExecutor.closedException()
        }
    }

    /**
     * 获取绑定到 [session] 的受保护连接：先 [checkScope] 再返回统一默认拒绝的受控代理。
     * 调用方在会话失效后继续使用该连接会被拒绝。
     */
    internal fun scopedConnection(session: ScopeSession): Connection {
        checkScope(session)
        return ScopedJdbc.connection(this, session, connection)
    }

    /**
     * 获取绑定到 [session] 的受保护驱动：先 [checkScope] 再返回 [ScopedSlotDriver]。
     */
    internal fun scopedDriver(session: ScopeSession): app.cash.sqldelight.db.SqlDriver {
        checkScope(session)
        return ScopedSlotDriver(this, session)
    }

    // =====================================================================
    // R5 事务状态表
    // =====================================================================

    /** 事务状态：IDLE=空闲，IN_TRANSACTION=已开始未终止，COMMITTED=提交已确认，ROLLED_BACK=回滚已确认，POISONED=中毒不可复用 */
    private enum class TxState { IDLE, IN_TRANSACTION, COMMITTED, ROLLED_BACK, POISONED }

    private val txState = AtomicReference<TxState>(TxState.IDLE)

    /** 当前作用域结束后清空 Slot 的当前作用域引用（避免残留失效会话引用）。 */
    internal fun clearCurrentScope() {
        currentScope = null
    }

    /** 中毒原因：commit/rollback 终结操作失败时记录的异常。 */
    private val poisonCause = AtomicReference<Throwable?>(null)

    /** 中毒后物理连接安全清理失败（仅诊断，不覆盖主失败）。 */
    private val releaseFailure = AtomicReference<Throwable?>(null)

    /** 连接是否已中毒：commit/rollback 终结操作失败后连接状态不可信，禁止继续复用。 */
    private val poisoned = AtomicBoolean(false)

    /** 物理连接身份，测试用来断言写/读不是同一条连接。 */
    fun connectionIdentity(): Int = System.identityHashCode(connection)

    /** 连接是否已中毒，供执行器在复用前拒绝。 */
    fun isPoisoned(): Boolean = poisoned.get()

    /** 中毒原因，供诊断。 */
    internal fun getPoisonCause(): Throwable? = poisonCause.get()

    /** 检查连接是否中毒，中毒则抛具名异常禁止继续复用。 */
    internal fun checkPoison() {
        if (poisoned.get()) {
            throw ContextDatabaseExecutor.persistenceInvalid(
                "${kind.label} slot $index connection is poisoned after a failed transaction; the slot must not be reused",
            )
        }
    }

    /** 标记连接中毒：begin/commit/rollback/恢复失败后，连接状态不可信，禁止继续复用。 */
    internal fun poison(cause: Throwable? = null) {
        poisoned.set(true)
        txState.set(TxState.POISONED)
        if (cause != null) poisonCause.compareAndSet(null, cause)
    }

    /**
     * 中毒 Slot 的物理连接尽力释放（§8.3）：不恢复 autoCommit、不重试未知事务、
     * 不在 Slot 线程里 shutdown 并 await 自身；只关闭连接并标记驱动废弃。
     *
     * R2 rev5：**所有权只在 close 成功后转移**。首次 close 失败时保留 [physicalConnection]，
     * 供全局 [close] 再次尝试；失败本身只记录/附加，不覆盖主失败，也不提前 dispose 驱动。
     * 由执行器在「本次同步 JDBC 已退出、作用域已失效」的收尾边界调用；可重复安全调用。
     */
    internal fun releasePoisonedConnection() {
        if (!poisoned.get()) return
        val physical = physicalConnection ?: return
        val closeFailure =
            runCatching {
                ContextSqliteErrors.map("closing poisoned ${kind.label} slot $index connection") { physical.close() }
            }.exceptionOrNull()
        if (closeFailure != null) {
            // 清理失败：保留连接所有权，等全局 Slot.close 重试；不得静默丢失引用。
            poisonCause.get()?.let { it.addSuppressed(closeFailure) } ?: releaseFailure.compareAndSet(null, closeFailure)
            return
        }
        physicalConnection = null
        physicalDriver?.dispose()
    }

    /** 线程退出判定的首次失败（P1 诊断用）。 */
    private val firstThreadFailure = AtomicReference<Throwable?>(null)

    /** 物理连接 close 的首次失败（P1 诊断用）。 */
    private val firstConnectionFailure = AtomicReference<Throwable?>(null)

    /** 同模块测试用：注入线程退出判定失败（模拟 awaitTermination 失败的最小钩子）。 */
    private val injectedThreadTerminationFailure = AtomicReference<Throwable?>(null)

    internal fun injectThreadTerminationFailureForTesting(failure: Throwable?) {
        injectedThreadTerminationFailure.set(failure)
    }

    /** 同模块测试用：当前是否仍持有物理连接（所有权判据）。 */
    internal fun physicalConnectionForTesting(): Connection? = physicalConnection

    /** 线程退出的首次失败，供诊断与聚合。 */
    internal fun getFirstThreadFailure(): Throwable? = firstThreadFailure.get()

    /** 物理连接 close 的首次失败，供诊断与聚合。 */
    internal fun getFirstConnectionFailure(): Throwable? = firstConnectionFailure.get()

    /** 物理连接安全清理失败（诊断用，正常为 null）。 */
    internal fun getReleaseFailure(): Throwable? = releaseFailure.get()

    /** 读取该连接上的 PRAGMA 实值（测试断言 PRAGMA 持续生效）。 */
    fun pragma(name: String): String =
        ContextSqliteErrors.map("reading PRAGMA $name on ${kind.label} slot $index") {
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA $name").use { rs -> if (rs.next()) rs.getString(1).orEmpty() else "" }
            }
        }

    fun autoCommit(): Boolean = ContextSqliteErrors.map("reading autoCommit on ${kind.label} slot $index") { connection.autoCommit }

    /**
     * 开始一个事务：仅供执行器在 Slot 线程上调用。
     *
     * R3 rev4：`autoCommit=false` 失败时连接的事务状态未知，统一进入失败处置（poison）。
     */
    fun beginTransaction() {
        checkPoison()
        val state = txState.get()
        if (state != TxState.IDLE) {
            throw IllegalStateException("cannot begin transaction: slot is in $state state")
        }
        try {
            ContextSqliteErrors.map("begin transaction on ${kind.label} slot $index") {
                connection.autoCommit = false
            }
        } catch (failure: Throwable) {
            poison(failure)
            throw failure
        }
        txState.set(TxState.IN_TRANSACTION)
    }

    /**
     * 提交事务：检查 txState == IN_TRANSACTION；commit 成功后恢复 autoCommit 并置 IDLE；
     * commit 失败则置 POISONED（不尝试 rollback，原异常为主）；恢复 autoCommit 失败也置 POISONED。
     */
    fun commitTransaction() {
        val state = txState.get()
        if (state != TxState.IN_TRANSACTION) {
            throw IllegalStateException("cannot commit: slot is in $state state")
        }
        try {
            ContextSqliteErrors.map("commit on ${kind.label} slot $index") { connection.commit() }
        } catch (failure: Throwable) {
            poison(failure)
            throw failure
        }
        txState.set(TxState.COMMITTED)
        // commit 成功但恢复 autoCommit 失败：已提交内容不可撤销，只能报告失败并中毒。
        restoreAutoCommit("commit")
        txState.set(TxState.IDLE)
    }

    /**
     * 回滚事务：检查 txState == IN_TRANSACTION；rollback 成功后恢复 autoCommit 并置 IDLE；
     * rollback 失败则置 POISONED（不恢复 autoCommit、失败向外传播）；恢复失败也置 POISONED。
     */
    fun rollbackTransaction() {
        val state = txState.get()
        if (state != TxState.IN_TRANSACTION) {
            throw IllegalStateException("cannot rollback: slot is in $state state")
        }
        try {
            ContextSqliteErrors.map("rollback on ${kind.label} slot $index") { connection.rollback() }
        } catch (failure: Throwable) {
            poison(failure)
            throw failure
        }
        txState.set(TxState.ROLLED_BACK)
        restoreAutoCommit("rollback")
        txState.set(TxState.IDLE)
    }

    /** 仅在事务已安全终结（COMMITTED/ROLLED_BACK）后恢复 autoCommit；失败则中毒。 */
    private fun restoreAutoCommit(after: String) {
        try {
            ContextSqliteErrors.map("restore autoCommit after $after on ${kind.label} slot $index") {
                connection.autoCommit = true
            }
        } catch (failure: Throwable) {
            poison(failure)
            throw failure
        }
    }

    /** 语句执行后同步刷新 WAL，避免依赖连接关闭时机。 */
    fun checkpointIfWrite() {
        if (kind != SlotKind.WRITE) return
        ContextSqliteErrors.map("wal checkpoint on write slot") {
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(PASSIVE)") }
        }
    }

    /**
     * 在实际连接上验证 schema 版本。
     *
     * 必须在 [applyPragmas] 之前调用，确保持久化 PRAGMA 设置前，
     * 当前连接上的库确实是预期版本（防止验证后文件被替换）。
     */
    fun validateSchemaVersion(expected: Long) {
        val actual =
            ContextSqliteErrors.map("validating schema version on ${kind.label} slot $index") {
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rs ->
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            }
        if (actual != expected) {
            throw schemaVersionMismatch(
                "${kind.label} slot $index schema version $actual does not match required $expected: $databasePath",
            )
        }
    }

    /**
     * 在实际连接上验证六表五索引齐备。
     *
     * 必须在 [applyPragmas] 之前调用，确保持久化 PRAGMA 设置前，
     * 当前连接上的库结构齐备（防止验证后文件被替换为结构不完整的库）。
     */
    fun validateMissingObjects() {
        val missing =
            ContextSqliteErrors.map("validating structure on ${kind.label} slot $index") {
                ContextDatabaseFactory.missingObjects(connection)
            }
        if (missing.isNotEmpty()) {
            throw persistenceUnavailable(
                "${kind.label} slot $index context database is missing expected objects ${missing.sorted()}: $databasePath",
            )
        }
    }

    /**
     * 将连接级 PRAGMA 应用到实际连接。
     *
     * 仅在 [validateSchemaVersion] 通过后调用，确保持久化 PRAGMA 不会被设置到
     * 被替换或错版的连接上。
     */
    fun applyPragmas() {
        ContextSqliteErrors.map("applying PRAGMA on ${kind.label} slot $index") {
            connection.createStatement().use { statement ->
                pragmas.forEach { (name, value) -> statement.execute("PRAGMA $name=$value") }
            }
        }
    }

    /**
     * 关闭 Slot：先结束固定线程（排空已排队的任务），再关闭物理连接。
     *
     * 调用前执行器已确认在途计数归零，所以这里不会与正在执行的 JDBC 抢用连接。
     *
     * 中断语义（F2-R1）：等待线程终止被 [Thread.interrupt] 时不得直接跳出跳过物理关闭——
     * 记录中断标志后继续完成 [executor.shutdown] / [ExecutorService.shutdownNow] 与
     * [Connection.close]，全部完成后恢复中断标志。
     *
     * P1：`disposed` 只表示「停止接单」，不再兼作「已释放」；重复调用（含失败后的重试）都会
     * 重新执行 [releaseAttempt]，重试也走这个入口，不绕过 [disposed]、不直接调私有 helper。
     */
    override fun close() {
        disposed.compareAndSet(false, true)
        releaseAttempt()
    }

    /**
     * 一次清理尝试（P1，对应设计 §3/§9 修订版）。
     *
     * 顺序固定：线程退出判定 → 物理 close → 失败聚合 → 抛主失败。
     * 线程退出与物理释放**分别跟踪**，任一方失败都不得被另一方成功掩盖；中断事实在 `finally`
     * 恢复，不因失败路径丢失；**没有物理连接时也照常执行线程退出判定与聚合**。
     */
    internal fun releaseAttempt() {
        var interrupted = false
        var threadFailure: Throwable? = null
        try {
            executor.shutdown()
            interrupted = awaitTerminationInterruptibly()
            injectedThreadTerminationFailure.get()?.let { throw it }
        } catch (failure: Throwable) {
            threadFailure = failure
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }

        val physical = physicalConnection
        var connectionFailure: Throwable? = null
        if (physical != null) {
            connectionFailure =
                runCatching {
                    ContextSqliteErrors.map("closing ${kind.label} slot $index connection") { physical.close() }
                }.exceptionOrNull()
            if (connectionFailure == null) {
                physicalConnection = null
                physicalDriver?.dispose()
            }
        }

        recordAttemptFailure(threadFailure, connectionFailure)
        val primary = threadFailure ?: connectionFailure
        if (threadFailure != null && connectionFailure != null && threadFailure !== connectionFailure) {
            // 同一尝试内两类失败都发生：线程失败为主，连接失败作为 suppressed，绝不静默丢弃。
            threadFailure.addSuppressed(connectionFailure)
        }
        if (primary != null) throw primary
    }

    /** 保留每个资源各自的首次失败；后续尝试的失败不覆盖首次原因。 */
    private fun recordAttemptFailure(
        threadFailure: Throwable?,
        connectionFailure: Throwable?,
    ) {
        threadFailure?.let { firstThreadFailure.compareAndSet(null, it) }
        connectionFailure?.let { firstConnectionFailure.compareAndSet(null, it) }
    }

    /**
     * 排空固定线程并等待终止。等待被 [Thread.interrupt] 时记录中断标志后继续等待，
     * 绝不因中断而放弃线程终止判定；必要时 [ExecutorService.shutdownNow] 后再次等待。
     *
     * @return 本次等待期间是否被中断
     */
    private fun awaitTerminationInterruptibly(): Boolean {
        var interrupted = false
        try {
            if (executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return false
        } catch (failure: InterruptedException) {
            interrupted = true
        }
        executor.shutdownNow()
        while (true) {
            try {
                // 第二次（及重试）await 结果纳入判定：强制停止后仍未终止属于清理失败，如实抛出。
                require(executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "${kind.label} slot $index worker thread did not terminate after forced stop"
                }
                return interrupted
            } catch (failure: InterruptedException) {
                interrupted = true
            }
        }
    }

    /** 构造期资源释放：停止执行线程；线程停止失败以 suppressed 附加到主失败。 */
    private fun stopExecutorAfterConstructionFailure(failure: Throwable) {
        executor.shutdown()
        runCatching {
            ContextSqliteErrors.map("stopping ${kind.label} slot $index thread") {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                    executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }.exceptionOrNull()?.let(failure::addSuppressed)
    }

    /** 释放驱动：由 Slot.close 调用，标记驱动已废弃。 */
    internal fun dispose() {
        physicalDriver?.dispose()
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS: Long = 30
    }
}

/** Slot 类型：唯一写 Slot 与若干只读 Slot。 */
internal enum class SlotKind(
    val label: String,
) {
    WRITE("write"),
    READ("read"),
}

/** 写连接 PRAGMA：WAL + FULL 持久性优先，外键开启，忙等 5 秒。 */
internal val WRITE_PRAGMAS: List<Pair<String, String>> =
    listOf(
        "journal_mode" to "WAL",
        "synchronous" to "FULL",
        "busy_timeout" to "5000",
        "foreign_keys" to "ON",
    )

/** 读连接 PRAGMA：只读、外键开启、忙等 5 秒；不改库级 journal_mode。 */
internal val READ_PRAGMAS: List<Pair<String, String>> =
    listOf(
        "busy_timeout" to "5000",
        "foreign_keys" to "ON",
        "query_only" to "ON",
    )

/** 作用域会话：绑定到特定 Slot 线程，生命周期与回调一致。
 *
 * [active] 为 true 时会话可用；[invalidate] 置 false 后所有通过本会话的操作均被拒绝。
 * [ownerThread] 记录会话所属线程，非所属线程调用抛 CROSS_THREAD。
 */
internal class ScopeSession(
    val slot: ContextSlot,
    val ownerThread: Thread,
) {
    val active = AtomicBoolean(true)

    fun invalidate() {
        active.set(false)
    }
}
