package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.common.newUuidV7
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException

/**
 * 每个仓库一个的串行 Worker。
 *
 * - 内存只保留最新通知的变更代数、待立即执行请求与待执行事件，不保存逐条事件队列；
 * - 相关事件重置为完整 [GitBackupConfig.quietPeriod]；事件待执行请求携带代数，领取前重新核对，
 *   新事件会使旧事件请求失效，但不会推迟 MANUAL/CRON/STARTUP 立即请求；
 * - Cron / 手动 / 启动请求立即执行，不等静默期；
 * - 任何时刻最多执行一个 Git 流程；运行中的新通知保留其倒计时，不会被结束逻辑抹掉；
 * - Git I/O 在临界区之外执行，通知与手动请求不会被它阻塞。
 *
 * 本类是运行日志的唯一责任层：每次实际执行恰好一条开始与一条终结记录；[GitRepository] 不再重复记录。
 * 所有调度使用协程 `delay`（单调推进），与 Cron 的本机日历 [Clock] 分开。
 */
internal class GitBackupWorker(
    private val config: GitBackupConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val executor: SyncExecutor,
    private val logger: Logger,
    private val runIdFactory: () -> String = { newUuidV7().toString() },
) {
    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var closing = false

    /** 调度是否已终止（取消结果 / 取消异常 / 显式关闭）。 */
    internal val terminated: Boolean
        get() = closing
    private var started = false
    private var pendingImmediate = false
    private var pendingReason: TriggerReason? = null
    private var pendingEvent = false
    private var eventGeneration = 0L
    private var generation = 0L
    private var debounceJob: Job? = null
    private var schedulerJob: Job? = null
    private var cronJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (closing || started) return
            started = true
            schedulerJob = scope.launch { schedulerLoop() }
            cronJob = scope.launch { cronLoop() }
        }
        requestImmediate(TriggerReason.STARTUP)
    }

    /**
     * 相关变更事件：重置为完整静默期，并使任何尚未领取的事件请求失效。
     */
    fun onRelevantEvent() {
        synchronized(lock) {
            if (closing || !started) return
            generation++
            val currentGeneration = generation
            // 使旧的事件待执行请求失效：新静默期到期前不应执行旧请求。
            pendingEvent = false
            debounceJob?.cancel()
            debounceJob =
                scope.launch {
                    delay(config.quietPeriod.toMillis())
                    val shouldFire =
                        synchronized(lock) {
                            if (closing || generation != currentGeneration) {
                                false
                            } else {
                                debounceJob = null
                                pendingEvent = true
                                eventGeneration = currentGeneration
                                true
                            }
                        }
                    if (shouldFire) signal.trySend(Unit)
                }
        }
    }

    /**
     * 立即请求一次完整检查（启动 / Cron / 手动），不等静默期；多次立即请求合并为至多一次。
     * 立即请求会取代尚未到期的事件静默计时。
     */
    fun requestImmediate(reason: TriggerReason) {
        synchronized(lock) {
            if (closing || !started) return
            pendingImmediate = true
            pendingReason = reason
            pendingEvent = false
            debounceJob?.cancel()
            debounceJob = null
        }
        signal.trySend(Unit)
    }

    /**
     * 停止接纳、取消计时并等待/清理在途流程。只释放自身资源，不追加退出前 push。
     */
    suspend fun shutdown() {
        val scheduler: Job?
        synchronized(lock) {
            if (closing) {
                scheduler = schedulerJob
            } else {
                closing = true
                debounceJob?.cancel()
                debounceJob = null
                pendingEvent = false
                cronJob?.cancel()
                cronJob = null
                scheduler = schedulerJob
            }
        }
        signal.trySend(Unit)
        if (scheduler != null) {
            val finished =
                withTimeoutOrNull(config.shutdownGracePeriod.toMillis()) {
                    scheduler.join()
                    true
                } ?: false
            if (!finished) {
                scheduler.cancel()
                scheduler.join()
            }
        }
    }

    private suspend fun schedulerLoop() {
        try {
            while (!closing) {
                signal.receive()
                if (closing) break
                // 信号只是唤醒：醒来后重新核对全部待执行状态，不允许残留 pending 无信号。
                while (!closing) {
                    val reason = nextDueReason() ?: break
                    if (!runSync(reason)) {
                        terminateScheduling()
                        return
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            terminateScheduling()
            throw cancelled
        }
    }

    /**
     * 取消终止路径统一入口：停自身计时、关闭接纳、清除待处理信号。
     */
    private fun terminateScheduling() {
        val cron: Job?
        synchronized(lock) {
            closing = true
            debounceJob?.cancel()
            debounceJob = null
            pendingImmediate = false
            pendingReason = null
            pendingEvent = false
            cron = cronJob
            cronJob = null
        }
        cron?.cancel()
        signal.trySend(Unit)
    }

    /**
     * 领取下一个应执行的触发原因。
     *
     * - 立即请求（MANUAL/CRON/STARTUP）优先且不失效；
     * - 事件请求只有在代数仍为最新时才有效，否则丢弃（新事件会重新计时）。
     */
    private fun nextDueReason(): TriggerReason? =
        synchronized(lock) {
            if (pendingImmediate) {
                pendingImmediate = false
                val current = pendingReason ?: TriggerReason.MANUAL
                pendingReason = null
                current
            } else if (pendingEvent) {
                pendingEvent = false
                if (eventGeneration == generation) TriggerReason.EVENT else null
            } else {
                null
            }
        }

    /**
     * 执行一轮并写唯一日志。返回 true 表示继续调度，false 表示因取消结果停止。
     */
    private suspend fun runSync(reason: TriggerReason): Boolean {
        val runId = runIdFactory()
        val startNano = System.nanoTime()
        logger.info("[{}] Git backup started, trigger={}", runId, reason)
        val result =
            try {
                executor.execute(runId, reason)
            } catch (cancelled: SyncCancelledException) {
                val captured = cancelled.result
                if (captured is SyncResult.Failed) {
                    logResult(runId, reason, captured)
                } else {
                    logCancelled(runId, reason, null, elapsedMillis(startNano))
                }
                throw cancelled
            } catch (cancelled: CancellationException) {
                logCancelled(runId, reason, null, elapsedMillis(startNano))
                throw cancelled
            } catch (e: Exception) {
                logger.warn(
                    "[{}] Git backup failed: trigger={}, code={}, durationMs={}",
                    runId,
                    reason,
                    GitSecurityError.UNEXPECTED_FAILURE.name,
                    elapsedMillis(startNano),
                )
                // 普通执行器异常不终止调度：等待下一次正常触发。
                return true
            }
        logResult(runId, reason, result)
        return !(result is SyncResult.Failed && result.cancelled)
    }

    private fun logResult(
        runId: String,
        reason: TriggerReason,
        result: SyncResult,
    ) {
        when (result) {
            is SyncResult.Success ->
                logger.info(
                    "[{}] Git backup completed: trigger={}, commitCreated={}, sha={}, pushed={}, durationMs={}",
                    runId,
                    reason,
                    result.commitCreated,
                    result.commitSha,
                    result.pushed,
                    result.duration.toMillis(),
                )

            is SyncResult.SkippedNoContent ->
                logger.info(
                    "[{}] Git backup skipped: trigger={}, no content, durationMs={}",
                    runId,
                    reason,
                    result.duration.toMillis(),
                )

            is SyncResult.Failed ->
                if (result.cancelled) {
                    logCancelled(runId, reason, result, result.duration.toMillis())
                } else {
                    logger.warn(
                        "[{}] Git backup failed: trigger={}, stage={}, code={}, durationMs={}",
                        runId,
                        reason,
                        result.stage,
                        result.message,
                        result.duration.toMillis(),
                    )
                }
        }
    }

    private fun logCancelled(
        runId: String,
        reason: TriggerReason,
        result: SyncResult.Failed?,
        durationMs: Long,
    ) {
        if (result != null) {
            logger.info(
                "[{}] Git backup cancelled: trigger={}, stage={}, code={}, durationMs={}",
                runId,
                reason,
                result.stage,
                result.message,
                result.duration.toMillis(),
            )
        } else {
            logger.info(
                "[{}] Git backup cancelled: trigger={}, durationMs={}",
                runId,
                reason,
                durationMs,
            )
        }
    }

    private fun elapsedMillis(startNano: Long): Long = Duration.ofNanos(System.nanoTime() - startNano).toMillis()

    private suspend fun cronLoop() {
        val schedule = CronBackupSchedule(config.cron, clock)
        while (!closing) {
            val now = ZonedDateTime.now(clock)
            val next = schedule.nextExecution(now) ?: return
            val waitMillis = Duration.between(now, next).toMillis().coerceAtLeast(1L)
            delay(waitMillis)
            if (closing) break
            // 醒来后只补一次；下一次从当前时间重新计算，不积压补跑。
            requestImmediate(TriggerReason.CRON)
        }
    }
}
