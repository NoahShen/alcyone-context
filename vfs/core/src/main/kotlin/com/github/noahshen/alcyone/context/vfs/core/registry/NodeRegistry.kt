package com.github.noahshen.alcyone.context.vfs.core.registry

import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeInfo
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.StorageStat
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import java.time.Instant

/**
 * Node Registry：把「逻辑位置」和「稳定 Node ID」对上号，需要时顺手把 Node 存进状态库（T13）。
 *
 * 例：磁盘上已经有 `a.txt`，第一次 [resolveOrRegister] 确认它真的在，返回并记下一个 ID；
 * 之后再问同一个路径，拿到的还是这个 ID，写文件、改名都带着它。
 *
 * 它只管三件事：**查逻辑记录**、**到后端确认资源真的在**、**给还没有 ID 的资源发一个 ID**。
 * 不读文件内容、不列举目录、不写文件、不发事件、不改 Metadata——那些是 T15 / T14 / T17 的事。
 *
 * 错误语义：
 *
 * - 路径没有挂载覆盖、也不是配置目录 → `MOUNT_NOT_FOUND`；
 * - 命中挂载但没有对应的 Storage 实例 → `STATE_ERROR`，这是装配写错了，必须说清楚缺哪个 key，
 *   绝不能装作「文件不存在」把问题藏起来；
 * - 后端确认失败（不存在 / 拒绝 / 一般 I/O 错误）→ 原样抛，不吞；只有**可识别的两种情况**会转成虚拟目录，见 [virtualFallbackAllowed]；
 * - 磁盘上的实际类型和已登记的类型不一致 → `CONFLICT`，绝不偷偷换 ID、删 Metadata 或就地改记录。
 *
 * 状态读写一律经过 [StateBoundary]；同一个边界实例由 Runtime 共享给 T15 之后的编排代码。
 * 已经拿着边界的调用方用 [resolveInsideBoundary] / [getNodeInsideBoundary]，**不要再套 [StateBoundary.withLock]**（锁不可重入）。
 */
