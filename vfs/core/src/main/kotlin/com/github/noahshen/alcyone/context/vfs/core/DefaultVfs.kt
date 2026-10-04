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
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.operation.OperationGuard
import com.github.noahshen.alcyone.context.vfs.core.operation.OperationIntent
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
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
 * `list` 和 `getNode`；T16 加上 `delete`。Core 只依赖 Port，具体 SQLite 与本地磁盘由调用方注入。
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
 * | 通知队列满、Consumer 抛错 | 不影响已提交的结果（复用 T14 的 EventPipeline） |
 * | 协程被取消 | 取消原样传播；已提交的事实不撤回，不声称「取消 = 全部回滚」 |
 *
 * **删除（T16）**：物理删除走 Storage 自己的递归实现（Core 不重写遍历），逻辑侧把目标与「完整段边界内已登记的子树」
 * 标记删除、清掉各自 Metadata，追加**一条**目标级事件（目录删除表示整棵子树失效，不为后代逐个造事件）。
 *
 * **本轮没交付的方法**：[move] / [getMetadata] / [setMetadata] 明确抛
 * [VfsErrorCode.UNSUPPORTED_OPERATION] 且零副作用，分别由 T17、T20～T22 接续。
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

    /** 移动：T20～T22 接续。现在明确拒绝，不静默成功也不留副作用。 */
    override suspend fun move(
        source: VfsUri,
        target: VfsUri,
    ): NodeInfo = throw notDeliveredYet("move", "planned for T20~T22")

    /**
     * 删掉一个文件或目录：结构保护 → 路由 → 向 Storage 确认真实类型 → Guard → 读逻辑记录 → 物理删除 → 状态与事件同事务提交。
     *
     * 例：`delete(alcyone://resources/notes/a.txt)` 会让磁盘上的 `notes/a.txt` 消失、旧 Node ID 查不到、
     * Metadata 被清掉，并追加一条 `FILE_DELETED`；目标没登记过时事件的 `nodeId` 是 null。
     *
     * 顺序契约（任何可确认的拒绝都发生在碰盘之前）：
     *
     * 1. 逻辑根、命名空间根、挂载根、承载后代挂载的祖先 → `UNSUPPORTED_OPERATION`，`recursive = true` 也不例外；
     * 2. 路由（`MOUNT_NOT_FOUND`）、只读、能力快照缺失 → 走 T10 预检，`effect = NONE`；
     * 3. `storage.stat` 确认目标**真的存在**且确认实际类型；不存在 → `NOT_FOUND`（不当作幂等成功）；
     *    已登记 Node 的类型与实际类型冲突 → `CONFLICT`，不借删除静默把记录改对；
     * 4. 需要的逻辑记录先读出来（目标是文件看自身，是目录看已登记子树），查询失败发生在副作用之前；
     * 5. `storage.delete`（T12 负责递归、防符号链接和 `PARTIAL` / `UNKNOWN` 语义），Core 不重写物理遍历；
     * 6. 一个事务里标记这些 Node 删除、清各自 Metadata、追加**一条**目标级事件。
     *
     * 文件删不删得看 `recursive`；空目录非递归可删，非空目录非递归由 Storage 报 `DIRECTORY_NOT_EMPTY`。
     * 目标之外的空父目录不顺带删除（T02 §8.1）。
     */
    override suspend fun delete(
        uri: VfsUri,
        options: DeleteOptions,
    ) {
        // 整条链只取这一次锁：结构保护、预检、Storage 删除和最后的状态提交都在里面。
        return boundary.withLock { deleteInsideBoundary(uri, options) }
    }

    /** 读 Metadata：T17 接续。 */
    override suspend fun getMetadata(id: NodeId): NodeMetadata = throw notDeliveredYet("getMetadata", "planned for T17")

    /** 写 Metadata：T17 接续。 */
    override suspend fun setMetadata(
        id: NodeId,
        metadata: NodeMetadata,
    ): Unit = throw notDeliveredYet("setMetadata", "planned for T17")

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

        val cleanup = planLogicalCleanup(uri, target, attributes.type)
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
        target: RouteMatch,
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

    /** 读取实际上限：配置上限与单次上限里较小的那个。`null` 用配置，`0` 就是只接受空文件。 */
    private fun effectiveReadLimit(options: ReadOptions): Long =
        minOf(limits.defaultReadMaxBytes, options.maxBytes ?: limits.defaultReadMaxBytes)

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

    private fun notDeliveredYet(
        operation: String,
        plan: String,
    ): VfsException =
        VfsException(
            VfsErrorCode.UNSUPPORTED_OPERATION,
            "'$operation' is not implemented yet; $plan. The call was rejected before any state or storage change.",
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
