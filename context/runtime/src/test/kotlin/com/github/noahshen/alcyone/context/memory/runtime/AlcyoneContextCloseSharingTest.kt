package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CT06-B2 关闭契约：sync/async 共享真实清理结果、失败传播、同步线程中断、挂起路径不阻塞单线程。
 */
class AlcyoneContextCloseSharingTest {
    @Test
    fun `sync and async close share one cleanup result`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val releases = AtomicInteger()
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    object : AlcyoneContextFactory.ReleaseProbe {
                        override fun beforeRelease(target: AlcyoneContextFactory.ReleaseTarget) {
                            releases.incrementAndGet()
                        }
                    },
                )
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

            val syncDone = CountDownLatch(1)
            val syncFailure = AtomicReference<Throwable?>(null)
            val syncCloser =
                Thread {
                    try {
                        context.close()
                    } catch (failure: Throwable) {
                        syncFailure.set(failure)
                    } finally {
                        syncDone.countDown()
                    }
                }
            syncCloser.start()

            val asyncDone = CountDownLatch(1)
            val asyncFailure = AtomicReference<Throwable?>(null)
            val asyncCloser =
                Thread {
                    try {
                        runBlocking { context.closeAsync() }
                    } catch (failure: Throwable) {
                        asyncFailure.set(failure)
                    } finally {
                        asyncDone.countDown()
                    }
                }
            asyncCloser.start()

            try {
                assertFalse(syncDone.await(200, TimeUnit.MILLISECONDS), "在途写退出前两个 close 都不得返回")
                assertFalse(asyncDone.await(200, TimeUnit.MILLISECONDS), "在途写退出前两个 close 都不得返回")
            } finally {
                releaseWrite.countDown()
                writer.join(10_000)
            }

            assertTrue(syncDone.await(10, TimeUnit.SECONDS), "sync close 必须完成")
            assertTrue(asyncDone.await(10, TimeUnit.SECONDS), "async close 必须完成")
            syncCloser.join(10_000)
            asyncCloser.join(10_000)
            assertFalse(syncCloser.isAlive)
            assertFalse(asyncCloser.isAlive)
            assertNull(syncFailure.get(), "sync close 不得失败: ${syncFailure.get()}")
            assertNull(asyncFailure.get(), "async close 不得失败: ${asyncFailure.get()}")
            assertEquals(2, releases.get(), "VFS + executor 只释放一次（探针各记一次）")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `a release failure is shared by close and every later waiter`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    object : AlcyoneContextFactory.ReleaseProbe {
                        override fun beforeRelease(target: AlcyoneContextFactory.ReleaseTarget) {
                            if (target == AlcyoneContextFactory.ReleaseTarget.EXECUTOR) {
                                throw IllegalStateException("release-failure-marker")
                            }
                        }
                    },
                )
            runBlocking { context.start() }

            val first = assertFailsWith<IllegalStateException> { context.close() }
            assertEquals("release-failure-marker", first.message)
            val second = assertFailsWith<IllegalStateException> { context.close() }
            assertSame(first, second, "重复 close 必须共享同一失败")
            val third = assertFailsWith<IllegalStateException> { runBlocking { context.closeAsync() } }
            assertSame(first, third, "closeAsync 也必须共享同一失败")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle, "失败终态必须是 CLOSED，不停留 STOPPING")

            // 探针失败不阻止真实释放：VFS 锁与 Context 连接都可重新取得。
            runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
            ContextDatabaseFactory.open(fixture.contextPath).close()
        }

    @Test
    fun `release failure during the start close race is shared by start and close`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    },
                    object : AlcyoneContextFactory.ReleaseProbe {
                        override fun beforeRelease(target: AlcyoneContextFactory.ReleaseTarget) {
                            if (target == AlcyoneContextFactory.ReleaseTarget.VFS) {
                                throw IllegalStateException("race-release-failure")
                            }
                        }
                    },
                )
            val pool = Executors.newFixedThreadPool(2)
            try {
                val startFailure = AtomicReference<Throwable?>(null)
                val startFuture =
                    pool.submit {
                        runBlocking { startFailure.set(runCatching { context.start() }.exceptionOrNull()) }
                    }
                assertTrue(entered.await(10, TimeUnit.SECONDS), "start 必须已取得资源")
                val closeFailure = AtomicReference<Throwable?>(null)
                val closeFuture =
                    pool.submit {
                        closeFailure.set(runCatching { context.close() }.exceptionOrNull())
                    }

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (context.status().lifecycle == ContextLifecycleState.STARTING && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
                assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)

                release.countDown()
                startFuture.get(10, TimeUnit.SECONDS)
                closeFuture.get(10, TimeUnit.SECONDS)

                val startError = assertNotNull(startFailure.get()) as IllegalStateException
                val closeError = assertNotNull(closeFailure.get()) as IllegalStateException
                assertEquals("race-release-failure", startError.message)
                assertSame(startError, closeError, "竞态中真实清理失败必须由 start 与 close 共享")
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)

                // VFS 仍被真实关闭：探针失败不跳过释放。
                runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
            } finally {
                release.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `interrupting the sync close thread does not abort cleanup`() =
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

            val closeDone = CountDownLatch(1)
            val interruptedAfter = AtomicReference<Boolean?>(null)
            val closeFailure = AtomicReference<Throwable?>(null)
            val closer =
                Thread {
                    try {
                        context.close()
                        interruptedAfter.set(Thread.currentThread().isInterrupted)
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeDone.countDown()
                    }
                }
            closer.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (context.status().lifecycle != ContextLifecycleState.STOPPING && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)
            closer.interrupt()

            try {
                assertFalse(closeDone.await(300, TimeUnit.MILLISECONDS), "中断不得让 close 提前返回")
            } finally {
                releaseWrite.countDown()
                writer.join(10_000)
            }

            assertTrue(closeDone.await(10, TimeUnit.SECONDS), "中断后 close 仍必须完成真实清理")
            closer.join(10_000)
            assertFalse(closer.isAlive)
            assertNull(closeFailure.get(), "中断不得伪造失败或放弃清理: ${closeFailure.get()}")
            assertTrue(interruptedAfter.get() == true, "结束后必须恢复中断标志")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            ContextDatabaseFactory.open(fixture.contextPath).close()
        }

    @Test
    fun `closeAsync does not deadlock in-flight resumption on a single-thread dispatcher`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking { context.start() }
            val database = assertNotNull((context as AlcyoneContextImpl).databaseForTesting())

            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            val scope = CoroutineScope(dispatcher)
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            try {
                val writer =
                    scope.launch {
                        runCatching {
                            database.write {
                                entered.countDown()
                                gate.await(10, TimeUnit.SECONDS)
                            }
                        }.exceptionOrNull()?.let(failure::set)
                    }
                assertTrue(entered.await(10, TimeUnit.SECONDS), "在途写必须已进入事务")
                val closer = scope.launch { runCatching { context.closeAsync() }.exceptionOrNull()?.let(failure::set) }
                // 放行由同一个单线程 dispatcher 上的协程完成：挂起式 closeAsync 不得占住该线程。
                val releaser = scope.launch { gate.countDown() }

                runBlocking {
                    withTimeout(10_000) {
                        writer.join()
                        closer.join()
                        releaser.join()
                    }
                }
                assertNull(failure.get(), "单线程 dispatcher 上不得死锁或失败: ${failure.get()}")
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                ContextDatabaseFactory.open(fixture.contextPath).close()
            } finally {
                gate.countDown()
                scope.cancel()
            }
        }
}
