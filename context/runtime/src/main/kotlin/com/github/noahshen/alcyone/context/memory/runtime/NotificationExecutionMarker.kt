package com.github.noahshen.alcyone.context.memory.runtime

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 入库通知的「执行来源」标记（CT07 F1）。
 *
 * 为什么不能只用普通 `ThreadLocal`：通知体内一旦 `yield` 或 `withContext(Dispatchers.IO)`，
 * 协程可能换线程执行，只在起始线程设置的 ThreadLocal 就会失效。因此本标记是
 * **协程上下文元素**（随 `yield` / `withContext` 传播），同时通过 [ThreadContextElement]
 * 在每段真实执行期间维护线程可见值，供**非挂起**的 `close()` 同步读取。
 *
 * 身份用对象引用比较：只有「发起通知的那个 Context 实例」才算重入，其他 Context 不误拒。
 */
internal class NotificationExecutionMarker(
    private val owner: AlcyoneContextImpl,
) : ThreadContextElement<Any?>,
    CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Any? {
        val previous = CURRENT_THREAD_MARKER.get()
        CURRENT_THREAD_MARKER.set(owner)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Any?,
    ) {
        if (oldState == null) {
            CURRENT_THREAD_MARKER.remove()
        } else {
            CURRENT_THREAD_MARKER.set(oldState)
        }
    }

    companion object Key : CoroutineContext.Key<NotificationExecutionMarker> {
        /** 当前线程正在执行的「通知归属 Context」，非通知执行期间为 null。 */
        private val CURRENT_THREAD_MARKER = ThreadLocal<Any?>()

        fun currentOwner(): Any? = CURRENT_THREAD_MARKER.get()
    }
}
