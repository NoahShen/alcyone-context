package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程内异步通知：已提交的事件丢进有界队列就算返回，Consumer 由**自己的**分发协程一个个跑。
 *
 * 例：文件写好 → 一次事务更新 Node + 追加 `FILE_WRITTEN` → 事务提交成功 → [publish] 立刻返回 →
 * 后台的 Consumer 慢慢去推送索引。发布方不等 Consumer 的业务，也不占着状态锁跑 Consumer。
 *
 * 它就是「T07 的 [EventDispatcher]（逐个调用 + 异常隔离）+ 一个有界 Channel」：
 *
 * - **有界**：队列满了 [publish] 记一条日志并丢弃，**不抛给发布方**。慢 Consumer 会拖慢通知，
 *   但绝不会把已经提交的文件操作堵住；也不会靠无限缓存把内存撑爆。丢弃不改动已经提交的事件，
 *   事件日志里那条还在（本轮不做补发，补发属 E05）。
 * - **顺序**：一个分发协程、一个 FIFO 队列，所以事件按 [publish] 的先后送达；
 *   同一个事件内，Consumer 按订阅顺序一个个跑。**不保证全局排序**，也不为每个 Consumer 单独排队，
 *   一个慢 Consumer 会让后面的 Consumer 一起等（首版接受，见使用说明）。
 * - **无订阅者**：事件照收照丢，不报错、不缓存。事件日志才是可靠的那一份。
 *
 * @param capacity 队列容量，**必须有界**，默认 [DEFAULT_CAPACITY]。测试用很小的值逼出丢弃行为。
 */
class AsyncEventNotifier(
    private val capacity: Int = DEFAULT_CAPACITY,
) : AutoCloseable {
    private val delegate = EventDispatcher()
    private val queue = Channel<VfsEvent>(capacity)

    /** 自己的 Job：不接管宿主的 Scope / Dispatcher，宿主关掉自己的 scope 也不会顺手关掉通知。 */
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default + CoroutineName(NAME))

    /** 分发协程。宿主要等它停干净时可以 [job]。 */
    val job: Job

    private val closed = AtomicBoolean(false)
    private val dropped = AtomicLong()
    private val insideConsumer = ThreadLocal.withInitial { false }

    private val logger = LoggerFactory.getLogger(AsyncEventNotifier::class.java)

    init {
        job =
            scope.launch {
                for (event in queue) {
                    insideConsumer.set(true)
                    try {
                        delegate.dispatch(listOf(event))
                    } catch (cancellation: CancellationException) {
                        // 两种取消要分开：分发协程自己在停（[close]）就照常结束；
                        // 只是某个 Consumer 自己抛了 CancellationException，不能让它把整个通知带走。
                        currentCoroutineContext().ensureActive()
                        logger.warn("A consumer cancelled itself while handling event {}; the notifier keeps running", event.id)
                    } finally {
                        insideConsumer.set(false)
                    }
                }
            }
    }

    /** 注册消费者；同一个实例可以 [unsubscribe] 掉。 */
    fun subscribe(consumer: VfsEventConsumer) = delegate.subscribe(consumer)

    /** 退订：之后的事件不再投给它。正在执行的那一次不会被打断。 */
    fun unsubscribe(consumer: VfsEventConsumer) = delegate.unsubscribe(consumer)

    /**
     * 投递一批**已提交**的事件；只入队，不等 Consumer，队列满就丢。
     *
     * 这不是挂起函数：提交后的通知没有取消点，调用方取消不会把「已经提交」的结果改写成失败。
     *
     * 分发器已关闭时什么都不做，也不抛：事件日志里那条还在，只是这次没有进程内通知。
     */
    fun publish(events: List<VfsEvent>) {
        if (closed.get()) {
            logger.debug("{} committed event(s) were not delivered in process: the notifier is closed", events.size)
            return
        }
        for (event in events) {
            if (!queue.trySend(event).isSuccess) {
                dropped.incrementAndGet()
                logger.warn(
                    "Event buffer is full (capacity {}), event {} is not delivered in process; the event log keeps it",
                    capacity,
                    event.id,
                )
            }
        }
    }

    /** 因队列满而没能通知出去的事件条数，只作诊断用（首版不重放）。 */
    val droppedEvents: Long get() = dropped.get()

    /** 关闭是否已经发生。 */
    val isClosed: Boolean get() = closed.get()

    /**
     * 停掉自己的分发协程并等它结束，幂等：重复调用直接返回。
     *
     * 协作取消：Consumer 挂在可取消的挂起点（`awaitCancellation`、挂起的网络读）会立刻收到取消；
     * 卡在不可中断的阻塞代码里时本方法也会跟着卡住——**不宣称能强杀不可中断代码**。
     * 在 Consumer 回调里调 [close] 是自己等自己（和 [com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary]
     * 不可重入同一类问题），这种调用只取消不等待。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        supervisor.cancel()
        queue.close()
        if (!insideConsumer.get()) {
            runBlocking { job.join() }
        }
    }

    companion object {
        /**
         * 默认队列容量。够正常负载跑完一轮 Consumer，又不至于让一个卡住的 Consumer 把内存吃光。
         * 想更保守就调小，代价是更容易丢通知（首版丢了只丢通知，事件日志不受影响）。
         */
        const val DEFAULT_CAPACITY: Int = 256

        private const val NAME = "vfs-event-notifier"
    }
}
