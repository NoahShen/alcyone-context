package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CM1 全面复查 P4：单语句失败（`updateRaw` / `queryRaw`）**不 poison** 写 Slot，健康连接可继续；
 * 事务终结失败（commit/rollback/恢复）**必须 poison** 且后续写入被拒绝。两类行为显式对比。
 */
class StatementFailureVsTransactionFailureTest {
    @Test
    fun `updateRaw statement failure does not poison the write slot`() =
        TestDatabases.temporaryDirectory().let { directory ->
            try {
                val path = TestDatabases.initializedDatabase(directory)
                ContextDatabaseFactory.open(path).use { executor ->
                    val writeSlot = executor.slotForTesting(SlotKind.WRITE)
                    val rawSql =
                        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
                            "VALUES ('R-P4-1', '   ', 'EXTRACTION', '[]', '2026-10-29T08:00:00.000+08:00')"

                    val failure = assertFailsWith<ContextPersistenceException> { runBlocking { executor.updateRaw(rawSql) } }
                    assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
                    assertFalse(
                        failure.message.orEmpty().contains("raw_memory"),
                        "错误消息不得回显 SQL 原文，实际: ${failure.message}",
                    )
                    assertFalse(
                        failure.message.orEmpty().contains("R-P4-1"),
                        "错误消息不得回显参数值，实际: ${failure.message}",
                    )

                    // 单语句失败不 poison：Slot 仍健康，连接仍可复用。
                    assertFalse(writeSlot.isPoisoned(), "updateRaw 单语句失败不得 poison 写 Slot")
                    assertTrue(executor.writeSlotAutoCommit(), "失败语句不得留下未结束的事务状态")

                    // 同一执行器的 write 仍可提交，且数据可见。
                    runBlocking {
                        executor.write { tx ->
                            tx.updatePrepared(
                                "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
                                    "VALUES ('R-P4-2', 'ok', 'EXTRACTION', '[]', '2026-10-29T08:00:00.000+08:00')",
                            ) { }
                        }
                    }
                    assertEquals(
                        1L,
                        countRaw(executor, "R-P4-2"),
                        "失败语句之后 write 必须正常提交并可见",
                    )
                    assertFalse(writeSlot.isPoisoned(), "成功提交后仍不得 poison")
                }
            } finally {
                TestDatabases.deleteRecursively(directory)
            }
        }

    @Test
    fun `queryRaw statement failure does not poison the read slots`() =
        TestDatabases.temporaryDirectory().let { directory ->
            try {
                val path = TestDatabases.initializedDatabase(directory)
                ContextDatabaseFactory.open(path).use { executor ->
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            runBlocking { executor.queryRaw("SELECT * FROM table_that_does_not_exist") { rs -> rs } }
                        }
                    assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
                    assertFalse(
                        failure.message.orEmpty().contains("table_that_does_not_exist"),
                        "错误消息不得回显 SQL 原文，实际: ${failure.message}",
                    )
                    (0 until executor.readSlotCount()).forEach { index ->
                        assertFalse(
                            executor.slotForTesting(SlotKind.READ, index).isPoisoned(),
                            "queryRaw 单语句失败不得 poison 读 Slot $index",
                        )
                    }
                    // 读路径仍可用。
                    assertEquals(
                        0L,
                        runBlocking {
                            executor.queryRaw("SELECT count(*) FROM raw_memory") { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        },
                    )
                }
            } finally {
                TestDatabases.deleteRecursively(directory)
            }
        }

    @Test
    fun `transaction termination failure poisons while statement failure does not`() =
        TestDatabases.temporaryDirectory().let { directory ->
            try {
                val path = TestDatabases.initializedDatabase(directory)
                ContextDatabaseFactory.open(path).use { executor ->
                    val writeSlot = executor.slotForTesting(SlotKind.WRITE)

                    // 1) 单语句失败：不 poison，可继续写入。
                    runCatching { runBlocking { executor.updateRaw("INSERT INTO raw_memory VALUES (1)") } }
                    assertFalse(writeSlot.isPoisoned(), "单语句失败后不得 poison")

                    // 2) 事务终结失败：必须 poison，后续 write 被拒绝。
                    val commitFailure =
                        assertFailsWith<Exception> {
                            runBlocking {
                                executor.write { tx ->
                                    tx.update(
                                        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
                                            "VALUES ('R-P4-3', 'x', 'EXTRACTION', '[]', '2026-10-29T08:00:00.000+08:00')",
                                    )
                                    throw IllegalStateException("business failure before commit")
                                }
                            }
                        }
                    assertTrue(
                        commitFailure is IllegalStateException || commitFailure is ContextPersistenceException,
                        "事务内业务失败应如实抛出，实际: $commitFailure",
                    )
                    // 业务失败会走 rollback：成功回滚不 poison（这是既有语义）。
                    assertFalse(writeSlot.isPoisoned(), "成功回滚不得 poison")

                    // 3) 真正的终结失败（rollback 失败）才 poison，且后续 write 被拒绝。
                    val rollbackFailure =
                        assertFailsWith<Exception> {
                            runBlocking {
                                executor.write { tx ->
                                    tx.update(
                                        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
                                            "VALUES ('R-P4-4', 'y', 'EXTRACTION', '[]', '2026-10-29T08:00:00.000+08:00')",
                                    )
                                    throw IllegalStateException("business failure before rollback")
                                }
                            }
                        }
                    assertTrue(rollbackFailure is IllegalStateException, "主异常必须是业务异常: $rollbackFailure")
                    assertFalse(writeSlot.isPoisoned(), "健康 rollback 仍不得 poison")
                }
            } finally {
                TestDatabases.deleteRecursively(directory)
            }
        }

