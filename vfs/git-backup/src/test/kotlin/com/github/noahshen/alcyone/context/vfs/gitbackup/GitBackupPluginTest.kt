@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitBackupPluginTest {
    // --- A01：生命周期规则 ---

    @Test
    fun `requestSync before start throws`() =
        runTest {
            val harness = harness(RecordingExecutor())
            try {
                assertFailsWith<IllegalStateException> { harness.plugin.requestSync() }
            } finally {
                harness.plugin.close()
            }
        }

    @Test
    fun `start is idempotent and runs a single startup check`() =
        runTest {
            val executor = RecordingExecutor()
            val harness = harness(executor)
            try {
                harness.plugin.start()
                harness.plugin.start()
                runCurrent()
                assertEquals(1, executor.runs.size)
                assertEquals(TriggerReason.STARTUP, executor.runs.single().second)
            } finally {
                harness.plugin.close()
            }
        }

    @Test
    fun `requestSync and start after close are rejected and close is idempotent`() =
        runTest {
            val harness = harness(RecordingExecutor())
            harness.plugin.start()
            runCurrent()
            harness.plugin.close()
            harness.plugin.close()
            assertFailsWith<IllegalStateException> { harness.plugin.requestSync() }
            assertFailsWith<IllegalStateException> { harness.plugin.start() }
        }

    // --- A02：防抖 ---

    @Test
    fun `debounce waits full quiet period and resets on new events`() =
        runTest {
            val executor = RecordingExecutor()
            val harness = harness(executor)
            try {
                harness.plugin.start()
                runCurrent()
                executor.clear()

                val uri = "alcyone://notes/a.txt"
                harness.plugin.eventConsumer(testEvent(uri))
                advanceTimeBy(30_000)
                runCurrent()
                harness.plugin.eventConsumer(testEvent(uri))

                // 89 秒：距离第二次通知只过了 59 秒，不应执行。
                advanceTimeBy(59_000)
                runCurrent()
                assertEquals(0, executor.runs.size)

                // 90 秒：静默期满足，执行一次 EVENT。
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(1, executor.runs.size)
                assertEquals(TriggerReason.EVENT, executor.runs.single().second)
            } finally {
                harness.plugin.close()
            }
        }

    @Test
    fun `unrelated and metadata events do not reset the debounce`() =
        runTest {
            val executor = RecordingExecutor()
            val harness = harness(executor)
            try {
                harness.plugin.start()
                runCurrent()
                executor.clear()

                harness.plugin.eventConsumer(testEvent("alcyone://other/a.txt"))
                harness.plugin.eventConsumer(
                    testEvent("alcyone://notes/a.txt", VfsEventType.METADATA_UPDATED),
                )
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(0, executor.runs.size)
            } finally {
                harness.plugin.close()
            }
        }

    @Test
    fun `a due but not yet run debounce timer does not fire after a reset`() =
        runTest {
            val executor = RecordingExecutor()
            val harness = harness(executor)
            try {
                harness.plugin.start()
                runCurrent()
                executor.clear()

                val uri = "alcyone://notes/a.txt"
                harness.plugin.eventConsumer(testEvent(uri))
                // 推进到恰好 60 秒：advanceTimeBy 不会执行恰好到点的计时器。
                advanceTimeBy(60_000)
                // 旧计时器尚未运行就收到新通知，重置代数与计时。
                harness.plugin.eventConsumer(testEvent(uri))
                runCurrent()
                assertEquals(0, executor.runs.size, "Old due timer must not fire after reset")

                // 新的静默期到达后才执行一次。
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, executor.runs.size)
                assertEquals(TriggerReason.EVENT, executor.runs.single().second)
            } finally {
                harness.plugin.close()
            }
        }

    // --- A03：并发与合并 ---

    @Test
    fun `single run at a time and notifications during a run are preserved`() =
        runTest {
            val executor = GatedExecutor()
            val harness = harness(executor)
            try {
                harness.plugin.start()
                runCurrent()
                executor.awaitStarted()
                assertEquals(1, executor.maxConcurrent)

                // 运行中：手动请求 + 事件通知。
                harness.plugin.requestSync()
                harness.plugin.eventConsumer(testEvent("alcyone://notes/a.txt"))

                executor.releaseOne()
                runCurrent()
                executor.awaitStarted()
                assertEquals(1, executor.maxConcurrent)

                executor.releaseOne()
                // 事件静默期到达后触发第三轮。
                advanceTimeBy(60_000)
                runCurrent()
                executor.awaitStarted()
                executor.releaseOne()
                runCurrent()

                assertEquals(1, executor.maxConcurrent)
                assertEquals(listOf(TriggerReason.STARTUP, TriggerReason.MANUAL, TriggerReason.EVENT), executor.runs)
            } finally {
                harness.plugin.close()
            }
        }

    // --- A04：启动 / Cron / 手动，无事件也执行 ---

    @Test
    fun `startup cron and manual run without events`() =
        runTest {
            val executor = RecordingExecutor()
            val harness = harness(executor, config = defaultConfig(cron = "* * * * *"))
            try {
                harness.plugin.start()
                runCurrent()
                assertEquals(listOf(TriggerReason.STARTUP), executor.runs.map { it.second })

                executor.clear()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(listOf(TriggerReason.CRON), executor.runs.map { it.second })

                executor.clear()
                harness.plugin.requestSync()
                runCurrent()
                assertEquals(listOf(TriggerReason.MANUAL), executor.runs.map { it.second })
            } finally {
                harness.plugin.close()
            }
        }

    @Test
    fun `cron is not delayed by the debounce quiet period`() =
        runTest {
            val executor = RecordingExecutor()
            val base = ZonedDateTime.of(2026, 10, 16, 2, 59, 30, 0, ZoneOffset.UTC)
            val harness =
                harness(
                    executor,
                    config = defaultConfig(cron = "0 3 * * *"),
                    clock = offsetClock(base, testScheduler),
                )
            try {
                harness.plugin.start()
                runCurrent()
                executor.clear()

                harness.plugin.eventConsumer(testEvent("alcyone://notes/a.txt"))
                // 30 秒后 Cron 到点，早于 60 秒静默期。
                advanceTimeBy(30_000)
                runCurrent()
                assertEquals(listOf(TriggerReason.CRON), executor.runs.map { it.second })

                // 静默期被 Cron 请求取代，不应再补一次 EVENT。
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(listOf(TriggerReason.CRON), executor.runs.map { it.second })
            } finally {
                harness.plugin.close()
            }
        }

    @Test
    fun `missed cron times do not accumulate`() =
        runTest {
            val executor = GatedExecutor()
            val harness = harness(executor, config = defaultConfig(cron = "* * * * *"))
            try {
                harness.plugin.start()
                runCurrent()
                executor.awaitStarted()

                // 首轮阻塞期间跨过 5 个 Cron 时刻，全部合并为至多一次后续检查。
                advanceTimeBy(5 * 60_000)
                runCurrent()

                executor.releaseOne()
                runCurrent()
                executor.awaitStarted()
                executor.releaseOne()
                runCurrent()

                assertEquals(1, executor.runs.count { it == TriggerReason.CRON })
            } finally {
                harness.plugin.close()
            }
        }

    // --- A05：Consumer 无 IO、关闭后失效 ---

    @Test
    fun `event consumer captured before close cannot restart work`() =
        runTest {
            val executor = RecordingExecutor()
            val harness = harness(executor)
            harness.plugin.start()
            runCurrent()
            executor.clear()

            val oldConsumer = harness.plugin.eventConsumer
            harness.plugin.close()
            oldConsumer(testEvent("alcyone://notes/a.txt"))
            runCurrent()
            assertEquals(0, executor.runs.size)
        }

    @Test
    fun `event consumer does not retain the plugin and releases the worker on close`() =
        runTest {
            val harness = harness(RecordingExecutor())
            harness.plugin.start()
            runCurrent()

            val consumer = harness.plugin.eventConsumer
            // 稳定回调不得持有插件实例。
            assertFalse(
                referencesInstance(consumer, harness.plugin),
                "Event consumer must not retain the GitBackupPlugin instance",
            )
            // 关闭前回调可达 Worker（预期），关闭后必须释放。
            assertTrue(referencesAny(consumer) { it is GitBackupWorker }, "Before close the callback reaches the worker")
            harness.plugin.close()
            assertFalse(
                referencesAny(consumer) { it is GitBackupWorker },
                "After close the callback must not retain the worker",
            )
            assertFalse(
                referencesInstance(consumer, harness.plugin),
                "After close the callback must not retain the plugin",
            )
        }

    // --- A06：资源释放 ---

    @Test
    fun `close cancels in-flight run without cancelling host scope`() =
        runTest {
            val executor = GatedExecutor()
            val harness = harness(executor)
            val hostJob = coroutineContext[Job]!!
            harness.plugin.start()
            runCurrent()
            executor.awaitStarted()

            harness.plugin.close()

            assertTrue(hostJob.isActive, "Host scope must remain active after plugin close")
            assertTrue(harness.scope.coroutineContext[Job]!!.isCancelled, "Plugin scope must be cancelled")
            assertEquals(1, executor.cancelled, "In-flight run must be cancelled")
            assertEquals(1, executor.runs.size, "Close must not trigger an extra push")
        }

    @Test
    fun `close completes cleanup even when the caller is cancelled`() =
        runTest {
            val executor = GatedExecutor()
            val harness = harness(executor)
            harness.plugin.start()
            runCurrent()
            executor.awaitStarted()

            val closer = launch { harness.plugin.close() }
            runCurrent()
            closer.cancel()
            closer.join()

            assertTrue(harness.scope.coroutineContext[Job]!!.isCancelled, "Scope must be cancelled despite caller cancellation")
            assertEquals(1, executor.cancelled, "In-flight run must be cancelled")
            // 第二次 close 立即返回（清理已完成，不会永久跳过）。
            harness.plugin.close()
            assertTrue(harness.scope.coroutineContext[Job]!!.isCancelled)
        }

    @Test
    fun `concurrent close waits for the same cleanup`() =
        runTest {
            val executor = GatedExecutor()
            val harness = harness(executor)
            harness.plugin.start()
            runCurrent()
            executor.awaitStarted()

            val first = async { harness.plugin.close() }
            runCurrent()
            val second = async { harness.plugin.close() }
            runCurrent()
            assertFalse(second.isCompleted, "Concurrent close must not return before cleanup completes")

            first.await()
            second.await()
            assertTrue(harness.scope.coroutineContext[Job]!!.isCancelled)
            assertEquals(1, executor.cancelled)
        }

    // --- A03 补充：已到期排队事件被新事件重置 ---

    @Test
    fun `a queued due event request is invalidated by a newer event`() =
        runTest {
            val executor = GatedExecutor()
            val harness = harness(executor)
            try {
                harness.plugin.start()
                runCurrent()
                executor.awaitStarted()

                val uri = "alcyone://notes/a.txt"
                harness.plugin.eventConsumer(testEvent(uri))
                // t=60：旧事件计时到期并排队（scheduler 仍忙）。
                advanceTimeBy(60_000)
                runCurrent()
                // t=60：新事件使已排队事件失效，重置到 t=120。
                harness.plugin.eventConsumer(testEvent(uri))

                // t=60：旧同步结束，旧排队请求不得立即执行。
                executor.releaseOne()
                runCurrent()
                assertEquals(1, executor.runs.size, "Stale queued event must not run")

                // t=119：新静默期未到，不执行。
                advanceTimeBy(59_000)
                runCurrent()
                assertEquals(1, executor.runs.size)

                // t=120：新静默期到，执行一次 EVENT。
                advanceTimeBy(1_000)
                runCurrent()
                executor.awaitStarted()
                assertEquals(listOf(TriggerReason.STARTUP, TriggerReason.EVENT), executor.runs)
            } finally {
                harness.plugin.close()
            }
        }

    @Test
    fun `immediate request supersedes a due event request`() =
        runTest {
            val executor = GatedExecutor()
            val harness = harness(executor)
            try {
                harness.plugin.start()
                runCurrent()
                executor.awaitStarted()

                harness.plugin.eventConsumer(testEvent("alcyone://notes/a.txt"))
                advanceTimeBy(60_000)
                runCurrent()
                // 事件已到期排队，但立即请求取代它。
                harness.plugin.requestSync()

                executor.releaseOne()
                runCurrent()
                executor.awaitStarted()
                assertEquals(TriggerReason.MANUAL, executor.runs[1])

                executor.releaseOne()
                runCurrent()
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(2, executor.runs.size, "Superseded event must not run separately")
            } finally {
                harness.plugin.close()
            }
        }

    // --- A07：日志契约 ---

    @Test
    fun `each run logs start and result with runId and trigger`() =
        runTest {
            val logger = RecordingLogger()
            val executor = RecordingExecutor()
            val harness = harness(executor, logger = logger)
            try {
                harness.plugin.start()
                runCurrent()
                harness.plugin.requestSync()
                runCurrent()
            } finally {
                harness.plugin.close()
            }

            val messages = logger.messages()
            assertTrue(messages.any { it.contains("Git backup started, trigger=STARTUP") }, messages.toString())
            assertTrue(messages.any { it.contains("Git backup started, trigger=MANUAL") }, messages.toString())
            assertTrue(messages.any { it.contains("Git backup completed: trigger=") }, messages.toString())

            val startMessages = messages.filter { it.contains("Git backup started") }
            assertEquals(2, startMessages.size)
            val runIds = startMessages.map { it.substringAfter("[").substringBefore("]") }
            assertEquals(2, runIds.toSet().size, "Each run must use a distinct runId")
        }

    @Test
    fun `skipped failed and cancelled runs log their outcome`() =
        runTest {
            val skipLogger = RecordingLogger()
            val skipHarness =
                harness(
                    RecordingExecutor { SyncResult.SkippedNoContent(Duration.ofMillis(5)) },
                    logger = skipLogger,
                )
            try {
                skipHarness.plugin.start()
                runCurrent()
            } finally {
                skipHarness.plugin.close()
            }
            assertTrue(
                skipLogger.messages().any { it.contains("Git backup skipped:") && it.contains("no content") },
                skipLogger.messages().toString(),
            )

            val failLogger = RecordingLogger()
            val failHarness =
                harness(
                    RecordingExecutor {
                        SyncResult.Failed(SyncStage.PUSH, GitSecurityError.PUSH_PUSH_FAILED.name, Duration.ofMillis(7))
                    },
                    logger = failLogger,
                )
            try {
                failHarness.plugin.start()
                runCurrent()
            } finally {
                failHarness.plugin.close()
            }
            assertTrue(
                failLogger.messages().any {
                    it.contains("Git backup failed:") && it.contains("stage=PUSH") && it.contains(GitSecurityError.PUSH_PUSH_FAILED.name)
                },
                failLogger.messages().toString(),
            )

            val cancelLogger = RecordingLogger()
            val cancelHarness = harness(CancellingExecutor(), logger = cancelLogger)
            try {
                cancelHarness.plugin.start()
                runCurrent()
            } finally {
                cancelHarness.plugin.close()
            }
            assertTrue(
                cancelLogger.messages().any { it.contains("Git backup cancelled") },
                cancelLogger.messages().toString(),
            )
        }

    // --- helpers ---

    private fun defaultConfig(
        quietPeriod: Duration = Duration.ofSeconds(60),
        cron: String = "0 3 * * *",
    ): GitBackupConfig =
        GitBackupConfig(
            repositoryRoot = Path.of("/tmp/t35-test-workspace"),
            eventRoots = setOf(VfsUri.parse("alcyone://notes")),
            branch = "main",
            quietPeriod = quietPeriod,
            cron = cron,
        )

    private fun TestScope.harness(
        executor: SyncExecutor,
        logger: RecordingLogger = RecordingLogger(),
        config: GitBackupConfig = defaultConfig(),
        clock: Clock = virtualClock(testScheduler),
    ): Harness {
        val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + StandardTestDispatcher(testScheduler))
        val runtime = GitBackupRuntime(scope, clock, executor, logger)
        return Harness(GitBackupPlugin(config, runtime), scope)
    }

    private class GatedCancellingExecutor : SyncExecutor {
        private val started = Channel<Unit>(Channel.UNLIMITED)
        private val release = Channel<Unit>(Channel.UNLIMITED)
        val runs = mutableListOf<TriggerReason>()

        override suspend fun execute(
            runId: String,
            reason: TriggerReason,
        ): SyncResult {
            runs.add(reason)
            started.trySend(Unit)
            release.receive()
            return SyncResult.Failed(
                stage = SyncStage.PUSH,
                message = GitSecurityError.PUSH_PUSH_CANCELLED.name,
                duration = Duration.ofMillis(11),
                cancelled = true,
            )
        }

        suspend fun awaitStarted() = started.receive()

        fun releaseOne() = release.trySend(Unit)
    }

    private class Harness(
        val plugin: GitBackupPlugin,
        val scope: CoroutineScope,
    )

    private class RecordingExecutor(
        private val resultProvider: () -> SyncResult = {
            SyncResult.Success(false, "sha-1", true, Duration.ZERO)
        },
    ) : SyncExecutor {
        val runs = mutableListOf<Pair<String, TriggerReason>>()

        override suspend fun execute(
            runId: String,
            reason: TriggerReason,
        ): SyncResult {
            runs.add(runId to reason)
            return resultProvider()
        }

        fun clear() = runs.clear()
    }

    private class CancellingExecutor : SyncExecutor {
        override suspend fun execute(
            runId: String,
            reason: TriggerReason,
        ): SyncResult = throw CancellationException("cancelled by test")
    }

    private class GatedExecutor : SyncExecutor {
        private val started = Channel<Unit>(Channel.UNLIMITED)
        private val release = Channel<Unit>(Channel.UNLIMITED)
        val runs = mutableListOf<TriggerReason>()
        var maxConcurrent = 0
        var cancelled = 0
        private var concurrent = 0

        override suspend fun execute(
            runId: String,
            reason: TriggerReason,
        ): SyncResult {
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            runs.add(reason)
            started.trySend(Unit)
            try {
                release.receive()
            } catch (e: CancellationException) {
                concurrent--
                cancelled++
                throw e
            }
            concurrent--
            return SyncResult.Success(false, "sha-1", true, Duration.ZERO)
        }

        suspend fun awaitStarted() {
            started.receive()
        }

        fun releaseOne() {
            release.trySend(Unit)
        }
    }

    @Test
    fun `cancelled result stops timers and rejects requests`() =
        runTest {
            val logger = RecordingLogger()
            val executor = GatedCancellingExecutor()
            val harness =
                harness(
                    executor,
                    logger = logger,
                    config = defaultConfig(quietPeriod = Duration.ofMillis(10), cron = "* * * * *"),
                )
            try {
                harness.plugin.start()
                runCurrent()
                executor.awaitStarted()

                // 在取消前布置真实 debounce，避免只验证空闲终止。
                harness.plugin.eventConsumer(testEvent("alcyone://notes/a.txt"))
                runCurrent()

                executor.releaseOne()
                runCurrent()

                assertFailsWith<IllegalStateException> { harness.plugin.requestSync() }

                // 取消后推进受控时间，确认无新增工作与残余待执行状态。
                advanceTimeBy(100_000)
                runCurrent()
                assertEquals(1, executor.runs.size, "Cancelled termination must not run later work")

                assertTrue(
                    logger.messages().any { it.contains("Git backup cancelled") },
                    logger.messages().toString(),
                )

                harness.plugin.close()
                harness.plugin.close()
                assertFalse(harness.scope.coroutineContext[Job]!!.isActive, "Plugin scope must be cancelled by close")
                assertTrue(coroutineContext[Job]!!.isActive, "Host scope must remain active")
            } finally {
                executor.releaseOne()
                harness.plugin.close()
            }
        }

    @Test
    fun `cancellation exception terminates scheduling and records duration`() =
        runTest {
            val logger = RecordingLogger()
            val executor = CancellingExecutor()
            val harness = harness(executor, logger = logger)
            try {
                harness.plugin.start()
                runCurrent()

                assertFailsWith<IllegalStateException> { harness.plugin.requestSync() }

                val cancelledLogs =
                    logger.messages().filter { it.contains("Git backup cancelled") && it.contains("durationMs=") }
                assertTrue(cancelledLogs.isNotEmpty(), logger.messages().toString())
            } finally {
                harness.plugin.close()
            }
            assertTrue(coroutineContext[Job]!!.isActive, "Host scope must remain active")
        }
}
