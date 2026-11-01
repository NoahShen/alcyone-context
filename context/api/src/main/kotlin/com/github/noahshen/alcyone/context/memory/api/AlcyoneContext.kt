package com.github.noahshen.alcyone.context.memory.api

/**
 * Context SDK 的生命周期入口（CT01 冻结契约，CT06-B2 落实）。
 *
 * 仅暴露 `start` / `close` / `closeAsync` / `getStatus`；CT07 才追加业务入口
 * （如 `ingestConversation`），本批不放 TODO 或假实现。
 *
 * 同步 [close] 服务阻塞宿主，挂起 [closeAsync] 服务协程宿主；两者共享同一次真实清理结果
 * （成功或失败），重复/并发调用等待同一结果。状态机：
 * `CREATED → STARTING → RUNNING → STOPPING → CLOSED`；启动失败直接 `CLOSED`，`CLOSED` 为终态。
 */
interface AlcyoneContext : AutoCloseable {
    /**
     * 规范化、校验并幂等写入一个会话轮次；`CREATED` 在 COMMIT 后异步通知 Count Trigger。
     *
     * 未启动 → `ContextLifecycleException(NOT_STARTED)`；关闭中/已关闭 → `CLOSED`。
     */
    suspend fun ingestConversation(turn: ConversationTurn): IngestResult

    /** 打开已初始化的 Context 状态库并组装 VFS；并发调用共享同一结果，`RUNNING` 重复调用幂等。 */
    suspend fun start()

    /** 阻塞宿主关闭实例：等待同一次真实清理结果（含失败）；线程被中断不放弃清理。 */
    override fun close()

    /** 协程宿主关闭入口：与 [close] 共享同一次真实清理结果；等待者取消只取消自己。 */
    suspend fun closeAsync()

    /** 返回当前生命周期、真实存储路径与**已缓存**的数据库可用事实；不做 I/O，不探测文件/连接。 */
    suspend fun getStatus(): ContextStatus
}
