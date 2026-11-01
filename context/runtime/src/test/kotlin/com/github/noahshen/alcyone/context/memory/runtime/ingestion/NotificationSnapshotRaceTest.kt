package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import com.github.noahshen.alcyone.context.memory.runtime.status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CM1 F1-R1：关闭快照通知 Job 时，`invokeOnCompletion` 会在其它线程并发移除元素。
 *
 * 回归目标：快照**不得**因为并发移除抛 `NoSuchElementException`，且 close 仍会 join 全部在途 Job。
 * 用真实 `notificationJobs` 语义（完成回调移除），不使用反射。
 */
class NotificationSnapshotRaceTest {
    @Test
    fun `snapshot tolerates notifications removing themselves while close is draining`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val gate = CompletableDeferred<Unit>()
            val started = CountDownLatch(NOTIFICATION_COUNT)
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    ingestionNotifier =
                        IngestionNotifier {
                            // 正常完成（不依赖取消）：gate 放行后退出，退出时 invokeOnCompletion 会移除自己。
                            started.countDown()
                            gate.await()
                        },
                ) as AlcyoneContextImpl
            runBlocking { context.start() }
            repeat(NOTIFICATION_COUNT) { index ->
                runBlocking { context.ingestConversation(IngestionTurns.turn(sessionTurnId = "turn-$index")) }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS), "全部通知必须已开始")
            assertEquals(NOTIFICATION_COUNT, context.notificationJobCountForTesting())

            // 快照前的受控交错：放行通知，等它们开始并发移除自己（但不必全部移除完），
            // 这样 close 取快照/遍历时仍会有别的线程在移除元素。
            context.beforeNotificationSnapshotForTesting = {
                gate.complete(Unit)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (context.notificationJobCountForTesting() >= NOTIFICATION_COUNT && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
            }

            val failure = AtomicReference<Throwable?>(null)
            val done = CountDownLatch(1)
            val closer =
                Thread {
                    try {
                        context.close()
                    } catch (throwable: Throwable) {
                        failure.set(throwable)
                    } finally {
                        done.countDown()
                    }
                }
            closer.start()
            try {
                assertTrue(done.await(10, TimeUnit.SECONDS), "close 必须在有界时间内完成")
            } finally {
                gate.complete(Unit)
                closer.join(10_000)
            }

            assertFalse(closer.isAlive)
            assertNull(failure.get(), "快照期间并发移除不得抛异常: ${failure.get()}")
            assertTrue(
                failure.get() !is NoSuchElementException,
                "不得出现 ConcurrentHashMap + toList 的 NoSuchElementException",
            )
            assertEquals(0, context.notificationJobCountForTesting(), "close 之后不得残留通知 Job")
            assertEquals(
                com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState.CLOSED,
                context.status().lifecycle,
            )
        }

    @Test
    fun `repeated close and notification completion does not throw NoSuchElementException`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            repeat(ROUNDS) { round ->
                val gate = CompletableDeferred<Unit>()
                val started = CountDownLatch(2)
                val context =
                    AlcyoneContextFactory.create(
                        fixture.config(),
                        fixture.vfsConfig(),
                        object : AlcyoneContextFactory.StartProbe {
                            override suspend fun afterResourcesAcquired() = Unit
                        },
                        ingestionNotifier =
                            IngestionNotifier {
                                started.countDown()
                                try {
                                    awaitCancellation()
                                } finally {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                                }
                            },
                    ) as AlcyoneContextImpl
                try {
                    runBlocking { context.start() }
                    repeat(2) { index ->
                        runBlocking { context.ingestConversation(IngestionTurns.turn(sessionTurnId = "round-$round-$index")) }
                    }
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    if (round % 2 == 0) {
                        // 偶数轮：先放行再 close（完成移除与快照并发）。
                        gate.complete(Unit)
                    }
                    val failure = AtomicReference<Throwable?>(null)
                    val closer = Thread { failure.set(runCatching { context.close() }.exceptionOrNull()) }
                    closer.start()
                    if (round % 2 != 0) {
                        // 奇数轮：close 已开始后再放行。
                        gate.complete(Unit)
                    }
                    closer.join(10_000)
                    assertFalse(closer.isAlive, "第 $round 轮 close 必须有界退出")
                    assertNull(failure.get(), "第 $round 轮 close 不得失败: ${failure.get()}")
                } finally {
                    gate.complete(Unit)
                    runCatching { context.close() }
                }
            }
        }

    private companion object {
        const val NOTIFICATION_COUNT = 32
        const val ROUNDS = 20
    }
}
