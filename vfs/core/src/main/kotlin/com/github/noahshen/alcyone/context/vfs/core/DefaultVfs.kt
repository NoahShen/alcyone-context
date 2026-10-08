package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeInfo
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.ReadOptions
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.StorageStat
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsEntry
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsStreamResult
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.operation.ExecutionStrategy
import com.github.noahshen.alcyone.context.vfs.core.operation.OperationGuard
import com.github.noahshen.alcyone.context.vfs.core.operation.OperationIntent
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.repository.MetadataRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.router.RouteMatch
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import kotlinx.coroutines.CancellationException
import java.io.FilterInputStream
import java.time.Instant

/**
 * 首版默认限额：读写都是 16 MiB（T04 §6 确认，单元是字节）。Runtime 可以按配置改小或改大（T18）。
 */
data class VfsLimits(
    /** `ReadOptions.maxBytes` 没给时的读取上限，也是单次读取能放宽到的天花板。 */
    val defaultReadMaxBytes: Long = DEFAULT_LIMIT_BYTES,
    /** 一次 `write` 允许的内容上限；超限在碰磁盘之前就拒绝。 */
    val defaultWriteMaxBytes: Long = DEFAULT_LIMIT_BYTES,
) {
    init {
        if (defaultReadMaxBytes < 0 || defaultWriteMaxBytes < 0) {
            throw VfsException(
                VfsErrorCode.INVALID_ARGUMENT,
                "VfsLimits must not be negative: read=$defaultReadMaxBytes write=$defaultWriteMaxBytes",
            )
        }
    }

    companion object {
        /** 16 MiB。 */
        const val DEFAULT_LIMIT_BYTES: Long = 16L * 1024 * 1024
    }
}

/**
 * VFS 的基础文件操作（T15）：把已交付的路由、预检、Node Registry、事件提交和 Storage 拼成 `read` / `write` / `stat` /
 * `list` 和 `getNode`；T16 加上 `delete`；T17 加上 `getMetadata` / `setMetadata`；T18 加上 `openStream`（流式读）。Core 只依赖 Port，具体 SQLite 与本地磁盘由调用方注入。
 *
 * 例：挂载 `/resources` 指向本地磁盘，`write(alcyone://resources/notes/a.txt, "hi")` 会
 * 自动建出 `notes` 目录、写文件、在同一个事务里登记 Node 并追加 `FILE_CREATED` 事件。
 *
 * **唯一持锁者**：写文件从预检到状态提交全程只在 [StateBoundary] 上取一次锁，
 * SQLite 事务只覆盖最后的「Node 状态 + 事件」这一段，不包住 Storage I/O。读和列表同样在这把锁里，
 * 所以状态库上的读写都排同一条队（T11 单连接不能并发事务）。`stat` / `getNode` 直接委托 Registry，它自己取锁。
 *
 * **失败时报告实际副作用**（effect 合并规则）：
 *
 * | 发生了什么 | 报什么 |
 * | --- | --- |
 * | 参数、限额、结构、路由、能力、模式、类型冲突被拒 | 原错误码 + effect `NONE`（一个字节都没动） |
 * | 补父目录**建到一半**失败 | 原错误码 + 至少 `PARTIAL`（已经建出来的空目录是真实副作用）；一个都没建成时保留后端自己的 effect |
 * | 补完父目录之后 Storage 写失败 | 原错误码 + 至少 `PARTIAL`（留下的空目录是真实副作用） |
 * | 后端说不清是否写成功 | 保持 `UNKNOWN`，不改报 `NONE` / `PARTIAL` |
 * | Storage 写成功但状态或事件提交失败 | `STATE_ERROR` + `PARTIAL`（文件内容留着，Node 与事件一起回滚，没有成功通知） |
 * | 删除时 Storage 报错 | 原错误码 + 后端自己的 effect（原样带出，不降级）；逻辑状态先留着不清 |
 * | 删除已在盘上生效但状态或事件提交失败 | `STATE_ERROR` + `PARTIAL`（文件删了回不来，Node 与 Metadata 保持原样） |
 * | Metadata 替换的事务失败 | `STATE_ERROR` + `NONE`（这一批状态与事件一起回滚，没有 Storage 副作用，不套 PARTIAL） |
 * | 通知队列满、Consumer 抛错 | 不影响已提交的结果（复用 T14 的 EventPipeline） |
 * | 协程被取消 | 取消原样传播；已提交的事实不撤回，不声称「取消 = 全部回滚」 |
 *
 * **删除（T16）**：物理删除走 Storage 自己的递归实现（Core 不重写遍历），逻辑侧把目标与「完整段边界内已登记的子树」
 * 标记删除、清掉各自 Metadata，追加**一条**目标级事件（目录删除表示整棵子树失效，不为后代逐个造事件）。
 *
 * **Metadata（T17）是纯状态库操作**：不查挂载、不 stat 物理文件、不碰 Storage、不懒注册。
 * 例：外部直接删掉了磁盘上的 `a.dcm`，它的 Node 还有效，标签和说明照样能读能改；
 * 经 VFS 删掉之后同一个旧 ID 就报 `NOT_FOUND`，不会退化成「返回空 Metadata」。
 *
 * **本轮没交付的方法**：**目录移动**（T22 接续）明确抛 [VfsErrorCode.UNSUPPORTED_OPERATION] 且零副作用。
 * 普通文件移动的**三种策略**都已交付：同 Mount 原生移动（T20，见 [move]）、同 Mount 复制回退与
 * 跨 Mount 复制移动（T21，见 [move] 与 [moveByCopy]）。
 */
