package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * CT05-B2 Raw Memory：不可变插入、批量/精确/分页读取、来源严格往返、约束失败与事务可见性。
 */
class ContextRawMemoryRealDbTest {
    @Test
    fun `insertBatch returns real row counts and round-trips both source variants`() =
        ConversationFixtures.withDatabase { executor ->
            val conversation = MemorySourceRef.ConversationTurnRef("runtime-1", "agent-1", "session-1", "turn-1")
            val rawRef = MemorySourceRef.RawMemoryRef("raw-0")
            val first = B2Fixtures.raw("raw-1", "first content", MemoryOrigin.EXTRACTION, listOf(conversation, rawRef))
            val second = B2Fixtures.raw("raw-2", "second content", MemoryOrigin.DREAMING, emptyList(), B2Fixtures.T0.plusSeconds(1))

            assertEquals(2, B2Fixtures.insertRaw(executor, first, second))

            val repository = RawMemoryRepositoryImpl(executor)
            assertEquals(first, repository.findById("raw-1"))
            assertEquals(second, repository.findById("raw-2"))
        }

    @Test
    fun `sources_json is stored as the documented canonical array`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            runBlocking {
                ContextDatabaseFactory.open(path).use { executor ->
                    B2Fixtures.insertRaw(
                        executor,
                        B2Fixtures.raw(
                            "raw-1",
                            sources =
                                listOf(
                                    MemorySourceRef.ConversationTurnRef("runtime-1", "agent-1", "session-1", "turn-1"),
                                    MemorySourceRef.RawMemoryRef("raw-0"),
                                ),
                        ),
                    )
                }
            }
            val stored =
                TestDatabases.readOnly(
                    path,
                    "SELECT sources_json, generated_by, created_at FROM raw_memory WHERE id = 'raw-1'",
                ) { rs ->
                    rs.next()
                    Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                }
            assertEquals(
                "[{\"type\":\"conversation_turn\",\"runtime_id\":\"runtime-1\",\"agent_id\":\"agent-1\"," +
                    "\"session_id\":\"session-1\",\"session_turn_id\":\"turn-1\"}," +
                    "{\"type\":\"raw_memory\",\"memory_id\":\"raw-0\"}]",
                stored.first,
            )
            assertEquals("EXTRACTION", stored.second)
            assertEquals("2026-10-28T08:00:00.000+08:00", stored.third)
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `findByIds handles empty collections missing ids and duplicates`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1"), B2Fixtures.raw("raw-2"), B2Fixtures.raw("raw-3"))
            val repository = RawMemoryRepositoryImpl(executor)

            assertEquals(emptyList(), repository.findByIds(emptyList()))
            assertEquals(emptyList(), repository.findByIds(listOf("missing")))
            assertNull(repository.findById("missing"))
            assertEquals(setOf("raw-1", "raw-3"), repository.findByIds(listOf("raw-1", "raw-3", "raw-1")).map { it.id }.toSet())
            assertEquals(2, repository.findByIds(listOf("raw-1", "raw-3")).size)
        }

    @Test
    fun `scanPage orders by createdAt then id and honours offset and limit`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(
                executor,
                B2Fixtures.raw("raw-b", createdAt = B2Fixtures.T0),
                B2Fixtures.raw("raw-a", createdAt = B2Fixtures.T0),
                B2Fixtures.raw("raw-c", createdAt = B2Fixtures.T0.plusSeconds(1)),
                B2Fixtures.raw("raw-d", createdAt = B2Fixtures.T0.plusSeconds(2)),
            )
            val repository = RawMemoryRepositoryImpl(executor)

            assertEquals(listOf("raw-a", "raw-b"), repository.scanPage(0, 2).map { it.id })
            assertEquals(listOf("raw-b", "raw-c"), repository.scanPage(1, 2).map { it.id })
            assertEquals(listOf("raw-d"), repository.scanPage(3, 2).map { it.id })
            assertEquals(emptyList(), repository.scanPage(10, 2))
        }

    @Test
    fun `duplicate raw id fails safely and keeps the original row`() =
        ConversationFixtures.withDatabase { executor ->
            val original = B2Fixtures.raw("raw-1", "original content")
            assertEquals(1, B2Fixtures.insertRaw(executor, original))

            assertFailsWith<ContextPersistenceException> {
                B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1", "overwritten content"))
            }

            val repository = RawMemoryRepositoryImpl(executor)
            assertEquals(original, repository.findById("raw-1"))
        }

    @Test
    fun `write rolls back on failure while committed rows survive a reopen`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            runBlocking {
                ContextDatabaseFactory.open(path).use { executor ->
                    assertFailsWith<IllegalStateException> {
                        executor.write { tx ->
                            RawMemoryTxOpsImpl(tx).insertBatch(listOf(B2Fixtures.raw("raw-rollback")))
                            throw IllegalStateException("boom")
                        }
                    }
                    assertNull(RawMemoryRepositoryImpl(executor).findById("raw-rollback"))
                    B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-commit"))
                }
            }
            val ids =
                TestDatabases.readOnly(path, "SELECT id FROM raw_memory ORDER BY id ASC") { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows += rs.getString(1)
                    rows
                }
            assertEquals(listOf("raw-commit"), ids)
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
