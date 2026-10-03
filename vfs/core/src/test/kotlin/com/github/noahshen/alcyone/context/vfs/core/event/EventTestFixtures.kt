package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 测试用的固定时间：事件时间可预期，才好断言「通知里的时间 = 库里的毫秒值」。 */
val FIXED_TIME: Instant = Instant.parse("2026-10-03T00:00:00Z")

/** 造一条事件记录；默认是「写好了 /notes/a.txt」。 */
fun testRecord(
    type: VfsEventType = VfsEventType.FILE_WRITTEN,
    uri: VfsUri = VfsUri.parse("alcyone://resources/notes/a.txt"),
    nodeId: NodeId? = null,
): EventRecord = EventFactory { FIXED_TIME }.newRecord(type, nodeId, uri)

/**
 * 收够 [expected] 条就放行的 Consumer：测试据此**确定性地**等到投递完成。
 *
 * 超时只负责「测试卡住时快速失败」，不拿「等不到」当互斥或时序的证明。
 */
class EventRecorder(
    private val expected: Int,
) : VfsEventConsumer {
    val received = CopyOnWriteArrayList<VfsEvent>()
    private val arrived = CompletableDeferred<Unit>()
    private val remaining = AtomicInteger(expected)

    override suspend fun onEvent(event: VfsEvent) {
        received.add(event)
        if (remaining.decrementAndGet() == 0) arrived.complete(Unit)
    }

    /** 收到 [expected] 条就返回；到点没收到说明投递确实出了问题，直接失败。 */
    suspend fun await(): List<VfsEvent> {
        withTimeout(30_000) { arrived.await() }
        return received.toList()
    }
}

/**
 * 测试里造的通知器都登记在这里，由**测试类自己**的 `@AfterEach` 统一关掉。
 *
 * 为什么要多这一层：用例自己写 `notifier.close()` 时，**断言先失败就跳过了关闭**，
 * 分发协程会活到测试进程退出（还可能在别的用例里继续送事件）。
 *
 * 注意：关闭钩子必须写在测试类上——JUnit 只在**测试实例**上执行 `@AfterEach`，
 * 挂在这个辅助对象上不会触发（`NotifierTeardownTest` 就是钉这一条的）。
 */
class TrackedNotifiers {
    private val created = CopyOnWriteArrayList<AsyncEventNotifier>()

    fun create(capacity: Int = AsyncEventNotifier.DEFAULT_CAPACITY): AsyncEventNotifier =
        AsyncEventNotifier(capacity).also { created.add(it) }

    /** 幂等：重复调用无害。 */
    fun closeAll() {
        created.forEach { it.close() }
        created.clear()
    }

    companion object {
        /** 最近一次 [create] 造出来的通知器，供「钩子有没有真的触发」的检查用。 */
        @Volatile
        var lastCreated: AsyncEventNotifier? = null
    }
}

/**
 * 「末尾标记」：失败事务之后发的探针事件。
 *
 * 等它到达 = 等到分发链把队列处理到了这里；因为是同一条 FIFO 链，在它之前入队的
 * （包括本该被拒绝的失败事务那条）一定已经先被处理过。这是「没有通知」这类断言的依据，不靠延时。
 */
fun sentinelEvent(name: String = "sentinel"): VfsEvent =
    testRecord(VfsEventType.FILE_CREATED, VfsUri.parse("alcyone://resources/$name.txt")).toVfsEvent()
