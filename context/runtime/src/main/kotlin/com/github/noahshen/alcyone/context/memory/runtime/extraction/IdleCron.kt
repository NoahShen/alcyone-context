package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 可注入的 tick 来源：`true` = tick 已到达，`false` = 来源关闭；抛异常表示本次调度失败。 */
internal fun interface IdleTickSource {
    suspend fun awaitTick(): Boolean
}

/**
 * CT09-B2 Idle Cron 驱动（Runtime internal）。
 *
 * 语义（D2/D6）：
 * - cron 为 **UNIX 五字段**（分/时/日/月/周），时区固定 `Asia/Shanghai`（[ExtractionDefaults.TIME_ZONE_ID]）；
 * - 每次从当前时刻选择**严格未来**的 tick（`nextAfter(now) > now`），启动**不立即扫描**，
 *   **不补跑**停机或扫描期间错过的 tick（每轮都用当前时刻重算，不做追赶），无热更新；
 * - 每个 tick 触发一次 [IdleTrigger.scanOnce]；扫描以**结构化子 Job 异步启动**，循环不 await 扫描完成，
 *   因此前一扫描未结束时到达的 tick 会真实走到 single-flight 并产生 skip（不得用串行 await 规避重叠）；
 * - 调度失败（tick 来源抛异常）：`extraction.idle.failed` WARN 后执行**内部固定 1 秒的可取消退避**
 *   （不新增配置、不是 tick：退避结束不扫描、不补跑，只回到循环重新计算严格未来 tick）；来源关闭/
 *   无下一 tick 结束循环。每轮等待 tick 前都 `ensureActive()`，因此持续失败也不会形成无挂起点的紧凑循环，
 *   关闭可在 `delay`/等待中立即响应；
 * - [closeAsync]：先停 tick 循环，再取消并 `join` 在途扫描与全部结构化子任务，释放自有 scope parent Job，
 *   关闭后不再触发；共享关闭完成信号，重复关闭安全。
 *
 * tick/clock 可注入：测试用可控 tick 与固定时钟，不真实等待 5 分钟。日志只含固定事件名，不含
 * SessionKey/消息/SQL/异常文本，也不传 Throwable。
 */
