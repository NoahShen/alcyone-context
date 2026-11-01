package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import com.github.noahshen.alcyone.context.memory.runtime.status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CM1 全面复查 F1-R1：关闭必须先封闭准入并转 STOPPING，再取消/等待通知。
 *
 * 用「取消已进入 finally」的显式屏障，不用 sleep 或固定毫秒猜测。
 */
class NotificationDrainBarrierTest {
    @Test
    fun `ingestion is refused while the cancelled notification drains in finally`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val draining = CountDownLatch(1)
            val gate = CompletableDeferred<Unit>()
            val finished = AtomicBoolean(false)
            val closeFailure = AtomicReference<Throwable?>(null)

            val context =
                newContext(fixture) {
                    try {
                        entered.countDown()
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            // 显式屏障：证明 close 已取消通知且清理已进入 finally。
                            draining.countDown()
                            gate.await()
                        }
                        finished.set(true)
                    }
                }
            runBlocking { context.start() }
            assertEquals(IngestStatus.CREATED, runBlocking { context.ingestConversation(IngestionTurns.turn()) }.status)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "通知必须已开始")

            val closeReturned = CountDownLatch(1)
            val closer =
                Thread {
                    try {
                        context.close()
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeReturned.countDown()
                    }
                }
            closer.start()
            try {
                assertTrue(draining.await(10, TimeUnit.SECONDS), "close 必须已取消通知并进入 finally")

                // 关键断言：此时状态必须是 STOPPING（不是 RUNNING），业务准入已封闭。
                assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)
                assertFalse(closeReturned.await(0, TimeUnit.MILLISECONDS), "通知 finally 未退出时 close 不得返回")

                val sameKey =
                    runCatching { runBlocking { context.ingestConversation(IngestionTurns.turn()) } }.exceptionOrNull()
                val newKey =
                    runCatching {
                        runBlocking {
                            context.ingestConversation(IngestionTurns.turn(sessionTurnId = "turn-2"))
                        }
                    }.exceptionOrNull()
                assertEquals(ContextLifecycleErrorCode.CLOSED, (sameKey as? ContextLifecycleException)?.errorCode, "重复键也必须拒绝: $sameKey")
                assertEquals(ContextLifecycleErrorCode.CLOSED, (newKey as? ContextLifecycleException)?.errorCode, "新键必须拒绝: $newKey")
                assertEquals(1L, countTurns(fixture), "drain 期间被拒绝的 ingest 不得新增行")
            } finally {
                gate.complete(Unit)
                closer.join(10_000)
            }
            assertFalse(closer.isAlive, "关闭线程必须有界退出")
            assertTrue(closeReturned.await(10, TimeUnit.SECONDS), "gate 放行后 close 必须返回")
            assertTrue(finished.get(), "通知 finally 必须真正退出")
            assertNull(closeFailure.get(), "close 不得失败: ${closeFailure.get()}")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `structured child notification keeps close waiting until the child finally exits`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val childStarted = CountDownLatch(1)
            val childDraining = CountDownLatch(1)
            val gate = CompletableDeferred<Unit>()
            val childFinished = AtomicBoolean(false)

            val context =
                newContext(fixture) {
                    coroutineScope {
                        val child =
                            launch {
                                try {
                                    childStarted.countDown()
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        childDraining.countDown()
                                        gate.await()
                                    }
                                    childFinished.set(true)
                                }
                            }
                        child.join()
                    }
                }
            runBlocking { context.start() }
            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            // 先确认子协程真的进入了 try 体，否则取消可能发生在启动之前、finally 不会执行。
            assertTrue(childStarted.await(10, TimeUnit.SECONDS), "结构化子协程必须已开始执行")

            val closeReturned = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable?>(null)
            val closer =
                Thread {
                    try {
                        context.close()
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeReturned.countDown()
                    }
                }
            closer.start()
            try {
                assertTrue(childDraining.await(10, TimeUnit.SECONDS), "结构化子协程的 finally 必须已进入")
                assertFalse(closeReturned.await(0, TimeUnit.MILLISECONDS), "子协程 finally 未退出时 close 不得返回")
                assertFalse(childFinished.get())
            } finally {
                gate.complete(Unit)
                closer.join(10_000)
            }
            assertFalse(closer.isAlive)
            assertTrue(closeReturned.await(10, TimeUnit.SECONDS))
            assertTrue(childFinished.get(), "结构化子协程的 finally 必须真正退出")
            assertNull(closeFailure.get(), "close 不得失败: ${closeFailure.get()}")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `draining state also refuses SDK ingestion for a duplicate key`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val draining = CountDownLatch(1)
            val gate = CompletableDeferred<Unit>()
            val context =
                newContext(fixture) {
                    try {
                        entered.countDown()
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            draining.countDown()
                            gate.await()
                        }
                    }
                }
            runBlocking { context.start() }
            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            val closeFailure = AtomicReference<Throwable?>(null)
            val closer =
                Thread {
                    closeFailure.set(runCatching { context.close() }.exceptionOrNull())
                }
            closer.start()
            try {
                assertTrue(draining.await(10, TimeUnit.SECONDS))
                // STOPPING 期间返回 CLOSED（Server 会把 ContextLifecycleException 映射为 503）。
                val rejection =
                    runCatching { runBlocking { context.ingestConversation(IngestionTurns.turn()) } }.exceptionOrNull()
                assertEquals(ContextLifecycleErrorCode.CLOSED, (rejection as? ContextLifecycleException)?.errorCode)
                assertEquals(1L, countTurns(fixture))
            } finally {
                gate.complete(Unit)
                closer.join(10_000)
            }
            assertNull(closeFailure.get(), "close 不得失败: ${closeFailure.get()}")
        }

    private fun countTurns(fixture: ContextFixture): Long =
        java.sql.DriverManager
            .getConnection("jdbc:sqlite:${fixture.contextPath.toUri()}?mode=ro", java.util.Properties())
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM conversation_turn").use { rs ->
                        assertTrue(rs.next())
                        rs.getLong(1)
                    }
                }
            }

    private fun newContext(
        fixture: ContextFixture,
        notifier: suspend (SessionKey) -> Unit,
    ): AlcyoneContext =
        AlcyoneContextFactory.create(
            fixture.config(),
            fixture.vfsConfig(),
            object : AlcyoneContextFactory.StartProbe {
                override suspend fun afterResourcesAcquired() = Unit
            },
            ingestionNotifier = IngestionNotifier { key -> notifier(key) },
        )
}
