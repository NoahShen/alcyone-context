package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Time
import java.sql.Timestamp
import java.util.concurrent.CancellationException

/**
 * 绑定到某条固定物理连接的 SQLDelight 驱动。
 *
 * **为什么不用 stock `JdbcSqliteDriver`**：探针 P1 实测（`tools/validation/cm1-compat-probe`）
 * 它对文件库按线程持有连接（`ThreadedConnectionManager`），非事务语句执行完就 `closeConnection`
 * 掉该线程的连接，且 `driver.close()` 是空操作。后果是：连接级 `PRAGMA` 不能持续生效，
 * 事务也会随线程切换静默换连接。
 *
 * **本类只做一件事**：让父类 `JdbcDriver` 的语句执行、参数绑定与游标映射全部照旧复用，
 * 只把「连接从哪来、要不要关」改成本 Slot 的固定语义：
 * - [getConnection] 永远返回本 Slot 自持的那条物理连接；
 * - [closeConnection] 是空操作，逐语句执行**绝不**关闭连接；
 * - [close] 不关连接：连接由 [ContextSlot] 在所有在途操作退出后显式关闭。
 */
internal class SlotConnectionJdbcDriver(
    private val connection: Connection,
) : JdbcDriver() {
    // 满足 SQLDelight 的查询变化通知契约；CT04 不注册监听者，CT05 若需要再接管。
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()

    /** 连接/线程被 Slot 释放后置位；此后对驱动的任何使用都被拒绝（R3）。 */
    @Volatile
    var disposed: Boolean = false

    override fun addListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) {
        synchronized(listeners) {
            queryKeys.forEach { key -> listeners.getOrPut(key) { mutableSetOf() } += listener }
        }
    }

    override fun removeListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) {
        synchronized(listeners) {
            queryKeys.forEach { key -> listeners[key]?.remove(listener) }
        }
    }

    override fun notifyListeners(queryKeys: Array<out String>) {
        val snapshot = synchronized(listeners) { queryKeys.mapNotNull { listeners[it]?.toList() }.flatten() }
        snapshot.forEach { it.queryResultsChanged() }
    }

    /** 连接所有权在 [ContextSlot]，驱动关闭不触碰物理连接。 */
    override fun close() {
        disposed = true
    }

    /** 由 [ContextSlot.close] 调用：标记驱动已彻底废弃，所有操作拒绝。 */
    internal fun dispose() {
        disposed = true
    }

    override fun getConnection(): Connection = connection

    /** 逐语句执行后不关闭：这是本驱动存在的理由。 */
    override fun closeConnection(connection: Connection) = Unit
}

/**
 * 作用域绑定的 SQLDelight 驱动（R1/R3 rev4）：句柄固定持有创建时的 [ScopeSession]，
 * 每次真实操作前通过 [ContextSlot.checkScope] 校验活跃、线程归属、当前作用域身份、未中毒。
 *
 * 借出者只能执行 SQL；**事务控制权只归执行器**——`beginTransaction` / `endTransaction` /
 * `rollbackTransaction` / `newTransaction` / `currentTransaction` 一律具名拒绝，
 * 不会通过 `JdbcDriver` 自带的 thread-local transaction 机制取得额外事务控制。
 *
 * [getConnection] 返回统一默认拒绝的受控代理（见 [ScopedJdbc]），绝不透出 Slot 驱动本体
 * 或未受保护的物理连接。
 */
