package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * CT06-B2 生命周期：并发 start 共享结果、RUNNING 幂等、close 共享结果、失败共享、
 * start/close 竞争与取消清理。
 */
class AlcyoneContextLifecycleTest {
    @Test
    fun `concurrent start shares one initialization`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val invocations = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() {
                            invocations.incrementAndGet()
                            entered.countDown()
                            assertTrue(release.await(10, TimeUnit.SECONDS), "探针必须有界放行")
                        }
                    },
                )
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Unit> { runBlocking { context.start() } }
                assertTrue(entered.await(10, TimeUnit.SECONDS), "首次 start 必须取得资源")
                val second = pool.submit<Unit> { runBlocking { context.start() } }
                awaitStartWaiter(context)
                release.countDown()
                first.get(10, TimeUnit.SECONDS)
                second.get(10, TimeUnit.SECONDS)

                assertEquals(1, invocations.get(), "并发 start 只能初始化一次")
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                context.close()
            } finally {
                release.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "后台线程必须有界退出")
            }
        }

    @Test
    fun `start is idempotent while running`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking { context.start() }
            val database = (context as AlcyoneContextImpl).databaseForTesting()
            assertNotNull(database)

            runBlocking { context.start() }
            assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
            assertSame(database, (context as AlcyoneContextImpl).databaseForTesting(), "RUNNING 幂等重入不得重建资源")
            context.close()
        }

    @Test
    fun `close before start closes directly without creating resources`() =
        ContextFixture().use { fixture ->
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            context.close()
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertFalse(fixture.snapshot().contains("data"), "close 在 CREATED 不得创建数据库目录")

            // 重复 close 共享成功结果。
            context.close()
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `close after a failed start shares the same failure`() =
        ContextFixture().use { fixture ->
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val startFailure = runBlocking { assertFailsWith<ContextPersistenceException> { context.start() } }
            assertEquals(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, startFailure.errorCode)
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)

            val closeFailure = assertFailsWith<ContextPersistenceException> { context.close() }
            assertSame(startFailure, closeFailure, "CLOSED 后 close 必须共享此前失败结果")
            val again = assertFailsWith<ContextPersistenceException> { runBlocking { context.closeAsync() } }
            assertSame(startFailure, again)

            val startAgain = assertFailsWith<ContextLifecycleException> { runBlocking { context.start() } }
            assertEquals(ContextLifecycleErrorCode.CLOSED, startAgain.errorCode)
        }

    @Test
    fun `a failed start is visible to concurrent waiters and releases resources`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            fixture.setUserVersion(99)
            val gateEntered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    startGate =
                        object : AlcyoneContextFactory.StartGate {
                            override suspend fun beforeOpen() {
                                gateEntered.countDown()
                                assertTrue(gate.await(10, TimeUnit.SECONDS), "失败闸门必须有界放行")
                            }
                        },
                )
            val pool = Executors.newFixedThreadPool(2)
            try {
                val failures = listOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
                val first =
                    pool.submit {
                        runBlocking { failures[0].set(runCatching { context.start() }.exceptionOrNull()) }
                    }
                assertTrue(gateEntered.await(10, TimeUnit.SECONDS), "首个 start 必须已进入打开前阶段")
                val second =
                    pool.submit {
                        runBlocking { failures[1].set(runCatching { context.start() }.exceptionOrNull()) }
                    }
                awaitStartWaiter(context)
                gate.countDown()
                first.get(10, TimeUnit.SECONDS)
                second.get(10, TimeUnit.SECONDS)

                val firstFailure = assertNotNull(failures[0].get()) as ContextPersistenceException
                val secondFailure = assertNotNull(failures[1].get()) as ContextPersistenceException
                assertEquals(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, firstFailure.errorCode)
                assertSame(firstFailure, secondFailure, "并发 start 的失败结果必须一致")
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertNull((context as AlcyoneContextImpl).databaseForTesting())
                assertFalse(fixture.snapshot().contains("data/vfs.db"), "启动失败时不得留下 VFS 状态库")
            } finally {
                gate.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `close wins the race against start and the start never publishes RUNNING`() =
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
                            assertTrue(release.await(10, TimeUnit.SECONDS), "探针必须有界放行")
                        }
                    },
                )
            val pool = Executors.newFixedThreadPool(2)
            try {
                val startFailure = AtomicReference<Throwable?>()
                val startFuture =
                    pool.submit {
                        runBlocking { startFailure.set(runCatching { context.start() }.exceptionOrNull()) }
                    }
                assertTrue(entered.await(10, TimeUnit.SECONDS), "start 必须已取得资源")
                val closeFailure = AtomicReference<Throwable?>()
                val closeFuture =
                    pool.submit {
                        closeFailure.set(runCatching { context.close() }.exceptionOrNull())
                    }

                // close 一旦进入 shutdown 就会把 STARTING 改为 STOPPING；这是状态事实，不是概率等待。
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (context.status().lifecycle == ContextLifecycleState.STARTING && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
                assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle, "close 必须已进入关闭流程")

                release.countDown()
                startFuture.get(10, TimeUnit.SECONDS)
                closeFuture.get(10, TimeUnit.SECONDS)

                val failure = assertNotNull(startFailure.get()) as ContextLifecycleException
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode, "close 获胜时 start 得到 CLOSED")
                assertNull(closeFailure.get(), "close 必须正常完成: ${closeFailure.get()}")
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertNull((context as AlcyoneContextImpl).databaseForTesting())

                // 资源确实释放：VFS 独占锁与 Context 执行器都可重新取得。
                runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
                ContextDatabaseFactory.open(fixture.contextPath).close()
            } finally {
                release.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `cancelling the start initiator reverses acquired resources`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() {
                            entered.countDown()
                            gate.await()
                        }
                    },
                )
            runBlocking {
                val job = launch(Dispatchers.Default) { context.start() }
                assertTrue(entered.await(10, TimeUnit.SECONDS), "start 必须已取得资源")
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }

            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle, "取消的启动必须落到 CLOSED")
            assertNull((context as AlcyoneContextImpl).databaseForTesting())
            runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
            ContextDatabaseFactory.open(fixture.contextPath).close()
        }

    /** 确定性等待：第二个 start 必须已在 STARTING 分支登记等待，而不是两个线程同时起跑。 */
    private fun awaitStartWaiter(context: AlcyoneContext) {
        val impl = context as AlcyoneContextImpl
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (impl.startWaiterEnteredForTesting() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(impl.startWaiterEnteredForTesting() > 0, "第二个 start 必须已进入 STARTING 等待")
    }
}
