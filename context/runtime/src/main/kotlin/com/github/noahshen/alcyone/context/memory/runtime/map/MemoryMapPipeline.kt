package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.vfs.Vfs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * CT19 Memory Map Pipeline：组合状态缓存、读取器、Conflated Worker 与 Periodic Cron。
 *
 * start 只启动资源并异步请求一次启动检查；activate 前 Worker 可以持有信号但不会读取或生成。
 * beginClosing 同步封准入；closeAsync 先停 Cron，再取消并真实 join Worker，并共享关闭失败。
 */
internal class MemoryMapPipeline(
    config: MemoryMapConfig,
    database: ContextDatabase,
    vfs: Vfs,
    service: MemoryMapService,
    clock: Clock = Clock.system(MemoryMapPeriodicCron.ZONE),
    tickSource: MemoryMapPeriodicTickSource? = null,
) {
    private enum class State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private val stateLock = Any()
    private val closeLock = Any()
    private var state = State.NEW
    private var closeCompletion: CompletableDeferred<Throwable?>? = null

    internal var closeFailureForTesting: Throwable? = null

    private val tracker = MemoryMapStatusTracker()
    private val reader = MemoryMapBootstrapReader(vfs, tracker)
    private val coordinator = MemoryMapRefreshCoordinator(database, reader, service, tracker)
    private val periodicCron: MemoryMapPeriodicCron? =
        if (config.periodic.enabled) {
            MemoryMapPeriodicCron(
                cron = config.periodic.cron,
                database = database,
                tracker = tracker,
                request = coordinator::requestRefresh,
                clock = clock,
                tickSource = tickSource,
            )
        } else {
            null
        }

    fun start() {
        synchronized(stateLock) {
            when (state) {
                State.RUNNING -> return
                State.NEW -> state = State.RUNNING
                State.CLOSING, State.CLOSED ->
                    throw ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "memory map pipeline is closed")
            }
        }
        coordinator.start()
        periodicCron?.start()
        coordinator.requestRefresh()
    }

    fun activate() {
        coordinator.activate()
    }

    fun beginClosing() {
        synchronized(stateLock) {
            if (state == State.NEW || state == State.RUNNING) state = State.CLOSING
        }
        coordinator.beginClosing()
        periodicCron?.beginClosing()
    }

    suspend fun closeAsync() {
        beginClosing()
        var owner = false
        val completion =
            synchronized(closeLock) {
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

        var primary: Throwable? = null
        withContext(NonCancellable) {
            try {
                periodicCron?.closeAsync()
            } catch (failure: Throwable) {
                primary = chain(primary, failure)
            }
            try {
                coordinator.closeAsync()
            } catch (failure: Throwable) {
                primary = chain(primary, failure)
            }
            closeFailureForTesting?.let { failure -> primary = chain(primary, failure) }
            synchronized(stateLock) { state = State.CLOSED }
            completion.complete(primary)
        }
        primary?.let { throw it }
    }

    fun snapshot(): MemoryMapState = tracker.snapshot()

    internal fun trackerForTesting(): MemoryMapStatusTracker = tracker

    internal fun readerForTesting(): MemoryMapBootstrapReader = reader

    internal fun coordinatorForTesting(): MemoryMapRefreshCoordinator = coordinator

    internal fun periodicCronForTesting(): MemoryMapPeriodicCron? = periodicCron

    internal fun isClosingForTesting(): Boolean = synchronized(stateLock) { state == State.CLOSING || state == State.CLOSED }

    private fun chain(
        primary: Throwable?,
        next: Throwable?,
    ): Throwable? {
        if (next == null) return primary
        if (primary == null) return next
        primary.addSuppressed(next)
        return primary
    }
}