internal class ScopedSlotDriver(
    private val slot: ContextSlot,
    private val session: ScopeSession,
) : JdbcDriver() {
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()

    override fun addListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) {
        slot.checkScope(session)
        synchronized(listeners) {
            queryKeys.forEach { key -> listeners.getOrPut(key) { mutableSetOf() } += listener }
        }
    }

    override fun removeListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) {
        slot.checkScope(session)
        synchronized(listeners) {
            queryKeys.forEach { key -> listeners[key]?.remove(listener) }
        }
    }

    override fun notifyListeners(queryKeys: Array<out String>) {
        slot.checkScope(session)
        val snapshot = synchronized(listeners) { queryKeys.mapNotNull { listeners[it]?.toList() }.flatten() }
        snapshot.forEach { it.queryResultsChanged() }
    }

    /** 仅处理本句柄：不置共享 Slot 驱动 disposed，不关闭共享连接。 */
    override fun close() {
        slot.checkScope(session)
    }

    override fun getConnection(): Connection = ScopedJdbc.connection(slot, session, slot.connection)

    override fun closeConnection(connection: Connection) = Unit

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        slot.checkScope(session)
        SqlBoundaryGuard.check(sql)
        return super.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        slot.checkScope(session)
        SqlBoundaryGuard.check(sql)
        return super.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun Connection.beginTransaction() = rejectTransactionApi("beginTransaction")

    override fun Connection.endTransaction() = rejectTransactionApi("endTransaction")

    override fun Connection.rollbackTransaction() = rejectTransactionApi("rollbackTransaction")

    override fun newTransaction(): QueryResult<Transacter.Transaction> = rejectTransactionApi("newTransaction")

    override fun currentTransaction(): Transacter.Transaction? = rejectTransactionApi("currentTransaction")

    private fun rejectTransactionApi(operation: String): Nothing =
        throw ContextDatabaseExecutor.persistenceInvalid(
            "driver $operation is not available through a scoped driver; transaction control belongs to the executor",
        )
}

/**
 * 受作用域保护的最小 JDBC 句柄（R1 rev4）：**统一默认拒绝**的显式签名白名单派发。
 *
 * 上一版用 `Connection by delegate` 加零散覆写，未覆写的重载与 `unwrap` 会直接透传真实
 * 连接/语句/结果集（Architect 实测 `unwrap escaped update accepted=true`）。本版改为
 * [Proxy] + 精确到参数类型的签名表：只有 [ScopedJdbc] 表内显式登记的方法可委托，
 * 其余（`unwrap`、`isWrapperFor`、元数据、反向对象出口、未列明重载、未建模的 JDBC 方法）
 * 一律抛具名 `ContextPersistenceException(PERSISTENCE_UNAVAILABLE)`。
 *
 * 规则：
 * - 每次放行前先 [ContextSlot.checkScope]（会话活跃、线程归属、作用域身份、未中毒）；
 * - SQL 执行入口统一走 [SqlBoundaryGuard]（事务控制与多语句阻断）；
 * - 委托调用的 `SQLException` 在边界安全映射（不泄露 SQL 正文/底层异常链），
 *   `CancellationException` 原样传播；
 * - `Object` 自身方法只回安全身份信息，不打印 delegate。
 */
internal object ScopedJdbc {
    /** 受控连接：只允许无参 `createStatement()` 与 `prepareStatement(String)`。 */
    fun connection(
        slot: ContextSlot,
        session: ScopeSession,
        delegate: Connection,
    ): Connection {
        val table =
            mapOf<String, (Array<Any?>?) -> Any?>(
                key("createStatement") to { statement(slot, session, delegate.createStatement()) },
                key("prepareStatement", String::class.java) to { args ->
                    val sql = args!![0] as String
                    SqlBoundaryGuard.check(sql)
                    preparedStatement(slot, session, delegate.prepareStatement(sql))
                },
            )
        return proxy(Connection::class.java, slot, session, "connection", table) as Connection
    }

    /** 受控语句：单参数 SQL 执行 + close；执行前统一事务控制判定。 */
    fun statement(
        slot: ContextSlot,
        session: ScopeSession,
        delegate: Statement,
    ): Statement {
        val table =
            mapOf<String, (Array<Any?>?) -> Any?>(
                key("executeQuery", String::class.java) to { args ->
                    val sql = args!![0] as String
                    SqlBoundaryGuard.check(sql)
                    resultSet(slot, session, delegate.executeQuery(sql))
                },
                key("executeUpdate", String::class.java) to { args ->
                    val sql = args!![0] as String
                    SqlBoundaryGuard.check(sql)
                    delegate.executeUpdate(sql)
                },
                key("execute", String::class.java) to { args ->
                    val sql = args!![0] as String
                    SqlBoundaryGuard.check(sql)
                    delegate.execute(sql)
                },
                key("close") to { delegate.close() },
            )
        return proxy(Statement::class.java, slot, session, "statement", table) as Statement
    }

