package com.github.noahshen.alcyone.context.memory.runtime.map

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.MapStatus
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

/** 可注入 Periodic tick 来源：true=到达，false=来源关闭，抛异常=本次调度失败。 */
internal fun interface MemoryMapPeriodicTickSource {
    suspend fun awaitTick(): Boolean
}

/**
 * Memory Map Periodic Cron：UNIX 五字段、固定 Asia/Shanghai、严格未来、不启动即扫、不补跑。
 * 每 tick 只读 DB MAX 并与 Tracker 缓存比较，不在 Cron 内等待 LLM。
 */
internal class MemoryMapPeriodicCron(
    cron: String,
    private val database: ContextDatabase,
    private val tracker: MemoryMapStatusTracker,
    private val request: () -> Boolean,
    private val clock: Clock = Clock.system(ZONE),
    tickSource: MemoryMapPeriodicTickSource? = null,
    private val backoff: suspend () -> Unit = { delay(FAILURE_BACKOFF_MILLIS) },
    private val logger: Logger = LoggerFactory.getLogger(MemoryMapPeriodicCron::class.java),
) {
    private enum class State {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private val executionTime = ExecutionTime.forCron(CRON_PARSER.parse(cron))
    private val ticks = tickSource ?: MemoryMapPeriodicTickSource { awaitNextCronTick() }
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
            val next = executionTime.nextExecution(base.atZone(ZONE)).orElse(null) ?: return null
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
                    throw ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "memory map periodic cron is closed")
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
                loopToStop?.cancel(CancellationException("memory map periodic cron is closing"))
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
                    logger.warn("memoryMap.periodic.failed")
                    backoff()
                    continue
                }
            if (!delivered) return

            try {
                val current = tracker.snapshot()
                val databaseMax = database.read { transaction -> transaction.organizedMemories.maxUpdatedAt() }
                val currentReliable = current.status == MapStatus.CURRENT && current.view?.sourceUpdatedAt == databaseMax
                if (!currentReliable) request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                logger.warn("memoryMap.periodic.failed")
                backoff()
            }
        }
    }

    private suspend fun awaitNextCronTick(): Boolean {
        val now = clock.instant()
        val next =
            nextAfter(now)
                ?: run {
                    logger.warn("memoryMap.periodic.failed")
                    return false
                }
        delay(Duration.between(now, next).toMillis().coerceAtLeast(1L))
        return true
    }

    internal companion object {
        val ZONE: ZoneId = ZoneId.of(MemoryMapDefaults.TIME_ZONE_ID)
        const val FAILURE_BACKOFF_MILLIS: Long = 1_000L
        private const val MAX_NEXT_ATTEMPTS = 3
    }
}

private val CRON_PARSER: CronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
