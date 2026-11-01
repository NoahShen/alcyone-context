package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CM1 全面复查 P3：`updated_at` 全局水位是**调用方契约**。
 *
 * 调用方必须在同一写事务内 `maxUpdatedAt` → 分配 `next > max` → `advanceUpdatedAt`；
 * 本层不加条件更新。这里只回归「合法使用」的路径（多行递增、MERGE、隔离、影响行数），
 * 不把契约违规写成已防护。
 */
class WatermarkContractTest {
    private val t0: Instant = Instant.parse("2026-10-29T00:00:00.000Z")

    @Test
    fun `legal allocation across multiple rows in one write transaction is strictly increasing`() =
        withExecutor { executor ->
            insertOrganized(executor, organized("org-1", updatedAt = t0))
            insertOrganized(executor, organized("org-2", updatedAt = t0))
            insertOrganized(executor, organized("org-3", updatedAt = t0))

            val allocated =
                runBlocking {
                    executor.write { tx ->
                        val ops = OrganizedMemoryTxOpsImpl(tx)
                        val assigned = mutableListOf<Instant>()
                        listOf("org-1", "org-2", "org-3").forEach { id ->
                            // 契约：每次都在同一事务内先读全局 MAX，再分配严格更大值。
                            val current = ops.maxUpdatedAt() ?: t0
                            val next = current.plusMillis(1)
                            assertEquals(1, ops.advanceUpdatedAt(id, next), "合法分配必须命中 1 行")
                            assigned += next
                        }
                        assigned
                    }
                }

            assertEquals(3, allocated.size)
            assertEquals(allocated.sorted(), allocated, "同一事务内分配必须严格递增")
            assertEquals(allocated.last(), allocated.maxOf { it })

            val repository = OrganizedMemoryRepositoryImpl(executor)
            assertEquals(allocated.last(), repository.maxUpdatedAt(), "全局 MAX 必须是最后分配值")
            listOf("org-1", "org-2", "org-3").forEachIndexed { index, id ->
                assertEquals(allocated[index], assertNotNull(repository.findById(id)).updatedAt)
            }
        }

    @Test
    fun `merge style update advances the watermark in the same transaction`() =
        withExecutor { executor ->
            insertOrganized(executor, organized("org-1", updatedAt = t0))
            // 来源关联受外键约束：先准备一条 Raw。
            runBlocking {
                executor.write { tx ->
                    assertEquals(
                        1,
                        RawMemoryTxOpsImpl(tx).insertBatch(
                            listOf(RawMemory("raw-1", "content-raw-1", MemoryOrigin.EXTRACTION, emptyList(), t0)),
                        ),
                    )
                }
            }

            val next =
                runBlocking {
                    executor.write { tx ->
                        val ops = OrganizedMemoryTxOpsImpl(tx)
                        val current = ops.maxUpdatedAt() ?: t0
                        val assigned = current.plusMillis(5)
                        // MERGE 路径：更新正文 / domains / updated_at，并补一条来源关联。
                        val merged =
                            organized("org-1", content = "merged content", domains = listOf("d1", "d2"), updatedAt = assigned)
                        assertEquals(1, ops.update(merged), "MERGE 更新必须命中 1 行")
                        assertEquals(1, ops.advanceUpdatedAt("org-1", assigned), "MERGE 水位推进必须命中 1 行")
                        assertEquals(1, ops.insertSourceLink("org-1", "raw-1"))
                        assigned
                    }
                }

            val repository = OrganizedMemoryRepositoryImpl(executor)
            assertEquals(next, repository.maxUpdatedAt(), "MERGE 后全局水位必须是新分配值")
            val stored = assertNotNull(repository.findById("org-1"))
            assertEquals("merged content", stored.content)
            assertEquals(listOf("d1", "d2"), stored.domains)
            assertEquals(next, stored.updatedAt)
            assertEquals(listOf("raw-1"), repository.findSourcesByOrganized("org-1"))
        }

    @Test
    fun `advancing one row does not change other rows and the global max follows the target`() =
        withExecutor { executor ->
            insertOrganized(executor, organized("org-1", updatedAt = t0))
            insertOrganized(executor, organized("org-2", updatedAt = t0.plusSeconds(3)))

            val next = t0.plusSeconds(10)
            runBlocking {
                executor.write { tx ->
                    assertEquals(1, OrganizedMemoryTxOpsImpl(tx).advanceUpdatedAt("org-1", next))
                }
            }

            val repository = OrganizedMemoryRepositoryImpl(executor)
            assertEquals(next, repository.maxUpdatedAt())
            assertEquals(next, assertNotNull(repository.findById("org-1")).updatedAt)
            assertEquals(t0.plusSeconds(3), assertNotNull(repository.findById("org-2")).updatedAt, "非目标行不得被改动")
        }

    @Test
    fun `advance on a missing row reports zero without touching the watermark`() =
        withExecutor { executor ->
            insertOrganized(executor, organized("org-1", updatedAt = t0))
            val repository = OrganizedMemoryRepositoryImpl(executor)

            val affected =
                runBlocking {
                    executor.write { tx ->
                        OrganizedMemoryTxOpsImpl(tx).advanceUpdatedAt("missing", t0.plusSeconds(30))
                    }
                }
            assertEquals(0, affected, "不存在的行必须返回真实影响行数 0")
            assertEquals(t0, repository.maxUpdatedAt(), "缺失行的推进不得改变全局水位")
        }

    @Test
    fun `watermarks are isolated between separate databases`() =
        withExecutor { first ->
            withExecutor { second ->
                insertOrganized(first, organized("org-1", updatedAt = t0))
                insertOrganized(second, organized("org-1", updatedAt = t0))

                val firstNext = t0.plusSeconds(7)
                runBlocking {
                    first.write { tx -> assertEquals(1, OrganizedMemoryTxOpsImpl(tx).advanceUpdatedAt("org-1", firstNext)) }
                }

                assertEquals(firstNext, OrganizedMemoryRepositoryImpl(first).maxUpdatedAt())
                assertEquals(t0, OrganizedMemoryRepositoryImpl(second).maxUpdatedAt(), "另一个库的水位不得受影响")
            }
        }

    // ------------------------------------------------------------------ 夹具

    private fun withExecutor(block: suspend (ContextDatabaseExecutor) -> Unit) {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor -> runBlocking { block(executor) } }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    private fun insertOrganized(
        executor: ContextDatabaseExecutor,
        memory: OrganizedMemory,
    ) {
        runBlocking { executor.write { tx -> assertEquals(1, OrganizedMemoryTxOpsImpl(tx).insertNew(memory)) } }
    }

    private fun organized(
        id: String,
        content: String = "content-$id",
        domains: List<String> = listOf("domain-a"),
        updatedAt: Instant = t0,
    ): OrganizedMemory = OrganizedMemory(id, content, domains, emptyList(), t0, updatedAt)
}