    /** 受控预编译语句：执行/更新计数/close + 显式列出的标量绑定签名。 */
    fun preparedStatement(
        slot: ContextSlot,
        session: ScopeSession,
        delegate: PreparedStatement,
    ): PreparedStatement {
        val table = LinkedHashMap<String, (Array<Any?>?) -> Any?>()
        table[key("executeQuery")] = { resultSet(slot, session, delegate.executeQuery()) }
        table[key("executeUpdate")] = { delegate.executeUpdate() }
        table[key("execute")] = { delegate.execute() }
        table[key("getUpdateCount")] = { delegate.updateCount }
        table[key("close")] = { delegate.close() }
        bind(table, "setNull", Int::class.java) { index, value -> delegate.setNull(index, value as Int) }
        bind(table, "setBoolean", Boolean::class.java) { index, value -> delegate.setBoolean(index, value as Boolean) }
        bind(table, "setByte", Byte::class.java) { index, value -> delegate.setByte(index, value as Byte) }
        bind(table, "setShort", Short::class.java) { index, value -> delegate.setShort(index, value as Short) }
        bind(table, "setInt", Int::class.java) { index, value -> delegate.setInt(index, value as Int) }
        bind(table, "setLong", Long::class.java) { index, value -> delegate.setLong(index, value as Long) }
        bind(table, "setFloat", Float::class.java) { index, value -> delegate.setFloat(index, value as Float) }
        bind(table, "setDouble", Double::class.java) { index, value -> delegate.setDouble(index, value as Double) }
        bind(table, "setBigDecimal", BigDecimal::class.java) { index, value ->
            delegate.setBigDecimal(index, value as BigDecimal)
        }
        bind(table, "setString", String::class.java) { index, value -> delegate.setString(index, value as String) }
        bind(table, "setBytes", ByteArray::class.java) { index, value -> delegate.setBytes(index, value as ByteArray) }
        bind(table, "setDate", Date::class.java) { index, value -> delegate.setDate(index, value as Date) }
        bind(table, "setTime", Time::class.java) { index, value -> delegate.setTime(index, value as Time) }
        bind(table, "setTimestamp", Timestamp::class.java) { index, value ->
            delegate.setTimestamp(index, value as Timestamp)
        }
        table[key("setObject", Int::class.java, Any::class.java, Int::class.java)] = { args ->
            val index = args!![0] as Int
            val value = args[1]
            val sqlType = args[2] as Int
            if (!isScalarBinding(value)) {
                throw ContextDatabaseExecutor.persistenceInvalid(
                    "prepared statement setObject with unsupported value type is not available through a scoped handle",
                )
            }
            delegate.setObject(index, value, sqlType)
            Unit
        }
        return proxy(PreparedStatement::class.java, slot, session, "prepared statement", table) as PreparedStatement
    }

    /** 受控结果集：`next` / `wasNull` / `close` 与显式列出的标量读取（索引与标签版）。 */
    fun resultSet(
        slot: ContextSlot,
        session: ScopeSession,
        delegate: ResultSet,
    ): ResultSet {
        val table = LinkedHashMap<String, (Array<Any?>?) -> Any?>()
        table[key("next")] = { delegate.next() }
        table[key("wasNull")] = { delegate.wasNull() }
        table[key("close")] = { delegate.close() }
        scalar(table, "getLong", delegate::getLong, delegate::getLong)
        scalar(table, "getInt", delegate::getInt, delegate::getInt)
        scalar(table, "getShort", delegate::getShort, delegate::getShort)
        scalar(table, "getByte", delegate::getByte, delegate::getByte)
        scalar(table, "getFloat", delegate::getFloat, delegate::getFloat)
        scalar(table, "getDouble", delegate::getDouble, delegate::getDouble)
        scalar(table, "getBoolean", delegate::getBoolean, delegate::getBoolean)
        scalar(table, "getString", delegate::getString, delegate::getString)
        scalar(table, "getBytes", delegate::getBytes, delegate::getBytes)
        scalar(table, "getBigDecimal", delegate::getBigDecimal, delegate::getBigDecimal)
        scalar(table, "getDate", delegate::getDate, delegate::getDate)
        scalar(table, "getTime", delegate::getTime, delegate::getTime)
        scalar(table, "getTimestamp", delegate::getTimestamp, delegate::getTimestamp)
        return proxy(ResultSet::class.java, slot, session, "result set", table) as ResultSet
    }