class NodeRegistry(
    /** 挂载与配置目录（T09）。决定这个路径归哪块盘管，以及它是不是「配置里就有的目录」。 */
    private val router: MountRouter,
    /** 状态库里的节点记录。必须是**自动提交**的那份（不是事务作用域里的视图），本类自己不开事务。 */
    private val nodes: NodeRepository,
    /**
     * 按 [com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord.storageKey] 找 Storage 实例。
     * 返回 `null` 表示装配漏了这块盘，[resolveInsideBoundary] 会报 `STATE_ERROR` 并在消息里点名这个 key。
     */
    private val storages: (String) -> Storage?,
    /** 状态库串行边界，整个 Runtime 共用一个实例（T11 单连接不支持并发事务）。 */
    private val boundary: StateBoundary,
    /** 取当前时间；测试可以换成固定值，好断言「逻辑时间没被物理时间覆盖」。 */
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * 查一个**已经登记过**的 Node 的逻辑信息；纯查库，不碰后端。
     *
     * 例：拿着移动后拿到的 ID 查它现在在哪，返回的 `storage` 是 `null`（这次没问磁盘）。
     *
     * @throws VfsException `NOT_FOUND`：ID 没登记过，或者登记过但已经删除（删除的记录不再算有效节点）。
     */
    suspend fun getNode(id: NodeId): NodeInfo = boundary.withLock { getNodeInsideBoundary(id) }

    /**
     * 按路径确认资源并拿到 Node：这是 `stat` 的实现方（T15 接线），没有 Node 就当场登记一个。
     *
     * 例：磁盘上有 `a.txt`、数据库里还没有它，`resolveOrRegister("/resources/a.txt")` 会
     * 先问那块盘「`a.txt` 在不在」，在的话发一个新 ID 存进状态库，再把 ID 和属性一起返回。
     *
     * @param includeStorage 是否返回磁盘属性，默认 `true`：
     *  - `true`：**连已登记的 Node 也要再去问一次后端**。文件被人从外面删了，这里就报 `NOT_FOUND`。
     *  - `false`：已登记的 Node 完全走查库，一次后端都不碰；没登记过的仍然要先问后端「资源在不在」，
     *    只是返回的 `storage` 是 `null`。这跟 T01 §5.1 一致：不让调用方靠 `includeStorage=false` 伪造一个不存在的文件。
     *
     * 磁盘属性只填 [NodeInfo.storage]，**不覆盖** [NodeInfo.registeredAt] / [NodeInfo.updatedAt]——那两个是 VFS 自己的登记时间。
     *
     * 取消：如果取消发生在注册提交**之后**、返回之前，Node 可能已经落库。Registry 不做补偿删除，
     * 也不假装「收到取消就等于没提交」；下一次查同一个路径会复用那条记录。
     */
    suspend fun resolveOrRegister(
        path: VfsPath,
        includeStorage: Boolean = true,
    ): NodeInfo = boundary.withLock { resolveInsideBoundary(path, includeStorage) }

    /**
     * [getNode] 的不加锁版本：**调用方已经拿着 [StateBoundary] 时用这个**，别再套一次 `withLock`（会自己等自己）。
     */
    internal suspend fun getNodeInsideBoundary(id: NodeId): NodeInfo =
        nodes.findById(id)?.toNodeInfo(storage = null) ?: throw VfsException(
            VfsErrorCode.NOT_FOUND,
            "No active node with the given id",
        )

    /** [resolveOrRegister] 的不加锁版本：[StateBoundary] 由调用方持有时用这个（[StateBoundary] 的 Kdoc 有完整调用示例）。 */
    internal suspend fun resolveInsideBoundary(
        path: VfsPath,
        includeStorage: Boolean = true,
    ): NodeInfo {
        // 先查逻辑记录。includeStorage=false 且已登记时，到这里就结束：一次后端都不碰。
        val existing = nodes.findByPath(path)
        if (existing != null && !includeStorage) return existing.toNodeInfo(storage = null)

        val route =
            router.route(path) ?: run {
                // 没有挂载覆盖。只有「配置里就有的目录」（逻辑根、命名空间根、挂载点或挂载的祖先）才当虚拟目录，
                // 其他路径是真的没地方可查。绝不把任意路径当虚拟目录凭空造一个。
                if (!router.isConfiguredDirectory(path)) throw mountNotFound(path)
                return register(path, existing, NodeType.DIRECTORY, physical = false, storage = null)
            }
        val storage =
            storages(route.mount.storageKey) ?: throw VfsException(
                VfsErrorCode.STATE_ERROR,
                "Storage instance is missing for the mounted storage key '${route.mount.storageKey}'; " +
                    "the mount at '$path' cannot be served",
            )
        val attributes =
            try {
                storage.stat(route.relativePath)
            } catch (failure: VfsException) {
                // 只处理「后端明确说没有」这一种情况。权限不足、后端已关闭、一般 I/O 错误都是真实故障，原样抛。
                if (failure.code == VfsErrorCode.NOT_FOUND && virtualFallbackAllowed(path)) null else throw failure
            }
        // 配置目录在磁盘上还是个文件时，按逻辑目录处理：这个文件被遮蔽了（下面 register 记的是虚拟目录）。
        val effective = if (attributes != null && virtualFallbackAllowed(path) && attributes.type == NodeType.FILE) null else attributes
        return register(
            path = path,
            existing = existing,
            type = effective?.type ?: NodeType.DIRECTORY,
            physical = effective != null,
            storage = if (includeStorage) effective?.toStorageStat() else null,
        )
    }

    /**
     * 这条路径「后端说没有」时，能不能按虚拟目录继续。
     *
     * 能的只有一种：**配置里推导出的目录，且不是挂载点自己**（T02 §3.2 的缺层祖先）。
     * 例：只挂了 `/resources/medical/ct`，那么 `/resources/medical` 磁盘上本来就不该有目录。
     *
     * 不能的两种：挂载点自己必须真的能访问到后端根（`/resources/medical/ct` 挂不上就是挂了，绝不降级成「虚拟目录，正常」）；
     * 普通路径同理，后端说没有就是 `NOT_FOUND`。
     */
    private fun virtualFallbackAllowed(path: VfsPath): Boolean = router.isConfiguredDirectory(path) && !router.isMountPoint(path)

    /**
     * 登记（或复用）这个路径的 Node。
     *
     * 已有记录：直接用它，不重建 ID、不改登记时间。类型对不上就报 `CONFLICT`——
     * 本类**不会**顺手把记录改对，换 ID、删 Metadata、修数据都不是查询该干的事。
     *
     * 没有记录：先在串行范围内复查一次（同一把边界里排队进来的调用不会重复登记），
     * 再生成 UUIDv7 交给 [NodeRepository.register]。**真正生效的是 register 返回的那条**：
     * 别的协程先登记成功时，本次 INSERT 会被数据库的唯一索引忽略，返回的是先到者的记录，
     * 于是两个调用方拿到的是同一个 ID，而不是各自手里的候选 ID。
     */
    private suspend fun register(
        path: VfsPath,
        existing: NodeRecord?,
        type: NodeType,
        physical: Boolean,
        storage: StorageStat?,
    ): NodeInfo {
        if (existing != null && existing.type != type) throw typeConflict(path, existing.type, type)
        if (existing != null) return existing.toNodeInfo(storage = storage)

        val now = clock()
        val registered =
            nodes.register(
                NodeRecord(
                    id = NodeId.parse(newUuidV7().toString()),
                    path = path,
                    type = type,
                    physical = physical,
                    registeredAt = now,
                    updatedAt = now,
                ),
            )
        if (registered.type != type) throw typeConflict(path, registered.type, type)
        return registered.toNodeInfo(storage = storage)
    }

    private fun mountNotFound(path: VfsPath): VfsException =
        VfsException(
            VfsErrorCode.MOUNT_NOT_FOUND,
            "No mount covers '$path', and it is not a configured directory",
            VfsUri.of(path),
        )

    /** 磁盘上的实际类型和记录里的类型对不上。消息只说逻辑路径，不带后端细节。 */
    private fun typeConflict(
        path: VfsPath,
        recorded: NodeType,
        actual: NodeType,
    ): VfsException =
        VfsException(
            VfsErrorCode.CONFLICT,
            "Registered node at '$path' is $recorded but the resource is now $actual; " +
                "resolving a path does not change stored node records",
            VfsUri.of(path),
        )
}

/** 记录 → 返回值。物理属性放 [NodeInfo.storage]，逻辑时间原样保留。 */
private fun NodeRecord.toNodeInfo(storage: StorageStat?): NodeInfo =
    NodeInfo(
        id = id,
        uri = VfsUri.of(path),
        type = type,
        registeredAt = registeredAt,
        updatedAt = updatedAt,
        storage = storage,
    )

private fun StorageAttributes.toStorageStat(): StorageStat = StorageStat(sizeBytes = sizeBytes, modifiedAt = modifiedAt)
