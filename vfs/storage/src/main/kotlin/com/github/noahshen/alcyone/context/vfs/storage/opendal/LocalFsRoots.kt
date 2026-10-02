package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 本地物理根的规范化与重叠校验（T12 §2.2），供 Runtime 组装挂载配置时调用（T18）。
 *
 * 单独成入口而不是塞进 Adapter：装配阶段要先拿到一整组根做校验，此时还没有 Storage 实例。
 * 本文件不依赖 MountRouter——逻辑挂载路由属于 Core，物理根边界属于本模块。
 *
 * 例：`data/a` 与 `data/a/sub` 冲突；`data/ab` 是兄弟根，允许共存。
 */
object LocalFsRoots {
    /**
     * 规范化一个物理根：必须**已存在**且是目录，然后用 [Path.toRealPath] 消解符号链接。
     *
     * 根本身允许是符号链接（会被解析成真实目录）；根**内部**的符号链接由 Adapter 拒绝，见 [LocalFsStorage]。
     */
    fun normalize(root: Path): Path {
        if (!Files.isDirectory(root)) {
            throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "storage root must be an existing directory")
        }
        return try {
            root.toRealPath()
        } catch (e: IOException) {
            // 消息不带物理路径，原始异常挂 cause。
            throw VfsException(
                VfsErrorCode.INVALID_ARGUMENT,
                "storage root cannot be resolved (${e::class.simpleName})",
            ).apply { initCause(e) }
        }
    }

    /**
     * 校验一组物理根两两既不相同也不互相包含，返回规范化后的根（顺序与入参一致）。
     *
     * 比较按**完整路径段**：`Path.startsWith` 比的是路径段而不是字符串，所以 `data/a` 不会误判包含 `data/ab`。
     * 错误消息不带任何物理路径，避免把宿主目录结构暴露到公共异常里。
     */
    fun requireNonOverlapping(roots: Collection<Path>): List<Path> {
        val normalized = roots.map { normalize(it) }
        for (i in normalized.indices) {
            for (j in i + 1 until normalized.size) {
                if (overlaps(normalized[i], normalized[j])) {
                    throw VfsException(
                        VfsErrorCode.INVALID_ARGUMENT,
                        "storage roots must be distinct and must not contain each other (roots $i and $j overlap)",
                    )
                }
            }
        }
        return normalized
    }

    private fun overlaps(
        left: Path,
        right: Path,
    ): Boolean = left == right || left.startsWith(right) || right.startsWith(left)
}
