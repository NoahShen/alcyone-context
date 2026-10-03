package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 事件消费者。消费失败由分发器吞掉，不影响已提交状态与其他消费者（X2）。
 */
fun interface VfsEventConsumer {
    suspend fun onEvent(event: VfsEvent)
}

/**
 * 提交后事件分发器：Core 内部组件，**不是** Storage 或 Repository 接口的一部分（X2）。
 *
 * 契约：
 *
 * - 只能在事务提交成功后由编排层调用；提交失败时调用方不得分发；
 * - 分发失败不影响已提交状态：消费者抛异常只被记录，不回滚事务，也不向调用方冒泡。
 *
 * 首版只做进程内分发，不实现持久化消费进度与重启重放（E05）。
 */
class EventDispatcher {
    private val consumers = CopyOnWriteArrayList<VfsEventConsumer>()

    private val logger = LoggerFactory.getLogger(EventDispatcher::class.java)

    /** 注册消费者。 */
    fun subscribe(consumer: VfsEventConsumer) {
        consumers.add(consumer)
    }

    /**
     * 退订：同一个实例注册过就摘掉，之后的事件不再投给它。**正在执行的那一次不会被打断**。
     *
     * 例：一次会话结束，把会话专属的推送消费者摘掉，事件日志照旧，只是进程内不再有人收。
     */
    fun unsubscribe(consumer: VfsEventConsumer) {
        consumers.remove(consumer)
    }

    /**
     * 向全部已注册消费者投递已提交的事件。
     *
     * 捕获范围：普通 [Exception]（含 [RuntimeException] 与 [IOException] 等受检异常）只记录日志并继续
     * 投递给其余消费者；[kotlinx.coroutines.CancellationException] 原样传播；
     * [Error] 不捕获，让 JVM 级故障按原义上抛。
     */
    suspend fun dispatch(events: List<VfsEvent>) {
        for (event in events) {
            for (consumer in consumers) {
                try {
                    consumer.onEvent(event)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    logger.warn("Event consumer failed for event {}", event.id, failure)
                }
            }
        }
    }
}
