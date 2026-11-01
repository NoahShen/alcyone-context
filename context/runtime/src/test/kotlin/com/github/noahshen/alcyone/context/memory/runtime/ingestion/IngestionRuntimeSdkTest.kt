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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CT07-B1 Runtime SDK 接线：生命周期门、created_at 时钟注入、CREATED 后异步通知、失败不回滚。
 */
class IngestionRuntimeSdkTest {
    @Test
    fun `ingest is refused before start and after close`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val notStarted =
                assertFailsWith<ContextLifecycleException> { runBlocking { context.ingestConversation(IngestionTurns.turn()) } }
            assertEquals(ContextLifecycleErrorCode.NOT_STARTED, notStarted.errorCode)

            runBlocking { context.start() }
            val created = runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertEquals(IngestStatus.CREATED, created.status)

            context.close()
            val closed = assertFailsWith<ContextLifecycleException> { runBlocking { context.ingestConversation(IngestionTurns.turn()) } }
            assertEquals(ContextLifecycleErrorCode.CLOSED, closed.errorCode)
        }

    @Test
    fun `ingest during STARTING reports not started`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
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
                                entered.countDown()
                                assertTrue(release.await(10, TimeUnit.SECONDS), "启动闸门必须有界放行")
                            }
                        },
                )
            val scope = CoroutineScope(Dispatchers.Default)
            val startJob = scope.launch { context.start() }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "start 必须已进入 STARTING")
            val failure = assertFailsWith<ContextLifecycleException> { runBlocking { context.ingestConversation(IngestionTurns.turn()) } }
            assertEquals(ContextLifecycleErrorCode.NOT_STARTED, failure.errorCode)

            release.countDown()
            runBlocking { startJob.join() }
            scope.cancel()
            context.close()
        }

    @Test
    fun `created triggers an asynchronous notification and duplicate does not`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val notified = CopyOnWriteArrayList<SessionKey>()
            val latch = CountDownLatch(1)
            val context =
                createWithNotifier(
                    fixture,
                    notifier = { key ->
                        notified += key
                        latch.countDown()
                    },
                )
            runBlocking { context.start() }

            val created = runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertEquals(IngestStatus.CREATED, created.status)
            assertTrue(latch.await(10, TimeUnit.SECONDS), "CREATED 必须启动异步通知")
            assertEquals(listOf(SessionKey("runtime-1", "agent-1", "session-1")), notified.toList())

            val duplicate = runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertEquals(IngestStatus.DUPLICATE, duplicate.status)
            assertEquals(created.id, duplicate.id)
            assertEquals(1, notified.size, "DUPLICATE 不得再次通知")
            context.close()
        }

    @Test
    fun `a failing notifier reports the same failure and does not change the created result`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val called = AtomicInteger()
            val latch = CountDownLatch(1)
            val failure = IllegalStateException("notifier boom")
            val reported = CopyOnWriteArrayList<Throwable>()
            val context =
                createWithNotifier(
                    fixture,
                    notifier = {
                        called.incrementAndGet()
                        latch.countDown()
                        throw failure
                    },
                    reporter = { reported += it },
                )
            runBlocking { context.start() }

            val created = runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertEquals(IngestStatus.CREATED, created.status)
            assertTrue(latch.await(10, TimeUnit.SECONDS), "通知替身必须被调用")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reported.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait()
            assertEquals(listOf(failure), reported.toList(), "reporter 必须收到同一异常实例")
            assertEquals(1, called.get())
            assertEquals(ContextLifecycleState.RUNNING, runBlocking { context.getStatus().lifecycle })
            assertFalse(
                reported
                    .single()
                    .message
                    .orEmpty()
                    .contains("runtime-1"),
                "reporter 不得携带 SessionKey 值",
            )
            context.close()
        }

    @Test
    fun `server side createdAt uses the injected clock`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val fixed = Clock.fixed(Instant.parse("2026-10-29T03:04:05.678Z"), ZoneOffset.UTC)
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    clock = fixed,
                )
            runBlocking { context.start() }
            val created = runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            val stored =
                assertNotNull(
                    runBlocking {
                        (context as AlcyoneContextImpl)
                            .databaseForTesting()!!
                            .read { it.conversations.getById(created.id) }
                    },
                )
            assertEquals(fixed.instant(), stored.createdAt)
            context.close()
        }

    @Test
    fun `notification scope is created lazily on the first created turn`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = createWithNotifier(fixture, notifier = { }, reporter = { })
            val impl = context as AlcyoneContextImpl
            assertFalse(impl.notificationScopeCreatedForTesting(), "create 后不得创建通知作用域")
            assertEquals(0, impl.notificationJobCountForTesting())

            runBlocking { context.start() }
            assertFalse(impl.notificationScopeCreatedForTesting(), "start 本身不得创建通知作用域")

            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertTrue(impl.notificationScopeCreatedForTesting(), "首次 CREATED 后才懒创建通知作用域")
            context.close()
        }

    @Test
    fun `close cancels pending notifications and reports the cancellation`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val gate = CompletableDeferred<Unit>()
            val reported = CopyOnWriteArrayList<Throwable>()
            val context =
                createWithNotifier(
                    fixture,
                    notifier = {
                        entered.countDown()
                        gate.await()
                    },
                    reporter = { reported += it },
                )
            runBlocking { context.start() }
            runBlocking { context.ingestConversation(IngestionTurns.turn()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "通知必须已进入挂起状态")

            context.close()
            val impl = context as AlcyoneContextImpl
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (impl.notificationJobCountForTesting() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            assertEquals(0, impl.notificationJobCountForTesting(), "close 后不得残留通知 Job")
            assertTrue(reported.any { it is CancellationException }, "关闭必须把取消上报给 reporter")
            assertFalse(
                reported.joinToString("|") { it.message.orEmpty() }.contains("runtime-1"),
                "上报内容不得含 SessionKey 值",
            )
        }

    private fun createWithNotifier(
        fixture: ContextFixture,
        notifier: suspend (SessionKey) -> Unit,
        reporter: (Throwable) -> Unit = { },
    ): AlcyoneContext =
        AlcyoneContextFactory.create(
            fixture.config(),
            fixture.vfsConfig(),
            object : AlcyoneContextFactory.StartProbe {
                override suspend fun afterResourcesAcquired() = Unit
            },
            ingestionNotifier = IngestionNotifier { key -> notifier(key) },
            ingestionReporter = IngestionNotificationReporter { cause -> reporter(cause) },
        )
}
