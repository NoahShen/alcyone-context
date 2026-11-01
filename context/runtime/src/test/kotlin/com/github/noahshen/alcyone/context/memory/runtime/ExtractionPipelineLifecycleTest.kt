package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionPipeline
import com.github.noahshen.alcyone.context.memory.runtime.extraction.IdleTickSource
import com.github.noahshen.alcyone.context.memory.runtime.extraction.TurnStatus
import com.github.noahshen.alcyone.context.memory.runtime.extraction.insertTurn
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CT09-B3：Extraction pipeline 与 Runtime 生命周期接线。
 *
 * 覆盖委派单 §5 的 13 项：公共入口零 pipeline、无 Runner 不建组件、Count 阈值/DUPLICATE、
 * Idle tick、`idle.enabled=false`、关闭取消在途 Runner 并等 finally、释放顺序与失败继续释放、
 * start/close 竞争、pipeline 启动失败逆序清理、Runner/Idle 回调内关闭具名拒绝、关闭后 0 触发、
 * 并发 close 共享失败。
 */
class ExtractionPipelineLifecycleTest {
    private val sessionKey = SessionKey("runtime-1", "agent-1", "session-1")
    private val now: Instant = Instant.parse("2026-10-30T10:00:00Z")

    @Test
    fun `the public factory creates no pipeline and behaves like CT08`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                assertNull(impl.pipelineForTesting(), "公共 create 不得创建 pipeline")
            } finally {
                context.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `without a runner no pipeline cron or worker is created`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val ticks = ManualTicks()
            val context = createContext(fixture, runner = null, idleTickSource = ticks)
            val impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            try {
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                assertNull(impl.pipelineForTesting())
                ticks.tick()
                delay(200)
            } finally {
                context.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `the count trigger runs the runner only after the threshold and ignores duplicates`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val runner = FakeRunner()
            val context = createContext(fixture, runner = runner)
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertNotNull(impl.pipelineForTesting())

                repeat(4) { index ->
                    assertEquals(IngestStatus.CREATED, runBlocking { context.ingestConversation(turn(index)) }.status)
                }
                awaitNotificationsDrained(impl)
                assertEquals(0, runner.executions.size, "4 条 pending 不得触发")

                assertEquals(IngestStatus.CREATED, runBlocking { context.ingestConversation(turn(4)) }.status)
                awaitUntil { runner.executions.size == 1 }
                assertEquals(listOf(sessionKey), runner.executions)

                // DUPLICATE 不通知，因此不会再次触发。
                assertEquals(IngestStatus.DUPLICATE, runBlocking { context.ingestConversation(turn(0)) }.status)
                awaitNotificationsDrained(impl)
                delay(150)
                assertEquals(1, runner.executions.size, "DUPLICATE 不得重复执行")
            } finally {
                context.close()
            }
        }

    @Test
    fun `an idle tick runs the runner and a non idle session does not`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val runner = FakeRunner()
            val ticks = ManualTicks()
            val context = createContext(fixture, runner = runner, idleTickSource = ticks)
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                val database = assertNotNull(impl.databaseForTesting())
                insertTurn(database, sessionKey, "idle-turn-1", now.minusSeconds(3_600))

                ticks.tick()
                awaitUntil { runner.executions.size == 1 }
                assertEquals(listOf(sessionKey), runner.executions)

                // 让会话不再空闲后再 tick：不得执行。
                insertTurn(database, sessionKey, "idle-turn-2", now, TurnStatus.COMPLETE)
                ticks.tick()
                delay(300)
                assertEquals(1, runner.executions.size, "无空闲会话不得执行")
            } finally {
                context.close()
            }
        }

    @Test
    fun `disabling idle keeps the count and scheduler working without a cron`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val runner = FakeRunner()
            val ticks = ManualTicks()
            val context =
                createContext(
                    fixture,
                    runner = runner,
                    idleTickSource = ticks,
                    extraction = ExtractionConfig(idleEnabled = false),
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertNull(assertNotNull(impl.pipelineForTesting()).idleCronForTesting(), "idle 关闭时不得创建 Cron")

                insertTurn(assertNotNull(impl.databaseForTesting()), sessionKey, "idle-turn-1", now.minusSeconds(3_600))
                ticks.tick()
                delay(300)
                assertEquals(0, runner.executions.size, "idle 关闭时 tick 不得触发")

                repeat(5) { index ->
                    runBlocking { context.ingestConversation(turn(index)) }
                }
                awaitUntil { runner.executions.size == 1 }
            } finally {
                context.close()
            }
        }

    @Test
    fun `close cancels the in-flight runner and waits for its finally`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val runner =
                FakeRunner { _ ->
                    try {
                        entered.countDown()
                        awaitCancellation()
                    } finally {
                        finished.countDown()
                    }
                }
            val context = createContext(fixture, runner = runner)
            val impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            val pipeline = assertNotNull(impl.pipelineForTesting())
            assertTrue(pipeline.schedulerForTesting().request(sessionKey))
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Runner 必须已开始执行")

            context.close()
            assertEquals(0L, finished.count, "close 返回时 Runner finally 必须已执行")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertEquals(1, runner.executions.size, "不得有新的执行")
        }

    @Test
    fun `resources are released pipeline then llm then vfs then executor`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val order = mutableListOf<String>()
            val unique = IllegalStateException("ct09-b3-pipeline-close-failure")
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(llm = llmConfig()),
                    fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    releaseProbe = AlcyoneContextFactory.ReleaseProbe { target -> order += target.name },
                    clock = fixedClock(),
                    extractionRunner = FakeRunner(),
                    pipelineConfigurator = { pipeline ->
                        pipeline.closeHookForTesting = { order += "PIPELINE" }
                        pipeline.closeFailureForTesting = unique
                    },
                )
            val impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            val llmClient = assertNotNull(impl.llmClientForTesting())
            llmClient.closeActionForTesting = { order += "LLM" }

            val failure = assertFailsWith<IllegalStateException> { context.close() }
            assertSame(unique, failure, "pipeline 清理失败必须作为主失败抛出")
            assertEquals(listOf("PIPELINE", "LLM", "VFS", "EXECUTOR"), order, "释放顺序与失败继续释放")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `close wins the race against start without a false start failure`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val runner = FakeRunner()
            val context =
                createContext(
                    fixture,
                    runner = runner,
                    startProbe =
                        object : AlcyoneContextFactory.StartProbe {
                            override suspend fun afterResourcesAcquired() {
                                entered.countDown()
                                assertTrue(release.await(10, TimeUnit.SECONDS), "探针必须有界放行")
                            }
                        },
                )
            val impl = context as AlcyoneContextImpl
            val pool = Executors.newFixedThreadPool(2)
            try {
                val startFailure = AtomicReference<Throwable?>()
                val startFuture =
                    pool.submit {
                        runBlocking { startFailure.set(runCatching { context.start() }.exceptionOrNull()) }
                    }
                assertTrue(entered.await(10, TimeUnit.SECONDS), "start 必须已启动 pipeline 并进入探针")

                val closeFailure = AtomicReference<Throwable?>()
                val closeFuture = pool.submit { closeFailure.set(runCatching { context.close() }.exceptionOrNull()) }
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
                assertNull(impl.pipelineForTesting())
                assertTrue(runner.executions.isEmpty(), "RUNNING 之前（含 close 获胜）不得执行 Runner")
            } finally {
                release.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `a pipeline start failure is reported and the acquired resources are released`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val released = mutableListOf<AlcyoneContextFactory.ReleaseTarget>()
            val unique = IllegalStateException("ct09-b3-pipeline-start-failure")
            val context =
                createContext(
                    fixture,
                    runner = FakeRunner(),
                    releaseProbe = AlcyoneContextFactory.ReleaseProbe { target -> released += target },
                    configurator = { pipeline -> pipeline.startFailureForTesting = unique },
                )
            try {
                val failure = assertFailsWith<IllegalStateException> { runBlocking { context.start() } }
                assertSame(unique, failure, "必须抛出原始启动失败")
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertEquals(
                    listOf(AlcyoneContextFactory.ReleaseTarget.VFS, AlcyoneContextFactory.ReleaseTarget.EXECUTOR),
                    released,
                    "逆序清理必须释放 VFS 与执行器",
                )
            } finally {
                // 启动失败后 close 会重抛同一共享失败，这里只做兜底清理。
                runCatching { context.close() }
            }
        }

    @Test
    fun `closing from inside the runner is rejected by name without self waiting`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val rejections = CopyOnWriteArrayList<Throwable?>()
            lateinit var impl: AlcyoneContextImpl
            val runner =
                FakeRunner { _ ->
                    rejections += runCatching { impl.close() }.exceptionOrNull()
                    rejections += runCatching { runBlocking { impl.closeAsync() } }.exceptionOrNull()
                }
            val context = createContext(fixture, runner = runner)
            impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            try {
                val pipeline = assertNotNull(impl.pipelineForTesting())
                assertTrue(pipeline.schedulerForTesting().request(sessionKey))
                awaitUntil { rejections.size == 2 }
                rejections.forEach { rejection ->
                    val failure = assertNotNull(rejection) as ContextLifecycleException
                    assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                    assertTrue(
                        failure.message.orEmpty().contains("cannot be called from inside an extraction runner/scan callback"),
                        failure.message.orEmpty(),
                    )
                }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle, "回调内拒绝不得改变状态")
            } finally {
                context.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `closing from inside an idle request callback is rejected by name`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val rejections = CopyOnWriteArrayList<Throwable?>()
            lateinit var impl: AlcyoneContextImpl
            val ticks = ManualTicks()
            val runner = FakeRunner()
            val context =
                createContext(
                    fixture,
                    runner = runner,
                    idleTickSource = ticks,
                    configurator = { pipeline ->
                        pipeline.idleRequestHookForTesting = {
                            rejections += runCatching { impl.close() }.exceptionOrNull()
                        }
                    },
                )
            impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            try {
                insertTurn(assertNotNull(impl.databaseForTesting()), sessionKey, "turn-1", now.minusSeconds(3_600))
                ticks.tick()
                awaitUntil { rejections.size == 1 }
                val failure = assertNotNull(rejections.single()) as ContextLifecycleException
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                assertTrue(
                    failure.message.orEmpty().contains("cannot be called from inside an extraction runner/scan callback"),
                    failure.message.orEmpty(),
                )
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
            } finally {
                context.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `after close no tick or count notification triggers the runner`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val runner = FakeRunner()
            val ticks = ManualTicks()
            val failures = mutableListOf<Throwable>()
            val context =
                createContext(
                    fixture,
                    runner = runner,
                    idleTickSource = ticks,
                    reporter = IngestionNotificationReporter { failure -> failures += failure },
                )
            val impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            val pipeline: ExtractionPipeline = assertNotNull(impl.pipelineForTesting())
            insertTurn(assertNotNull(impl.databaseForTesting()), sessionKey, "turn-1", now.minusSeconds(3_600))
            context.close()
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)

            ticks.tick()
            delay(300)
            assertEquals(0, runner.executions.size, "关闭后 tick 不得触发")

            // 关闭后 Count 通知：查询已释放的 DB 只会交给既有 reporter，不得触发、不得抛出。
            runBlocking { pipeline.ingestionNotifier.onCreated(sessionKey) }
            assertEquals(0, runner.executions.size, "关闭后通知不得触发")
            assertEquals(1, failures.size, "关闭后的查询失败必须交给既有 reporter")
        }

    @Test
    fun `concurrent closes share the pipeline failure`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val unique = IllegalStateException("ct09-b3-concurrent-close-failure")
            val pipelineCloses =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val context =
                createContext(
                    fixture,
                    runner = FakeRunner(),
                    configurator = { pipeline ->
                        pipeline.closeHookForTesting = { pipelineCloses.incrementAndGet() }
                        pipeline.closeFailureForTesting = unique
                    },
                )
            runBlocking { context.start() }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results =
                    (1..2)
                        .map { pool.submit<Throwable> { runCatching { context.close() }.exceptionOrNull() } }
                        .map { future -> future.get(10, TimeUnit.SECONDS) }
                results.forEach { failure -> assertSame(unique, failure, "并发 close 必须共享同一失败") }
                assertEquals(1, pipelineCloses.get(), "pipeline 关闭只执行一次")
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `a new idle tick cannot start the runner while the context is stopping`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val runner = FakeRunner()
            val ticks = ManualTicks()
            val context = createContext(fixture, runner = runner, idleTickSource = ticks)
            val impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            insertTurn(assertNotNull(impl.databaseForTesting()), sessionKey, "idle-turn-1", now.minusSeconds(3_600))

            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            impl.beforeNotificationSnapshotForTesting = {
                closeEntered.countDown()
                assertTrue(releaseClose.await(10, TimeUnit.SECONDS), "关闭屏障必须有界放行")
            }
            val closerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val closing = closerScope.async { impl.closeAsync() }
            try {
                assertTrue(closeEntered.await(10, TimeUnit.SECONDS), "close 必须已进入 STOPPING 窗口")
                assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)

                // STOPPING 之后的新 Idle tick：不得启动 Runner。
                ticks.tick()
                delay(500)
                assertEquals(0, runner.executions.size, "STOPPING 后新 tick 不得启动 Runner")
            } finally {
                releaseClose.countDown()
                ticks.close()
                withTimeout(10_000L) { closing.await() }
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertNull(impl.pipelineForTesting())
        }

    @Test
    fun `a queued request cannot start the runner while the context is stopping`() =
        pipelineTest { fixture ->
            fixture.initialize()
            val attempts =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val runner =
                FakeRunner {
                    if (attempts.incrementAndGet() == 1) {
                        firstEntered.countDown()
                        assertTrue(releaseFirst.await(10, TimeUnit.SECONDS), "第一项必须有界放行")
                    }
                }
            val context = createContext(fixture, runner = runner)
            val impl = context as AlcyoneContextImpl
            runBlocking { context.start() }
            val pipeline = assertNotNull(impl.pipelineForTesting())
            val scheduler = pipeline.schedulerForTesting()

            val otherSession = SessionKey("runtime-1", "agent-1", "session-2")
            assertTrue(scheduler.request(sessionKey))
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS), "第一项必须已进入 Runner")
            // 另一个会话在全局单 Worker 下进入 Scheduler 队列（排队一项）。
            assertTrue(scheduler.request(otherSession))
            assertEquals(1, scheduler.queueDepthForTesting())

            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            impl.beforeNotificationSnapshotForTesting = {
                closeEntered.countDown()
                assertTrue(releaseClose.await(10, TimeUnit.SECONDS), "关闭屏障必须有界放行")
            }
            val closerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val closing = closerScope.async { impl.closeAsync() }
            try {
                assertTrue(closeEntered.await(10, TimeUnit.SECONDS), "close 必须已进入 STOPPING 窗口")
                assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)
                assertTrue(pipeline.isClosingForTesting(), "STOPPING 时 pipeline 准入必须已封闭")

                // 放行第一项：Worker 会取第二项，但关闭门必须阻止它启动 Runner。
                releaseFirst.countDown()
                delay(500)
                assertEquals(1, runner.executions.size, "STOPPING 后已排队项不得启动 Runner")
            } finally {
                releaseFirst.countDown()
                releaseClose.countDown()
                withTimeout(10_000L) { closing.await() }
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertEquals("CLOSED", scheduler.stateForTesting())
            assertEquals(0, scheduler.queueDepthForTesting())
            assertTrue(scheduler.pendingSessionsForTesting().isEmpty(), "关闭后不得残留排队计数")
        }

    // ------------------------------------------------------------------ 工具

    private fun createContext(
        fixture: ContextFixture,
        runner: ExtractionRunner?,
        extraction: ExtractionConfig = ExtractionConfig(),
        llm: LlmConfig? = null,
        idleTickSource: IdleTickSource? = null,
        startProbe: AlcyoneContextFactory.StartProbe = AlcyoneContextFactory.StartProbe { },
        releaseProbe: AlcyoneContextFactory.ReleaseProbe = AlcyoneContextFactory.ReleaseProbe { },
        reporter: IngestionNotificationReporter = IngestionNotificationReporter { },
        configurator: ((ExtractionPipeline) -> Unit)? = null,
    ): AlcyoneContext =
        AlcyoneContextFactory.create(
            contextConfig = fixture.config().copy(extraction = extraction, llm = llm),
            vfsConfig = fixture.vfsConfig(),
            probe = startProbe,
            releaseProbe = releaseProbe,
            clock = fixedClock(),
            ingestionReporter = reporter,
            extractionRunner = runner,
            idleTickSource = idleTickSource,
            pipelineConfigurator = configurator,
        )

    /** 每个用例：临时目录 + 真实 SQLite 夹具 + `runBlocking`（使 `use` 内可直接调用挂起函数）。 */
    private fun pipelineTest(block: suspend (ContextFixture) -> Unit) {
        ContextFixture().use { fixture -> runBlocking { block(fixture) } }
    }

    private fun fixedClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun llmConfig(): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "extraction" to
                        LlmProfileConfig(
                            name = "extraction",
                            baseUrl = "https://llm.example.com/v1",
                            apiKey = "ct09-b3-llm-key-marker",
                            model = "fast-model",
                            contextWindowTokens = 64_000,
                            jsonMode = LlmResponseFormat.PROMPT_ONLY,
                        ),
                ),
            tasks =
                mapOf(
                    LlmTask.EXTRACTION to
                        LlmTaskConfig(profile = "extraction", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                ),
        )

    private fun turn(index: Int): ConversationTurn =
        ConversationTurn(
            runtimeId = sessionKey.runtimeId,
            agentId = sessionKey.agentId,
            sessionId = sessionKey.sessionId,
            sessionTurnId = "turn-$index",
            timestamp = now,
            messages = listOf(ChatMessage(ChatRole.USER, "hello-$index")),
        )

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(10_000L) {
            while (!condition()) delay(10)
        }
    }

    private suspend fun awaitNotificationsDrained(impl: AlcyoneContextImpl) {
        awaitUntil { impl.notificationJobCountForTesting() == 0 }
    }

    /** 记录执行次数；[behavior] 收到当次执行的 SessionKey。 */
    private class FakeRunner(
        private val behavior: suspend (SessionKey) -> Unit = { },
    ) : ExtractionRunner {
        val executions: MutableList<SessionKey> = CopyOnWriteArrayList()

        override suspend fun execute(sessionKey: SessionKey) {
            executions += sessionKey
            behavior(sessionKey)
        }
    }

    /** 可控 tick 来源：`tick()` 投递一次，`close()` 结束循环。 */
    private class ManualTicks : IdleTickSource {
        private val channel = Channel<Unit>(Channel.UNLIMITED)

        fun tick() {
            channel.trySend(Unit)
        }

        fun close() {
            channel.close()
        }

        override suspend fun awaitTick(): Boolean = !channel.receiveCatching().isClosed
    }
}