class DefaultVfs(
    /** 挂载与配置目录（T09）。 */
    private val router: MountRouter,
    /** 每块存储的能力快照（T10）。预检和「能不能补父目录」都查它。 */
    private val capabilities: CapabilitySnapshot,
    /** Node 身份与懒注册（T13）。`stat` / `getNode` 直接委托它。 */
    private val registry: NodeRegistry,
    /** 自动提交的 Node 仓库：写之前查逻辑记录、列表补已登记的 Node ID。 */
    private val nodes: NodeRepository,
    /**
     * 自动提交的 Metadata 仓库：只给 [getMetadata] 读用，写永远走事务里那一份（见 [setMetadata]）。
     * 同一个文件里，属性名 `metadata` 指这里这个仓库；[setMetadata] 的同名参数只在该方法内指 `NodeMetadata`。
     */
    private val metadata: MetadataRepository,
    /** 按 [com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord.storageKey] 取 Storage。 */
    private val storages: (String) -> Storage?,
    /** 状态库串行边界。整个 Runtime 共用一个实例，Registry 与 EventPipeline 也用同一个。 */
    private val boundary: StateBoundary,
    /** 「状态 + 事件」同事务提交（T14）。 */
    private val pipeline: EventPipeline,
    /** 事件工厂；测试可以给固定时钟。 */
    private val events: EventFactory = EventFactory(),
    /** 读写限额。 */
    private val limits: VfsLimits = VfsLimits(),
    /**
     * 流式读取的总量上限，`null` 表示不设总量上限（仍然分块读）。和 [limits] 分开：
     * [ReadOptions] 管的是 ByteArray 整体读进来的字节数，这里管的是一条流累计读到的字节数。
     */
    private val streamTotalLimit: Long? = null,
    /** Node 的逻辑时间；测试可以换成固定值。 */
    private val clock: () -> Instant = Instant::now,
) : Vfs {
    /**
     * 读一个文件的内容。不登记 Node、不改 Metadata、不发事件。
     *
     * 例：磁盘上有个从没登记过的 `a.txt`，`read` 照样能读到字节，`getNode` 却查不到它——第一次 `stat` 才发身份。
     *
     * 实际上限 = [VfsLimits.defaultReadMaxBytes] 与 [ReadOptions.maxBytes] 里**较小**的那个：
     * `null` 表示用配置（不是无限），`0` 表示只接受空文件。交给 Storage 的有界读取去执行，
     * 不靠 stat 的长度判断——读取过程中才产生的字节同样要算进来。
     */
    override suspend fun read(
        uri: VfsUri,
        options: ReadOptions,
    ): ByteArray =
        boundary.withLock {
            val path = uri.path
            // 配置里推导出的目录——逻辑根、命名空间根、挂载点、挂载的缺层祖先——都是目录。
            // 它下面的同名物理文件已被遮蔽，不许从这条逻辑路径读出来（T02 §3.2、§8.2）。
            if (router.isConfiguredDirectory(path)) throw configuredDirectory(path, "read")
            val route = router.route(path) ?: throw mountNotFound(path)
            storageFor(route).read(route.relativePath, effectiveReadLimit(options)).bytes
        }

    /**
     * 流式读一个文件（T18 §2.4）。读大文件用：不用把整个文件变成 ByteArray，也就没有 16 MiB 那道坎。
     *
     * 语义和 [read] 一致：不登记 Node、不发事件、不碰状态库。**取锁只覆盖打开流这一下**，
     * 调用方慢慢读的时候不占着状态库的队——所以这里没有 `boundary.withLock`。
     *
     * 目录（配置推导出的目录、盘上的真目录）报 `TYPE_MISMATCH`，路径不存在由后端报 `NOT_FOUND`。
     */
    override suspend fun openStream(
        uri: VfsUri,
        options: VfsStreamOptions,
    ): VfsStreamResult = openStream(uri, options, onClose = {})

    /**
     * 和 [openStream] 同一个实现，多带一个「关掉时叫一下」的回调。
     *
     * @param onClose 这次读取被关掉时调一次（Runtime 用它把流从在途资源里摘掉；普通调用方不用传）。
     *
     * 例：`read` 因为 16 MiB 限额拒掉 20 MiB 的视频，`openStream` 可以一块一块读完。
     */
    suspend fun openStream(
        uri: VfsUri,
        options: VfsStreamOptions,
        onClose: () -> Unit = {},
    ): VfsStreamResult {
        val path = uri.path
        if (router.isConfiguredDirectory(path)) throw configuredDirectory(path, "openStream")
        val route = router.route(path) ?: throw mountNotFound(path)
        val opened = storageFor(route).readStream(route.relativePath, effectiveStreamLimit(options))
        val stream =
            try {
                opened.openStream()
            } catch (failure: Throwable) {
                // 流已经建出来、还没交出去就失败了：当场放掉，别把文件句柄留在盘上。
                // 清理自己报错不顶掉原始失败，挂 suppressed。
                runCatching { opened.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        return VfsStreamResult(
            uri = uri,
            sizeBytes = opened.attributes.sizeBytes,
            onClose = onClose,
            // 关流时把后端那份也放掉：两者共用同一个文件句柄，关掉谁都行，只能生效一次。
            stream =
                object : FilterInputStream(stream) {
                    /**
                     * 两步都要试：内容流关了、后端那份也要关。
                     * 第一个失败当主异常，第二个挂 suppressed——不能因为第一步报错就把句柄漏在盘上。
                     */
                    override fun close() {
                        var failure: Throwable? = null
                        try {
                            super.close()
                        } catch (problem: Throwable) {
                            failure = problem
                        }
                        try {
                            opened.close()
                        } catch (problem: Throwable) {
                            if (failure == null) failure = problem else failure.addSuppressed(problem)
                        }
                        failure?.let { throw it }
                    }
                },
        )
    }

    /**
     * `stat`：直接委托 T13 的 Node Registry，所以懒注册、includeStorage 两种语义、虚拟目录和挂载根类型检查
     * 全部沿用已确认的行为，本类不重复实现一遍。
     */
    override suspend fun stat(
        uri: VfsUri,
        options: StatOptions,
    ): NodeInfo = registry.resolveOrRegister(uri.path, options.includeStorage)

    /** `getNode`：纯查库，查不到报 `NOT_FOUND`，不会顺着 ID 找磁盘。 */
    override suspend fun getNode(id: NodeId): NodeInfo = registry.getNode(id)

/**
     * 列一个目录的直接子项。物理目录 + 配置推导出的目录入口，单层合并（T02 §5.2）。
     *
     * 例：父盘上有个叫 `medical` 的文件，却把 `/resources/medical/ct` 挂了出去。
     * 列 `/resources` 看到的是逻辑目录 `medical`（父盘那个文件被遮蔽，不列出来）；
     * 列 `/resources/medical` 才看得到 `ct`。父列表不连接子后端，所以 ct 那块盘离线时父层入口照样可见。
     *
     * 不注册 Node、不逐项 stat：已登记的 Node ID 用一次 `findByPaths` 批量补齐，没登记的就是 `null`。
     * 返回顺序没有保证。
     */
    override suspend fun list(uri: VfsUri): List<VfsEntry> = boundary.withLock { listInsideBoundary(uri.path) }

    private suspend fun listInsideBoundary(path: VfsPath): List<VfsEntry> {
        val route = router.route(path)
        // 没有挂载覆盖时，只有配置里就有的目录可列；其他路径是真的没地方可查。
        if (route == null && !router.isConfiguredDirectory(path)) throw mountNotFound(path)

        val entries = LinkedHashMap<VfsPath, VfsEntry>()
        if (route != null) collectPhysicalChildren(path, route, entries)
        // 配置入口后放，同名时覆盖物理条目：子挂载（以及被文件遮蔽的逻辑目录）以逻辑结构为准。
        for (name in router.listConfiguredChildren(path)) {
            val child = VfsPath.of(path.segments + name)
            entries[child] = VfsEntry(VfsUri.of(child), NodeType.DIRECTORY, null, null)
        }
        if (entries.isEmpty()) return emptyList()

        val registered = nodes.findByPaths(entries.keys.toList()).associateBy { it.path }
        return entries.values.map { entry -> entry.copy(nodeId = registered[entry.uri.path]?.id) }
    }

    /**
     * 把物理目录的直接子项收进来。三种结果：
     *
     * - 真的是目录 → 列出来（这次确实要访问后端，它失败就整体失败，不吞成「只有配置入口」）；
     * - 配置推导目录且磁盘上缺失，或同名物理文件把它遮蔽了 → 一个物理子项都不加，也不伪造物理访问；
     * - 普通文件 → `TYPE_MISMATCH`。
     *
     * 挂载根不回退成虚拟目录：它必须真的能访问到后端根。
     */
    private suspend fun collectPhysicalChildren(
        path: VfsPath,
        route: RouteMatch,
        entries: MutableMap<VfsPath, VfsEntry>,
    ) {
        val storage = storageFor(route)
        val attributes =
            try {
                storage.stat(route.relativePath)
            } catch (failure: VfsException) {
                // 只把「后端说没有」和「必要祖先在磁盘上是个文件」当作遮蔽；权限不足、后端已关、一般 I/O 错误一律照抛。
                if (failure.code in SHADOWED_CODES && virtualFallbackAllowed(path)) null else throw failure
            }
        if (attributes != null && router.isMountPoint(path) && attributes.type != NodeType.DIRECTORY) {
            throw VfsException(
                VfsErrorCode.TYPE_MISMATCH,
                "Mount root '$path' is a ${attributes.type} on the backing storage; a mount root must be a directory",
                VfsUri.of(path),
            )
        }
        if (attributes == null) return
        if (attributes.type == NodeType.FILE) {
            // 被遮蔽的同名物理文件不算子项，也不因此报错；没被遮蔽的普通文件才是类型不符。
            if (!router.isConfiguredDirectory(path)) {
                throw VfsException(VfsErrorCode.TYPE_MISMATCH, "'$path' is a file, not a directory", VfsUri.of(path))
            }
            return
        }
        for (entry in storage.list(route.relativePath)) {
            val child = VfsPath.of(path.segments + entry.name)
            val attributes = entry.attributes
            entries[child] =
                VfsEntry(
                    uri = VfsUri.of(child),
                    type = entry.type,
                    nodeId = null,
                    storage = attributes?.let { StorageStat(sizeBytes = it.sizeBytes, modifiedAt = it.modifiedAt) },
                )
        }
    }

    /**
     * 写入一个文件：预检 → 确认实际类型与存在性 → 补缺失父目录 → Storage 写入 → 状态与事件同事务提交。
     *
     * 顺序契约（任何可确认的拒绝都发生在建目录和写内容之前）：
     *
     * 1. 内容超过 [VfsLimits.defaultWriteMaxBytes] → `LIMIT_EXCEEDED`，零物理变更；
     * 2. T10 预检（结构保护、路由、能力）；
     * 3. 向 Storage 确认目标到底是文件还是目录、有没有真的存在，再按 [WriteMode] 判存在性；
     *    [OperationIntent] 里的类型是调用方的声明，不是验证过的事实。
     * 4. 只在目标所属挂载内补**缺失的**父目录；父组件是物理文件就拒绝，不改写那个遮蔽文件；
     *    缺建目录能力且确实要补 → `UNSUPPORTED_OPERATION`，父目录已经齐全时**不**因此拒绝；
     * 5. Storage 写入；
     * 6. 一个事务里创建或更新 Node 并追加事件：写入前确实不存在 → `FILE_CREATED`，覆盖已有文件 → `FILE_WRITTEN`。
     *    覆盖保留 Node ID、登记时间和 Metadata，只推进 [NodeInfo.updatedAt]。
     *
     * 返回的 [NodeInfo] 复用第 5 步拿到的磁盘属性，不为返回值再 stat 一次。
     */
    override suspend fun write(
        uri: VfsUri,
        content: ByteArray,
        options: WriteOptions,
    ): NodeInfo {
        val path = uri.path
        if (content.size.toLong() > limits.defaultWriteMaxBytes) {
            throw VfsException(
                VfsErrorCode.LIMIT_EXCEEDED,
                "Content of ${content.size} bytes exceeds the configured write limit of ${limits.defaultWriteMaxBytes} bytes",
                uri,
            )
        }
        // 整条链只取这一次锁：预检、实际确认、补目录、Storage 写入和最后的状态提交都在里面。
        return boundary.withLock { writeInsideBoundary(uri, content, options.mode) }
    }

    private suspend fun writeInsideBoundary(
        uri: VfsUri,
        content: ByteArray,
        mode: WriteMode,
    ): NodeInfo {
        val target = OperationGuard.check(OperationIntent.write(uri.path, mode), router, capabilities).target!!
        val storage = storageFor(target)
        val existedBefore = confirmWriteTarget(uri, storage, target, mode)
        // 逻辑记录存在但类型不是文件：状态和现实对不上，绝不靠写入把类型改过来。
        nodes.findByPath(uri.path)?.let { recorded ->
            if (recorded.type != NodeType.FILE) {
                throw VfsException(
                    VfsErrorCode.CONFLICT,
                    "Registered node at '${uri.path}' is ${recorded.type} but a file is being written; " +
                        "writing does not change stored node records",
                    uri,
                )
            }
        }

        val createdDirectories = createMissingParents(uri, storage, target)
        val written =
            try {
                storage.write(target.relativePath, content, mode.toStorageMode())
            } catch (failure: VfsException) {
                // 补出来的空目录是真实副作用，不能把 effect 降回 NONE；后端说不清的 UNKNOWN 保持原样。
                throw failure.withKnownChanges(createdDirectories)
            }
        return try {
            pipeline.commitInsideBoundary { scope -> commitWrite(scope, uri, existedBefore, written) }
        } catch (cancellation: CancellationException) {
            // 取消原样传播。文件已经写好这件事不会因为取消而消失，也不声称状态已回滚。
            throw cancellation
        } catch (failure: Exception) {
            // 只收普通异常：JVM 的 Error（OOM、StackOverflow）不是状态库拒绝了一条记录，原样抛出去。
            throw VfsException(
                VfsErrorCode.STATE_ERROR,
                "The file was written to storage but the node state and event could not be committed: " +
                    "${failure.message ?: failure::class.java.simpleName}",
                uri,
                effect = VfsEffect.PARTIAL,
            ).apply { initCause(failure) }
        }
    }

    /**
     * 写入前向 Storage 确认事实，并按模式判定存在性（T02 §6.1 的矩阵）。
     *
     * @return 写入前目标**真的**是否存在：它决定事件是 `FILE_CREATED` 还是 `FILE_WRITTEN`，也决定覆盖时能不能沿用已有身份。
     */
    private suspend fun confirmWriteTarget(
        uri: VfsUri,
        storage: Storage,
        target: RouteMatch,
        mode: WriteMode,
    ): Boolean {
        val attributes =
            try {
                storage.stat(target.relativePath)
            } catch (missing: VfsException) {
                // 只把「没有」当作不存在；祖先是文件（TYPE_MISMATCH）和任何 I/O 错误都照抛。
                if (missing.code == VfsErrorCode.NOT_FOUND) null else throw missing
            }
        if (attributes != null && attributes.type == NodeType.DIRECTORY) {
            throw VfsException(
                VfsErrorCode.TYPE_MISMATCH,
                "'${uri.path}' is an existing directory and cannot be overwritten by a file",
                uri,
            )
        }
        val exists = attributes != null
        if (exists && mode == WriteMode.CREATE_NEW) {
            throw VfsException(VfsErrorCode.ALREADY_EXISTS, "'${uri.path}' already exists", uri)
        }
        if (!exists && mode == WriteMode.REPLACE_EXISTING) {
            throw VfsException(VfsErrorCode.NOT_FOUND, "'${uri.path}' does not exist", uri)
        }
        return exists
    }

    /**
     * 只在目标所属挂载内补缺失的父目录；返回是否真的建过。
     *
     * 从挂载根往下逐段看：已存在的目录跳过，是文件就拒绝（改写遮蔽文件是另一回事，T02 §3.2），
     * 缺失的记下来等会儿建。父目录不批量登记、不发单独的目录创建事件。
     *
     * 逐层建，每建成一层就记一次已建层数：中途某层失败时，这次调用已经留下的目录
     * 不会因为函数退出就被忘掉，异常直接带着这个事实往上抛，由调用方合并进 effect（至少 `PARTIAL`）。
     *
     * @return 这次真的建出来的层数；诊断文本只说「建了部分父目录」，不带后端相对路径（那是 Storage 的内部坐标）。
     */
    private suspend fun createMissingParents(
        uri: VfsUri,
        storage: Storage,
        target: RouteMatch,
    ): Int {
        val missing = mutableListOf<StoragePath>()
        var prefix = emptyList<String>()
        for (segment in target.relativePath.parent.segments) {
            prefix = prefix + segment
            val candidate = StoragePath.of(prefix)
            val attributes =
                try {
                    storage.stat(candidate)
                } catch (absent: VfsException) {
                    if (absent.code == VfsErrorCode.NOT_FOUND) null else throw absent
                }
            when (attributes?.type) {
                null -> missing += candidate
                NodeType.FILE ->
                    throw VfsException(
                        VfsErrorCode.TYPE_MISMATCH,
                        "Cannot write '${uri.path}': '${candidate.toRelativeString()}' on the backing storage is a file",
                        uri,
                    )

                NodeType.DIRECTORY -> Unit
            }
        }
        if (missing.isEmpty()) return 0
        // 父目录已经齐全时不看这个能力；确实要补而补不了才拒绝。
        if (!capabilitiesOf(target).createDirectory) {
            throw VfsException(
                VfsErrorCode.UNSUPPORTED_OPERATION,
                "Cannot write '${uri.path}': the backing storage cannot create the missing parent directories",
                uri,
            )
        }
        var created = 0
        for (directory in missing) {
            try {
                storage.createDirectory(directory)
            } catch (failure: VfsException) {
                // 先建成的那些目录留在盘上，不回删也不自动补偿；只是必须把「已经建了什么」如实带出去。
                throw failure.withKnownChanges(created)
            }
            created++
        }
        return created
    }

    /**
     * 最后一个事务：新建或更新 Node，追加对应事件（T03 §4、§5）。
     *
     * 不调用 Registry 的自提交注册方法：那会在另一个事务里写一条记录，事件就落不到同一批里。
     * 事件类型按写入前的实际存在性选，不看之前有没有登记过。
     */
    private suspend fun commitWrite(
        scope: TransactionScope,
        uri: VfsUri,
        existedBefore: Boolean,
        written: StorageAttributes,
    ): NodeInfo {
        val now = clock()
        val storageStat = StorageStat(sizeBytes = written.sizeBytes, modifiedAt = written.modifiedAt)
        val existing = scope.nodes.findByPath(uri.path)
        val info =
            if (existing == null) {
                val registered =
                    scope.nodes.register(
                        NodeRecord(
                            id = NodeId.parse(newUuidV7().toString()),
                            path = uri.path,
                            type = NodeType.FILE,
                            physical = true,
                            registeredAt = now,
                            updatedAt = now,
                        ),
                    )
                NodeInfo(registered.id, uri, NodeType.FILE, registered.registeredAt, registered.updatedAt, storageStat)
            } else {
                scope.nodes.touch(existing.id, now)
                NodeInfo(existing.id, uri, NodeType.FILE, existing.registeredAt, now, storageStat)
            }
        val type = if (existedBefore) VfsEventType.FILE_WRITTEN else VfsEventType.FILE_CREATED
        scope.events.append(events.newRecord(type, info.id, uri))
        return info
    }

    /**
     * 移动一个文件（T20：同一 Mount 内的原生移动 / 重命名；T21：同 Mount 复制回退与跨 Mount 复制移动）。
     *
     * 例：`move(alcyone://resources/docs/a.txt, alcyone://resources/docs/archive/b.txt)` 把文件从 `docs`
     * 挪到 `docs/archive`（缺失的 `archive` 自动补出来），保留同一个 Node ID 与 Metadata，追加一条
     * `FILE_MOVED`；旧路径随即 `NOT_FOUND`，在旧路径另建文件会拿到新 ID。
     *
     * 顺序契约：**纯参数、已知结构 / 阶段、只读、目标已存在等预检**拒绝发生在注册源、建目录、物理移动**之前**；
     * 但预检之后的**源懒注册**与**逐层补父目录**本身就是真实副作用，它们之后的任何失败（含复制路径的读 / 写 / 确认 / 删源）
     * 都按 T15 的 effect 合并规则如实报告，**不声称「拒绝一律零副作用」**：
     *
     * 1. 纯参数冲突：源和目标同路径、目标落进源子树 → `INVALID_ARGUMENT`（T02 §8.2「参数错误优先」）；
     * 2. 结构保护：源或目标是受保护配置目录 → `UNSUPPORTED_OPERATION`；
     * 3. 路由：源或目标没有挂载覆盖 → `MOUNT_NOT_FOUND`；
     * 4. `storage.stat` 确认源**真的存在**并拿到**实际类型**（不存在 → `NOT_FOUND`）；
     * 5. 按实际类型跑一遍 T10 预检（[OperationIntent.move]）拿到策略；**目录**仍阶段拒绝（T22），
     *    其余三种策略各走各的分支——不把后端支持的能力伪报为不支持；
     * 6. 目标**已存在**（文件或目录都算）→ `ALREADY_EXISTS`，不覆盖、不创建父目录、不把目标目录
     *    解释成「放进去」；
     * 7. 已登记源复用原 ID；未登记源先建立**一次**身份再迁移这条记录（不在目标重新生成 ID）；
     * 8. 只在目标所属 Mount 内补缺失父目录（父组件是文件 → `TYPE_MISMATCH`）；
     * 9. `storage.move`（同 Mount 原生移动，不把内容读进 ByteArray）；
     * 10. 一个事务里更新 Node 路径（同一个 ID、同一个登记时间，只推进 `updatedAt`）并追加一条 `FILE_MOVED`，
     *    提交成功后才通知。
     *
     * 失败时的效果沿用 T15 / T16 的合并规则：补目录之后任何失败至少 `PARTIAL`，后端 `UNKNOWN` 不降级；
     * 物理移动成功但状态 / 事件提交失败 → `STATE_ERROR` + `PARTIAL`，保留 cause，不回移文件、不发成功通知。
     * [CancellationException] 原样传播。
     */
    override suspend fun move(
        source: VfsUri,
        target: VfsUri,
    ): NodeInfo {
        // 整条链只取这一次锁：预检、补目录、原生移动与状态提交都在里面（锁不可重入，内部一律用不加锁入口）。
        return boundary.withLock { moveInsideBoundary(source, target) }
    }

    /**
     * 移动的锁内部分：纯参数 / 结构 / 路由 / 只读 / 目标已存在等**预检**拒绝先于注册源、补目录、物理复制或移动；
     * 预检之后源懒注册与逐层建目录已是真实副作用，后续失败按 effect 合并如实上报（不声称「拒绝一律零副作用」）。
     */
    private suspend fun moveInsideBoundary(
        source: VfsUri,
        target: VfsUri,
    ): NodeInfo {
        val sourcePath = source.path
        val targetPath = target.path
        // 1. 纯参数冲突放到最前：源真实类型要等 stat，所以这里单独拒一次（T02 §8.2）。
        OperationGuard.rejectMoveArgumentConflicts(sourcePath, targetPath)
        // 2. 结构保护先于路由与任何后端访问：源 / 目标都是受保护配置目录时当场拒。
        requireMovableStructure(source)
        requireMovableStructure(target)
        // 3. 路由：源和目标都必须有挂载覆盖，否则 MOUNT_NOT_FOUND。
        val sourceRoute = router.route(sourcePath) ?: throw mountNotFound(sourcePath)
        val targetRoute = router.route(targetPath) ?: throw mountNotFound(targetPath)
        // 4. 确认源真的存在并拿到实际类型；不存在时 Storage 直接报 NOT_FOUND，其他错误照抛。
        val sourceStorage = storageFor(sourceRoute)
        val sourceAttributes = sourceStorage.stat(sourceRoute.relativePath)
        val actualType = sourceAttributes.type
        // 5. 按真实类型跑预检：目录仍阶段拒绝（T22）；其余三种策略由各自分支处理。
        val precondition =
            OperationGuard.check(
                OperationIntent.move(sourcePath, targetPath, actualType),
                router,
                capabilities,
            )
        if (actualType == NodeType.DIRECTORY) {
            throw VfsException(
                VfsErrorCode.UNSUPPORTED_OPERATION,
                "Cannot move '$sourcePath': directory move is not implemented yet (planned for T22). " +
                    "The call was rejected before any state or storage change.",
                source,
            )
        }
        return when (precondition.strategy) {
            ExecutionStrategy.NATIVE_MOVE -> moveNative(source, target, sourceRoute, targetRoute, sourceStorage)
            ExecutionStrategy.COPY_FALLBACK_MOVE ->
                moveByCopy(source, target, sourceRoute, targetRoute, sourceStorage, sourceStorage, sourceAttributes)
            ExecutionStrategy.CROSS_MOUNT_COPY_MOVE -> {
                val targetStorage = storageFor(targetRoute)
                moveByCopy(source, target, sourceRoute, targetRoute, sourceStorage, targetStorage, sourceAttributes)
            }
            else -> throw IllegalStateException("Unexpected move strategy: ${precondition.strategy}")
        }
    }

    /**
     * 同 Mount 原生移动（T20）：预检 → 目标全新 → 身份 → 补父目录 → storage.move → 同事务提交。
     */
    private suspend fun moveNative(
        source: VfsUri,
        target: VfsUri,
        sourceRoute: RouteMatch,
        targetRoute: RouteMatch,
        storage: Storage,
    ): NodeInfo {
        // 目标必须全新：已有普通文件或普通目录都拒绝，不覆盖、不创建父目录、不当作「放进去」。
        confirmMoveTargetAbsent(target, storage, targetRoute)

        // 身份：已登记源复用原 ID；未登记源按 NodeRepository 的注册语义建立一次身份（懒注册）再迁移这条记录。
        val nodeId = ensureSourceIdentity(source.path)

        // 只在目标 Mount 内补缺失父目录（复用 T15 的逐层创建，不批量登记、不发目录事件）。
        val createdDirectories = createMissingParents(target, storage, targetRoute)
        // 物理原生移动；失败时把「已建出的父目录」合并进 effect，不把 UNKNOWN 降级成 NONE。
        val moved =
            try {
                storage.move(sourceRoute.relativePath, targetRoute.relativePath)
            } catch (failure: VfsException) {
                throw failure.withKnownChanges(createdDirectories)
            }
        // 状态 / 事件同事务提交；物理已经移动这件事不会因为提交失败而回退。
        return try {
            pipeline.commitInsideBoundary { scope -> commitMove(scope, nodeId, source, target, moved) }
        } catch (cancellation: CancellationException) {
            // 取消原样传播。文件已经到目标这件事不会因为取消而消失，也不声称状态已回滚。
            throw cancellation
        } catch (failure: Exception) {
            // 只收普通异常：JVM 的 Error（OOM、StackOverflow）不是状态库拒绝了一条记录，原样抛出去。
            throw VfsException(
                VfsErrorCode.STATE_ERROR,
                "The file was moved on the backing storage but the node path and event could not be committed: " +
                    "${failure.message ?: failure::class.java.simpleName}",
                target,
                effect = VfsEffect.PARTIAL,
            ).apply { initCause(failure) }
        }
    }

    /**
     * 复制回退 / 跨 Mount 文件移动（T21）：读源 → 写目标（CREATE_NEW）→ 确认目标 → 删源 → 同事务提交。
     *
     * 两个挂载点可能是同一块存储（复制回退），也可能是不同存储（跨 Mount）；源 / 目标各按各自路由取 Storage 与相对路径。
     * 不调用公开 Vfs.read/write/delete，避免重复取锁、中间身份与中间事件。
     * 读取上限不超过 Core 现有读写限额；后端更严格限制仍生效，超限报 LIMIT_EXCEEDED 并保留源。
     * 读取成功并确认实际字节数后再补目标父目录、再写入。
     * 写入成功后 stat 目标确认为文件，把**所有可用长度**（源预检 stat、读取回执、写入回执、目标 stat）
     * 与实际复制字节数核对；已知长度中任意一个不等就报 CONFLICT，不继续删源。
     * 长度缺失（null）表示「未知」，不参与比较、不等于 0。
     * 确认 / 删源遇到后端错误时**保留后端原始 code / effect（含 UNKNOWN）**，仅把 effect 由 NONE 提到 PARTIAL；
     * 只有已观测到的类型 / 长度矛盾才用 CONFLICT。
     * 确认通过后才调用源 Storage.delete；删源失败不提交新映射。
     * 删源成功但状态 / 事件提交失败报 STATE_ERROR + PARTIAL，保留 cause、不回移、不通知成功。
     */
    private suspend fun moveByCopy(
        source: VfsUri,
        target: VfsUri,
        sourceRoute: RouteMatch,
        targetRoute: RouteMatch,
        sourceStorage: Storage,
        targetStorage: Storage,
        sourcePrecheck: StorageAttributes,
    ): NodeInfo {
        // 目标必须全新：已有普通文件或普通目录都拒绝，不覆盖、不创建父目录、不当作「放进去」。
        // 跨 Mount 时用目标自己的后端确认——源盘上「没有同名文件」不代表目标盘上没有。
        confirmMoveTargetAbsent(target, targetStorage, targetRoute)

        // 身份：已登记源复用原 ID；未登记源按 NodeRepository 的注册语义建立一次身份（懒注册）再迁移这条记录。
        val nodeId = ensureSourceIdentity(source.path)

        // 1. 有界读取源文件：取 Core 现有读写限额里较小的那个（写侧也要过一遍内容），后端更严格的限制仍然生效。
        //    读取失败（包括超限的 LIMIT_EXCEEDED）原样往上抛：源保留、目标盘连父目录都不会被补、无事件。
        val copyLimit = minOf(limits.defaultReadMaxBytes, limits.defaultWriteMaxBytes)
        val content = sourceStorage.read(sourceRoute.relativePath, copyLimit)
        // 实际复制到多少字节——确认阶段要与所有可用长度（源预检 stat、读取回执、写入回执、目标 stat）对齐。
        val copiedBytes = content.bytes.size.toLong()

        // 1'. 第一阶段长度确认：只比较**源侧**可用长度（源预检 stat、读取回执）与实际复制字节数。
        //     读完就查，此时还没补目录、没写目标，所以已知源侧矛盾时目标一个字节都不动（effect NONE）。
        //     null 表示未知，不参与比较、也不等于 0。
        val sourceLengths =
            listOf(
                "sourceStat" to sourcePrecheck.sizeBytes,
                "readReceipt" to content.attributes.sizeBytes,
            ).filter { it.second != null }
        sourceLengths.firstOrNull { it.second != copiedBytes }?.let { mismatch ->
            throw VfsException(
                VfsErrorCode.CONFLICT,
                "Source length mismatch after reading '${source.path}': copied=$copiedBytes but " +
                    "${mismatch.first}=${mismatch.second} (known: ${sourceLengths.joinToString { "${it.first}=${it.second}" }})",
                source,
                effect = VfsEffect.NONE,
            )
        }

        // 2. 补目标父目录（读取成功并确认实际字节数后）。
        val createdDirectories = createMissingParents(target, targetStorage, targetRoute)

        // 3. 写入目标：用 CREATE_NEW 防止预检后目标出现时被覆盖。
        //    CREATE_NEW 表达「不覆盖已有目标」，**不**承诺写入原子性；后端仍可能写入一部分后报错。
        val written =
            try {
                targetStorage.write(targetRoute.relativePath, content.bytes, StorageWriteMode.CREATE_NEW)
            } catch (failure: VfsException) {
                // 写入失败：把已建父目录合并进 effect，源保留。目标可能已留下部分内容，不自动清理。
                throw failure.withKnownChanges(createdDirectories)
            }

        // 4. 确认目标：stat 目标确认为文件，核对所有可用长度。
        val targetAttributes =
            try {
                targetStorage.stat(targetRoute.relativePath)
            } catch (failure: VfsException) {
                // 确认失败：源保留、目标可能已写入（后端行为），不自动删残留目标、不继续删源。
                // 保留后端原始 code / effect（含 UNKNOWN），仅把 NONE 提到 PARTIAL（已有目标写入）。
                throw failure.withKnownTargetWrite()
            }
        if (targetAttributes.type != NodeType.FILE) {
            // 目标不是文件（极罕见：并发外部把目标改成了目录），不删源、不提交
            throw VfsException(
                VfsErrorCode.CONFLICT,
                "Target '${target.path}' is ${targetAttributes.type} after write, expected FILE",
                target,
                effect = VfsEffect.PARTIAL,
            )
        }
        // 核对长度：只比较**可用**的长度（源预检 stat、读取回执、写入回执、目标 stat）。
        // 任何一个为 null 则不参与比较；已知长度中任意一个与实际复制字节数不等 → CONFLICT + PARTIAL。
        val knownLengths =
            listOf(
                "sourceStat" to sourcePrecheck.sizeBytes,
                "readReceipt" to content.attributes.sizeBytes,
                "writtenReceipt" to written.sizeBytes,
                "targetStat" to targetAttributes.sizeBytes,
            ).filter { it.second != null }
        val mismatch = knownLengths.firstOrNull { it.second != copiedBytes }
        if (mismatch != null) {
            throw VfsException(
                VfsErrorCode.CONFLICT,
                "Length mismatch after copy for '${target.path}': copied=$copiedBytes but ${mismatch.first}=${mismatch.second} " +
                    "(known: ${knownLengths.joinToString { "${it.first}=${it.second}" }})",
                target,
                effect = VfsEffect.PARTIAL,
            )
        }

        // 5. 删除源：确认通过后才删源。确认不过的任何一条路径都在上面抛出去了，永远走不到这里。
        try {
            sourceStorage.delete(sourceRoute.relativePath, recursive = false)
        } catch (failure: VfsException) {
            // 删源失败：源与目标可能都在、逻辑路径仍指向源、不提交新映射。
            // 保留后端原始 code / effect（含 UNKNOWN），仅把 NONE 提到 PARTIAL（目标已写入）。
            throw failure.withKnownTargetWrite()
        }

        // 6. 状态 / 事件同事务提交：更新 Node 路径、追加 FILE_MOVED。
        // 物理已完成（目标有内容、源已删），提交失败报 STATE_ERROR + PARTIAL，不回移、不发成功通知。
        return try {
            pipeline.commitInsideBoundary { scope ->
                commitMove(scope, nodeId, source, target, written)
            }
        } catch (cancellation: CancellationException) {
            // 取消原样传播。物理已变更（目标有文件、源已删）这件事不会因为取消而消失。
            throw cancellation
        } catch (failure: Exception) {
            // 只收普通异常：JVM 的 Error（OOM、StackOverflow）不是状态库拒绝了一条记录，原样抛出去。
            throw VfsException(
                VfsErrorCode.STATE_ERROR,
                "The file was copied and source deleted but the node path and event could not be committed: " +
                    "${failure.message ?: failure::class.java.simpleName}",
                target,
                effect = VfsEffect.PARTIAL,
            ).apply { initCause(failure) }
        }
    }

    /**
     * 移动的结构保护：源或目标是配置推导出的目录（逻辑根、命名空间根、挂载点、挂载祖先）就不许动
     * （T02 §8.2）。排在路由之前，受保护路径一次后端都不碰。
     */
    private fun requireMovableStructure(uri: VfsUri) {
        val path = uri.path
        if (!router.isConfiguredDirectory(path)) return
        val reason =
            when {
                path.isRoot -> "it is the logical root"
                router.isMountPoint(path) -> "it is a mount root, and a mounted directory must not be moved"
                router.hasDescendantMounts(path) -> "it contains another mount, and a move must not span mounts"
                else -> "it is a configured directory (namespace root or a mount ancestor)"
            }
        throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "Cannot move '$path': $reason", uri)
    }

    /**
     * 移动目标必须是全新位置：已有文件或目录都报 `ALREADY_EXISTS`（T02 §7.1「目标为文件或目录都不覆盖，
     * 不将目标目录自动解释为放入其中」）。只在后端确认真实存在时拒绝；`NOT_FOUND`（含被父目录文件遮蔽）留给
     * 后面的补父目录 / 移动去报更贴切的错误。
     */
    private suspend fun confirmMoveTargetAbsent(
        target: VfsUri,
        storage: Storage,
        route: RouteMatch,
    ) {
        val existing =
            try {
                storage.stat(route.relativePath)
            } catch (missing: VfsException) {
                if (missing.code == VfsErrorCode.NOT_FOUND) null else throw missing
            }
        if (existing != null) {
            throw VfsException(
                VfsErrorCode.ALREADY_EXISTS,
                "Cannot move to '${target.path}': the target already exists as a ${existing.type}",
                target,
            )
        }
    }

    /**
     * 拿到要迁移的 Node ID：已登记源直接复用（不重建、不改登记时间）；未登记源直接复用 [NodeRepository] 的
     * 注册语义建立**一次**身份（不是调用 [NodeRegistry]，本方法已经在边界里），之后再迁移这条记录。
     *
     * 例：磁盘上已有、状态库里没有的 `a.txt` 移到 `b.txt`，这里先给它发一个新 ID，`getNode(新 ID)`
     * 随即指向 `b.txt`；目标位置不会另外生成第二个 ID。
     */
    private suspend fun ensureSourceIdentity(sourcePath: VfsPath): NodeId {
        // 直接读写自动提交的仓库：这里就在边界里，不再套会自取锁的 Registry 公开方法。
        nodes.findByPath(sourcePath)?.let { return it.id }
        val now = clock()
        val registered =
            nodes.register(
                NodeRecord(
                    id = NodeId.parse(newUuidV7().toString()),
                    path = sourcePath,
                    type = NodeType.FILE,
                    physical = true,
                    registeredAt = now,
                    updatedAt = now,
                ),
            )
        return registered.id
    }

    /**
     * 移动的最后一个事务：把 Node 记录迁到目标路径，追加**一条** `FILE_MOVED`（T03 §4）。
     *
     * 不新建身份、不动 Metadata：`updatePath` 只改逻辑路径与 `updatedAt`，ID、类型与 `registeredAt` 都不变。
     * 事件用 [EventFactory.newMove]，`uri` 自动是目标位置且 `sourceUri` / `targetUri` 都填。
     *
     * 源记录在这个事务视图里**必须已经存在**（真实栈里懒注册已在同一状态库自动提交）。找不到就报状态异常，
     * 绝不在这里重新登记一条记录——那会重建 `registeredAt` 并掩盖接线 / 状态不一致（复核 R1）。
     */
    private suspend fun commitMove(
        scope: TransactionScope,
        nodeId: NodeId,
        source: VfsUri,
        target: VfsUri,
        moved: StorageAttributes,
    ): NodeInfo {
        val now = clock()
        val registered =
            scope.nodes.findById(nodeId) ?: throw IllegalStateException(
                "The moved node '$nodeId' is missing from the committing transaction view; " +
                    "refusing to recreate its identity at the source path",
            )
        scope.nodes.updatePath(registered.id, target.path, now)
        scope.events.append(events.newMove(VfsEventType.FILE_MOVED, registered.id, source, target))
        return NodeInfo(
            id = registered.id,
            uri = target,
            type = NodeType.FILE,
            registeredAt = registered.registeredAt,
            updatedAt = now,
            storage = StorageStat(sizeBytes = moved.sizeBytes, modifiedAt = moved.modifiedAt),
        )
    }

    /**
     * 删掉一个文件或目录：结构保护 → 路由 → 向 Storage 确认真实类型 → Guard → 读逻辑记录 → 物理删除 → 状态与事件同事务提交。
     *
     * 例：`delete(alcyone://resources/notes/a.txt)` 会让磁盘上的 `notes/a.txt` 消失、旧 Node ID 查不到、
     * Metadata 被清掉，并追加一条 `FILE_DELETED`；目标没登记过时事件的 `nodeId` 是 null。
     *
     * 顺序契约（除了第 3 步那次 `stat`，任何可确认的拒绝都发生在**删除副作用之前**）：
     *
     * 1. 结构：逻辑根、命名空间根、挂载根、承载后代挂载的祖先 → `UNSUPPORTED_OPERATION`，`recursive = true` 也不例外；
     * 2. 路由（`MOUNT_NOT_FOUND`）与取这块盘的 Storage 实例；
     * 3. `storage.stat` 确认目标**真的存在**并拿到**实际类型**；不存在 → `NOT_FOUND`（不当作幂等成功）。
     *    只读与能力快照是在第 4 步才判的，所以它们排在这一次 `stat` 之后——这时的 `effect = NONE`
     *    指的是「没删任何东西」，不承诺「一次没问过端」；
     * 4. 按实际类型构造 [OperationIntent.delete] 再跑一遍 T10 预检：只读 → `READ_ONLY`，缺能力快照 → `INVALID_ARGUMENT`。
     *    声明类型是调用方给的，删目录时不能拿它当事实；
     * 5. 已登记 Node 的类型与实际类型冲突 → `CONFLICT`，不借删除静默把记录改对；同时把要清理的逻辑记录先读出来
     *    （文件看自身，目录看已登记子树），这些查询都在删盘之前；
     * 6. `storage.delete`（T12 负责递归、防符号链接和 `PARTIAL` / `UNKNOWN` 语义），Core 不重写物理遍历；
     * 7. 一个事务里标记这些 Node 删除、清各自 Metadata、追加**一条**目标级事件。
     *
     * 删除文件**不要求** `recursive`（给了也不影响结果）；空目录非递归可删，非空目录非递归由 Storage 报 `DIRECTORY_NOT_EMPTY`。
     * 目标之外的空父目录不顺带删除（T02 §8.1）。
     */
    override suspend fun delete(
        uri: VfsUri,
        options: DeleteOptions,
    ) {
        // 整条链只取这一次锁：结构保护、预检、Storage 删除和最后的状态提交都在里面。
        return boundary.withLock { deleteInsideBoundary(uri, options) }
    }

    /**
     * 读一个 Node 上保存的 Metadata（标签 / 说明 / 扩展字段）。
     *
     * 例：给 `a.dcm` 打上 `ct` 标签之后，`getMetadata(id)` 读回同一个对象；外部把磁盘上的 `a.dcm` 删了，
     * 这个查询照样读得到——Metadata 存在状态库里，只跟 Node ID 走，不看物理文件在不在。
     *
     * 有效 Node 但从没设置过 → 返回空 [NodeMetadata]；ID 没登记过或者已被删除 → `NOT_FOUND`。
     * **空对象和「查不到」是两回事**，无效 ID 不会被当成「没设置过」。
     *
     * 纯查询：不查挂载、不 stat 物理文件、不碰 Storage、不懒注册、不发事件。Node 校验和 Metadata 读取
     * 共用同一次 [StateBoundary]，所以不会读到别人还没提交的中间状态。
     */
    override suspend fun getMetadata(id: NodeId): NodeMetadata =
        boundary.withLock {
            // 先确认这个 ID 现在还是有效 Node：查不到（含已标记删除）就 NOT_FOUND，
            // 绝不把无效 ID 当成「没设置过 Metadata」返回空对象。
            registry.getNodeInsideBoundary(id)
            // 有效但还没设置过 → 空对象。查询不发事件，也不碰挂载和磁盘。
            metadata.get(id) ?: NodeMetadata()
        }

    /**
     * 整体替换一个 Node 的 Metadata。
     *
     * 例：原来是 `tags = {ct}`、`description = 胸部 CT`，传一个只有 `description = 报告` 的对象进去，
     * 结果是 `tags` 变空——这是替换，不是合并。传空对象就是清空；只改一个字段就自己
     * `getMetadata(id).copy(...)` 再传回来（不提供字段级 patch）。
     *
     * Node 有效性校验、Metadata 替换、Node 逻辑更新时间（`updatedAt`）和 `METADATA_UPDATED` 事件
     * 在**同一个事务**里提交，成功返回之后才通知；锁由 [EventPipeline.commit] 取，全链只取一次。
     *
     * 事件在每次有效调用后都发一条，**包括传入和当前完全相同的值、包括重复清空**——本轮不做相等比较。
     */
    override suspend fun setMetadata(
        id: NodeId,
        metadata: NodeMetadata,
    ) {
        try {
            // 整条链只取这一次锁：commit 自己拿边界，里面用事务视图读写，不再套 withLock（锁不可重入）。
            pipeline.commit { scope -> commitMetadata(scope, id, metadata) }
        } catch (cancellation: CancellationException) {
            // 取消原样传播。提交前的取消已经整体回滚；提交后的取消不证明提交被撤销，也不宣称「取消 = 没写入」。
            throw cancellation
        } catch (failure: VfsException) {
            // 领域错误（NOT_FOUND）和状态库自己报出的 STATE_ERROR 原样保留，带它自己的 code 与 effect。
            throw failure
        } catch (failure: Exception) {
            // 只收普通异常：JVM 的 Error（OOM、StackOverflow）原样抛出去。
            throw VfsException(
                VfsErrorCode.STATE_ERROR,
                "The metadata could not be committed: ${failure.message ?: failure::class.java.simpleName}",
                effect = VfsEffect.NONE,
            ).apply { initCause(failure) }
        }
    }

    /** 删链的锁内部分：先拒绝结构冲突，再按真实类型跑预检，最后物理删除 + 逻辑提交。 */
    private suspend fun deleteInsideBoundary(
        uri: VfsUri,
        options: DeleteOptions,
    ) {
        val path = uri.path
        requireDeletableStructure(uri)

        val route = router.route(path) ?: throw mountNotFound(path)
        val storage = storageFor(route)
        // 确认物理存在与**实际类型**：不存在时 Storage 直接报 NOT_FOUND，不当作幂等成功；
        // 其他错误（后端已关、权限不足）照抛。
        val attributes = storage.stat(route.relativePath)

        // 按真实类型构造意图再走一遍 T10 预检：声明类型是调用方给的，删目录时更不能拿声明当事实。
        val target =
            OperationGuard
                .check(
                    OperationIntent.delete(path, attributes.type, options.recursive),
                    router,
                    capabilities,
                ).source!!

        val cleanup = planLogicalCleanup(uri, attributes.type)
        // 物理删除交给 Storage：递归遍历、防符号链接和部分删除的 effect 都由 T12 负责。
        storage.delete(target.relativePath, options.recursive)

        try {
            pipeline.commitInsideBoundary { scope -> commitDelete(scope, uri, cleanup) }
        } catch (cancellation: CancellationException) {
            // 取消原样传播。盘上的东西已经删掉这件事不会因为取消而消失，也不声称逻辑状态已回滚。
            throw cancellation
        } catch (failure: Exception) {
            // 只收普通异常：JVM 的 Error（OOM、StackOverflow）不是状态库拒绝了一条记录，原样抛出去。
            throw VfsException(
                VfsErrorCode.STATE_ERROR,
                "The delete reached the storage but the node state, metadata and event could not be committed: " +
                    "${failure.message ?: failure::class.java.simpleName}",
                uri,
                effect = VfsEffect.PARTIAL,
            ).apply { initCause(failure) }
        }
    }

    /**
     * 配置结构冲突先于一切 I/O：逻辑根、命名空间根、挂载根、缺层祖先以及承载后代挂载的祖先都不许删，
     * `recursive = true` 也不例外（T02 §8.2）。
     *
     * 例：`/resources/work` 下面挂了 `medical`，`delete("/resources/work", recursive = true)` 当场拒；
     * 但 `/resources/work/note.md` 是普通文件，照常能删。
     *
     * 消息按具体原因写一句，方便调用方区分是哪条结构规则挡住了。
     */
    private fun requireDeletableStructure(uri: VfsUri) {
        val path = uri.path
        if (!router.isConfiguredDirectory(path)) return
        val reason =
            when {
                path.isRoot -> "it is the logical root"
                router.isMountPoint(path) -> "it is a mount root, and a mounted directory must not be deleted"
                router.hasDescendantMounts(path) ->
                    "it contains another mount, and recursive = true cannot delete across mounts"
                else -> "it is a configured directory (namespace root or a mount ancestor)"
            }
        throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "Cannot delete '$path': $reason", uri)
    }

    /**
     * 物理删除**之前**把需要清理的逻辑记录读出来：目标自己的记录，目录则加上完整段边界内的已登记子树。
     *
     * 例：`a` 从没登记过，但 `a/b.txt` 登记过——删 `a` 仍然会把 `b.txt` 的记录和 Metadata 一起清掉。
     * `findSubtree` 按完整段匹配，`a-old`、`A` 都不会被算进 `a` 的子树，这里不再自己写前缀比较。
     */
    private suspend fun planLogicalCleanup(
        uri: VfsUri,
        actualType: NodeType,
    ): DeleteCleanup {
        val recorded = nodes.findByPath(uri.path)
        // 逻辑记录说它是目录、盘上却是文件（或反过来）：状态和现实对不上，绝不靠删除把类型改过来。
        if (recorded != null && recorded.type != actualType) {
            throw VfsException(
                VfsErrorCode.CONFLICT,
                "Registered node at '${uri.path}' is ${recorded.type} but the backing storage reports $actualType; " +
                    "deleting does not change stored node records",
                uri,
            )
        }
        val victims =
            if (actualType == NodeType.DIRECTORY) {
                nodes.findSubtree(uri.path).map { it.id }
            } else {
                listOfNotNull(recorded?.id)
            }
        return DeleteCleanup(recorded?.id, victims.distinct(), actualType)
    }

    /**
     * 最后一个事务：标记这些 Node 删除、清掉各自 Metadata、追加**一条**目标级事件（T03 §2、§4）。
     *
     * 目录删除事件代表整棵逻辑子树失效，不为每个后代另造事件或身份（T16 §2.4）。
     * 历史事件不删：里面留着的旧 Node ID 仍然是那次写入的事实。
     */
    private suspend fun commitDelete(
        scope: TransactionScope,
        uri: VfsUri,
        cleanup: DeleteCleanup,
    ) {
        scope.nodes.markDeleted(cleanup.ids, clock())
        cleanup.ids.forEach { scope.metadata.delete(it) }
        val type = if (cleanup.type == NodeType.DIRECTORY) VfsEventType.DIRECTORY_DELETED else VfsEventType.FILE_DELETED
        scope.events.append(events.newRecord(type, cleanup.targetNodeId, uri))
    }

    /**
     * 最后一个事务：整体替换 Metadata、推进 Node 逻辑时间、追加**一条**事件（T03 §4）。
     *
     * 例：原来存着 `tags = {ct}`、`description = 胸部 CT`，传一个只有 `description = 报告` 的对象进去，
     * 结果就是 `tags` 变空——这是替换，不是合并。传空对象等于清空。
     *
     * **每次有效 set 都产生一条事件，包括传完全相同的值、包括重复清空**：本轮不做相等比较也不去重。
     * 想判断「变没变」，调用方自己比 `getMetadata` 的前后两次结果。
     *
     * 顺带 [com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository.touch] 一下：
     * `updatedAt` 是「逻辑记录最后被改动的时间」，不是磁盘文件的修改时间；ID、路径、类型和 `registeredAt` 都不变。
     * 时钟精度内连续设置可能拿到相同时间，不要求严格递增。
     *
     * 事件里的路径取的是**这一次受保护读取**看到的 Node 路径：Node 已被删除的话，第一步的
     * `findById` 就返回空，所以不会给已删除的 Node 写回 Metadata。
     */
    private suspend fun commitMetadata(
        scope: TransactionScope,
        id: NodeId,
        metadata: NodeMetadata,
    ) {
        val record =
            scope.nodes.findById(id) ?: throw VfsException(
                VfsErrorCode.NOT_FOUND,
                "No active node with the given id",
            )
        scope.metadata.put(id, metadata)
        scope.nodes.touch(record.id, clock())
        scope.events.append(events.newRecord(VfsEventType.METADATA_UPDATED, record.id, VfsUri.of(record.path)))
    }

    /** 读取实际上限：配置上限与单次上限里较小的那个。`null` 用配置，`0` 就是只接受空文件。 */
    private fun effectiveReadLimit(options: ReadOptions): Long =
        minOf(limits.defaultReadMaxBytes, options.maxBytes ?: limits.defaultReadMaxBytes)

    /**
     * 流式读取的实际上限：配置总量上限与单次上限里较小的那个；两个都没给就是不限量。
     *
     * 和 [effectiveReadLimit] 分开是因为默认不同：`read` 默认 16 MiB，流式默认不设总量上限。
     */
    private fun effectiveStreamLimit(options: VfsStreamOptions): Long? = listOfNotNull(streamTotalLimit, options.maxTotalBytes).minOrNull()

    /** 取这块盘的实例。拼装漏了就报 `STATE_ERROR` 并点名 key，绝不装作「文件不存在」。 */
    private fun storageFor(route: RouteMatch): Storage =
        storages(route.mount.storageKey) ?: throw VfsException(
            VfsErrorCode.STATE_ERROR,
            "Storage instance is missing for the mounted storage key '${route.mount.storageKey}'; " +
                "the mount at '${route.relativePath}' cannot be served",
        )

    /** 预检已经保证能力快照里有这块盘；真的没有就是拼装错误，报出来而不是让它变成 NPE。 */
    private fun capabilitiesOf(route: RouteMatch): StorageCapabilities =
        capabilities.capabilitiesOf(route.mount.storageKey) ?: throw VfsException(
            VfsErrorCode.INVALID_ARGUMENT,
            "Capability snapshot is incomplete: no entry for the storage of the write target '${route.relativePath}'",
        )

    /** 「必要祖先被物理文件遮蔽」时还能不能按配置目录继续：能，但挂载点自己不行（T02 §3.2，与 T13 同一判断）。 */
    private fun virtualFallbackAllowed(path: VfsPath): Boolean = router.isConfiguredDirectory(path) && !router.isMountPoint(path)

    private fun mountNotFound(path: VfsPath): VfsException =
        VfsException(
            VfsErrorCode.MOUNT_NOT_FOUND,
            "No mount covers '$path', and it is not a configured directory",
            VfsUri.of(path),
        )

    private fun configuredDirectory(
        path: VfsPath,
        operation: String,
    ): VfsException =
        VfsException(
            VfsErrorCode.TYPE_MISMATCH,
            "Cannot $operation '$path': it is a configured directory (logical root, namespace root, mount root or mount ancestor)",
            VfsUri.of(path),
        )
}

