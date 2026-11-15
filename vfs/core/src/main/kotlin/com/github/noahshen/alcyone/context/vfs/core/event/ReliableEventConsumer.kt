package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.core.repository.EventCursorStart
import com.github.noahshen.alcyone.context.vfs.core.repository.ReliableEventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.SequencedEventRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 显式持久事件消费者。处理函数按 eventId 幂等：整批处理成功与 cursor 提交之间崩溃会重投。
 *
 * 每个 consumerId 只允许一个进程内实例；本类不实现多进程 lease。实时 wakeup 只是减少等待的提示，
 * 丢失通知不会改变 SQLite backlog 的最终可读性。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliableEventConsumer(
    private val repository: ReliableEventRepository,
    private val consumerId: String,
    private val start: EventCursorStart = EventCursorStart.EARLIEST,
    private val batchSize: Int = 100,
    private val pollInterval: Duration = 100.milliseconds,
    private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
    private val wakeups: ReceiveChannel<Unit>? = null,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var runningJob: Job? = null

    init {
        require(consumerId.isNotBlank()) { "consumerId must not be blank" }
        require(batchSize in 1..1000) { "batchSize must be in 1..1000: $batchSize" }
        require(!pollInterval.isNegative() && pollInterval > Duration.ZERO) { "pollInterval must be positive" }
        require(retryDelays.isNotEmpty() && retryDelays.all { it > Duration.ZERO }) { "retryDelays must be positive" }
    }

    /** 启动唯一消费协程；重复 start 或 close 后 start 都明确拒绝。 */
    fun start(
        scope: CoroutineScope,
        handler: suspend (SequencedEventRecord) -> Unit,
    ): Job {
        check(started.compareAndSet(false, true)) { "consumer $consumerId has already been started" }
        check(!closing.get()) { "consumer $consumerId is closed" }
        val job =
            scope.launch {
                var nextSequence = repository.initializeCursor(consumerId, start)
                var consecutiveFailures = 0
                while (!closing.get()) {
                    val batch = repository.readAfter(nextSequence - 1, batchSize)
                    if (batch.isEmpty()) {
                        waitForWakeupOrPoll()
                        continue
                    }
                    try {
                        // DB 读取已经返回，处理期间没有事务或 StateBoundary 锁。
                        batch.forEach { handler(it) }
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        // 只重试 handler 失败；advance 失败在 catch 外传播，不能再次执行已经成功的 handler。
                        delay(retryDelays[consecutiveFailures.coerceAtMost(retryDelays.lastIndex)])
                        consecutiveFailures++
                        continue
                    }
                    // cursor CAS/DB 错误说明提交状态未知；传播给调用方，绝不再次执行本批 handler。
                    repository.advanceCursor(consumerId, nextSequence, batch.last().sequence + 1)
                    nextSequence = batch.last().sequence + 1
                    consecutiveFailures = 0
                }
            }
        runningJob = job
        return job
    }

    /** 停止领取新批，不取消处理协程；等待已领取批成功提交后返回。 */
    override fun close() {
        closing.set(true)
        wakeup.trySend(Unit)
        runningJob?.let { job ->
            kotlinx.coroutines.runBlocking { job.join() }
        }
    }

    private suspend fun waitForWakeupOrPoll() {
        if (closing.get()) return
        select {
            wakeup.onReceive { }
            wakeups?.onReceive { }
            onTimeout(pollInterval) { }
        }
    }

    companion object {
        val DEFAULT_RETRY_DELAYS: List<Duration> = listOf(100.milliseconds, 500.milliseconds, 1_000.milliseconds, 5_000.milliseconds)
    }
}
