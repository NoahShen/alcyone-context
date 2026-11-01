package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * CT09-B1 Extraction 调度器（Runtime internal；Context 生命周期接线在 B3）。
 *
 * 职责：把「某个会话有新轮次」折叠成**至多一个排队项或一个执行位置**，用**单 Worker 全局串行**
 * 调用 [ExtractionRunner.execute]，并把失败隔离在单个会话内。
 *
 * - 状态：`NEW / RUNNING / CLOSING / CLOSED`；[start] 幂等（只有一个消费者），关闭后 `start` 抛
 *   `ContextLifecycleException(CLOSED)`。
 * - [request] 仅在 `RUNNING` 接受：同一会话计数 `Int` **饱和**到 `MAX_VALUE`（不 wrap），只有
 *   `0 → 1` 才入队一次；`trySend` 失败**回滚该次计数**并记录 `extraction.scheduler.enqueue.failed`
 *   （不传 Throwable、不含 SessionKey），返回 false。
 * - 执行完成（正常或普通异常）消费一次计数；剩余 > 0 在锁内排到**队尾**（不饿死其他会话），
 *   归零移除。执行期间同会话不会重复入队。
 * - 普通异常：`extraction.run.failed` WARN（仅固定事件名 + `elapsedMillis` / `pendingSessions`），
 *   隔离后继续下一会话；`CancellationException` 不作为普通失败消费。
 * - 非关闭状态下 Runner 自行抛 `CancellationException` 导致唯一 Worker 结束：Worker **只**在状态锁内
 *   把 `RUNNING → CLOSING`（封闭准入）并记录 `extraction.worker.stopped` WARN；**不自行**置 `CLOSED`、
 *   不清计数、不发布结果，随后由唯一清理 owner 完成真实收尾。
 * - [closeAsync] 是**唯一清理入口**（外部关闭、并发关闭、Worker 非预期退出共用同一清理与结果）：
 *   第一个进入 `CLOSING` 的路径创建共享 `closeOutcome` 并在**调度器自有的 cleanup scope**（sibling Job，
 *   不是失败 Worker 自身，避免 self-join；不继承 Runner 执行标记）中启动清理；owner 全程 `NonCancellable`：
 *   取消 Worker → `cancel(Channel)`（**不 drain**，释放缓冲与排队引用）→ 真实 `join` Worker（等 finally 与
 *   全部结构化子 Job）→ `closeActionForTesting` → 清 `pending`/`queuedItems` → 释放 Worker scope parent Job
 *   与 cleanup scope parent Job、置空引用 → `CLOSED` → **最后**发布结果。失败也发布 `CLOSED` + 同一实例，
 *   重复关闭重抛同一实例，等待者被取消只放弃本次等待。Runner 回调内调用会在改状态前具名拒绝（避免自等待）。
 *
 * 状态、计数、入队、执行准入与关闭封闭共用**同一把普通短锁**（锁内无 I/O、无用户回调、无挂起）。
 * 日志只含固定事件名与受控数值：**不输出 SessionKey、消息、SQL、异常文本，也不把 Throwable 传给 logger**。
 */
