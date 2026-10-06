package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.router.RouteMatch
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMountRepository

/**
 * 重开时核对旧挂载（T18 §2.2）。对不上的地方一律 `CONFLICT`，不替换原映射、不重新注册旧 Node。
 *
 * 例：首次启动保存 `/resources → 本机目录 A`；下次启动把它改成目录 B（key 不变）会被拒，
 * 否则旧文件的 Node ID 会指向另一份文件。
 */
internal suspend fun reconcileMounts(
    config: ResolvedConfig,
    boundary: StateBoundary,
    nodes: NodeRepository,
    mounts: SqliteMountRepository,
) = boundary.withLock {
    val persisted = mounts.list()

    // 旧库迁移上来的行拿不到物理身份：不能拿当前配置替它认证，要说清怎么办。
    persisted.firstOrNull { !it.hasPhysicalIdentity }?.let { throw missingIdentity(it) }

    // 每一条旧映射都必须还在，而且身份一个字段都不许变。
    for (record in persisted) {
        if (record.path.segments.first() !in config.namespaces) throw namespaceGone(record.path, config.namespaces)
        val current = config.mounts.firstOrNull { it.path == record.path } ?: throw mountGone(record)
        if (current.storageKey != record.storageKey) throw storageChanged(record, current)
        if (current.backendType != record.backendType || current.physicalRoot != record.physicalRoot) {
            throw identityChanged(record, current)
        }
    }

    // 库里每个还活着的 Node 都得留在已配置的命名空间下，包括没有挂物理盘的虚拟目录。
    // 例：只挂了 resources，`/memory` 是 stat 出来的虚拟目录；下次启动把 memory 从配置里删掉，
    // 那个已经登记的 Node 就再也找不到了，所以这里要拒。
    // 这一段必须在下面 added 为空的提前返回之前，否则没新增挂载时一次都跑不到。
    for (node in nodes.findSubtree(VfsPath.root)) {
        val namespace = node.path.segments.firstOrNull() ?: continue
        if (namespace !in config.namespaces) throw namespaceGone(node.path, config.namespaces)
    }

    val added = config.mounts.filter { candidate -> persisted.none { it.path == candidate.path } }
    if (added.isEmpty()) return@withLock

    // 新增挂载不许遮蔽旧挂载：路径上互相包含（前缀 / 后缀）都会让一批路径换路由结果。
    val oldRouter = MountRouter.of(config.namespaces, persisted)
    for (new in added) {
        persisted
            .firstOrNull { old -> new.path.isStrictlyUnder(old.path) || old.path.isStrictlyUnder(new.path) }
            ?.let { old -> throw shadowing(old, new) }
    }
    // 再逐个看已登记的 Node：换挂载之后它会不会指向别的内容。节点认的是逻辑路径 + 哪块盘，不认配置。
    for (old in persisted) {
        for (node in nodes.findSubtree(old.path)) {
            val before = oldRouter.route(node.path)
            val after = config.router.route(node.path)
            if (before?.mount?.storageKey != after?.mount?.storageKey || before?.relativePath != after?.relativePath) {
                throw rerouted(node.path, before, after)
            }
        }
    }

    // 到这一步这一批才被接受：整批在一个事务里写进去（首启就是全部，之后只增不改）。
    mounts.replaceAll(config.mounts)
}

/** `a/b` 严格在 `a` 之下：按完整段比，`/a-b` 不算在 `/a` 下面。 */
private fun VfsPath.isStrictlyUnder(other: VfsPath): Boolean =
    segments.size > other.segments.size && segments.subList(0, other.segments.size) == other.segments

/**
 * 旧库里的挂载行是迁移上来的，没有物理身份。
 *
 * 处理办法写进消息里：`mount` 表清空（或删掉这几行）后重新启动就会按新配置存一遍，
 * 前提是调用方确认这些旧 Node 指向的还是同一批目录。**代码不替调用方做这个判断。**
 */
private fun missingIdentity(record: MountRecord): VfsException =
    conflict(
        "the mount at '${record.path}' was stored by an older version that did not record the physical " +
            "identity of the storage; this build cannot verify that the configured storage is the same one. " +
            "Check the configuration, then clear the 'mount' table (or those rows) and start again to store " +
            "the verified mapping",
    )

/** 删掉一个还挂着 Node 的命名空间，这些 Node 就再也寻址不到了，所以拒绝这次启动。 */
private fun namespaceGone(
    path: VfsPath,
    namespaces: Set<String>,
): VfsException =
    conflict(
        "something already registered at '$path' belongs to namespace '${path.segments.first()}', which is no longer " +
            "configured (namespaces now: ${namespaces.sorted().joinToString().ifEmpty { "none" }}); " +
            "dropping it would leave that node unaddressable, so the old mapping is kept as is",
    )

private fun mountGone(record: MountRecord): VfsException =
    conflict(
        "the mount at '${record.path}' (storage key '${record.storageKey}') was stored before and is no longer " +
            "configured; mounts are never removed automatically because nodes registered under it would " +
            "become unaddressable",
    )

private fun storageChanged(
    record: MountRecord,
    current: MountRecord,
): VfsException =
    conflict(
        "the mount at '${record.path}' now points at storage key '${current.storageKey}' but was stored with " +
            "'${record.storageKey}'",
    )

private fun identityChanged(
    record: MountRecord,
    current: MountRecord,
): VfsException =
    conflict(
        "the storage behind the mount at '${record.path}' changed identity: it was stored as " +
            "${describe(record)} and is now configured as ${describe(current)}",
    )

private fun shadowing(
    old: MountRecord,
    new: MountRecord,
): VfsException =
    conflict(
        "the new mount at '${new.path}' (storage key '${new.storageKey}') would take over part of the " +
            "already mounted subtree at '${old.path}' (storage key '${old.storageKey}'), so existing nodes " +
            "there would resolve to different content",
    )

private fun rerouted(
    path: VfsPath,
    before: RouteMatch?,
    after: RouteMatch?,
): VfsException =
    conflict(
        "the registered node at '$path' would resolve to different content after this change " +
            "(from ${describe(before)} to ${describe(after)})",
    )

/** 诊断文本用：路由结果说清「哪块盘上的哪个相对路径」，都不说也不猜。 */
private fun describe(route: RouteMatch?): String =
    route?.let { "storage key '${it.mount.storageKey}' at '${it.relativePath.toRelativeString()}'" } ?: "no mount"

private fun describe(record: MountRecord): String =
    "storage key '${record.storageKey}', backend type '${record.backendType}', physical root '${record.physicalRoot}'"

/** 全部冲突都用 `CONFLICT`：是配置与已存状态对不上，不是调用参数写错，也不是存储故障。 */
private fun conflict(reason: String): VfsException = VfsException(VfsErrorCode.CONFLICT, "VFS configuration conflict: $reason")
