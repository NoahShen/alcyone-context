package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextDreamingErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextDreamingException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.DreamingResult
import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import com.github.noahshen.alcyone.context.memory.api.DreamingRunStage
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.runtime.organization.CandidateBudgetExceededException
import com.github.noahshen.alcyone.context.memory.runtime.organization.TokenBatchPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** CT22 固定增量 Dreaming run：单 Worker、固定 C0/U、跨页预算分批、串行 processor。 */
internal class DreamingRunCoordinator(
    private val database: ContextDatabase,
    private val processor: DreamingBatchProcessor,
    private val budget: DreamingBatchBudget,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val logger: Logger = LoggerFactory.getLogger(DreamingRunCoordinator::class.java),
) {
    private enum class State {
        OPEN,
        CLOSING,
        CLOSED,
    }

    private val lock = Any()
    private val closeLock = Any()
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.IO)
    private var state = State.OPEN
    private var activeRun = false
    private val manualRuns = mutableSetOf<Job>()
    private var ownedJob: Job? = null
    private var closeCompletion: CompletableDeferred<Throwable?>? = null
    private var rounds = 0L
    private var ownedRuns = 0

    internal var closeFailureForTesting: Throwable? = null
    internal var closeHookForTesting: (() -> Unit)? = null
    internal var runHookForTesting: (suspend () -> Unit)? = null

    init {
        require(pageSize >= 1) { "pageSize must be at least 1" }
    }

    suspend fun runOnce(): DreamingResult {
        val callerJob = currentCoroutineContext()[Job] ?: error("dreaming run requires a coroutine Job")
        synchronized(lock) {
            if (state != State.OPEN) throw closed()
            if (activeRun) throw alreadyRunning()
            activeRun = true
            manualRuns += callerJob
        }
        try {
            return executeRun()
        } finally {
            releaseRun(callerJob)
        }
    }

    /** Cron 请求 owned run；running/closing 时直接返回 false，不排队、不抛普通错误。 */
    fun requestBackground(): Boolean {
        var toStart: Job? = null
        val accepted =
            synchronized(lock) {
                if (state != State.OPEN || activeRun) {
                    false
                } else {
                    activeRun = true
                    ownedRuns++
                    rounds++
                    ownedJob =
                        scope
                            .launch(start = CoroutineStart.LAZY) {
                                try {
                                    executeRun()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    logger.warn("dreaming.background.failed")
                                } finally {
                                    releaseOwnedRun()
                                }
                            }.also { toStart = it }
                    true
                }
            }
        if (!accepted) {
            logger.warn("dreaming.run.skipped")
            return false
        }
        toStart?.start()
        return true
    }

    fun beginClosing() {
        synchronized(lock) {
            if (state == State.OPEN) state = State.CLOSING
        }
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

        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                val (toCancel, manualToJoin) = synchronized(lock) { ownedJob to manualRuns.toList() }
                toCancel?.cancel(CancellationException("dreaming coordinator is closing"))
                toCancel?.join()
                // Caller-owned Jobs are never cancelled; all admitted work must finish before resources close.
                manualToJoin.forEach { it.join() }
                closeHookForTesting?.invoke()
                closeFailureForTesting?.let { failure = it }
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                synchronized(lock) {
                    ownedJob = null
                    state = State.CLOSED
                }
                parent.complete()
                completion.complete(failure)
            }
        }
        failure?.let { throw it }
    }

    internal fun activeForTesting(): Boolean = synchronized(lock) { activeRun }

    internal fun roundCountForTesting(): Long = synchronized(lock) { rounds }

    internal fun ownedRunCountForTesting(): Int = synchronized(lock) { ownedRuns }

    internal fun stateForTesting(): String = synchronized(lock) { state.name }

    private suspend fun executeRun(): DreamingResult {
        runHookForTesting?.invoke()
        val fixed = readFixedRange()
        val start = fixed.checkpoint
        val max = fixed.maxTurnId
        if (fixed.checkpoint < 0L) {
            throw failure(
                stage = DreamingRunStage.FIXED_RANGE,
                partial = partial(start = 0L, end = 0L),
                message = "dreaming checkpoint must not be negative",
            )
        }
        if (max == null || max == fixed.checkpoint) {
            return DreamingResult(start, start, 0, 0, 0, completed = true)
        }
        if (fixed.checkpoint > max) {
            throw failure(
                stage = DreamingRunStage.FIXED_RANGE,
                partial = partial(start = start, end = start),
                message = "dreaming checkpoint exceeds fixed upper turn id",
            )
        }

        var lastReadId = fixed.checkpoint
        var lastSuccessfulId = fixed.checkpoint
        var scanned = 0
        var topics = 0
        var memories = 0
        var pending = emptyList<StoredConversationTurn>()

        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readPage(lastReadId, max, partial(start, lastSuccessfulId, scanned, topics, memories))
            if (page.isEmpty()) break
            validatePage(page, lastReadId, max)
            lastReadId = page.last().id
            scanned += page.size
            val assembled = pending + page
            val batches =
                planBatches(
                    assembled,
                    partial(start, lastSuccessfulId, scanned - pending.size, topics, memories),
                )
            if (batches.isEmpty()) {
                pending = emptyList()
            } else {
                batches.dropLast(1).forEach { batch ->
                    val outcome =
                        processBatch(
                            batch,
                            lastSuccessfulId,
                            max,
                            partial(start, lastSuccessfulId, scanned - pending.size, topics, memories),
                        )
                    lastSuccessfulId = outcome.lastTurnId
                    topics += outcome.discoveredTopics
                    memories += outcome.generatedMemories
                }
                pending = batches.last()
            }
            if (page.size < pageSize) break
        }

        if (pending.isNotEmpty()) {
            val outcome = processBatch(pending, lastSuccessfulId, max, partial(start, lastSuccessfulId, scanned, topics, memories))
            lastSuccessfulId = outcome.lastTurnId
            topics += outcome.discoveredTopics
            memories += outcome.generatedMemories
        }

        return DreamingResult(
            startCheckpoint = start,
            endCheckpoint = max,
            scannedTurns = scanned,
            discoveredTopics = topics,
            generatedMemories = memories,
            completed = true,
        )
    }

    private suspend fun readFixedRange(): FixedRange =
        try {
            database.read { read ->
                FixedRange(
                    checkpoint = read.dreaming.readCheckpoint(),
                    maxTurnId = read.conversations.maxTurnId(),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw failure(
                DreamingRunStage.FIXED_RANGE,
                partial(start = 0L, end = 0L),
                "failed to read dreaming fixed range",
                failure,
            )
        }

    private suspend fun readPage(
        fromExclusive: Long,
        toInclusive: Long,
        partial: DreamingResult,
    ): List<StoredConversationTurn> =
        try {
            database.read { read -> read.conversations.idRange(fromExclusive, toInclusive, pageSize) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw failure(DreamingRunStage.PAGE_READ, partial, "failed to read dreaming page", failure)
        }

    private suspend fun planBatches(
        items: List<StoredConversationTurn>,
        partial: DreamingResult,
    ): List<List<StoredConversationTurn>> =
        try {
            TokenBatchPlanner.plan(items) { batch ->
                currentCoroutineContext().ensureActive()
                budget.requiredTotalFor(batch)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exceeded: CandidateBudgetExceededException) {
            throw failure(
                DreamingRunStage.BUDGET,
                partial,
                "dreaming token budget exceeded (reason=${exceeded.reason.name.lowercase()}, required=${exceeded.requiredTotalTokens})",
            )
        } catch (failure: Throwable) {
            throw failure(DreamingRunStage.BUDGET, partial, "dreaming token budget evaluation failed", failure)
        }

    private suspend fun processBatch(
        batch: List<StoredConversationTurn>,
        expectedCheckpoint: Long,
        upperBound: Long,
        partial: DreamingResult,
    ): DreamingBatchOutcome {
        currentCoroutineContext().ensureActive()
        val outcome =
            try {
                processor.process(DreamingBatchContext(expectedCheckpoint, upperBound, batch))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                throw failure(DreamingRunStage.BATCH_PROCESSING, partial, "dreaming batch processing failed", failure)
            }
        val expectedLast = batch.last().id
        if (outcome.lastTurnId != expectedLast || outcome.lastTurnId <= partial.endCheckpoint) {
            throw failure(
                DreamingRunStage.BATCH_PROCESSING,
                partial,
                "dreaming processor returned an invalid lastTurnId",
            )
        }
        if (outcome.discoveredTopics < 0 || outcome.generatedMemories < 0) {
            throw failure(
                DreamingRunStage.BATCH_PROCESSING,
                partial,
                "dreaming processor returned negative counters",
            )
        }
        return outcome
    }

    private fun validatePage(
        page: List<StoredConversationTurn>,
        fromExclusive: Long,
        toInclusive: Long,
    ) {
        check(
            page.zipWithNext().none { (left, right) -> left.id >= right.id } &&
                page.first().id > fromExclusive &&
                page.last().id <= toInclusive,
        ) { "dreaming page is outside the fixed id range or not strictly ascending" }
    }

    private fun releaseRun(callerJob: Job) {
        synchronized(lock) {
            activeRun = false
            manualRuns -= callerJob
        }
    }

    private fun releaseOwnedRun() {
        synchronized(lock) {
            activeRun = false
            ownedJob = null
        }
    }

    private fun partial(
        start: Long,
        end: Long,
        scanned: Int = 0,
        topics: Int = 0,
        memories: Int = 0,
    ): DreamingResult = DreamingResult(start, maxOf(start, end), scanned, topics, memories, completed = false)

    private fun failure(
        stage: DreamingRunStage,
        partial: DreamingResult,
        message: String,
        cause: Throwable? = null,
    ): DreamingRunException = DreamingRunException(stage, partial, message, cause)

    private fun alreadyRunning(): ContextDreamingException =
        ContextDreamingException(ContextDreamingErrorCode.ALREADY_RUNNING, "dreaming run is already active")

    private fun closed(): ContextLifecycleException =
        ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "dreaming run is closing or closed")

    private data class FixedRange(
        val checkpoint: Long,
        val maxTurnId: Long?,
    )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
    }
}
