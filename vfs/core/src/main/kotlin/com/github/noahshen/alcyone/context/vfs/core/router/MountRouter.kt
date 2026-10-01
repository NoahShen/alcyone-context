package com.github.noahshen.alcyone.context.vfs.core.router

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath

/**
 * 一次路由结果：选中的挂载与该挂载内的相对位置。
 *
 * @property mount 命中的挂载记录；[MountRecord.storageKey] 是不透明标识，调用方据此取 Storage 实例。
 * @property relativePath 挂载内的相对路径；恰好访问挂载根时是 [StoragePath.root]。
 */
data class RouteMatch(
    val mount: MountRecord,
    val relativePath: StoragePath,
)

/**
 * 逻辑挂载路由与配置目录结构（T09）。纯逻辑、构建后不可变，不访问 Storage / Repository，也不查询后端存在性。
 *
 * 例：挂载 `/notes` 与 `/notes/archive` 时，`/notes/archive/a.txt` 选择后者，相对路径是 `a.txt`。
 *
 * 规则来自 T02 §3、T09 §2：
 *
 * - 匹配按**已解码的完整路径段**逐段前缀比较，取最长命中，因此 `/notes/a` 匹配 `/notes/a/x`、不匹配 `/notes/abc`；
 * - 无命中的普通路径由调用方按 T02 §3.2 映射为 `MOUNT_NOT_FOUND`，本对象不抛该异常；
 * - 配置目录 = 逻辑根 + 已配置命名空间根 + 挂载点及其祖先；它与路由映射互不覆盖：
 *   同时挂载 `/notes` 与 `/notes/team/docs` 时，`/notes/team` 既能路由到 `/notes`（相对路径 `team`），也是含子目录 `docs` 的配置目录。
 *
 * 物理根边界、符号链接与后端能力不在这里校验（T12）；`storageKey` 不透明，不同 key 不代表物理根一定不重叠。
 */
class MountRouter private constructor(
    /** 快照化后的挂载，顺序不影响匹配结果。 */
    private val mounts: List<MountRecord>,
    /** 由命名空间与挂载推导的全部配置目录，含逻辑根。 */
    private val configuredDirectories: Set<VfsPath>,
    /** 配置目录的直接子目录名，同名子目录去重。 */
    private val childrenByDirectory: Map<VfsPath, Set<String>>,
) {
    /** 挂载点的逻辑路径集合，供移动 / 删除检查精确匹配使用。 */
    private val mountPoints: Set<VfsPath> = mounts.mapTo(HashSet()) { it.path }

    /** 选中最深的挂载；无任何挂载命中时返回 `null`。 */
    fun route(path: VfsPath): RouteMatch? {
        val segments = path.segments
        var best: MountRecord? = null
        for (mount in mounts) {
            val prefix = mount.path.segments
            if (prefix.size > segments.size || !segments.startsWithSegments(prefix)) continue
            if (best == null || prefix.size > best.path.segments.size) best = mount
        }
        val mount = best ?: return null
        return RouteMatch(mount, StoragePath.of(segments.drop(mount.path.segments.size)))
    }

    /**
     * 是否是配置推导出的目录（逻辑根、命名空间根、挂载点或挂载祖先）。
     * 只描述配置结构，不证明后端存在该目录；实际内容由后续 stat / list 合并。
     */
    fun isConfiguredDirectory(path: VfsPath): Boolean = path in configuredDirectories

    /** 给定配置目录下直接可见的配置子目录名；`path` 不是配置目录时返回空集合。 */
    fun listConfiguredChildren(path: VfsPath): Set<String> = childrenByDirectory[path].orEmpty()

    /** `path` 是否恰好是某个挂载根。 */
    fun isMountPoint(path: VfsPath): Boolean = path in mountPoints

    /** `path` 之下是否还有挂载点（严格更深，按完整段判断）；用于整体移动 / 删除前的拒绝。 */
    fun hasDescendantMounts(path: VfsPath): Boolean =
        mounts.any { it.path.segments.size > path.segments.size && it.path.segments.startsWithSegments(path.segments) }

    companion object {
        /**
         * 用命名空间与挂载记录构建固定路由视图；调用方之后修改入参集合不影响本对象。
         *
         * 拒绝：挂载逻辑根 `/`、规范化后重复的挂载位置、未配置命名空间下的挂载（T02 §3.1）。
         * 命名空间本身的名称格式错误沿用路径错误码 `INVALID_URI`；空挂载集合允许，只有配置目录可导航。
         */
        fun of(
            namespaces: Set<String>,
            mounts: Collection<MountRecord>,
        ): MountRouter {
            val namespacesCopy = namespaces.mapTo(LinkedHashSet()) { requireNamespace(it) }
            val mountsCopy = mounts.toList()
            val seen = HashSet<VfsPath>()
            for (mount in mountsCopy) {
                if (mount.path.isRoot) throw invalidArgument("mount must not use the logical root '/'")
                if (mount.path.segments.first() !in namespacesCopy) {
                    throw invalidArgument("mount '${mount.path}' is outside the configured namespaces")
                }
                if (!seen.add(mount.path)) throw invalidArgument("duplicate mount path '${mount.path}'")
            }

            val directories =
                LinkedHashSet<VfsPath>().apply {
                    add(VfsPath.root)
                    namespacesCopy.forEach { add(VfsPath.of(listOf(it))) }
                    mountsCopy.forEach { mount ->
                        // 挂载点本身与缺失祖先都在这里补齐（T02 §3.2 虚拟祖先）。
                        mount.path.segments.indices
                            .forEach { end -> add(VfsPath.of(mount.path.segments.subList(0, end + 1))) }
                    }
                }
            val children =
                directories
                    .filter { !it.isRoot } // 逻辑根不是任何目录的子项
                    .groupBy { VfsPath.of(it.segments.dropLast(1)) }
                    .mapValues { (_, childPaths) -> childPaths.mapTo(LinkedHashSet()) { it.segments.last() } }
            return MountRouter(mountsCopy, directories, children)
        }

        private fun requireNamespace(name: String): String {
            VfsPath.of(listOf(name)) // 空串、分隔符、控制字符、字面 '%'、孤立代理项 → INVALID_URI
            if (' ' in name) throw VfsException(VfsErrorCode.INVALID_URI, "Invalid namespace: '$name' contains a raw space")
            return name
        }

        private fun invalidArgument(reason: String): VfsException =
            VfsException(VfsErrorCode.INVALID_ARGUMENT, "Invalid mount configuration: $reason")
    }
}

/** 按完整路径段比较前缀：`/notes/a` 命中 `/notes/a/x`，不命中 `/notes/abc`。 */
private fun List<String>.startsWithSegments(prefix: List<String>): Boolean = prefix.size <= size && subList(0, prefix.size) == prefix
