package com.github.noahshen.alcyone.context.vfs.core.transaction

import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MetadataRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import java.time.Instant

/**
 * 一次事务内的聚合视图（G7 方案 A）。
 *
 * 进入 [UnitOfWork.inTransaction] 回调后获得；视图类型与 B4 的 Repository 接口一致，
 * 调用方不需要为事务再写一套接口。事务对象离开回调即失效，回调外使用抛 [IllegalStateException]，
 * 绝不退化为自动提交模式。
 */
interface TransactionScope {
    /** 节点状态视图。 */
    val nodes: NodeRepository

    /** 元数据视图。 */
    val metadata: MetadataRepository

    /** 事件追加视图。与节点、元数据共用同一次提交。 */
    val events: EventRepository

    /** recovery journal 的同事务关闭视图；关闭与 Node / Event 一起提交或回滚。 */
    val recovery: RecoveryCloseRepository
}

/** 事务内的 recovery 终态写入。只允许从已观察阶段写入 CLOSED。 */
interface RecoveryCloseRepository {
    suspend fun close(
        operationId: String,
        expectedGeneration: Long,
        at: Instant,
    )
}

/** 未启用 journal 的组装共用该视图；只有调用方确认 journal 已启用时才会调用它。 */
object DisabledRecoveryCloseRepository : RecoveryCloseRepository {
    override suspend fun close(
        operationId: String,
        expectedGeneration: Long,
        at: Instant,
    ): Unit = throw IllegalStateException("recovery journal is disabled")
}

/**
 * 事务作用域入口（G7 方案 A）。
 *
 * 回调正常返回即提交；回调抛出异常则整体回滚，回调内已排队的变更全部不生效。
 * [kotlinx.coroutines.CancellationException] 原样传播，不包装、不改变语义。
 * Storage 操作不在此事务内，T03 第 4 节不承诺外部文件写入回滚。
 */
interface UnitOfWork {
    /**
     * 在单个事务内执行 [block]。
     *
     * @return 回调的返回值；提交成功后原样返回
     * @throws IllegalStateException 事务对象逃逸到回调外使用
     */
    suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T
}
