package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A05：WAL 快照隔离——写事务未提交时读端看旧值，提交后才可见。 */
class SnapshotIsolationTest {
    private val insertRaw =
        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
            "VALUES ('R-MEM-1', 'x', 'EXTRACTION', '[]', '2026-10-23T18:30:25.123+08:00')"

    @Test
    fun `reader sees old value while an uncommitted write transaction is open`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val insideWrite = CompletableDeferred<Unit>()
                    val releaseWrite = CompletableDeferred<Unit>()

                    val writer =
                        async {
                            executor.write { tx ->
                                tx.update(insertRaw)
                                insideWrite.complete(Unit)
                                // 用 runBlocking 在写事务内等待，模拟「提交前」这段窗口。
                                runBlocking { releaseWrite.await() }
                            }
                        }
                    insideWrite.await()

                    // 写事务尚未提交：读 Slot 必须看到旧值（0 行）。
                    assertEquals(0L, countRaw(executor), "未提交的写入对读端不可见")

                    releaseWrite.complete(Unit)
                    writer.await()
                    assertEquals(1L, countRaw(executor), "提交后读端可见")
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `read context provides a stable snapshot across several queries`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    executor.updateRaw(insertRaw)

                    // 受控屏障：读事务先做第一次读（快照建立），随后外部写提交，再读一次。
                    val firstReadDone = java.util.concurrent.CountDownLatch(1)
                    val writeDone = java.util.concurrent.CountDownLatch(1)
                    val pool =
                        java.util.concurrent.Executors
                            .newSingleThreadExecutor()
                    pool.submit {
                        firstReadDone.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        runBlocking { executor.updateRaw(insertRaw.replace("R-MEM-1", "R-MEM-2")) }
                        writeDone.countDown()
                    }
                    val observed =
                        try {
                            executor.read { scope ->
                                val first =
                                    scope.query("SELECT count(*) FROM raw_memory") { rs ->
                                        rs.next()
                                        rs.getLong(1)
                                    }
                                firstReadDone.countDown()
                                check(writeDone.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "外部写未完成" }
                                val second =
                                    scope.query("SELECT count(*) FROM raw_memory") { rs ->
                                        rs.next()
                                        rs.getLong(1)
                                    }
                                first to second
                            }
                        } finally {
                            pool.shutdownNow()
                        }
                    assertEquals(1L to 1L, observed, "同一 ReadContext 必须看到稳定快照")
                    assertEquals(2L, countRaw(executor), "写 Slot 的提交不受读快照影响")
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `two read slots are independent and can be used concurrently`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path, readSlotCount = 2).use { executor ->
                runBlocking {
                    val first = CompletableDeferred<Unit>()
                    val second = CompletableDeferred<Unit>()
                    val a =
                        async {
                            executor.read { scope ->
                                scope.query("SELECT 1") { rs ->
                                    rs.next()
                                    rs.getInt(1)
                                }
                                first.complete(Unit)
                                runBlocking { second.await() }
                                scope.query("SELECT 2") { rs ->
                                    rs.next()
                                    rs.getInt(1)
                                }
                            }
                        }
                    first.await()
                    // 第一个读 Slot 仍被占用时，第二个读请求必须能并发完成。
                    val b =
                        async {
                            executor.read { scope ->
                                scope.query("SELECT 3") { rs ->
                                    rs.next()
                                    rs.getInt(1)
                                }
                            }
                        }
                    assertEquals(3, b.await(), "第二个读 Slot 应可并发使用")
                    second.complete(Unit)
                    assertEquals(2, a.await())
                    assertTrue(executor.readSlotCount() == 2)
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
