package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * CT06-B2 关闭屏障：close 必须等在途 JDBC 真正退出，观察期限到点也不得提前发布 CLOSED；
 * 取消一个 close 等待者不影响共享清理。
 */
class AlcyoneContextInFlightCloseTest {
    @Test
    fun `close waits for in-flight JDBC even when the observation window is tiny`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(shutdownTimeout = 1.milliseconds), fixture.vfsConfig())
            runBlocking { context.start() }
            val database = assertNotNull((context as AlcyoneContextImpl).databaseForTesting())

            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val writeFailure = AtomicReference<Throwable?>(null)
            val writer =
                Thread {
                    try {
                        runBlocking {
                            database.write {
                                writeEntered.countDown()
                                assertTrue(releaseWrite.await(10, TimeUnit.SECONDS), "受控写必须有界放行")
                            }
                        }
                    } catch (failure: Throwable) {
                        writeFailure.set(failure)
                    }
                }
            writer.start()
            assertTrue(writeEntered.await(10, TimeUnit.SECONDS), "受控写必须已进入事务")

            val closeDone = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable?>(null)
            val closer =
                Thread {
                    try {
                        context.close()
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeDone.countDown()
                    }
                }
            closer.start()
            try {
                assertFalse(closeDone.await(300, TimeUnit.MILLISECONDS), "在途写退出前 close 不得返回")
            } finally {
                releaseWrite.countDown()
                writer.join(10_000)
            }

            assertFalse(writer.isAlive, "写线程必须有界退出")
            assertTrue(closeDone.await(10, TimeUnit.SECONDS), "在途写退出后 close 必须完成")
            closer.join(10_000)
            assertFalse(closer.isAlive, "关闭线程必须有界退出")
            assertNull(writeFailure.get(), "受控写不得失败: ${writeFailure.get()}")
            assertNull(closeFailure.get(), "close 不得失败: ${closeFailure.get()}")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertTrue(
                context.lastShutdownExceededObservationWindow,
                "1ms 观察窗必然被超过，但这只是观察事实，不能提前发布 CLOSED",
            )

            // 资源确实释放：VFS 独占锁可以重新取得，Context 库可以重新打开。
            runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
            ContextDatabaseFactory.open(fixture.contextPath).close()
        }

    @Test
    fun `cancelling one close waiter does not cancel the shared cleanup`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking { context.start() }
            val database = assertNotNull((context as AlcyoneContextImpl).databaseForTesting())

            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val writer =
                Thread {
                    runBlocking {
                        database.write {
                            writeEntered.countDown()
                            releaseWrite.await(10, TimeUnit.SECONDS)
                        }
                    }
                }
            writer.start()
            assertTrue(writeEntered.await(10, TimeUnit.SECONDS))

            val primaryDone = CountDownLatch(1)
            val primaryFailure = AtomicReference<Throwable?>(null)
            val primary =
                Thread {
                    try {
                        context.close()
                    } catch (failure: Throwable) {
                        primaryFailure.set(failure)
                    } finally {
                        primaryDone.countDown()
                    }
                }
            primary.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (context.status().lifecycle != ContextLifecycleState.STOPPING && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle, "close 必须已进入关闭流程")

            val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
            try {
                val waiter = scope.launch { context.closeAsync() }
                // 确定性判据：等待者必须已进入共享等待（beginClose 非 owner 分支），再取消它。
                val impl = context as AlcyoneContextImpl
                val waiterDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (impl.closeWaiterEnteredForTesting() == 0 && System.nanoTime() < waiterDeadline) {
                    Thread.onSpinWait()
                }
                assertTrue(impl.closeWaiterEnteredForTesting() > 0, "close 等待者必须已进入共享等待")
                runBlocking { waiter.cancelAndJoin() }
                assertTrue(waiter.isCancelled, "等待者取消必须原样传播")
            } finally {
                scope.cancel()
            }
            try {
                assertFalse(primaryDone.await(200, TimeUnit.MILLISECONDS), "等待者取消不得取消共享清理")
            } finally {
                releaseWrite.countDown()
                writer.join(10_000)
            }

            assertFalse(writer.isAlive)
            assertTrue(primaryDone.await(10, TimeUnit.SECONDS), "共享清理必须最终完成")
            primary.join(10_000)
            assertFalse(primary.isAlive)
            assertNull(primaryFailure.get(), "共享清理不得被等待者取消影响: ${primaryFailure.get()}")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }
}
