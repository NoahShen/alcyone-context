package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B3 装配集成：多 Repository 原子提交/回滚、读快照、并发幂等与 CAS、取消后健康复用、重开一致。
 */
class ContextDatabaseAssemblyIntegrationTest {
    @Test
    fun `write commits all five aggregates atomically`(): Unit =
        TestDatabase().use { fixture ->
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                runBlocking {
                    database.write { tx ->
                        tx.conversations.insertTurn(
                            B3Fixtures.SESSION,
                            "turn-1",
                            B3Fixtures.T0,
                            B3Fixtures.T0,
                            B3Fixtures.messages("hello"),
                        )
                        tx.rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-1", sources = listOf(B3Fixtures.conversationRef()))))
                        tx.organizedMemories.insertNew(B3Fixtures.organized("org-1"))
                        assertEquals(1, tx.organizedMemories.insertSourceLink("org-1", "raw-1"))
                        assertEquals(1, tx.pending.enqueue("raw-1"))
                        assertEquals(1, tx.dreaming.casUpdateCheckpoint(0, 7))
                    }
                }
            }

            assertEquals(1L, fixture.count("conversation_turn"))
            assertEquals(1L, fixture.count("raw_memory"))
            assertEquals(1L, fixture.count("organized_memory"))
            assertEquals(1L, fixture.count("organized_memory_source"))
            assertEquals(1L, fixture.count("organization_pending"))
            assertEquals(7L, fixture.scalarLong("SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id = 1"))
        }

    @Test
    fun `write rolls back every aggregate when the callback throws`(): Unit =
        TestDatabase().use { fixture ->
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                runBlocking {
                    assertFailsWith<IllegalStateException> {
                        database.write { tx ->
                            tx.conversations.insertTurn(
                                B3Fixtures.SESSION,
                                "turn-1",
                                B3Fixtures.T0,
                                B3Fixtures.T0,
                                B3Fixtures.messages("hello"),
                            )
                            tx.rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-1")))
                            tx.organizedMemories.insertNew(B3Fixtures.organized("org-1"))
                            tx.organizedMemories.insertSourceLink("org-1", "raw-1")
                            tx.pending.enqueue("raw-1")
                            tx.dreaming.casUpdateCheckpoint(0, 7)
                            throw IllegalStateException("boom")
                        }
                    }
                    assertEquals(0, database.read { rc -> rc.conversations.countPending(B3Fixtures.SESSION) })
                    assertEquals(0, database.read { rc -> rc.rawMemories.scanPage(0, 10).size })
                    assertEquals(0, database.read { rc -> rc.organizedMemories.scanPage(0, 10).size })
                    assertFalse(database.read { rc -> rc.pending.hasAnyPending() })
                    assertEquals(0L, database.read { rc -> rc.dreaming.readCheckpoint() })
                }
            }

            assertEquals(0L, fixture.count("conversation_turn"))
            assertEquals(0L, fixture.count("raw_memory"))
            assertEquals(0L, fixture.count("organized_memory"))
            assertEquals(0L, fixture.count("organization_pending"))
            assertEquals(0L, fixture.scalarLong("SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id = 1"))
        }

    @Test
    fun `read context keeps one snapshot while an external write commits`(): Unit =
        TestDatabase().use { fixture ->
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                runBlocking {
                    database.write { tx -> tx.rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-existing"))) }
                }

                val snapshotEstablished = CountDownLatch(1)
                val writeCommitted = CountDownLatch(1)
                val writerFailure = AtomicReference<Throwable?>(null)
                val writer =
                    Thread {
                        try {
                            assertTrue(snapshotEstablished.await(10, TimeUnit.SECONDS), "读快照必须先建立")
                            runBlocking {
                                database.write { tx -> tx.rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-new"))) }
                            }
                        } catch (failure: Throwable) {
                            writerFailure.set(failure)
                        } finally {
                            writeCommitted.countDown()
                        }
                    }
                writer.start()

                var snapshot: Pair<RawMemory?, RawMemory?>? = null
                try {
                    snapshot =
                        runBlocking {
                            database.read { rc ->
                                val before = rc.rawMemories.findById("raw-new")
                                snapshotEstablished.countDown()
                                assertTrue(writeCommitted.await(10, TimeUnit.SECONDS), "外部写必须在读回调内提交")
                                val after = rc.rawMemories.findById("raw-new")
                                before to after
                            }
                        }
                } finally {
                    // 无论读取断言成功或失败，都放行 writer 并等待其有界退出，避免线程泄漏或互相等待。
                    snapshotEstablished.countDown()
                    writeCommitted.countDown()
                    writer.join(10_000)
                    assertFalse(writer.isAlive, "后台写线程必须在 10s 内退出")
                }
                assertNull(writerFailure.get(), "外部写不得失败: ${writerFailure.get()}")
                val (before, after) = assertNotNull(snapshot, "读回调必须返回快照结果")
                assertNull(before)
                assertNull(after, "同一 read 回调必须保持同一快照")

                val visible = runBlocking { database.read { rc -> rc.rawMemories.findById("raw-new") } }
                assertNotNull(visible, "回调返回后的新读取必须看到已提交数据")
            }
        }

    @Test
    fun `concurrent identical conversation inserts yield one CREATED and one DUPLICATE`(): Unit =
        TestDatabase().use { fixture ->
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val start = CountDownLatch(1)
                    val attempts = listOf(B3Fixtures.T0, B3Fixtures.T0.plusSeconds(3600))
                    val futures =
                        attempts.map { createdAt ->
                            createdAt to
                                pool.submit<IngestResult> {
                                    start.await()
                                    runBlocking {
                                        database.write { tx ->
                                            tx.conversations.insertTurn(
                                                B3Fixtures.SESSION,
                                                "turn-1",
                                                B3Fixtures.T0,
                                                createdAt,
                                                B3Fixtures.messages("same"),
                                            )
                                        }
                                    }
                                }
                        }
                    start.countDown()
                    val results = futures.map { (createdAt, future) -> createdAt to future.get(10, TimeUnit.SECONDS) }

                    assertEquals(setOf(IngestStatus.CREATED, IngestStatus.DUPLICATE), results.map { it.second.status }.toSet())
                    val id = results.first().second.id
                    assertTrue(results.all { it.second.id == id }, "幂等重放必须返回同一 id")
                    val winner = results.first { it.second.status == IngestStatus.CREATED }
                    val stored = runBlocking { database.read { rc -> rc.conversations.getById(id) } }
                    assertEquals(winner.first, assertNotNull(stored).createdAt, "created_at 不得被重放刷新")
                    assertEquals(1L, fixture.count("conversation_turn"))
                } finally {
                    pool.shutdownNow()
                }
            }
        }

    @Test
    fun `concurrent checkpoint CAS only advances once`(): Unit =
        TestDatabase().use { fixture ->
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val start = CountDownLatch(1)
                    val futures =
                        listOf(1L, 2L).map { next ->
                            pool.submit<Int> {
                                start.await()
                                runBlocking { database.write { tx -> tx.dreaming.casUpdateCheckpoint(0, next) } }
                            }
                        }
                    start.countDown()
                    val results = futures.map { it.get(10, TimeUnit.SECONDS) }

                    assertEquals(1, results.count { it == 1 }, "只有一个 CAS 能成功")
                    assertEquals(1, results.count { it == 0 }, "另一个必须返回 0")
                    val value = runBlocking { database.read { rc -> rc.dreaming.readCheckpoint() } }
                    assertTrue(value == 1L || value == 2L, "水位只能前进一次，实际: $value")
                } finally {
                    pool.shutdownNow()
                }
            }
        }

    @Test
    fun `write cancellation propagates rolls back and keeps the write slot reusable`() =
        withDatabase { database, _, _ ->
            assertFailsWith<CancellationException> {
                database.write { tx ->
                    tx.rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-cancelled")))
                    throw CancellationException("cancel")
                }
            }
            assertNull(database.read { rc -> rc.rawMemories.findById("raw-cancelled") }, "取消的写必须回滚")

            database.write { tx -> tx.rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-after"))) }
            assertNotNull(database.read { rc -> rc.rawMemories.findById("raw-after") }, "取消后写连接必须仍可用")
        }

    @Test
    fun `read cancellation propagates and does not poison the read slots`() =
        withDatabase { database, _, _ ->
            assertFailsWith<CancellationException> {
                database.read { throw CancellationException("cancel") }
            }
            assertFalse(database.read { rc -> rc.pending.hasAnyPending() })
            assertEquals(0L, database.read { rc -> rc.dreaming.readCheckpoint() })

            database.write { tx -> tx.rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-after-read-cancel"))) }
            assertNotNull(database.read { rc -> rc.rawMemories.findById("raw-after-read-cancel") })
        }

    @Test
    fun `reopening the database preserves all five aggregates`(): Unit =
        TestDatabase().use { fixture ->
            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                runBlocking {
                    database.write { tx ->
                        tx.conversations.insertTurn(
                            B3Fixtures.SESSION,
                            "turn-1",
                            B3Fixtures.T0,
                            B3Fixtures.T0,
                            B3Fixtures.messages("hello", "world"),
                        )
                        tx.rawMemories.insertBatch(
                            listOf(B3Fixtures.raw("raw-1", sources = listOf(B3Fixtures.conversationRef()))),
                        )
                        tx.organizedMemories.insertNew(
                            B3Fixtures.organized("org-1", domains = listOf("d1", "d2"), updatedAt = B3Fixtures.T0.plusSeconds(3)),
                        )
                        tx.organizedMemories.insertSourceLink("org-1", "raw-1")
                        tx.pending.enqueue("raw-1")
                        tx.dreaming.casUpdateCheckpoint(0, 11)
                    }
                }
            }

            fixture.open().use { executor ->
                val database = ContextDatabaseImpl(executor)
                runBlocking {
                    val turn = assertNotNull(database.read { rc -> rc.conversations.findByBusinessKey(B3Fixtures.SESSION, "turn-1") })
                    assertEquals(B3Fixtures.T0, turn.createdAt)
                    assertEquals(B3Fixtures.messages("hello", "world"), turn.messages)
                    assertEquals(
                        listOf(B3Fixtures.conversationRef()),
                        assertNotNull(
                            database.read { rc ->
                                rc.rawMemories.findById("raw-1")
                            },
                        ).sources,
                    )
                    val organized = assertNotNull(database.read { rc -> rc.organizedMemories.findById("org-1") })
                    assertEquals(listOf("d1", "d2"), organized.domains)
                    assertEquals(listOf("raw-1"), organized.rawMemoryIds)
                    assertEquals(B3Fixtures.T0.plusSeconds(3), organized.updatedAt)
                    assertTrue(database.read { rc -> rc.pending.exists("raw-1") })
                    assertEquals(11L, database.read { rc -> rc.dreaming.readCheckpoint() })
                }
            }
        }
}
