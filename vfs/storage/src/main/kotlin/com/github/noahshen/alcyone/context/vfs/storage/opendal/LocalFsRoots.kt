package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 校验并整理一组本地磁盘目录（挂载根），供 Runtime 组装配置时调用（T18）。
 *
 * 单独成一个入口而不是放进 [LocalFsStorage]：装配时要先把一整组根一起校验，那时还没有创建存储实例。
 * 这里只管磁盘目录的边界；逻辑路径怎么路由到哪个根是 Core 的事。
 *
 * 例：`data/a` 和 `data/a/sub` 互相包含，冲突；`data/ab` 是平级目录，可以共存。
 */
object LocalFsRoots {
    /**
     * 整理单个根目录：必须已经存在且是个目录，然后把符号链接换成它指向的真实路径。
     *
     * 根本身允许是符号链接；根**里面**的符号链接由 [LocalFsStorage] 拒绝。
     */
    fun normalize(root: Path): Path {
        if (!Files.isDirectory(root)) {
            throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "storage root must be an existing directory")
        }
        return try {
            root.toRealPath()
        } catch (e: IOException) {
            // 消息不带磁盘路径，原始异常挂 cause。
            throw VfsException(
                VfsErrorCode.INVALID_ARGUMENT,
                "storage root cannot be resolved (${e::class.simpleName})",
            ).apply { initCause(e) }
        }
    }

    /**
     * 检查一组根目录互不相同、也不互相包含，返回整理后的结果（顺序与传入一致）。
     *
     * 比较是按完整目录名一段一段比的：`data/a` 不会被误判成包含了 `data/ab`。
     * 错误消息不带任何磁盘路径。
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
