package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B3 作用域边界与 CT04 保护：ReadContext / TransactionContext 不能逃逸回调、不能跨线程，
 * 装配不暴露 SQL / JDBC，执行器事务控制与多语句仍被拒绝。
 */
class ContextDatabaseScopeBoundaryIntegrationTest {
    @Test
    fun `read context cannot be used after its callback returns`() =
        withDatabase { database, _, _ ->
            val captured = AtomicReference<ReadContext?>()
            database.read { rc -> captured.set(rc) }

            val failure =
                assertFailsWith<ContextPersistenceException> {
                    assertNotNull(captured.get()).pending.exists("raw-1")
                }
            assertScopeFailure(failure, "after its callback")
        }

    @Test
    fun `transaction context cannot be used after its callback returns`() =
        withDatabase { database, _, _ ->
            val captured = AtomicReference<TransactionContext?>()
            database.write { tx -> captured.set(tx) }

            val failure =
                assertFailsWith<ContextPersistenceException> {
                    assertNotNull(captured.get()).rawMemories.insertBatch(listOf(B3Fixtures.raw("raw-1")))
                }
            assertScopeFailure(failure, "after its callback")
            assertEquals(0, database.read { rc -> rc.rawMemories.scanPage(0, 10).size })
        }

    @Test
    fun `read context rejects use from a foreign thread while the callback is active`() =
        withDatabase { database, _, _ ->
            val captured = AtomicReference<ReadContext?>()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            val callback =
                pool.submit {
                    runBlocking {
                        database.read { rc ->
                            captured.set(rc)
                            entered.countDown()
                            assertTrue(release.await(10, TimeUnit.SECONDS), "读回调必须在 10s 内被放行")
                        }
                    }
                }
            var callbackFailure: Throwable? = null
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "读回调必须已进入")
                val failure =
                    assertFailsWith<ContextPersistenceException> {
                        assertNotNull(captured.get()).pending.exists("raw-1")
                    }
                assertScopeFailure(failure, "thread")
            } finally {
                // 无论断言成功或失败都放行回调，并等待后台任务结束；异常与超时都不得被吞掉。
                release.countDown()
                try {
                    callback.get(10, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    callbackFailure = failure
                }
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "后台线程必须在 10s 内退出")
            }
            assertNull(callbackFailure, "后台回调不得失败: $callbackFailure")
        }

    @Test
    fun `transaction context rejects use from a foreign thread while the callback is active`() =
        withDatabase { database, _, _ ->
            val captured = AtomicReference<TransactionContext?>()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            val callback =
                pool.submit {
                    runBlocking {
                        database.write { tx ->
                            captured.set(tx)
                            entered.countDown()
                            assertTrue(release.await(10, TimeUnit.SECONDS), "写回调必须在 10s 内被放行")
                        }
                    }
                }
            var callbackFailure: Throwable? = null
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "写回调必须已进入")
                val failure =
                    assertFailsWith<ContextPersistenceException> {
                        assertNotNull(captured.get()).conversations.countPending(B3Fixtures.SESSION)
                    }
                assertScopeFailure(failure, "thread")
            } finally {
                release.countDown()
                try {
                    callback.get(10, TimeUnit.SECONDS)
                } catch (failure: Throwable) {
                    callbackFailure = failure
                }
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "后台线程必须在 10s 内退出")
            }
            assertNull(callbackFailure, "后台回调不得失败: $callbackFailure")
        }

    @Test
    fun `assembly exposes no SQL or JDBC surface and CT04 guards still reject`() =
        withDatabase { _, executor, _ ->
            val exposed =
                listOf(
                    ContextDatabase::class.java,
                    ReadContext::class.java,
                    TransactionContext::class.java,
                    ContextDatabaseImpl::class.java,
                ).flatMap { type ->
                    type.methods.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
                }
            assertTrue(
                exposed.none { it.name.startsWith("java.sql.") || it.name.startsWith("app.cash.sqldelight.") },
                "装配不得暴露 JDBC / SQLDelight 类型: ${exposed.map { it.name }.filter { it.contains("sql") }}",
            )
            assertTrue(
                exposed.none { it.simpleName.contains("Connection") || it.simpleName.contains("Driver") },
                "装配不得暴露 Connection / Driver: ${exposed.map { it.simpleName }}",
            )

            // 同一执行器上的 CT04 保护仍然生效：事务控制、Savepoint、多语句全部具名拒绝。
            assertFailsWith<ContextPersistenceException> { executor.updateRaw("COMMIT") }
            assertFailsWith<ContextPersistenceException> { executor.updateRaw("SAVEPOINT sp") }
            assertFailsWith<ContextPersistenceException> {
                executor.write { tx -> tx.update("SELECT 1; COMMIT") }
            }
        }

    private fun assertScopeFailure(
        failure: ContextPersistenceException,
        needle: String,
    ) {
        assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        assertNull(failure.cause, "作用域错误不得携带底层异常链")
        assertTrue(
            failure.message.orEmpty().contains(needle, ignoreCase = true),
            "实际消息: ${failure.message}",
        )
    }
}
