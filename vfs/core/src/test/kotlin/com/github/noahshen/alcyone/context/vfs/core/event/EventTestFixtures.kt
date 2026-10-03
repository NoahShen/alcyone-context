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
