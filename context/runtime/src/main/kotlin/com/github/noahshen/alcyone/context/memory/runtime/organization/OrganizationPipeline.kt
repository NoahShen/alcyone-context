package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * CT16 B1 Organization Pipeline：组合 activation gate、Scheduler、PeriodicCron 与 Immediate Wakeup。
 *
 * start 前或 activate 前只允许计数，不执行业务 round。beginClosing 同步关闭 Scheduler 准入与 gate；
 * closeAsync 先停 Cron，再取消/join Scheduler，始终完成 activation 以避免等待悬挂，并共享关闭失败。
 * 本组件不接 Factory/Impl，不修改 public ContextStatus。
 */
internal class OrganizationPipeline(
    private val config: OrganizationConfig,
    database: ContextDatabase,
    runner: OrganizationRoundRunner,
    clock: Clock = Clock.system(OrganizationPeriodicCron.ZONE),
    tickSource: OrganizationPeriodicTickSource? = null,
) {
    private val admissionLock = Any()
    private var closing = false
    private val activation = CompletableDeferred<Unit>()
    internal val runnerGateReachedForTesting = CompletableDeferred<Unit>()
    private val closeLock = Any()
    private var closeCompletion: CompletableDeferred<Throwable?>? = null

    internal var startFailureForTesting: Throwable? = null
    internal var closeFailureForTesting: Throwable? = null
    internal var closeHookForTesting: (() -> Unit)? = null

    private val scheduler: OrganizationScheduler
    private val periodicCron: OrganizationPeriodicCron?

    val wakeup: OrganizationWakeup

    init {
        val gatedRunner =
            OrganizationRoundRunner {
                runnerGateReachedForTesting.complete(Unit)
                activation.await()
                if (admissionOpen()) runner.runRound()
            }
        scheduler = OrganizationScheduler(gatedRunner)
        periodicCron =
            if (config.periodicEnabled) {
                OrganizationPeriodicCron(
                    cron = config.cron,
                    database = database,
                    request = scheduler::request,
                    clock = clock,
                    tickSource = tickSource,
                )
            } else {
                null
            }
        wakeup = OrganizationWakeup { requestImmediate() }
    }

    fun start() {
        startFailureForTesting?.let { throw it }
        scheduler.start()
        periodicCron?.start()
    }

    fun activate() {
        activation.complete(Unit)
    }

    internal fun requestImmediate(): Boolean {
        if (!config.immediateEnabled || !admissionOpen()) return false
        return scheduler.request()
    }

    fun beginClosing() {
        synchronized(admissionLock) { closing = true }
        scheduler.beginClosing()
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
            closeHookForTesting?.let { hook -> primary = chain(primary, runCatching(hook).exceptionOrNull()) }
            try {
                periodicCron?.closeAsync()
            } catch (failure: Throwable) {
                primary = chain(primary, failure)
            }
            try {
                scheduler.closeAsync()
            } catch (failure: Throwable) {
                primary = chain(primary, failure)
            }
            activation.complete(Unit)
            closeFailureForTesting?.let { failure -> primary = chain(primary, failure) }
            completion.complete(primary)
        }
        primary?.let { throw it }
    }

    internal fun schedulerForTesting(): OrganizationScheduler = scheduler

    internal fun periodicCronForTesting(): OrganizationPeriodicCron? = periodicCron

    internal fun isActivatedForTesting(): Boolean = activation.isCompleted

    internal fun isClosingForTesting(): Boolean = !admissionOpen()

    internal fun workerStatusForTesting(): OrganizationWorkerStatus = scheduler.statusForTesting()

    private fun admissionOpen(): Boolean = synchronized(admissionLock) { !closing }

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