    // ------------------------------------------------------------------ 内部实现

    private fun proxy(
        iface: Class<*>,
        slot: ContextSlot,
        session: ScopeSession,
        label: String,
        table: Map<String, (Array<Any?>?) -> Any?>,
    ): Any {
        val handler =
            InvocationHandler { proxyInstance, method, args ->
                if (method.declaringClass == Any::class.java) {
                    objectMethod(proxyInstance, label, method, args)
                } else {
                    val action =
                        table[signatureOf(method)]
                            ?: throw ContextDatabaseExecutor.persistenceInvalid(
                                "$label ${method.name} is not available through a scoped $label; " +
                                    "only the executor-managed SQL surface is allowed",
                            )
                    slot.checkScope(session)
                    try {
                        action(args)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        // 委托的 SQLException 在边界安全映射；具名异常原样透传；取消不包装。
                        throw ContextSqliteErrors.map("scoped $label ${method.name}") { throw failure }
                    }
                }
            }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler) as Any
    }

    /** `Object` 方法只给安全身份信息，不透出 delegate。 */
    private fun objectMethod(
        proxyInstance: Any,
        label: String,
        method: Method,
        args: Array<Any?>?,
    ): Any? =
        when (method.name) {
            "equals" -> proxyInstance === args?.get(0)
            "hashCode" -> System.identityHashCode(proxyInstance)
            "toString" -> "scoped-$label@${Integer.toHexString(System.identityHashCode(proxyInstance))}"
            else -> throw ContextDatabaseExecutor.persistenceInvalid("$label ${method.name} is not available")
        }

    private fun bind(
        table: MutableMap<String, (Array<Any?>?) -> Any?>,
        name: String,
        type: Class<*>,
        action: (Int, Any?) -> Unit,
    ) {
        table[key(name, Int::class.java, type)] = { args -> action(args!![0] as Int, args[1]) }
    }

    private fun <T> scalar(
        table: MutableMap<String, (Array<Any?>?) -> Any?>,
        name: String,
        byIndex: (Int) -> T,
        byLabel: (String) -> T,
    ) {
        table[key(name, Int::class.java)] = { args -> byIndex(args!![0] as Int) }
        table[key(name, String::class.java)] = { args -> byLabel(args!![0] as String) }
    }

    private fun isScalarBinding(value: Any?): Boolean =
        value == null ||
            value is String ||
            value is ByteArray ||
            value is BigDecimal ||
            value is Date ||
            value is Time ||
            value is Timestamp ||
            value is Boolean ||
            value is Byte ||
            value is Short ||
            value is Int ||
            value is Long ||
            value is Float ||
            value is Double

    private fun key(
        name: String,
        vararg params: Class<*>,
    ): String = name + "(" + params.joinToString(",") { it.name } + ")"

    private fun signatureOf(method: Method): String = key(method.name, *method.parameterTypes)
}

/**
 * SQL 语句边界（R2 rev4）：所有允许执行 SQL 的入口统一调用，阻断执行器之外的事务控制。
 *
 * 规则：
 * 1. 按顶层分号拆语句：字符串（单引号，含两个单引号的转义）、带引号标识符（双引号、方括号）、
 *    行注释（双减号开头）与块注释（斜杠星号开头）内的分号不算语句边界，避免误拒普通正文；
 * 2. 任一语句跳过前导空白/注释后的首个 token 命中 `BEGIN` / `COMMIT` / `END` /
 *    `ROLLBACK` / `SAVEPOINT` / `RELEASE` 即拒绝，错误消息点名关键字；
 * 3. 存在多条语句一律阻断（禁止多语句绕过）。只做语句边界判断，不写通用 SQL 引擎。
 */
