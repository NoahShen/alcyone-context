package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CT05-B2 Organized Memory 与来源关联：插入/更新/读取、来源正反查、外键 RESTRICT、
 * 全局水位推进与同一事务内严格递增分配。
 */
class ContextOrganizedMemoryRealDbTest {
    @Test
    fun `insertNew and update return real row counts and persist fields`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = OrganizedMemoryRepositoryImpl(executor)
            val created = B2Fixtures.organized("org-1", "first content", listOf("domain-a", "domain-b"))
            assertEquals(1, B2Fixtures.insertOrganized(executor, created))

            val stored = assertNotNull(repository.findById("org-1"))
            assertEquals("first content", stored.content)
            assertEquals(listOf("domain-a", "domain-b"), stored.domains)
            assertEquals(emptyList(), stored.rawMemoryIds)
            assertEquals(B2Fixtures.T0, stored.createdAt)
            assertEquals(B2Fixtures.T0, stored.updatedAt)

            val updated = created.copy(content = "updated content", domains = listOf("domain-c"), updatedAt = B2Fixtures.T0.plusSeconds(5))
            assertEquals(1, B2Fixtures.updateOrganized(executor, updated))
            val afterUpdate = assertNotNull(repository.findById("org-1"))
            assertEquals("updated content", afterUpdate.content)
            assertEquals(listOf("domain-c"), afterUpdate.domains)
            assertEquals(B2Fixtures.T0.plusSeconds(5), afterUpdate.updatedAt)
            assertEquals(B2Fixtures.T0, afterUpdate.createdAt)

