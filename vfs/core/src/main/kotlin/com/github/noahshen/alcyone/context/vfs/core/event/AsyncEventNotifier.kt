package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 进程内异步通知：已提交的事件丢进有界队列就算返回，Consumer 由**自己的**分发协程一个个跑。
 *
 * 例：文件写好 → 一次事务更新 Node + 追加 `FILE_WRITTEN` → 事务提交成功 → [publish] 立刻返回 →
 * 后台的 Consumer 慢慢去推送索引。发布方不等 Consumer 的业务，也不占着状态锁跑 Consumer。
 *
 * 它就是「T07 的 [EventDispatcher]（逐个调用 + 异常隔离）+ 一个有界 Channel」：
 *
 * - **有界**：队列满了 [publish] 记一条日志（只记首次，不逐条刷屏）并丢弃，**不抛给发布方**。慢 Consumer 会拖慢通知，
 *   但绝不会把已经提交的文件操作堵住；也不会靠无限缓存把内存撑爆。丢弃不改动已经提交的事件，
 *   事件日志里那条还在（本轮不做补发，补发属 E05）。
 * - **顺序**：一个分发协程、一个 FIFO 队列，所以事件按 [publish] 的先后送达；
 *   同一个事件内，Consumer 按订阅顺序一个个跑。**不保证全局排序**，也不为每个 Consumer 单独排队，
 *   一个慢 Consumer 会让后面的 Consumer 一起等（首版接受，见使用说明）。
 * - **无订阅者**：事件照样入队并被分发协程取走丢掉，不报错、也不计入 [droppedEvents]（那不是队列满）。
 *   分发慢的时候照样受容量约束；晚订阅的 Consumer 可能收到订阅之前入队、还没分发的那一批。
 *
 * @param capacity 队列容量，**必须是有界的普通正整数**，默认 [DEFAULT_CAPACITY]。除了 `0`（无缓冲）、
 *   `-1`（新事件覆盖未分发的）、`-2`（`Channel.BUFFERED`，用调度器默认容量）、`Int.MAX_VALUE`（无界）
 *   这些特殊取值以外，其他非正数同样一律拒绝：建 Channel、起协程之前就报错。
 */
class AsyncEventNotifier(
    capacity: Int = DEFAULT_CAPACITY,
) : AutoCloseable {
    private val delegate = EventDispatcher()
    private val queue = Channel<VfsEvent>(checkedCapacity(capacity))

    /** 自己的 Job：不接管宿主的 Scope / Dispatcher，宿主关掉自己的 scope 也不会顺手关掉通知。 */
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default + CoroutineName(NAME))

    /** 分发协程。宿主要等它停干净时可以 [job]。 */
    val job: Job

    private val closed = AtomicBoolean(false)
    private val dropped = AtomicLong()
    private val capacity = capacity
    private val insideConsumer = ThreadLocal.withInitial { false }

    private val logger = LoggerFactory.getLogger(AsyncEventNotifier::class.java)

    init {
        job =
            scope.launch(InsideConsumer(insideConsumer)) {
                for (event in queue) {
                    try {
                        delegate.dispatch(listOf(event))
                    } catch (cancellation: CancellationException) {
                        // 两种取消要分开：分发协程自己在停（[close]）就照常结束；
                        // 只是某个 Consumer 自己抛了 CancellationException，不能让它把整个通知带走。
                        currentCoroutineContext().ensureActive()
                        logger.warn("A consumer cancelled itself while handling event {}; the notifier keeps running", event.id)
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
     * 这不是挂起函数，所以调用方取消不会把「已经提交」的结果改写成失败。提交附近被取消时，
     * 这一批**可能**进了队列、也可能没进（取消可能落在提交返回与入队之间），已提交的事实不会因此撤回。
     *
     * 分发器已关闭时什么都不做，也不抛：事件日志里那条还在，只是这次没有进程内通知。
     */
    fun publish(events: List<VfsEvent>) {
        if (closed.get()) {
            logger.debug("{} committed event(s) were not delivered in process: the notifier is closed", events.size)
            return
        }
        for (event in events) {
            if (queue.trySend(event).isSuccess) continue
            dropped.incrementAndGet()
            when {
                // close 与 publish 抢先后：队列已经关闭，事件没入队——这和「队列满」是两回事，日志要说清楚
                queue.isClosedForSend ->
                    logger.debug("Event {} was not queued: the notifier is closing", event.id)
                // 只记第一次：publish 是在持锁的调用栈里跑的，逐条 warn 会把日志刷爆；之后的靠 droppedEvents 累计
                dropped.get() == 1L ->
                    logger.warn(
                        "Event buffer is full (capacity {}), event {} is not delivered in process; the event log keeps it",
                        capacity,
                        event.id,
                    )

                else -> Unit
            }
        }
    }

    /** 没能进入队列的事件条数：队列满或通知器正在关闭。只作诊断用（首版不重放）。 */
    val droppedEvents: Long get() = dropped.get()

    /** 关闭是否已经发生。 */
    val isClosed: Boolean get() = closed.get()

    /**
     * 停掉自己的分发协程并等它结束，幂等：重复调用直接返回。
     *
     * 协作取消：Consumer 挂在可取消的挂起点（`awaitCancellation`、挂起的网络读）会立刻收到取消；
     * 卡在不可中断的阻塞代码里时本方法也会跟着卡住——**不宣称能强杀不可中断代码**。
     *
     * **Consumer 回调里调 [close] 不会自己等自己**：[InsideConsumer] 让「正在回调里」这个事实跟着协程换线程，
     * 即使 Consumer 用 `withContext` 换到别的线程，关闭也只做取消、不等待。外部调用则正常等分发协程收尾。
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

        /** [Channel] 的特殊取值：`-1` conflated、`-2` BUFFERED（默认容量）、`Int.MAX_VALUE` 无界。 */
        private const val MAX_BUFFER = Int.MAX_VALUE - 1

        /**
         * 只接受有界的普通正整数。
         *
         * 用完整范围判断而不是「排除几个特殊值」：`Channel` 的负值全是保留含义（`-1` conflated、
         * `-2` BUFFERED），漏掉一个就会静默变成另一种队列语义，「有界」名存实亡。
         */
        private fun checkedCapacity(value: Int): Int {
            require(value in 1..MAX_BUFFER) {
                "capacity must be a bounded positive buffer size (1..$MAX_BUFFER); " +
                    "got $value (0 = rendezvous, negatives are reserved by Channel, Int.MAX_VALUE = unlimited)"
            }
            return value
        }
    }
}

/**
 * 「现在正在跑 Consumer 回调」的标记。
 *
 * 为什么不能直接用 [ThreadLocal]：Consumer 完全可以 `withContext` 换到别的线程再调 `close()`，
 * 那个线程上标记是 false，于是 [AsyncEventNotifier.close] 会去等分发协程——而分发协程正等着这个 Consumer 返回，自己等自己。
 * 协程每次换线程都会问这个 Element 要不要改线程上下文，所以标记会跟着回调走到任何线程上；
 * 回调退出时恢复原值，别的线程、别的协程看不到残留。
 */
private class InsideConsumer(
    private val flag: ThreadLocal<Boolean>,
) : AbstractCoroutineContextElement(InsideConsumer),
    ThreadContextElement<Boolean?> {
    companion object Key : CoroutineContext.Key<InsideConsumer>

    override fun updateThreadContext(context: CoroutineContext): Boolean = flag.get().also { flag.set(true) }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Boolean?,
    ) {
        flag.set(oldState ?: false)
    }
}
