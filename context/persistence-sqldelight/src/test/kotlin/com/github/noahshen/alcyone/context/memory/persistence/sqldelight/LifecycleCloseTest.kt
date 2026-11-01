package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A06：关闭幂等、等待在途阻塞 JDBC 退出、取消不抢关、关闭后拒绝。 */
class LifecycleCloseTest {
    private val insertRaw =
        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
            "VALUES (?, 'x', 'EXTRACTION', '[]', '2026-10-23T18:30:25.123+08:00')"

    @Test
    fun `concurrent close calls share one completion and release once`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val executor = ContextDatabaseFactory.open(path)
            runBlocking {
                val outcomes = CopyOnWriteArrayList<Throwable>()
                (1..8)
                    .map { async { runCatching { executor.closeAsync() }.exceptionOrNull()?.let { outcomes += it } } }
                    .awaitAll()
                assertTrue(outcomes.isEmpty(), "并发关闭不应抛错: $outcomes")
            }
            // 再同步关闭一次也必须安全（幂等）。
            executor.close()
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `close waits for a blocked in-flight statement before releasing the connection`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val executor = ContextDatabaseFactory.open(path)
            runBlocking {
                val insideStatement = CompletableDeferred<Unit>()
                val releaseStatement = CountDownLatch(1)
                val statementFinished = CompletableDeferred<Unit>()

                val worker =
                    async {
                        executor.write { tx ->
                            tx.updatePrepared(insertRaw.replace("?", "'R-MEM-1'")) { }
                            insideStatement.complete(Unit)
                            // 阻塞在途 JDBC：写事务停在提交之前。
                            releaseStatement.await(30, TimeUnit.SECONDS)
                            tx.updatePrepared(insertRaw.replace("?", "'R-MEM-2'")) { }
                            statementFinished.complete(Unit)
                        }
                    }
                insideStatement.await()

                val closeStarted = CompletableDeferred<Unit>()
                val closeDone = CompletableDeferred<Unit>()
                val closer =
                    async {
                        closeStarted.complete(Unit)
                        executor.closeAsync()
                        closeDone.complete(Unit)
                    }
                closeStarted.await()

                // 关闭必须还在等：在途 JDBC 未退出前，关闭不能完成。
                assertTrue(!closeDone.isCompleted, "close 必须等待阻塞在途操作退出")
                assertTrue(!statementFinished.isCompleted)

                releaseStatement.countDown()
                worker.await()
                closer.await()
                assertTrue(closeDone.isCompleted, "在途操作退出后 close 必须完成")
                assertTrue(statementFinished.isCompleted)

                // 连接确实已释放：关闭后的请求得到生命周期错误，而不是 connection closed。
                val failure = assertFailsWith<ContextLifecycleException> { executor.queryRaw("SELECT 1") { rs -> rs } }
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `write cancelled before commit rolls back and keeps the connection usable`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val executor = ContextDatabaseFactory.open(path)
            runBlocking {
                val cancelled =
                    async {
                        // 只取消本次写事务的调用方（async 自己的 Job），不动测试主协程。
                        val callerJob = currentCoroutineContext()[Job]!!
                        executor.write { tx ->
                            tx.updatePrepared(insertRaw.replace("?", "'R-MEM-1'")) { }
                            // 在回调内部取消调用方：回调不可挂起，取消在提交前才被观察到。
                            callerJob.cancel(CancellationException("caller cancelled mid-transaction"))
                        }
                    }
                assertFailsWith<CancellationException> { cancelled.await() }

                // 取消不得抢关底层连接：后续请求仍能正常服务，且未提交的数据不残留。
                assertEquals(
                    0L,
                    executor.queryRaw("SELECT count(*) FROM raw_memory") { rs ->
                        rs.next()
                        rs.getLong(1)
                    },
                    "提交前被取消的事务必须回滚",
                )
            }
            executor.close()
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `requests after close are rejected with CLOSED`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val executor = ContextDatabaseFactory.open(path)
            runBlocking { executor.closeAsync() }

            val readFailure = assertFailsWith<ContextLifecycleException> { runBlocking { executor.read { } } }
            assertEquals(ContextLifecycleErrorCode.CLOSED, readFailure.errorCode)
            val writeFailure = assertFailsWith<ContextLifecycleException> { runBlocking { executor.write { } } }
            assertEquals(ContextLifecycleErrorCode.CLOSED, writeFailure.errorCode)
            val updateFailure =
                assertFailsWith<ContextLifecycleException> {
                    runBlocking { executor.updateRaw("DELETE FROM raw_memory") }
                }
            assertEquals(ContextLifecycleErrorCode.CLOSED, updateFailure.errorCode)
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
