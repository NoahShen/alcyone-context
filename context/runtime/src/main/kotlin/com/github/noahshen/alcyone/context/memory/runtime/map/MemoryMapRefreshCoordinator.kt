package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Memory Map Worker 的内部状态；B2 再映射到 public WorkerStatus。 */
internal enum class MemoryMapWorkerStatus {
    IDLE,
    RUNNING,
    STOPPED,
    FAILED,
}

/**
 * CT19 单 Worker、Conflated 刷新协调器。
 *
 * 每个信号都重新读取已发布 Map 与 DB MAX；只有可靠且相等时才跳过生成。发布成功后保守标记
 * STALE，下一信号再次比较水位后才进入 CURRENT。普通失败保留请求前的 View/Status，日志不挂异常。
 */
internal class MemoryMapRefreshCoordinator(
    private val database: ContextDatabase,
    private val reader: MemoryMapBootstrapReader,
    private val service: MemoryMapService,
    private val tracker: MemoryMapStatusTracker,
    private val logger: Logger = LoggerFactory.getLogger(MemoryMapRefreshCoordinator::class.java),
) {
    private enum class State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private val lock = Any()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val activation = CompletableDeferred<Unit>()
    private var state = State.NEW
    private var parent: CompletableJob? = null
    private var scope: CoroutineScope? = null
    private var worker: Job? = null
    private var closeCompletion: CompletableDeferred<Throwable?>? = null
    private var workerStatus = MemoryMapWorkerStatus.IDLE
    private var workerCreations = 0
    private var processedRounds = 0L

    internal var closeActionForTesting: (() -> Unit)? = null

    fun start() {
        var toStart: Job? = null
        synchronized(lock) {
            when (state) {
                State.RUNNING -> return
                State.NEW -> {
                    state = State.RUNNING
                    val newParent = SupervisorJob()
                    parent = newParent
                    val newScope = CoroutineScope(newParent + Dispatchers.IO)
                    scope = newScope
                    worker = newScope.launch(start = CoroutineStart.LAZY) { runWorker() }.also { toStart = it }
                    workerCreations++
                }
                State.CLOSING, State.CLOSED ->
                    throw ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "memory map refresh coordinator is closed")
            }
        }
        toStart?.start()
    }

    fun activate() {
        activation.complete(Unit)
    }

    fun requestRefresh(): Boolean {
        synchronized(lock) {
            if (state != State.RUNNING) return false
        }
        return requests.trySend(Unit).isSuccess
    }

    /** 同步封闭刷新准入；不会创建新的 Worker。 */
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
                "closeAsync() cannot be called from inside its own memory map refresh",
            )
        }

        beginClosing()
        var owner = false
        val completion =
            synchronized(lock) {
                val existing = closeCompletion
                if (existing != null) {
                    existing
                } else {
                    owner = true
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
                workerToJoin?.cancel(CancellationException("memory map refresh coordinator is closing"))
                workerToJoin?.join()
                requests.close()
                closeActionForTesting?.invoke()
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                val parentToComplete = synchronized(lock) { parent }
                synchronized(lock) {
                    worker = null
                    parent = null
                    scope = null
                    workerStatus = MemoryMapWorkerStatus.STOPPED
                    state = State.CLOSED
                }
                parentToComplete?.complete()
                completion.complete(failure)
            }
        }
        failure?.let { throw it }
    }

    internal fun statusForTesting(): MemoryMapWorkerStatus = synchronized(lock) { workerStatus }

    internal fun stateForTesting(): String = synchronized(lock) { state.name }

    internal fun workerCreationCountForTesting(): Int = synchronized(lock) { workerCreations }

    internal fun processedRoundsForTesting(): Long = synchronized(lock) { processedRounds }

    /** 直接执行一轮，用于验证 CancellationException 原样传播等 Worker 内边界。 */
    internal suspend fun processOnceForTesting() {
        processOne()
    }

    private suspend fun runWorker() {
        try {
            while (true) {
                val signal = requests.receiveCatching().getOrNull() ?: return
                check(signal == Unit)
                if (!admissionOpen()) return
                activation.await()
                if (!admissionOpen()) return
                synchronized(lock) { workerStatus = MemoryMapWorkerStatus.RUNNING }
                processOne()
            }
        } catch (cancellation: CancellationException) {
            val unexpected =
                synchronized(lock) {
                    if (state == State.RUNNING) {
                        workerStatus = MemoryMapWorkerStatus.FAILED
                        state = State.CLOSING
                        true
                    } else {
                        false
                    }
                }
            if (unexpected) logger.warn("memoryMap.worker.stopped")
            throw cancellation
        } catch (_: Throwable) {
            synchronized(lock) {
                workerStatus = MemoryMapWorkerStatus.FAILED
                if (state == State.RUNNING) state = State.CLOSING
            }
            logger.warn("memoryMap.worker.failed")
        }
    }

    private suspend fun processOne() {
        val previous = tracker.snapshot()
        var failed = false
        try {
            val read = reader.refresh()
            val databaseMax =
                try {
                    database.read { transaction -> transaction.organizedMemories.maxUpdatedAt() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    tracker.markUnknown()
                    failed = true
                    logger.warn("memoryMap.refresh.watermark.failed")
                    return
                }

            val view = read.state.view
            if (read.reliable && view != null && view.sourceUpdatedAt == databaseMax) {
                tracker.update(view, MapStatus.CURRENT)
                return
            }

            val artifact = service.generateAndPublish()
            val publishedView =
                MemoryMapView(
                    content = artifact.renderedBytes.toString(Charsets.UTF_8),
                    generatedAt = artifact.document.generatedAt,
                    sourceUpdatedAt = artifact.document.sourceUpdatedAt,
                )
            tracker.update(publishedView, MapStatus.STALE)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            tracker.restore(previous)
            failed = true
            logger.warn("memoryMap.refresh.failed")
        } finally {
            synchronized(lock) {
                processedRounds++
                workerStatus = if (failed) MemoryMapWorkerStatus.FAILED else MemoryMapWorkerStatus.IDLE
            }
        }
    }

    private fun admissionOpen(): Boolean = synchronized(lock) { state == State.RUNNING }
}
