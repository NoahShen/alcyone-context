package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import com.github.noahshen.alcyone.context.memory.runtime.status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
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
 * CT07 F1：close 必须等待通知真实退出；通知内关闭具名拒绝。
 *
 * 退出判据用「通知 finally 置位的 AtomicBoolean」而不是 Job 计数清零。
 */
class NotificationCloseGateTest {
    @Test
    fun `sync close waits for the notification gate release and the finally really exited`() = gateScenario(syncEntry = true)

    @Test
    fun `async close waits for the notification gate release and the finally really exited`() = gateScenario(syncEntry = false)

    @Test
    fun `closing the same context from inside its notification is rejected on both entries`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val observed = CopyOnWriteArrayList<Throwable?>()
            val done = CountDownLatch(1)
            val context =
                newContext(fixture) {
                    observed += runCatching { contextRef!!.close() }.exceptionOrNull()
                    observed += runCatching { runBlocking { contextRef!!.closeAsync() } }.exceptionOrNull()
                    done.countDown()
                }
            runBlocking { context.start() }
            assertEquals(IngestStatus.CREATED, runBlocking { context.ingestConversation(IngestionTurns.turn()) }.status)
            assertTrue(done.await(10, TimeUnit.SECONDS), "通知内两次 close 都必须返回（具名拒绝，不死锁）")
            assertEquals(2, observed.size)
            observed.forEach { failure ->
                assertEquals(
                    ContextLifecycleErrorCode.CLOSED,
                    (failure as? ContextLifecycleException)?.errorCode,
                    "实际: $failure",
                )
            }
            context.close()
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `closing a different context from inside a notification is not rejected`() =
        ContextFixture().use { fixture ->
            ContextFixture().use { otherFixture ->
                fixture.initialize()
                otherFixture.initialize()
                val other = AlcyoneContextFactory.create(otherFixture.config(), otherFixture.vfsConfig())
                runBlocking { other.start() }

                val done = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>(null)
                val context =
                    newContext(fixture) {
                        failure.set(runCatching { other.close() }.exceptionOrNull())
                        done.countDown()
                    }
                runBlocking { context.start() }

                assertEquals(IngestStatus.CREATED, runBlocking { context.ingestConversation(IngestionTurns.turn()) }.status)
                assertTrue(done.await(10, TimeUnit.SECONDS), "通知必须完成")
                assertNull(failure.get(), "关闭另一个 Context 不应被拒绝: ${failure.get()}")
                assertEquals(ContextLifecycleState.CLOSED, other.status().lifecycle)
                context.close()
            }
        }

    @Test
    fun `close is still rejected inside a notification after an external close started`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val gate = CompletableDeferred<Unit>()
            val observed = AtomicReference<Throwable?>(null)
            val finished = AtomicBoolean(false)
            val context =
                newContext(fixture) {
                    entered.countDown()
                    try {
                        // 不可取消地等 gate：外部关闭已发起时通知仍然活着，用于验证「外部关闭已发起」分支。
                        withContext(NonCancellable) {
                            gate.await()
                            observed.set(runCatching { runBlocking { contextRef!!.closeAsync() } }.exceptionOrNull())
                        }
                    } finally {
                        finished.set(true)
                    }
                }
            runBlocking { context.start() }
            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "通知必须已开始")

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
                assertFalse(closeReturned.await(200, TimeUnit.MILLISECONDS), "gate 未放行时 close 不得返回")
            } finally {
                gate.complete(Unit)
                closer.join(10_000)
            }
            assertFalse(closer.isAlive, "关闭线程必须有界退出")
            assertTrue(finished.get(), "通知 finally 必须真正退出")
            assertNull(closeFailure.get(), "外部 close 不得失败: ${closeFailure.get()}")
            val lifecycle = observed.get() as? ContextLifecycleException
            assertEquals(ContextLifecycleErrorCode.CLOSED, lifecycle?.errorCode, "实际: ${observed.get()}")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    // ------------------------------------------------------------------ 场景实现

    private fun gateScenario(syncEntry: Boolean) {
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val draining = CountDownLatch(1)
            val gate = CompletableDeferred<Unit>()
            val finished = AtomicBoolean(false)
            val reported = CopyOnWriteArrayList<Throwable>()
            val context =
                newContext(fixture, reporter = { reported += it }) {
                    entered.countDown()
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            // 显式屏障：证明 close 已取消通知、清理已进入 finally。
                            draining.countDown()
                            gate.await()
                        }
                        finished.set(true)
                    }
                }
            runBlocking { context.start() }
            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "通知必须已开始")

            val closeReturned = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable?>(null)
            val closer =
                Thread {
                    try {
                        if (syncEntry) context.close() else runBlocking { context.closeAsync() }
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeReturned.countDown()
                    }
                }
            closer.start()
            try {
                assertTrue(draining.await(10, TimeUnit.SECONDS), "close 必须已取消通知并进入 finally")
                assertFalse(closeReturned.await(0, TimeUnit.MILLISECONDS), "gate 未放行时 close 不得返回")
                assertFalse(finished.get(), "通知 finally 不得在 gate 放行前退出")
            } finally {
                gate.complete(Unit)
                closer.join(10_000)
            }
            assertFalse(closer.isAlive, "关闭线程必须有界退出")
            assertTrue(closeReturned.await(10, TimeUnit.SECONDS), "gate 放行后 close 必须返回")
            assertTrue(finished.get(), "通知 finally 必须真正退出")
            assertNull(closeFailure.get(), "close 不得失败: ${closeFailure.get()}")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertEquals(0, (context as AlcyoneContextImpl).notificationJobCountForTesting())
            assertTrue(reported.any { it is kotlinx.coroutines.CancellationException }, "关闭必须上报取消")
        }
    }

    private var contextRef: AlcyoneContext? = null

    private fun newContext(
        fixture: ContextFixture,
        reporter: (Throwable) -> Unit = { },
        notifier: suspend (SessionKey) -> Unit,
    ): AlcyoneContext =
        AlcyoneContextFactory
            .create(
                fixture.config(),
                fixture.vfsConfig(),
                object : AlcyoneContextFactory.StartProbe {
                    override suspend fun afterResourcesAcquired() = Unit
                },
                ingestionNotifier = IngestionNotifier { key -> notifier(key) },
                ingestionReporter = IngestionNotificationReporter { cause -> reporter(cause) },
            ).also { contextRef = it }
}
