package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT05-B2 Organization Pending：入队/出队真实行数与幂等、存在性、按 Raw 创建时间取下一条、
 * 外键约束。
 */
class ContextOrganizationPendingRealDbTest {
    @Test
    fun `enqueue is idempotent and dequeue reports real row counts`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1"))
            val repository = OrganizationPendingRepositoryImpl(executor)

            assertFalse(repository.exists("raw-1"))
            assertFalse(repository.hasAnyPending())

            assertEquals(1, B2Fixtures.enqueue(executor, "raw-1"))
            assertEquals(0, B2Fixtures.enqueue(executor, "raw-1"))
            assertTrue(repository.exists("raw-1"))
            assertTrue(repository.hasAnyPending())

            assertEquals(1, B2Fixtures.dequeue(executor, "raw-1"))
            assertEquals(0, B2Fixtures.dequeue(executor, "raw-1"))
            assertFalse(repository.exists("raw-1"))
            assertFalse(repository.hasAnyPending())
        }

    @Test
    fun `nextByRawCreatedAt picks by raw createdAt then id regardless of enqueue order`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(
                executor,
                B2Fixtures.raw("raw-b", createdAt = B2Fixtures.T0),
                B2Fixtures.raw("raw-c", createdAt = B2Fixtures.T0),
                B2Fixtures.raw("raw-a", createdAt = B2Fixtures.T0.plusSeconds(2)),
            )
            // 故意按非时间顺序入队：选择必须由 raw_memory.created_at, id 决定。
            B2Fixtures.enqueue(executor, "raw-a")
            B2Fixtures.enqueue(executor, "raw-c")
            B2Fixtures.enqueue(executor, "raw-b")
            val repository = OrganizationPendingRepositoryImpl(executor)

            assertEquals("raw-b", repository.nextByRawCreatedAt())
            B2Fixtures.dequeue(executor, "raw-b")
            assertEquals("raw-c", repository.nextByRawCreatedAt())
            B2Fixtures.dequeue(executor, "raw-c")
            assertEquals("raw-a", repository.nextByRawCreatedAt())
            B2Fixtures.dequeue(executor, "raw-a")
            assertNull(repository.nextByRawCreatedAt())
        }

    @Test
    fun `listAllByRawCreatedAt returns an empty list when no raw is pending`() =
        ConversationFixtures.withDatabase { executor ->
            assertEquals(emptyList(), OrganizationPendingRepositoryImpl(executor).listAllByRawCreatedAt())
        }

    @Test
    fun `listAllByRawCreatedAt returns every pending id in stable raw order without truncation`() =
        ConversationFixtures.withDatabase { executor ->
            val late = B2Fixtures.raw("raw-z-late", createdAt = B2Fixtures.T0.plusSeconds(10))
            val sameA = B2Fixtures.raw("raw-a", createdAt = B2Fixtures.T0)
            val sameB = B2Fixtures.raw("raw-b", createdAt = B2Fixtures.T0)
            val bulk =
                (0 until 30).map { index ->
                    B2Fixtures.raw("raw-bulk-${index.toString().padStart(2, '0')}", createdAt = B2Fixtures.T0.plusMillis(index + 1L))
                }
            B2Fixtures.insertRaw(executor, late, sameA, sameB, *bulk.toTypedArray())

            // 入队顺序与查询顺序相反，确保排序只由 raw.created_at, raw.id 决定。
            B2Fixtures.enqueue(executor, late.id)
            bulk.asReversed().forEach { raw -> B2Fixtures.enqueue(executor, raw.id) }
            B2Fixtures.enqueue(executor, sameB.id)
            B2Fixtures.enqueue(executor, sameA.id)

            val expected = listOf(sameA.id, sameB.id) + bulk.map { it.id } + late.id
            assertEquals(expected, OrganizationPendingRepositoryImpl(executor).listAllByRawCreatedAt())
        }

    @Test
    fun `enqueue of a missing raw fails the foreign key`() =
        ConversationFixtures.withDatabase { executor ->
            assertFailsWith<ContextPersistenceException> { B2Fixtures.enqueue(executor, "missing") }
            assertFalse(OrganizationPendingRepositoryImpl(executor).exists("missing"))
            assertFalse(OrganizationPendingRepositoryImpl(executor).hasAnyPending())
        }
}