/** 后端说「没有」和「必要祖先是个文件」这两种可识别的遮蔽；其余错误一律照抛。 */
private val SHADOWED_CODES = setOf(VfsErrorCode.NOT_FOUND, VfsErrorCode.TYPE_MISMATCH)

/**
 * 一次删除在物理操作**之前**读好的逻辑清理清单。
 *
 * 例：删目录 `/resources/a`，`a` 自己没登记、但 `a/b.txt` 登记了，
 * 这时 `ids` 是 `[b 的 Node ID]`、`targetNodeId` 是 null，事件里也就没有身份。
 */
private data class DeleteCleanup(
    /** 目标自己的 Node ID；目标没登记过时为 null，也就是事件里没有身份。 */
    val targetNodeId: NodeId?,
    /** 这次要标记删除的 Node ID（目标 + 目录子树）；一个都没有时是空列表。 */
    val ids: List<NodeId>,
    /** 确认过的实际类型，决定 `FILE_DELETED` 还是 `DIRECTORY_DELETED`。 */
    val type: NodeType,
)

/** 公共模式直接对应后端模式：三个名字一一对应，不用再做翻译表。 */
private fun WriteMode.toStorageMode(): StorageWriteMode =
    when (this) {
        WriteMode.CREATE_NEW -> StorageWriteMode.CREATE_NEW
        WriteMode.REPLACE_EXISTING -> StorageWriteMode.REPLACE_EXISTING
        WriteMode.UPSERT -> StorageWriteMode.UPSERT
    }

