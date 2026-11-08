package com.github.noahshen.alcyone.context.memory.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Context SDK 的生命周期入口（CT01 冻结契约，CT06-B2 落实）。
 *
 * 暴露生命周期、会话接收与只读 Resource 访问。Resource 只允许 `alcyone://resources/...`
 * 下的文件，不暴露 VFS、存储路径或后端异常。
 *
 * 同步 [close] 服务阻塞宿主，挂起 [closeAsync] 服务协程宿主；两者共享同一次真实清理结果
 * （成功或失败），重复/并发调用等待同一结果。状态机：
 * `CREATED → STARTING → RUNNING → STOPPING → CLOSED`；启动失败直接 `CLOSED`，`CLOSED` 为终态。
 */
interface AlcyoneContext : AutoCloseable {
    /**
     * 返回进程内缓存的 Memory Map 与固定能力说明；不查数据库、不读 VFS、不调用 LLM、不触发刷新。
     *
     * 第三方默认实现返回空 Map + `UNKNOWN`；Runtime 在未启动/关闭时分别抛出
     * `ContextLifecycleException(NOT_STARTED/CLOSED)`。
     */
    suspend fun bootstrap(): BootstrapContext =
        BootstrapContext(
            memoryMap = null,
            mapStatus = MapStatus.UNKNOWN,
            capabilities = BootstrapContext.DEFAULT_CAPABILITIES,
        )

    /**
     * 规范化、校验并幂等写入一个会话轮次；`CREATED` 在 COMMIT 后异步通知 Count Trigger。
     *
     * 未启动 → `ContextLifecycleException(NOT_STARTED)`；关闭中/已关闭 → `CLOSED`。
     */
    suspend fun ingestConversation(turn: ConversationTurn): IngestResult

    /**
     * 读取 `/resources` 下文件的轻量元数据；后端不提供的统计字段保持 `null`。
     *
     * 未启动 → `ContextLifecycleException(NOT_STARTED)`；关闭中/已关闭 → `CLOSED`。
     * URI、范围或 VFS 失败 → [ContextResourceException]。
     */
    suspend fun loadMeta(uri: String): ResourceMetadata =
        throw ContextResourceException(
            ContextResourceErrorCode.UNAVAILABLE,
            "resource access is unavailable for this AlcyoneContext implementation",
        )

    /**
     * 将 `/resources` 下的文件完整读入内存，并复用 VFS 默认 ByteArray 限额。
     *
     * 未启动 → `ContextLifecycleException(NOT_STARTED)`；关闭中/已关闭 → `CLOSED`。
     * URI、范围、类型、限额或 VFS 失败 → [ContextResourceException]。
     */
    suspend fun read(uri: String): ByteArray =
        throw ContextResourceException(
            ContextResourceErrorCode.UNAVAILABLE,
            "resource access is unavailable for this AlcyoneContext implementation",
        )

    /** 语义检索当前 Organized Memory；第三方默认实现明确 UNAVAILABLE，不返回假空列表。 */
    suspend fun searchMemory(
        query: String,
        domains: List<String>? = null,
        limit: Int = 10,
    ): List<OrganizedMemory> =
        throw ContextRetrievalException(
            ContextRetrievalErrorCode.UNAVAILABLE,
            "memory search is unavailable for this AlcyoneContext implementation",
        )

    /** 按 ID 精确读取 Organized Memory；第三方默认实现明确 UNAVAILABLE。 */
    suspend fun getMemory(id: String): OrganizedMemory? =
        throw ContextRetrievalException(
            ContextRetrievalErrorCode.UNAVAILABLE,
            "memory retrieval is unavailable for this AlcyoneContext implementation",
        )

    /** 按 ID 精确读取 Raw Memory；第三方默认实现明确 UNAVAILABLE。 */
    suspend fun getRawMemory(id: String): RawMemory? =
        throw ContextRetrievalException(
            ContextRetrievalErrorCode.UNAVAILABLE,
            "raw memory retrieval is unavailable for this AlcyoneContext implementation",
        )

    /**
     * 返回一个冷 [Flow]，首次 collect 才检查生命周期并打开文件；每次 collect 独立开关流。
     *
     * 完成、失败、取消或下游提前结束时都会关闭底层流。未启动/已关闭的 collect 分别抛出
     * `ContextLifecycleException(NOT_STARTED/CLOSED)`。
     */
    fun readStream(uri: String): Flow<ByteArray> =
        flow {
            throw ContextResourceException(
                ContextResourceErrorCode.UNAVAILABLE,
                "resource access is unavailable for this AlcyoneContext implementation",
            )
        }

    /** 打开已初始化的 Context 状态库并组装 VFS；并发调用共享同一结果，`RUNNING` 重复调用幂等。 */
    suspend fun start()

    /** 阻塞宿主关闭实例：等待同一次真实清理结果（含失败）；线程被中断不放弃清理。 */
    override fun close()

    /** 协程宿主关闭入口：与 [close] 共享同一次真实清理结果；等待者取消只取消自己。 */
    suspend fun closeAsync()

    /** 返回当前生命周期、真实存储路径与**已缓存**的数据库可用事实；不做 I/O，不探测文件/连接。 */
    suspend fun getStatus(): ContextStatus
}
