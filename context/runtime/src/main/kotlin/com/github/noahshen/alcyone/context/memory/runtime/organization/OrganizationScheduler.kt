package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** 一次 Organization Trigger 对应的完整固定 Pending 轮次。 */
internal fun interface OrganizationRoundRunner {
    suspend fun runRound()
}

/** Organization Worker 的内部状态；B2 再映射到 public WorkerStatus。 */
internal enum class OrganizationWorkerStatus {
    IDLE,
    RUNNING,
    STOPPED,
    FAILED,
}

/**
 * CT16 单 Worker、饱和 Trigger Counter。
 *
 * request 仅 RUNNING 接受；Worker 每完成一整轮才消费一个 Trigger。运行期间新 request 只增加计数，
 * 不启动第二个 Worker。计数与 Worker 退出判断共用同一短锁，退出瞬间的新 request 要么阻止退出，
 * 要么在 Worker 置空后启动新的唯一 Worker。轮级 ContextPersistenceException 在仍有计数时执行固定
 * 1 秒可取消退避；单条失败由 Runner 自己隔离，不进入 FAILED。
 */
internal class OrganizationScheduler(
    private val runner: OrganizationRoundRunner,
    private val backoff: suspend () -> Unit = { delay(FAILURE_BACKOFF_MILLIS) },
    private val logger: Logger = LoggerFactory.getLogger(OrganizationScheduler::class.java),
) {
    private enum class State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private val lock = Any()

    private var state: State = State.NEW

    /** Trigger 计数，饱和到 Int.MAX_VALUE。 */
    private var triggerCount: Int = 0

    private var scope: CoroutineScope? = null

    private var parent: CompletableJob? = null

    private var worker: Job? = null

    private var closeCompletion: CompletableDeferred<Throwable?>? = null

    private var runningRound: Boolean = false

    private var failed: Boolean = false

    private var rounds: Long = 0

    private var workerCreations: Int = 0

    /** 测试 seam：清理的最后一步，用于注入失败或阻塞。 */
    internal var closeActionForTesting: (() -> Unit)? = null

    internal fun start() {
        synchronized(lock) {
            when (state) {
                State.RUNNING -> return
                State.NEW -> {
                    state = State.RUNNING
                    val newParent = SupervisorJob()
                    parent = newParent
                    scope = CoroutineScope(newParent + Dispatchers.IO)
                }
                State.CLOSING, State.CLOSED ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.CLOSED,
                        "organization scheduler is closed",
                    )
            }
        }
    }

    fun request(): Boolean {
        var toStart: Job? = null
        val accepted =
            synchronized(lock) {
                if (state != State.RUNNING) {
                    false
                } else {
                    triggerCount = if (triggerCount == Int.MAX_VALUE) Int.MAX_VALUE else triggerCount + 1
                    if (worker == null) {
                        val targetScope = scope ?: error("running organization scheduler must own a scope")
                        val nextWorker = targetScope.launch(start = CoroutineStart.LAZY) { runWorker() }
                        worker = nextWorker
                        workerCreations++
                        toStart = nextWorker
                    }
                    true
                }
            }
        toStart?.start()
        return accepted
    }

    /** 同步封闭准入；可由 Context 在状态锁内调用。 */
    fun beginClosing() {
        synchronized(lock) {
            if (state == State.NEW || state == State.RUNNING) state = State.CLOSING
        }
    }

    suspend fun closeAsync() {
        val currentJob = currentCoroutineContext()[Job]
        if (currentJob != null && currentJob === synchronized(lock) { worker }) {
            throw ContextLifecycleException(
                ContextLifecycleErrorCode.CLOSED,
                "closeAsync() cannot be called from inside its own organization round; let the round finish first",
            )
        }

        var owner = false
        val completion =
            synchronized(lock) {
                val existing = closeCompletion
                if (existing != null) {
                    existing
                } else {
                    owner = true
                    state = State.CLOSING
                    CompletableDeferred<Throwable?>().also { closeCompletion = it }
                }
            }
        if (!owner) {
            completion.await()?.let { throw it }
            return
        }

        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                val workerToJoin = synchronized(lock) { worker }
                workerToJoin?.cancel(CancellationException("organization scheduler is closing"))
                workerToJoin?.join()
                closeActionForTesting?.invoke()
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                val parentToComplete = synchronized(lock) { parent }
                synchronized(lock) {
                    triggerCount = 0
                    runningRound = false
                    worker = null
                    parent = null
                    scope = null
                    state = State.CLOSED
                }
                parentToComplete?.complete()
                completion.complete(failure)
            }
        }
        failure?.let { throw it }
    }

    internal fun statusForTesting(): OrganizationWorkerStatus =
        synchronized(lock) {
            when {
                failed -> OrganizationWorkerStatus.FAILED
                state == State.RUNNING && runningRound -> OrganizationWorkerStatus.RUNNING
                state == State.CLOSING || state == State.CLOSED -> OrganizationWorkerStatus.STOPPED
                else -> OrganizationWorkerStatus.IDLE
            }
        }

    internal fun stateForTesting(): String = synchronized(lock) { state.name }

    internal fun triggerCountForTesting(): Int = synchronized(lock) { triggerCount }

    /** Organization 以饱和计数表达待消费 Trigger；此访问器作为 queue depth 诊断别名。 */
    internal fun queueDepthForTesting(): Int = triggerCountForTesting()

    internal fun roundCountForTesting(): Long = synchronized(lock) { rounds }

    internal fun workerCreationCountForTesting(): Int = synchronized(lock) { workerCreations }

    internal fun setTriggerCountForTesting(count: Int) {
        synchronized(lock) { triggerCount = count.coerceAtLeast(0) }
    }

    private suspend fun runWorker() {
        try {
            while (true) {
                val shouldRun =
                    synchronized(lock) {
                        if (state != State.RUNNING || triggerCount <= 0) {
                            worker = null
                            false
                        } else {
                            runningRound = true
                            true
                        }
                    }
                if (!shouldRun) return

                var roundFailure: Throwable? = null
                try {
                    runner.runRound()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    roundFailure = failure
                }

                var continueWorker = false
                synchronized(lock) {
                    runningRound = false
                    rounds++
                    if (triggerCount > 0) triggerCount--
                    continueWorker = state == State.RUNNING && triggerCount > 0
                    if (!continueWorker) worker = null
                }
                if (roundFailure != null) logger.warn("organization.round.failed")
                if (roundFailure is ContextPersistenceException && continueWorker) backoff()
                if (!continueWorker) return
            }
        } catch (cancellation: CancellationException) {
            val unexpected =
                synchronized(lock) {
                    runningRound = false
                    worker = null
                    if (state == State.RUNNING) {
                        failed = true
                        state = State.CLOSING
                        true
                    } else {
                        false
                    }
                }
            if (unexpected) logger.warn("organization.worker.stopped")
            throw cancellation
        } catch (_: Throwable) {
            synchronized(lock) {
                runningRound = false
                worker = null
                failed = true
                state = State.CLOSING
            }
            logger.warn("organization.worker.failed")
        }
    }

    internal companion object {
        const val FAILURE_BACKOFF_MILLIS: Long = 1_000L
    }
}
