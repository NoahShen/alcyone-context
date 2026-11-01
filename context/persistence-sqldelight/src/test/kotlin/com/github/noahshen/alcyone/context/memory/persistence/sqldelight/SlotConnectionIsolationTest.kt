package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A03：写/读 Slot 是不同物理连接，PRAGMA 全程持续生效，读 Slot 真的拒写。 */
class SlotConnectionIsolationTest {
    @Test
    fun `write and read slots hold distinct physical connections`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path, readSlotCount = 2).use { executor ->
                val write = executor.connectionIdentity(SlotKind.WRITE, 0)
                val read0 = executor.connectionIdentity(SlotKind.READ, 0)
                val read1 = executor.connectionIdentity(SlotKind.READ, 1)

                assertNotEquals(write, read0, "写 Slot 与读 Slot 必须是不同连接")
                assertNotEquals(read0, read1, "两个读 Slot 必须各自独立")
                assertNotEquals(write, read1)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `write slot keeps WAL FULL foreign keys and busy timeout across many statements`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                repeat(5) {
                    assertEquals("wal", executor.pragma(SlotKind.WRITE, 0, "journal_mode"), "PRAGMA 必须持续生效")
                    assertEquals("2", executor.pragma(SlotKind.WRITE, 0, "synchronous"), "FULL = 2")
                    assertEquals("1", executor.pragma(SlotKind.WRITE, 0, "foreign_keys"))
                    assertEquals("5000", executor.pragma(SlotKind.WRITE, 0, "busy_timeout"))
                    runBlocking {
                        executor.queryRaw("SELECT count(*) FROM dreaming_checkpoint") { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `read slot is query_only and refuses writes`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                assertEquals("1", executor.pragma(SlotKind.READ, 0, "query_only"), "读连接必须 query_only")
                assertEquals("1", executor.pragma(SlotKind.READ, 1, "query_only"))
                assertEquals("1", executor.pragma(SlotKind.READ, 0, "foreign_keys"))
                assertEquals("5000", executor.pragma(SlotKind.READ, 0, "busy_timeout"))

                try {
                    runBlocking {
                        executor.queryRaw("INSERT INTO dreaming_checkpoint (id, last_processed_turn_id) VALUES (2, 0)") { rs -> rs }
                    }
                    throw AssertionError("expected ContextPersistenceException")
                } catch (failure: ContextPersistenceException) {
                    assertTrue(
                        failure.errorCode == ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                        "读连接写入必须被 SQLite 拒绝为 PERSISTENCE_UNAVAILABLE，实际：${failure.message}",
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `each read slot sees the same committed data`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    executor.updateRaw(
                        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
                            "VALUES ('R-MEM-1', 'x', 'EXTRACTION', '[]', '2026-10-23T18:30:25.123+08:00')",
                    )
                    repeat(4) {
                        val count =
                            executor.queryRaw("SELECT count(*) FROM raw_memory") { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        assertEquals(1L, count)
                    }
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `sql delinght driver is bound to the slot connection`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val observed =
                        executor.read { scope ->
                            scope.query("SELECT 1") { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                    assertEquals(1, observed)
                    assertEquals(
                        1,
                        executor.queryRaw("SELECT 1") { rs ->
                            rs.next()
                            rs.getInt(1)
                        },
                    )
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