            assertEquals(0, B2Fixtures.updateOrganized(executor, updated.copy(id = "missing")))
        }

    @Test
    fun `findByIds and scanPage return stored rows with deterministic paging`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-b", createdAt = B2Fixtures.T0))
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-a", createdAt = B2Fixtures.T0))
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-c", createdAt = B2Fixtures.T0.plusSeconds(1)))
            val repository = OrganizedMemoryRepositoryImpl(executor)

            assertEquals(emptyList(), repository.findByIds(emptyList()))
            assertEquals(setOf("org-a", "org-c"), repository.findByIds(listOf("org-a", "org-c")).map { it.id }.toSet())
            assertEquals(listOf("org-a", "org-b"), repository.scanPage(0, 2).map { it.id })
            assertEquals(listOf("org-b", "org-c"), repository.scanPage(1, 2).map { it.id })
            assertEquals(emptyList(), repository.scanPage(10, 2))
        }

    @Test
    fun `maxUpdatedAt is null on an empty table and tracks the maximum`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = OrganizedMemoryRepositoryImpl(executor)
            assertNull(repository.maxUpdatedAt())

            B2Fixtures.insertOrganized(
                executor,
                B2Fixtures.organized("org-1", updatedAt = B2Fixtures.T0.plusSeconds(2)),
            )
            B2Fixtures.insertOrganized(
                executor,
                B2Fixtures.organized("org-2", updatedAt = B2Fixtures.T0.plusSeconds(4)),
            )
            assertEquals(B2Fixtures.T0.plusSeconds(4), repository.maxUpdatedAt())

            assertEquals(1, B2Fixtures.advance(executor, "org-1", B2Fixtures.T0.plusSeconds(9)))
            assertEquals(B2Fixtures.T0.plusSeconds(9), repository.maxUpdatedAt())
        }

    @Test
    fun `insertSourceLink returns real row counts and forward reverse queries agree`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1"), B2Fixtures.raw("raw-2"))
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-1"))
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-2"))

            assertEquals(1, B2Fixtures.link(executor, "org-1", "raw-1"))
            assertEquals(1, B2Fixtures.link(executor, "org-1", "raw-2"))
            assertEquals(1, B2Fixtures.link(executor, "org-2", "raw-1"))
            assertFailsWith<ContextPersistenceException> { B2Fixtures.link(executor, "org-1", "raw-1") }

            val repository = OrganizedMemoryRepositoryImpl(executor)
            assertEquals(listOf("raw-1", "raw-2"), repository.findSourcesByOrganized("org-1"))
            assertEquals(listOf("org-1", "org-2"), repository.findOrganizedByRaw("raw-1"))
            assertEquals(listOf("org-1"), repository.findOrganizedByRaw("raw-2"))
            assertEquals(listOf("raw-1", "raw-2"), assertNotNull(repository.findById("org-1")).rawMemoryIds)
            assertEquals(listOf("raw-1"), assertNotNull(repository.findById("org-2")).rawMemoryIds)
        }

    @Test
    fun `foreign keys restrict deleting referenced raw and organized rows`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1"), B2Fixtures.raw("raw-2"), B2Fixtures.raw("raw-3"))
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-1"))
            B2Fixtures.link(executor, "org-1", "raw-1")
            B2Fixtures.enqueue(executor, "raw-2")

            assertFailsWith<ContextPersistenceException> {
                executor.updateRaw("DELETE FROM raw_memory WHERE id = 'raw-1'")
            }
            assertFailsWith<ContextPersistenceException> {
                executor.updateRaw("DELETE FROM raw_memory WHERE id = 'raw-2'")
            }
            assertFailsWith<ContextPersistenceException> {
                executor.updateRaw("DELETE FROM organized_memory WHERE id = 'org-1'")
            }
            assertEquals(3, countRows(executor, "raw_memory"))
            assertEquals(1, countRows(executor, "organized_memory"))

            // 解除关联与 pending 后，同样的 DELETE 才能成功：证明限制来自引用而非其他原因。
            assertEquals(1, executor.updateRaw("DELETE FROM organized_memory_source WHERE organized_memory_id = 'org-1'"))
            assertEquals(1, executor.updateRaw("DELETE FROM organization_pending WHERE raw_memory_id = 'raw-2'"))
            assertEquals(1, executor.updateRaw("DELETE FROM organized_memory WHERE id = 'org-1'"))
            assertEquals(1, executor.updateRaw("DELETE FROM raw_memory WHERE id = 'raw-1'"))
            assertEquals(1, executor.updateRaw("DELETE FROM raw_memory WHERE id = 'raw-2'"))
            assertEquals(1, countRows(executor, "raw_memory"))
        }

    @Test
    fun `advanceUpdatedAt reports one row and the watermark is visible`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-1"))
            val repository = OrganizedMemoryRepositoryImpl(executor)

            assertEquals(1, B2Fixtures.advance(executor, "org-1", B2Fixtures.T0.plusSeconds(1)))
            assertEquals(B2Fixtures.T0.plusSeconds(1), repository.maxUpdatedAt())
            assertEquals(B2Fixtures.T0.plusSeconds(1), assertNotNull(repository.findById("org-1")).updatedAt)

            assertEquals(0, B2Fixtures.advance(executor, "missing", B2Fixtures.T0.plusSeconds(2)))
            assertEquals(B2Fixtures.T0.plusSeconds(1), repository.maxUpdatedAt())
        }

    @Test
    fun `multiple advances inside one write transaction are strictly increasing and visible`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-1", updatedAt = B2Fixtures.T0))
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-2", updatedAt = B2Fixtures.T0))

            val (firstNext, secondNext) =
                executor.write { tx ->
                    val ops = OrganizedMemoryTxOpsImpl(tx)
                    val first = (ops.maxUpdatedAt() ?: B2Fixtures.T0).plusMillis(1)
                    assertEquals(1, ops.advanceUpdatedAt("org-1", first))
                    val second = (ops.maxUpdatedAt() ?: first).plusMillis(1)
                    assertEquals(1, ops.advanceUpdatedAt("org-2", second))
                    first to second
                }

            assertEquals(B2Fixtures.T0.plusMillis(1), firstNext)
            assertEquals(B2Fixtures.T0.plusMillis(2), secondNext)

            val repository = OrganizedMemoryRepositoryImpl(executor)
            assertEquals(secondNext, repository.maxUpdatedAt())
            assertEquals(firstNext, assertNotNull(repository.findById("org-1")).updatedAt)
            assertEquals(secondNext, assertNotNull(repository.findById("org-2")).updatedAt)
        }

    @Test
    fun `domains_json is stored as a JSON string array`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            runBlocking {
                ContextDatabaseFactory.open(path).use { executor ->
                    B2Fixtures.insertOrganized(
                        executor,
                        B2Fixtures.organized("org-1", domains = listOf("医疗", "中文", "emoji 🚀")),
                    )
                }
            }
            val stored =
                TestDatabases.readOnly(path, "SELECT domains_json FROM organized_memory WHERE id = 'org-1'") { rs ->
                    rs.next()
                    rs.getString(1)
                }
            assertEquals("[\"医疗\",\"中文\",\"emoji 🚀\"]", stored)
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    private suspend fun countRows(
        executor: ContextDatabaseExecutor,
        table: String,
    ): Long =
        executor.queryRaw("SELECT count(*) FROM $table") { rs ->
            rs.next()
            rs.getLong(1)
        }
}
