package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import com.github.noahshen.alcyone.context.memory.runtime.status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT07 F1：重入标记必须由协程框架托管的线程上下文桥接，跨 `yield` / dispatcher 切换 /
 * 结构化子协程仍然生效；退出通知后不污染后续普通 close。
 */
class NotificationReentrancyContextTest {
    @Test
    fun `close is rejected after yield and dispatcher switches inside the notification`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val observed = CopyOnWriteArrayList<Throwable?>()
            val done = CountDownLatch(1)
            val context =
                newContext(fixture) {
                    // 切线程 + 让出调度：线程值必须由协程框架自动安装/还原。
                    withContext(Dispatchers.IO) { yield() }
                    observed += runCatching { contextRef!!.close() }.exceptionOrNull()
                    withContext(Dispatchers.Default) { yield() }
                    observed += runCatching { runBlocking { contextRef!!.closeAsync() } }.exceptionOrNull()
                    done.countDown()
                }
            runBlocking { context.start() }
            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertTrue(done.await(10, TimeUnit.SECONDS), "通知必须完成")
            assertEquals(2, observed.size)
            observed.forEach { failure ->
                assertEquals(
                    ContextLifecycleErrorCode.CLOSED,
                    (failure as? ContextLifecycleException)?.errorCode,
                    "跨线程后仍须具名拒绝，实际: $failure",
                )
            }
            context.close()
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `close is rejected inside a structured child coroutine of the notification`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val observed = CopyOnWriteArrayList<Throwable?>()
            val done = CountDownLatch(1)
            val context =
                newContext(fixture) {
                    // 结构化子协程继承通知的协程上下文（含标记）：子任务内也必须拒绝。
                    coroutineScope {
                        val child = async(Dispatchers.IO) { runCatching { contextRef!!.close() }.exceptionOrNull() }
                        observed += child.await()
                        val launched =
                            launch(Dispatchers.Default) {
                                observed += runCatching { contextRef!!.closeAsync() }.exceptionOrNull()
                            }
                        launched.join()
                    }
                    done.countDown()
                }
            runBlocking { context.start() }
            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertTrue(done.await(10, TimeUnit.SECONDS), "通知必须完成")
            assertEquals(2, observed.size)
            observed.forEach { failure ->
                assertEquals(
                    ContextLifecycleErrorCode.CLOSED,
                    (failure as? ContextLifecycleException)?.errorCode,
                    "结构化子协程内仍须具名拒绝，实际: $failure",
                )
            }
            context.close()
        }

    @Test
    fun `marker is restored on the same thread the notification used`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            // 受控单线程 dispatcher：通知与随后的检查复用同一条线程，才能证明框架真的还原了线程值。
            val executor =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            val single = executor.asCoroutineDispatcher()
            val threadUsed =
                java.util.concurrent.atomic
                    .AtomicReference<Thread?>(null)
            val markerInside =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val done = CountDownLatch(1)
            try {
                val context =
                    newContext(fixture) {
                        withContext(single) {
                            threadUsed.set(Thread.currentThread())
                            markerInside.set((contextRef!! as AlcyoneContextImpl).insideNotificationForTesting())
                        }
                        done.countDown()
                    }
                runBlocking { context.start() }
                runBlocking { context.ingestConversation(IngestionTurns.turn()) }
                assertTrue(done.await(10, TimeUnit.SECONDS), "通知必须完成")
                assertTrue(markerInside.get(), "通知执行期间标记必须可见")

                val impl = context as AlcyoneContextImpl
                val observed = threadUsed.get()
                // 在同一条线程上读取标记：必须已被框架还原，而不是残留。
                val markerAfter = runBlocking { withContext(single) { impl.insideNotificationForTesting() } }
                assertFalse(markerAfter, "通知上下文退出后，同一线程上的标记必须已还原")
                assertTrue(observed != null, "必须记录通知实际使用的线程")
                context.close()
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            } finally {
                single.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "受控线程必须有界退出")
            }
        }

    @Test
    fun `different contexts never reject each other inside notifications`() =
        ContextFixture().use { fixture ->
            ContextFixture().use { otherFixture ->
                fixture.initialize()
                otherFixture.initialize()
                val other = AlcyoneContextFactory.create(otherFixture.config(), otherFixture.vfsConfig())
                runBlocking { other.start() }

                val failure =
                    java.util.concurrent.atomic
                        .AtomicReference<Throwable?>(null)
                val done = CountDownLatch(1)
                val context =
                    newContext(fixture) {
                        failure.set(runCatching { other.close() }.exceptionOrNull())
                        done.countDown()
                    }
                runBlocking { context.start() }
                runBlocking { context.ingestConversation(IngestionTurns.turn()) }
                assertTrue(done.await(10, TimeUnit.SECONDS), "通知必须完成")
                assertNull(failure.get(), "不同 Context 不得误拒: ${failure.get()}")
                assertEquals(ContextLifecycleState.CLOSED, other.status().lifecycle)
                context.close()
            }
        }

    private var contextRef: AlcyoneContext? = null

    private fun newContext(
        fixture: ContextFixture,
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
            ).also { contextRef = it }
}