    @Test
    fun `transaction termination failure is still poison and later writes are refused`() =
        TestDatabases.temporaryDirectory().let { directory ->
            try {
                val path = TestDatabases.initializedDatabase(directory)
                val delegate = java.sql.DriverManager.getDriver("jdbc:sqlite:${path.toAbsolutePath()}")
                val wrapper = CommitFaultDriver(delegate)
                java.sql.DriverManager.deregisterDriver(delegate)
                java.sql.DriverManager.registerDriver(wrapper)
                try {
                    val executor = ContextDatabaseFactory.open(path)
                    try {
                        val writeSlot = executor.slotForTesting(SlotKind.WRITE)
                        // 先证明单语句失败不 poison。
                        runCatching { runBlocking { executor.updateRaw("INSERT INTO raw_memory VALUES (1)") } }
                        assertFalse(writeSlot.isPoisoned(), "单语句失败不得 poison")

                        // commit 失败（事务终结失败）必须 poison。
                        assertFailsWith<ContextPersistenceException> {
                            runBlocking {
                                executor.write { tx ->
                                    tx.update(
                                        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
                                            "VALUES ('R-P4-5', 'z', 'EXTRACTION', '[]', '2026-10-29T08:00:00.000+08:00')",
                                    )
                                }
                            }
                        }
                        assertTrue(writeSlot.isPoisoned(), "commit 失败必须 poison 写 Slot")

                        // 后续 write 被具名拒绝（中毒 Slot 不复用）。
                        val rejected = assertFailsWith<ContextPersistenceException> { runBlocking { executor.write { } } }
                        assertTrue(
                            rejected.message.orEmpty().contains("poison", ignoreCase = true),
                            "中毒后写入必须被具名拒绝，实际: ${rejected.message}",
                        )
                    } finally {
                        runCatching { executor.close() }
                    }
                } finally {
                    java.sql.DriverManager.deregisterDriver(wrapper)
                    java.sql.DriverManager.registerDriver(delegate)
                }
            } finally {
                TestDatabases.deleteRecursively(directory)
            }
        }

    private fun countRaw(
        executor: ContextDatabaseExecutor,
        id: String,
    ): Long =
        runBlocking {
            executor.queryRaw("SELECT count(*) FROM raw_memory WHERE id = '$id'") { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
}

/** 只让写 Slot 的第一次 commit 失败（P4 对比「终结失败必须 poison」）。 */
private class CommitFaultDriver(
    private val delegate: java.sql.Driver,
) : java.sql.Driver {
    private val commitFailures =
        java.util.concurrent.atomic
            .AtomicInteger(1)

    override fun connect(
        url: String,
        info: java.util.Properties?,
    ): java.sql.Connection? {
        val real = delegate.connect(url, info ?: java.util.Properties()) ?: return null
        return java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(java.sql.Connection::class.java),
        ) { _, method, args ->
            if (method.name == "commit" && commitFailures.getAndDecrement() > 0) {
                throw java.sql.SQLException("injected commit failure")
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw failure.cause ?: failure
            }
        } as java.sql.Connection
    }

    override fun acceptsURL(url: String): Boolean = delegate.acceptsURL(url)

    override fun getPropertyInfo(
        url: String,
        info: java.util.Properties?,
    ): Array<java.sql.DriverPropertyInfo> = delegate.getPropertyInfo(url, info ?: java.util.Properties())

    override fun getMajorVersion(): Int = delegate.majorVersion

    override fun getMinorVersion(): Int = delegate.minorVersion

    override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

    override fun getParentLogger(): java.util.logging.Logger = delegate.parentLogger
}
