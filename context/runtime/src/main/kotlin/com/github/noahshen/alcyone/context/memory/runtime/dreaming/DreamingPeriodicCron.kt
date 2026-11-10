package com.github.noahshen.alcyone.context.memory.runtime.dreaming

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

internal fun interface DreamingPeriodicTickSource {
    suspend fun awaitTick(): Boolean
}

/** Dreaming 可选 Cron：严格未来、不补跑、只 requestBackground、失败固定可取消退避。 */
internal class DreamingPeriodicCron(
    cron: String,
    timezone: String,
    private val request: () -> Boolean,
    private val clock: Clock = Clock.system(ZoneId.of(timezone)),
    tickSource: DreamingPeriodicTickSource? = null,
    private val backoff: suspend () -> Unit = { delay(FAILURE_BACKOFF_MILLIS) },
    private val logger: Logger = LoggerFactory.getLogger(DreamingPeriodicCron::class.java),
) {
    private enum class State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private val zone = ZoneId.of(timezone)
    private val executionTime = ExecutionTime.forCron(CRON_PARSER.parse(cron))
    private val ticks = tickSource ?: DreamingPeriodicTickSource { awaitNextCronTick() }
    private val lock = Any()
    private var state = State.NEW
    private var parent: CompletableJob? = null
    private var scope: CoroutineScope? = null
    private var loop: Job? = null
    private var closeCompletion: CompletableDeferred<Throwable?>? = null

    internal var closeActionForTesting: (() -> Unit)? = null

    fun nextAfter(now: Instant): Instant? {
        var base = now
        repeat(MAX_NEXT_ATTEMPTS) {
            val next = executionTime.nextExecution(base.atZone(zone)).orElse(null) ?: return null
            val candidate = next.toInstant()
            if (candidate.isAfter(now)) return candidate
            base = candidate.plusSeconds(1)
        }
        return null
    }

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
                    loop = newScope.launch(start = CoroutineStart.LAZY) { runTicks() }.also { toStart = it }
                }
                State.CLOSING, State.CLOSED ->
                    throw ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "dreaming periodic cron is closed")
            }
        }
        toStart?.start()
    }

    fun beginClosing() {
        synchronized(lock) {
            if (state == State.NEW || state == State.RUNNING) state = State.CLOSING
        }
    }

    suspend fun closeAsync() {
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
                val loopToStop = synchronized(lock) { loop }
                loopToStop?.cancel(CancellationException("dreaming periodic cron is closing"))
                loopToStop?.join()
                closeActionForTesting?.invoke()
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                val parentToComplete = synchronized(lock) { parent }
                synchronized(lock) {
                    loop = null
                    scope = null
                    parent = null
                    state = State.CLOSED
                }
                parentToComplete?.complete()
                completion.complete(failure)
            }
        }
        failure?.let { throw it }
    }

    internal fun stateForTesting(): String = synchronized(lock) { state.name }

    private suspend fun runTicks() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val delivered =
                try {
                    ticks.awaitTick()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    logger.warn("dreaming.periodic.failed")
                    backoff()
                    continue
                }
            if (!delivered) return
            try {
                request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                logger.warn("dreaming.periodic.failed")
                backoff()
            }
        }
    }

    private suspend fun awaitNextCronTick(): Boolean {
        val now = clock.instant()
        val next =
            nextAfter(now)
                ?: run {
                    logger.warn("dreaming.periodic.failed")
                    return false
                }
        delay(Duration.between(now, next).toMillis().coerceAtLeast(1L))
        return true
    }

    internal companion object {
        const val FAILURE_BACKOFF_MILLIS: Long = 1_000L
        private const val MAX_NEXT_ATTEMPTS = 3
    }
}

private val CRON_PARSER: CronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