internal object SqlBoundaryGuard {
    private val TRANSACTION_CONTROL = setOf("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE")

    fun check(sql: String) {
        val statements = splitStatements(sql)
        val meaningful = statements.filter { hasContent(it) }
        if (meaningful.isEmpty()) return
        meaningful.forEach { statement ->
            val token = firstToken(statement) ?: return@forEach
            val keyword = token.uppercase()
            if (keyword in TRANSACTION_CONTROL) {
                throw persistenceUnavailable(
                    "transaction control statement $keyword is blocked: " +
                        "a context transaction is committed or rolled back by the executor as a whole",
                )
            }
        }
        if (meaningful.size > 1) {
            throw persistenceUnavailable(
                "multiple SQL statements are blocked: only one statement is allowed through the context scope",
            )
        }
    }

    /** 按顶层分号切分，保留字符串/标识符/注释原文；不解析 SQL 语义。 */
    private fun splitStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < sql.length) {
            val char = sql[index]
            when {
                char == '\'' || char == '"' -> {
                    val quote = char
                    current.append(quote)
                    index++
                    while (index < sql.length) {
                        val next = sql[index]
                        current.append(next)
                        index++
                        if (next == quote) {
                            if (index < sql.length && sql[index] == quote) {
                                current.append(sql[index])
                                index++
                            } else {
                                break
                            }
                        }
                    }
                }
                char == '[' -> {
                    current.append(char)
                    index++
                    while (index < sql.length) {
                        val next = sql[index]
                        current.append(next)
                        index++
                        if (next == ']') break
                    }
                }
                char == '-' && index + 1 < sql.length && sql[index + 1] == '-' -> {
                    val end = sql.indexOf('\n', index)
                    val stop = if (end < 0) sql.length else end
                    current.append(sql, index, stop)
                    index = stop
                }
                char == '/' && index + 1 < sql.length && sql[index + 1] == '*' -> {
                    val end = sql.indexOf("*/", index + 2)
                    val stop = if (end < 0) sql.length else end + 2
                    current.append(sql, index, stop)
                    index = stop
                }
                char == ';' -> {
                    statements += current.toString()
                    current.setLength(0)
                    index++
                }
                else -> {
                    current.append(char)
                    index++
                }
            }
        }
        statements += current.toString()
        return statements
    }

    /**
     * 跳过前导空白、行/块注释与 U+FEFF（BOM）后的内容起点；无内容返回长度。
     *
     * R1 rev5：SQLite 接受前导 BOM，而 Kotlin 的 `Char.isWhitespace()` 对 U+FEFF 返回 false，
     * 上一版因此把 `\uFEFFCOMMIT` 判成「非关键字」而放行。这里只补齐已实测的 U+FEFF，
     * 不做通用零宽字符/未知前缀处理，避免误伤正常 SQL 正文与绑定值。
     */
    private fun contentStart(statement: String): Int {
        var index = 0
        while (index < statement.length) {
            val char = statement[index]
            when {
                char.isWhitespace() || char == '\uFEFF' -> index++
                char == '-' && index + 1 < statement.length && statement[index + 1] == '-' -> {
                    val end = statement.indexOf('\n', index)
                    index = if (end < 0) statement.length else end + 1
                }
                char == '/' && index + 1 < statement.length && statement[index + 1] == '*' -> {
                    val end = statement.indexOf("*/", index + 2)
                    index = if (end < 0) statement.length else end + 2
                }
                else -> return index
            }
        }
        return index
    }

    private fun hasContent(statement: String): Boolean = contentStart(statement) < statement.length

    /** 首个 token（仅字母/下划线开头的关键字）；非关键字开头返回 null。 */
    private fun firstToken(statement: String): String? {
        val start = contentStart(statement)
        if (start >= statement.length) return null
        val first = statement[start]
        if (!first.isLetter() && first != '_') return null
        var end = start
        while (end < statement.length && (statement[end].isLetterOrDigit() || statement[end] == '_' || statement[end] == '$')) {
            end++
        }
        return statement.substring(start, end)
    }
}
