@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitBackupWorkerTest {
    @Test
    fun `worker rejects work before start and after shutdown`() =
        runTest {
            val executor = CountingExecutor()
            val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + StandardTestDispatcher(testScheduler))
            val worker =
                GitBackupWorker(
                    config = config(),
                    clock = virtualClock(testScheduler),
                    scope = scope,
                    executor = executor,
                    logger = RecordingLogger(),
                )
            try {
                // 未启动：事件与立即请求都不应触发执行。
                worker.onRelevantEvent()
                worker.requestImmediate(TriggerReason.MANUAL)
                runCurrent()
                assertEquals(0, executor.count)

                // 启动后 STARTUP 执行一次。
                worker.start()
                runCurrent()
                assertEquals(1, executor.count)

                // 关闭后即使持有旧引用也无法重启工作。
                worker.shutdown()
                worker.onRelevantEvent()
                worker.requestImmediate(TriggerReason.MANUAL)
                runCurrent()
                assertEquals(1, executor.count)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `generic executor exception logs a controlled failure and keeps scheduling`() =
        runTest {
            var calls = 0
            val executor =
                SyncExecutor { _, _ ->
                    calls++
                    if (calls == 1) throw RuntimeException("boom")
                    SyncResult.Success(false, "sha-1", true, Duration.ZERO)
                }
            val logger = RecordingLogger()
            val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + StandardTestDispatcher(testScheduler))
            val worker = GitBackupWorker(config(), virtualClock(testScheduler), scope, executor, logger)
            try {
                worker.start()
                runCurrent()
                assertTrue(
                    logger.messages().any {
                        it.contains("Git backup failed") && it.contains(GitSecurityError.UNEXPECTED_FAILURE.name)
                    },
                    logger.messages().toString(),
                )
                // 普通异常不终止调度：下一次正常触发仍执行。
                worker.requestImmediate(TriggerReason.MANUAL)
                runCurrent()
                assertEquals(2, calls)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `cancelled result logs stage and duration and stops the loop`() =
        runTest {
            val executor =
                SyncExecutor { _, _ ->
                    SyncResult.Failed(
                        stage = SyncStage.PUSH,
                        message = GitSecurityError.PUSH_PUSH_CANCELLED.name,
                        duration = Duration.ofMillis(11),
                        cancelled = true,
                    )
                }
            val logger = RecordingLogger()
            val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + StandardTestDispatcher(testScheduler))
            val worker = GitBackupWorker(config(), virtualClock(testScheduler), scope, executor, logger)
            try {
                worker.start()
                runCurrent()
                val cancelled =
                    logger.messages().filter {
                        it.contains("Git backup cancelled") && it.contains("stage=PUSH") && it.contains("durationMs=11")
                    }
                assertTrue(cancelled.isNotEmpty(), logger.messages().toString())

                // 取消结果后循环停止，不再执行新请求。
                worker.requestImmediate(TriggerReason.MANUAL)
                runCurrent()
                assertEquals(1, logger.messages().count { it.contains("Git backup started") })
            } finally {
                scope.cancel()
            }
        }

    private class GatedExecutor : SyncExecutor {
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
            return SyncResult.Success(false, "sha-1", true, Duration.ZERO)
        }

        suspend fun awaitStarted() = started.receive()

        fun releaseOne() = release.trySend(Unit)
    }

    private fun config(): GitBackupConfig =
        GitBackupConfig(
            repositoryRoot = Path.of("/tmp/t35-worker-test"),
            eventRoots = setOf(VfsUri.parse("alcyone://notes")),
            branch = "main",
            quietPeriod = Duration.ofSeconds(60),
            cron = "0 3 * * *",
        )

    private class CountingExecutor : SyncExecutor {
        var count = 0

        override suspend fun execute(
            runId: String,
            reason: TriggerReason,
        ): SyncResult {
            count++
            return SyncResult.Success(false, "sha-1", true, Duration.ZERO)
        }
    }

    @Test
    fun `pending event is woken and cleared after immediate precedence`() =
        runTest {
            val executor = GatedExecutor()
            val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + StandardTestDispatcher(testScheduler))
            val worker =
                GitBackupWorker(
                    config =
                        GitBackupConfig(
                            repositoryRoot = Path.of("/tmp/t35-pending-probe"),
                            eventRoots = setOf(VfsUri.parse("alcyone://notes")),
                            branch = "main",
                            quietPeriod = Duration.ofMillis(10),
                            cron = "0 3 * * *",
                        ),
                    clock = virtualClock(testScheduler),
                    scope = scope,
                    executor = executor,
                    logger = RecordingLogger(),
                )
            try {
                worker.start()
                runCurrent()
                executor.awaitStarted() // STARTUP blocked

                worker.requestImmediate(TriggerReason.MANUAL)
                worker.onRelevantEvent()

                // 静默期到期，pendingEvent 进入待执行。
                advanceTimeBy(10)
                runCurrent()

                // 释放 STARTUP 与 MANUAL 后，事件请求被唤醒并执行。
                executor.releaseOne()
                runCurrent()
                executor.awaitStarted() // MANUAL
                executor.releaseOne()
                runCurrent()
                executor.awaitStarted() // EVENT

                assertEquals(
                    listOf(TriggerReason.STARTUP, TriggerReason.MANUAL, TriggerReason.EVENT),
                    executor.runs,
                )
            } finally {
                scope.cancel()
            }
        }
}