internal class IdleCron(
    private val idleTrigger: IdleTrigger,
    cron: String,
    private val clock: Clock = Clock.system(ZONE),
    tickSource: IdleTickSource? = null,
    private val logger: Logger = LoggerFactory.getLogger(IdleCron::class.java),
) {
    private enum class State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private val executionTime: ExecutionTime = ExecutionTime.forCron(CRON_PARSER.parse(cron))

    /** 默认 tick 来源：按 cron 计算严格未来的时刻并 `delay` 到点。 */
    private val ticks: IdleTickSource = tickSource ?: IdleTickSource { awaitNextCronTick() }

    private val lock = Any()

    private var state: State = State.NEW

    private var scope: CoroutineScope? = null

    private var parent: CompletableJob? = null

    private var loop: Job? = null

    private val scans = LinkedHashSet<Job>()

    private var closeCompletion: CompletableDeferred<Unit>? = null

    internal fun stateForTesting(): String = synchronized(lock) { state.name }

    internal fun inFlightScanCountForTesting(): Int = synchronized(lock) { scans.size }

    /**
     * 严格未来的下一次触发时刻；无可用 tick 返回 `null`。
     *
     * 只用注入时钟的 `Instant` 与固定上海时区做换算，不读取墙钟。
     */
    fun nextAfter(now: Instant): Instant? {
        var base = now
        repeat(MAX_NEXT_ATTEMPTS) {
            val next = executionTime.nextExecution(base.atZone(ZONE)).orElse(null) ?: return null
            val candidate = next.toInstant()
            if (candidate.isAfter(now)) return candidate
            // 理论上 nextExecution 已严格未来；防御性推进 1 秒后重算，绝不允许“回到过去”。
            base = candidate.plusSeconds(1)
        }
        return null
    }

    /** 幂等启动：只创建一个 tick 循环；`CLOSING/CLOSED` 后拒绝。 */
    fun start() {
        var toStart: Job? = null
        synchronized(lock) {
            when (state) {
                State.RUNNING -> return
                State.NEW -> {
                    state = State.RUNNING
                    val newParent = SupervisorJob()
                    val newScope = CoroutineScope(newParent + Dispatchers.IO)
                    parent = newParent
                    scope = newScope
                    val newLoop = newScope.launch(start = CoroutineStart.LAZY) { runTicks() }
                    loop = newLoop
                    toStart = newLoop
                }
                State.CLOSING, State.CLOSED ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.CLOSED,
                        "idle cron is closed",
                    )
            }
        }
        toStart?.start()
    }

    /** 停止循环 → 取消并 join 在途扫描 → 释放自有 scope parent Job；幂等且共享完成信号。 */
    suspend fun closeAsync() {
        var isOwner = false
        var completion: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
            } else {
                val created = CompletableDeferred<Unit>()
                closeCompletion = created
                state = State.CLOSING
                completion = created
                isOwner = true
            }
        }
        val result = completion ?: error("idle cron close completion must exist")
        if (!isOwner) {
            result.await()
            return
        }
        withContext(NonCancellable) {
            try {
                // 1) 先停 tick 循环：此后不再产生新扫描。
                val loopToStop = synchronized(lock) { loop }
                loopToStop?.cancel(CancellationException("idle cron is closing"))
                loopToStop?.join()
                // 2) 再取消并真实 join 在途扫描（含其 finally 与结构化子任务）。
                val pending = synchronized(lock) { ArrayList(scans) }
                pending.forEach { scan -> scan.cancel(CancellationException("idle cron is closing")) }
                pending.forEach { scan -> scan.join() }
                // 3) 释放 Scheduler 自有的 scope parent Job（子任务已结束，complete() 不取消子任务）。
                synchronized(lock) { parent }?.complete()
            } finally {
                synchronized(lock) {
                    scans.clear()
                    loop = null
                    scope = null
                    parent = null
                    state = State.CLOSED
                }
                result.complete(Unit)
            }
        }
    }

    private suspend fun runTicks() {
        while (true) {
            // 每轮等待 tick 前检查取消：持续失败时也不会形成无挂起点的紧凑循环。
            currentCoroutineContext().ensureActive()
            val delivered =
                try {
                    ticks.awaitTick()
                } catch (cancellation: CancellationException) {
                    // 取消原样传播，不当作调度失败。
                    throw cancellation
                } catch (failure: Throwable) {
                    // 调度失败：受控 WARN（固定事件名，不附 Throwable/SessionKey/文本），
                    // 然后固定 1s 可取消退避。退避不是 tick：不扫描、不补跑。
                    logger.warn("extraction.idle.failed")
                    delay(SCHEDULING_FAILURE_BACKOFF_MILLIS)
                    continue
                }
            if (!delivered) return
            triggerScan()
        }
    }

    /**
     * 启动一次扫描（**不等待**）：允许下一 tick 在前一扫描未结束时到达，由 [IdleTrigger] 的
     * single-flight 真实跳过。
     */
    private fun triggerScan() {
        val targetScope =
            synchronized(lock) {
                if (state == State.RUNNING) scope else null
            } ?: return
        val scan = targetScope.launch(start = CoroutineStart.LAZY) { idleTrigger.scanOnce() }
        var admitted = false
        synchronized(lock) {
            if (state == State.RUNNING) {
                scans += scan
                admitted = true
            }
        }
        if (!admitted) {
            scan.cancel()
            return
        }
        scan.invokeOnCompletion { synchronized(lock) { scans -= scan } }
        scan.start()
    }

    private suspend fun awaitNextCronTick(): Boolean {
        val now = clock.instant()
        val next = nextAfter(now)
        if (next == null) {
            // 无可用下一 tick（例如永不适用的日期组合）：受控失败，不触发，结束循环。
            logger.warn("extraction.idle.failed")
            return false
        }
        val waitMillis = Duration.between(now, next).toMillis().coerceAtLeast(1L)
        delay(waitMillis)
        return true
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of(ExtractionDefaults.TIME_ZONE_ID)
        val CRON_PARSER: CronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
        const val MAX_NEXT_ATTEMPTS = 3

        /** 调度失败后的内部固定退避（不暴露为配置）：可取消，结束不等于 tick。 */
        const val SCHEDULING_FAILURE_BACKOFF_MILLIS = 1_000L
    }
}
