package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.DreamingResult
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitter
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock

/** Owns exactly one coordinator and at most one cron; no Dreaming work runs before start. */
internal class DreamingRuntime(
    config: DreamingConfig,
    database: ContextDatabase,
    llmClient: LlmClient,
    llmConfig: LlmConfig,
    organizationWakeup: OrganizationWakeup,
    clock: Clock,
    idGenerator: MemoryIdGenerator,
    tickSource: DreamingPeriodicTickSource? = null,
) {
    private enum class State { NEW, RUNNING, CLOSING, CLOSED }

    private val lock = Any()
    private var state = State.NEW
    private var closeCompletion: CompletableDeferred<Throwable?>? = null
    private val coordinator: DreamingRunCoordinator
    private val periodicCron: DreamingPeriodicCron?

    internal var startFailureForTesting: Throwable? = null
    internal var closeFailureForTesting: Throwable? = null
    internal var closeHookForTesting: (() -> Unit)? = null

    init {
        val discovery = LlmDreamingTopicDiscoveryStage(llmClient, llmConfig)
        val evidence = LlmDreamingEvidenceStage(database, llmClient, llmConfig, discovery)
        val reflection = LlmDreamingReflectionStage(llmClient, llmConfig)
        val processor =
            LlmDreamingBatchProcessor(
                database,
                evidence,
                reflection,
                DreamingCommitter(database, idGenerator, clock),
                organizationWakeup,
            )
        coordinator = DreamingRunCoordinator(database, processor, LlmDreamingTopicBatchBudget(discovery))
        periodicCron =
            config.cron?.let { cron ->
                DreamingPeriodicCron(cron, config.timezone, coordinator::requestBackground, tickSource = tickSource)
            }
    }

    fun start() {
        synchronized(lock) {
            when (state) {
                State.RUNNING -> return
                State.NEW -> state = State.RUNNING
                State.CLOSING, State.CLOSED -> throw closed()
            }
        }
        startFailureForTesting?.let { throw it }
        periodicCron?.start()
    }

    suspend fun runOnce(): DreamingResult {
        synchronized(lock) { if (state != State.RUNNING) throw closed() }
        return coordinator.runOnce()
    }

    fun beginClosing() {
        synchronized(lock) { if (state == State.NEW || state == State.RUNNING) state = State.CLOSING }
        coordinator.beginClosing()
        periodicCron?.beginClosing()
    }

    suspend fun closeAsync() {
        beginClosing()
        var owner = false
        val completion =
            synchronized(lock) {
                closeCompletion ?: CompletableDeferred<Throwable?>().also {
                    closeCompletion = it
                    owner = true
                }
            }
        if (!owner) {
            completion.await()?.let { throw it }
            return
        }
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                closeHookForTesting?.let { failure = chain(failure, runCatching(it).exceptionOrNull()) }
                try {
                    periodicCron?.closeAsync()
                } catch (next: Throwable) {
                    failure = chain(failure, next)
                }
                try {
                    coordinator.closeAsync()
                } catch (next: Throwable) {
                    failure = chain(failure, next)
                }
                closeFailureForTesting?.let { failure = chain(failure, it) }
            } finally {
                synchronized(lock) { state = State.CLOSED }
                completion.complete(failure)
            }
        }
        failure?.let { throw it }
    }

    internal fun coordinatorForTesting() = coordinator

    internal fun periodicCronForTesting() = periodicCron

    internal fun stateForTesting() = synchronized(lock) { state.name }

    private fun closed() = ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "dreaming runtime is not running")

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