internal class ExtractionScheduler(
    private val runner: ExtractionRunner,
) {
    private enum class State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private val logger = LoggerFactory.getLogger(ExtractionScheduler::class.java)

    private val lock = Any()

    private var state: State = State.NEW

    /** 每会话的待执行次数（饱和 `Int.MAX_VALUE`）；`0` 表示无排队项。 */
    private val pending = LinkedHashMap<SessionKey, Int>()

    private val queue = Channel<SessionKey>(Channel.UNLIMITED)

    /** 已成功入队、尚未被 Worker 取走的项数（仅诊断/测试）。 */
    private var queuedItems: Int = 0

    private var scope: CoroutineScope? = null

    private var workerParent: CompletableJob? = null

    private var worker: Job? = null

    /** 唯一清理 owner 的 scope/parent（**不是** Worker 自身，避免 self-join）。 */
    private var cleanupScope: CoroutineScope? = null

    private var cleanupParent: CompletableJob? = null

    private var cleanupJob: Job? = null

    private var closeOutcome: CompletableDeferred<Throwable?>? = null

    private var executions: Long = 0

    /** 测试接缝：owner 清理的最后一个动作（生产为 null），用于注入失败或阻塞。 */
    internal var closeActionForTesting: (() -> Unit)? = null

    // ------------------------------------------------------------------ 测试/诊断访问器

    internal fun stateForTesting(): String = synchronized(lock) { state.name }

    internal fun pendingForTesting(sessionKey: SessionKey): Int = synchronized(lock) { pending[sessionKey] ?: 0 }

    internal fun pendingSessionsForTesting(): Set<SessionKey> = synchronized(lock) { pending.keys.toSet() }

    internal fun queueDepthForTesting(): Int = synchronized(lock) { queuedItems }

    internal fun executionCountForTesting(): Long = synchronized(lock) { executions }

    /** 受控设置计数（饱和用）：不循环 `MAX_VALUE` 次，也不入队。 */
    internal fun setPendingForTesting(
        sessionKey: SessionKey,
        count: Int,
    ) {
        synchronized(lock) {
            if (count <= 0) pending.remove(sessionKey) else pending[sessionKey] = count
        }
    }

    /** 受控关闭队列通道：让后续 `trySend` 失败（验证回滚与 WARN）。 */
    internal fun closeChannelForTesting() {
        queue.close()
    }

    // ------------------------------------------------------------------ 生命周期

    /** 幂等启动：只创建一个 Worker 消费者；`CLOSING/CLOSED` 后拒绝。 */
    fun start() {
        var toStart: Job? = null
        synchronized(lock) {
            when (state) {
                State.RUNNING -> return
                State.NEW -> {
                    state = State.RUNNING
                    val parent = SupervisorJob()
                    val newScope =
                        CoroutineScope(
                            parent + Dispatchers.IO + ExtractionExecutionMarker(this),
                        )
                    scope = newScope
                    workerParent = parent
                    val newWorker = newScope.launch(start = CoroutineStart.LAZY) { runWorker() }
                    worker = newWorker
                    toStart = newWorker
                }
                State.CLOSING, State.CLOSED ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.CLOSED,
                        "extraction scheduler is closed",
                    )
            }
        }
        // 锁外 start：LAZY 任务若在 start 前被取消会直接以取消终结，不会悬挂。
        toStart?.start()
    }

    /**
     * 请求对 [sessionKey] 执行一次提取。
     *
     * 仅 `RUNNING` 接受；关闭后返回 false/no-op 且不打 WARN。
     */
    fun request(sessionKey: SessionKey): Boolean {
        var enqueueFailed = false
        val accepted =
            synchronized(lock) {
                if (state != State.RUNNING) {
                    false
                } else {
                    val current = pending[sessionKey] ?: 0
                    val next = if (current == Int.MAX_VALUE) Int.MAX_VALUE else current + 1
                    pending[sessionKey] = next
                    if (current > 0) {
                        // 已在排队/执行中：只加计数，不重复入队。
                        true
                    } else if (queue.trySend(sessionKey).isSuccess) {
                        queuedItems++
                        true
                    } else {
                        // 回滚：不得留下无人消费的正计数。
                        pending.remove(sessionKey)
                        enqueueFailed = true
                        false
                    }
                }
            }
        if (enqueueFailed) {
            logger.warn("extraction.scheduler.enqueue.failed")
        }
        return accepted
    }

    /**
     * 唯一清理入口（**没有同步 close**）：封闭准入 → 取消 Worker → `cancel(Channel)`（不 drain）→
     * 真实 `join` → 清理计数与 scope → 发布共享结果。
     *
     * 清理本身由**调度器自有的 cleanup scope** 执行（不是失败 Worker 自身，也不是调用方协程），
     * 因此 Worker 非预期退出、外部关闭与并发关闭共享同一次清理和同一成功/失败结果。
     */
    suspend fun closeAsync() {
        if (ExtractionExecutionMarker.currentOwner() === this) {
            // 在 Runner 回调内关闭自身会自等待：改状态之前具名拒绝。
            throw ContextLifecycleException(
                ContextLifecycleErrorCode.CLOSED,
                "closeAsync() cannot be called from inside its own extraction runner; let the runner finish first",
            )
        }
        var cleanupToStart: Job? = null
        val outcome =
            synchronized(lock) {
                cleanupToStart = beginCleanupLocked()
                closeOutcome ?: error("close outcome must be created when closing begins")
            }
        // 锁外 start：LAZY 清理任务若在 start 前被取消会直接以取消终结，不会悬挂。
        cleanupToStart?.start()
        outcome.await()?.let { throw it }
    }

    /**
     * 进入 `CLOSING` 的唯一入口（**调用方必须持有 [lock]**）：第一个调用者创建共享结果与
     * cleanup owner Job 并返回它（需在锁外 `start`）；其余调用者返回 `null`（只等待同一结果）。
     */
    private fun beginCleanupLocked(): Job? {
        if (closeOutcome != null) return null
        val outcome = CompletableDeferred<Throwable?>()
        closeOutcome = outcome
        state = State.CLOSING
        val parent = Job()
        cleanupParent = parent
        // 独立 scope：不继承 Worker/调用方 Job 与 Runner 执行标记（避免 self-join 与自等待）。
        val cleanup = CoroutineScope(parent + Dispatchers.IO)
        cleanupScope = cleanup
        val job = cleanup.launch(start = CoroutineStart.LAZY) { runCleanup(outcome, parent) }
        cleanupJob = job
        return job
    }

    /**
     * 唯一清理 owner 的主体（`NonCancellable`，由 cleanup scope 承载）。
     *
     * 顺序固定：取消 Worker → 丢弃队列（不 drain）→ 真实 `join` Worker → 清理钩子 →
     * 清计数 → 释放 scope parent Job 与引用 → `CLOSED` → 最后发布共享结果。
     */
    private suspend fun runCleanup(
        outcome: CompletableDeferred<Throwable?>,
        ownerParent: CompletableJob,
    ) {
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                val workerToJoin = synchronized(lock) { worker }
                workerToJoin?.cancel(CancellationException("extraction scheduler is closing"))
                // 不 drain：cancel 会丢弃缓冲元素并释放排队引用。
                queue.cancel(CancellationException("extraction scheduler is closing"))
                // 真实 join：等待 Worker 的 finally 与全部结构化子 Job。
                workerToJoin?.join()
                closeActionForTesting?.invoke()
            } catch (throwable: Throwable) {
                // 任何清理失败都必须发布终态，不得滞留 CLOSING。
                failure = throwable
            } finally {
                synchronized(lock) {
                    pending.clear()
                    queuedItems = 0
                    worker = null
                }
                // 释放 Scheduler 自有的 Worker scope parent Job（Worker 已真实结束）。
                synchronized(lock) { workerParent }?.complete()
                synchronized(lock) {
                    workerParent = null
                    scope = null
                }
                // 释放 cleanup scope parent Job：complete() 不取消子任务（本协程是它唯一子任务）。
                ownerParent.complete()
                synchronized(lock) {
                    cleanupParent = null
                    cleanupScope = null
                    cleanupJob = null
                    state = State.CLOSED
                }
                outcome.complete(failure)
            }
        }
    }

    // ------------------------------------------------------------------ Worker

    private suspend fun runWorker() {
        try {
            while (true) {
                val sessionKey = queue.receiveCatching().getOrNull() ?: return
                val admitted =
                    synchronized(lock) {
                        if (queuedItems > 0) queuedItems--
                        // 关闭已封闭准入：不再启动新的 execute。
                        if (state == State.RUNNING) {
                            // 计数在**启动**时累加：诊断语义是「已启动的执行次数」。
                            executions++
                            true
                        } else {
                            false
                        }
                    }
                if (!admitted) return

                val startedAt = monotonicMillis()
                var failed = false
                try {
                    runner.execute(sessionKey)
                } catch (cancellation: CancellationException) {
                    // 关闭取消按关闭协议收尾；非关闭状态的自行取消见下方 catch。
                    throw cancellation
                } catch (failure: Throwable) {
                    failed = true
                }
                val elapsedMillis = monotonicMillis() - startedAt

                var requeueFailed = false
                synchronized(lock) {
                    val remaining = (pending[sessionKey] ?: 0) - 1
                    if (remaining <= 0) {
                        pending.remove(sessionKey)
                    } else {
                        pending[sessionKey] = remaining
                        if (state == State.RUNNING) {
                            // 剩余次数排到队尾：不饿死其他会话。
                            if (queue.trySend(sessionKey).isSuccess) {
                                queuedItems++
                            } else {
                                pending.remove(sessionKey)
                                requeueFailed = true
                            }
                        }
                    }
                }
                if (failed) {
                    logger.warn(
                        "extraction.run.failed elapsedMillis={} pendingSessions={}",
                        elapsedMillis,
                        pendingSessionCount(),
                    )
                }
                if (requeueFailed) {
                    logger.warn("extraction.scheduler.enqueue.failed")
                }
            }
        } catch (cancellation: CancellationException) {
            // 非关闭状态下 Runner 自行抛 CE：唯一 Worker 结束。
            // 这里**只**封闭准入并让终止可观察；真实 join/清理与结果发布由唯一 cleanup owner 完成，
            // 绝不由尚未结束的 Worker 自行宣布清理成功（否则会绕过子任务 join 与清理失败）。
            var cleanupToStart: Job? = null
            val unexpected =
                synchronized(lock) {
                    if (state == State.RUNNING) {
                        cleanupToStart = beginCleanupLocked()
                        true
                    } else {
                        false
                    }
                }
            if (unexpected) {
                logger.warn("extraction.worker.stopped")
                cleanupToStart?.start()
            }
            throw cancellation
        }
    }

    private fun pendingSessionCount(): Int = synchronized(lock) { pending.size }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
}

/**
 * Runner 执行的「来源」标记（复用既有 `NotificationExecutionMarker` 模式）。
 *
 * 协程上下文元素随 `yield` / `withContext` 传播，同时通过 [ThreadContextElement] 维护线程可见值，
 * 供 [ExtractionScheduler.closeAsync] 在**改状态之前**识别「Runner 内关闭自身」并具名拒绝。
 */
internal class ExtractionExecutionMarker(
    private val owner: ExtractionScheduler,
) : ThreadContextElement<Any?>,
    CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Any? {
        val previous = CURRENT_THREAD_MARKER.get()
        CURRENT_THREAD_MARKER.set(owner)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Any?,
    ) {
        if (oldState == null) {
            CURRENT_THREAD_MARKER.remove()
        } else {
            CURRENT_THREAD_MARKER.set(oldState)
        }
    }

    companion object Key : CoroutineContext.Key<ExtractionExecutionMarker> {
        private val CURRENT_THREAD_MARKER = ThreadLocal<Any?>()

        fun currentOwner(): Any? = CURRENT_THREAD_MARKER.get()
    }
}
