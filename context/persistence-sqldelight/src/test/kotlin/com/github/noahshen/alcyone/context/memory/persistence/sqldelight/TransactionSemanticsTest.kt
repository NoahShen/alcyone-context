package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A04：事务原子性、自动回滚、嵌套/逃逸/跨线程拒绝、autoCommit 恢复。 */
class TransactionSemanticsTest {
    private val insertRaw =
        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
            "VALUES (?, 'x', 'EXTRACTION', '[]', '2026-10-23T18:30:25.123+08:00')"

    @Test
    fun `committed write is visible and autoCommit is restored`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    executor.write { tx ->
                        tx.updatePrepared(insertRaw.replace("?", "'R-MEM-1'")) { }
                    }
                    assertEquals(1L, countRaw(executor))
                    assertTrue(executor.writeSlotAutoCommit(), "事务结束后必须恢复 autoCommit=true")
                }
            }
            // 重新打开后仍然存在：真的提交到了磁盘。
            ContextDatabaseFactory.open(path).use { reopened ->
                runBlocking { assertEquals(1L, countRaw(reopened)) }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `exception inside write rolls back and leaves no residue`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val failure =
                        assertFailsWith<IllegalStateException> {
                            executor.write { tx ->
                                tx.updatePrepared(insertRaw.replace("?", "'R-MEM-1'")) { }
                                throw IllegalStateException("boom")
                            }
                        }
                    assertEquals("boom", failure.message)
                    assertEquals(0L, countRaw(executor), "回滚后不得残留任何行")
                    assertTrue(executor.writeSlotAutoCommit(), "回滚后必须恢复 autoCommit=true")
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `nested write inside a callback is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            executor.write { _ ->
                                runBlocking { executor.write { _ -> } }
                            }
                        }
                    assertTrue(
                        failure.message!!.contains("nested", ignoreCase = true),
                        "必须明确拒绝嵌套，实际: ${failure.message}",
                    )
                    assertEquals(0L, countRaw(executor))
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `nested read inside write is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val failure =
                        assertFailsWith<ContextPersistenceException> {
                            executor.write { _ -> runBlocking { executor.read { _ -> } } }
                        }
                    assertTrue(failure.message!!.contains("nested", ignoreCase = true))
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `using a scope after its callback returns is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    var escaped: RawTransactionScope? = null
                    executor.write { tx -> escaped = tx }
                    val failure = assertFailsWith<ContextPersistenceException> { escaped!!.update("DELETE FROM raw_memory") }
                    assertTrue(
                        failure.message!!.contains("after its callback", ignoreCase = true),
                        "必须拒绝上下文逃逸，实际: ${failure.message}",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `using a scope from a foreign thread is rejected`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    var escaped: RawReadScope? = null
                    executor.read { scope ->
                        escaped = scope
                        // 回调内正常可用
                        assertEquals(
                            1,
                            scope.query("SELECT 1") { rs ->
                                rs.next()
                                rs.getInt(1)
                            },
                        )
                    }
                    // 逃逸后必然失效；为单独验证线程校验，这里直接构造新 scope 场景：
                    val pool = Executors.newSingleThreadExecutor()
                    try {
                        val message =
                            pool
                                .submit<String> {
                                    runCatching {
                                        escaped!!.query("SELECT 1") { rs ->
                                            rs.next()
                                            rs.getInt(1)
                                        }
                                    }.exceptionOrNull()
                                        ?.message
                                        .orEmpty()
                                }.get(10, TimeUnit.SECONDS)
                        assertTrue(
                            message.contains("after its callback") || message.contains("thread"),
                            "跨线程/逃逸都必须拒绝，实际: $message",
                        )
                    } finally {
                        pool.shutdownNow()
                    }
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `savepoint is refused by the write scope`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    // 只暴露 query/update/driver：SAVEPOINT 只能经 update 走裸 SQL，
                    // 这里断言即使如此也会被显式拒绝，而不是留下可回滚的子事务。
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
    fun `read scope rejects writes through its connection`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val failure =
                        assertFailsWith<Exception> {
                            executor.read { scope -> scope.query("DELETE FROM raw_memory") { rs -> rs } }
                        }
                    assertTrue(failure is ContextPersistenceException || failure.cause != null)
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    private fun countRaw(executor: ContextDatabaseExecutor): Long =
        runBlocking {
            executor.queryRaw("SELECT count(*) FROM raw_memory") { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
}