/**
 * 把「已经建出一部分父目录」这个已知事实合并进 effect：写文件失败和建目录失败共用这一条规则，
 * 只把还写着 `NONE` 的提到 `PARTIAL`；`UNKNOWN` 保持 `UNKNOWN`（后端说不清就是说不清），已经是 `PARTIAL` 的不动。
 *
 * 一个目录都没建成时原样抛出，保留后端自己的 effect（通常是 `NONE`，但后端说了别的就照它说的）。
 * message 末尾**追加**一句诊断说明，不替换原消息，也不带后端相对路径。
 */
private fun VfsException.withKnownChanges(createdDirectories: Int): VfsException {
    if (createdDirectories == 0 || effect != VfsEffect.NONE) return this
    return VfsException(
        code,
        "$message (the parent directories created for this call are still there)",
        uri,
        operationId,
        VfsEffect.PARTIAL,
    ).apply { initCause(this@withKnownChanges) }
}

/**
 * 把「目标已经写入」这个已知事实合并进 effect：复制路径的确认与删源失败共用这一条规则。
 *
 * 与 [withKnownChanges] 的区别：
 * - **不新增诊断句**。调用方拿到的 message / code / uri 与后端说的完全一致，cause 上挂原始异常；
 * - **不改写 code**。只有已观测到的类型 / 长度矛盾才用 `CONFLICT`（见 [moveByCopy]），
 *   后端自己抛的 `NOT_FOUND` / `STORAGE_ACCESS_DENIED` / `STORAGE_ERROR` / `STATE_ERROR` 等原样保留，
 *   调用方可以继续按 code 分支；
 * - **`UNKNOWN` 不降级**。后端说不清就是说不清，已经是 `PARTIAL` / `UNKNOWN` 的都不动，
 *   只把仍然写着 `NONE` 的提到 `PARTIAL`（因为目标确实已经写出来了）。
 */
private fun VfsException.withKnownTargetWrite(): VfsException {
    if (effect != VfsEffect.NONE) return this
    return VfsException(
        code,
        message ?: code.name,
        uri,
        operationId,
        VfsEffect.PARTIAL,
    ).apply { initCause(this@withKnownTargetWrite) }
}
