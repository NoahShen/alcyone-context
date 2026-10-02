package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A06：数据库失败一律映射为 STATE_ERROR，原始异常挂在 cause 上；已关闭的库不再接受任何调用。
 *
 * 用「关闭后再调用」构造真实故障：连接已关，JDBC 每次调用都会失败，不需要额外的故障注入开关。
 */
class StateErrorMappingTest {
    @Test
    fun `repository calls on a closed database fail with STATE_ERROR`() =
        runBlocking {
            val state = VfsStateDatabase.inMemory()
            val nodes = SqliteNodeRepository(state)
            val metadata = SqliteMetadataRepository(state)
            val events = SqliteEventRepository(state)
            state.close()

            val failures =
                listOf(
                    assertThrows(VfsException::class.java) { runBlocking { nodes.findByPath(VfsPath.parse("/notes/a.txt")) } },
                    assertThrows(VfsException::class.java) { runBlocking { nodes.register(testRecord("/notes/a.txt")) } },
                    assertThrows(VfsException::class.java) { runBlocking { metadata.get(testRecord("/notes/a.txt").id) } },
                    assertThrows(VfsException::class.java) { runBlocking { events.append(testEvent(null, "alcyone://notes/a.txt")) } },
                    assertThrows(VfsException::class.java) { runBlocking { SqliteMountRepository(state).list() } },
                )

            failures.forEach { failure ->
                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                // 不吞异常：原始异常保留在 cause 上，便于诊断。
                assertNotNull(failure.cause, "cause 必须保留原始数据库异常")
                assertTrue(failure.cause !is VfsException)
            }
        }

    @Test
    fun `transactions on a closed database fail with STATE_ERROR`() =
        runBlocking {
            val state = VfsStateDatabase.inMemory()
            val uow = SqliteUnitOfWork(state)
            state.close()

            val failure = assertThrows(VfsException::class.java) { runBlocking { uow.inTransaction { } } }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertNotNull(failure.cause)
        }

    @Test
    fun `opening an unusable path fails with STATE_ERROR`() {
        val failure =
            assertThrows(VfsException::class.java) {
                VfsStateDatabase.file(
                    java.nio.file.Files
                        .createTempDirectory("alcyone-vfs-open")
                        .resolve("missing/child.db"),
                )
            }

        assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
        assertNotNull(failure.cause)
    }

    /** 库文件不是 SQLite 数据库：打开阶段失败也映射为 STATE_ERROR，连接随之释放。 */
    @Test
    fun `opening a non-sqlite file fails with STATE_ERROR`() {
        val file =
            java.nio.file.Files
                .createTempFile("alcyone-vfs-broken", ".db")
        try {
            java.nio.file.Files
                .writeString(file, "this is not a database")

            val failure = assertThrows(VfsException::class.java) { VfsStateDatabase.file(file) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertNotNull(failure.cause)
        } finally {
            java.nio.file.Files
                .deleteIfExists(file)
        }
    }

    @Test
    fun `a live database keeps working and reports no error`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val registered = nodes.register(testRecord("/notes/a.txt"))

                assertEquals(registered, nodes.findByPath(registered.path))
                assertNull(SqliteMetadataRepository(state).get(registered.id))
            }
        }

    /** 作用域闸门抛的是 IllegalStateException，不应被错误映射成 STATE_ERROR（事务视图与错误映射的边界）。 */
    @Test
    fun `scope escape stays an IllegalStateException`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)
                var escaped: TransactionScope? = null
                uow.inTransaction { scope -> escaped = scope }
                val leaked = requireNotNull(escaped)

                val failure =
                    assertThrows(
                        IllegalStateException::class.java,
                    ) { runBlocking { leaked.nodes.findByPath(VfsPath.parse("/notes/a.txt")) } }

                assertSame("TransactionScope used outside of inTransaction block", failure.message)
            }
        }

    /** S3：关闭失败同样走统一映射，不把底层异常直接抛给调用方。 */
    @Test
    fun `a failing close is reported as STATE_ERROR`() {
        val state = VfsStateDatabase.inMemory()
        val failing = VfsStateDatabase(state.database, CloseFailingDriver(state.driver))

        val failure = assertThrows(VfsException::class.java) { failing.close() }

        assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
        assertNotNull(failure.cause)
        // 钩子是在真正关闭连接之前抛的，所以底层连接还开着。
        state.close()
    }
}
