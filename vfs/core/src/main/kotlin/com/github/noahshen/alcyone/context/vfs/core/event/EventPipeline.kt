package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork

/**
 * 一次「状态 + 事件」的提交：同一事务里改 Node / Metadata 并追加事件，**提交成功之后**才交给进程内分发。
 *
 * 例子（改一个已有 Node 的说明文字，这个操作不碰磁盘，所以顺序不会出错）：
 *
 * ```
 * // 整个 Runtime 造一套：boundary 全局共享（T11 单连接不支持并发事务），notifier 的 close 归 Runtime 管（T18）
 * val pipeline = EventPipeline(boundary, unitOfWork, notifier)
 *
 * suspend fun updateMetadata(id: NodeId, metadata: NodeMetadata): NodeInfo =
 *     pipeline.commit { scope ->
 *         val info = registry.getNodeInsideBoundary(id)        // ← 同一把锁里的逻辑查询，不再取锁
 *         scope.metadata.put(info.id, metadata)                 // 状态变更
 *         scope.events.append(events.newRecord(METADATA_UPDATED, info.id, info.uri))  // 同一批提交
 *         info
 *     }                                                          // ← 到这里事务已提交，notifier.publish 随后才发生
 * ```
 *
 * **锁**：整条链只取一次锁——[commit] 自己拿 [boundary]，里面一律用不加锁的入口
 * （`registry.getNodeInsideBoundary`）。调用方**不要**在外面再套一层 `boundary.withLock`（不可重入，会自己等自己）。
 *
 * **写文件的顺序属 T15**：`Storage 写入成功 → 本类提交 Node 状态与事件`。SQLite 回滚不了外部文件，
 * 本类不代管那件事；本类保证的只有「状态和事件同批提交，提交成功才通知」。
 *
 * **取消**：[kotlinx.coroutines.CancellationException] 原样传播。取消发生在提交前就是整体回滚、一个事件都不通知；
 * 取消落在「提交完成、入队前后」时，**这一批可能通知了、也可能没通知**——两种都算正常；已提交的事实不撤回，
 * 本类不补偿删除，也不报告「必定没提交」。
 *
 * **通知失败不回头**：Consumer 抛错、队列满丢弃、通知器已关闭，都不改已经提交的结果，也不抛给调用方。
 *
 * Core 只认 [UnitOfWork] 这个 Port，具体数据库由 Runtime 注入（T18）。
 */
class EventPipeline(
    private val boundary: StateBoundary,
    private val unitOfWork: UnitOfWork,
    private val notifier: AsyncEventNotifier,
) {
    /**
     * 在一个事务里执行 [block]，成功返回后把本次追加的事件交给分发。
     *
     * @return [block] 的返回值；提交成功后原样返回
     * @throws IllegalStateException [block] 把事务作用域带到了外面
     */
    suspend fun <T> commit(block: suspend (TransactionScope) -> T): T {
        val recorded = mutableListOf<EventRecord>()
        return boundary.withLock {
            val result =
                unitOfWork.inTransaction { scope ->
                    // 把事件视图换成「记一笔再转发」，调用方照常写 scope.events.append(...)，
                    // 不必自己维护第二个列表——忘了记的事件通知不了，也不会通知出没记录的事件。
                    block(RecordingScope(scope, recorded))
                }
            // 到这里 COMMIT 已经成功。整批一起入队，顺序即提交顺序（同一条边界串行）。
            notifier.publish(recorded.map { it.toVfsEvent() })
            result
        }
    }
}

/** 事件视图加一层「顺手记下来」，nodes / metadata 原样转发。 */
private class RecordingScope(
    private val delegate: TransactionScope,
    private val recorded: MutableList<EventRecord>,
) : TransactionScope {
    override val nodes get() = delegate.nodes

    override val metadata get() = delegate.metadata

    override val events: EventRepository = RecordingEventRepository(delegate.events, recorded)
}

private class RecordingEventRepository(
    private val delegate: EventRepository,
    private val recorded: MutableList<EventRecord>,
) : EventRepository {
    override suspend fun append(event: EventRecord) {
        delegate.append(event)
        // 只记成功追加的：追加抛异常时这条事件会被回滚，不该进通知名单
        recorded.add(event)
    }
}
