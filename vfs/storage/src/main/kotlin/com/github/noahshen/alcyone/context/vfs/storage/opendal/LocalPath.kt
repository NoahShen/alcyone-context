package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 把挂载根内的相对路径变成磁盘上的真实文件，并在每一级做安全检查。
 *
 * 例：`root = /data/reports`、路径 `ct/a.dcm` → `/data/reports/ct/a.dcm`。
 *
 * 只用 JDK 的路径接口做检查，真正的读写仍然交给 OpenDAL。[StoragePath] 已经拒绝
 * `/`、`\`、`.`、`..` 这些字符，所以逐层拼接不会跑出根目录。
 */
internal object LocalPath {
    /**
     * 逐层拼出真实路径，每层检查两件事：
     *
     * 1. 这一层是符号链接（快捷方式）→ 报 `STORAGE_ACCESS_DENIED`，不跟过去，也不改它指向的文件；
     * 2. 这一层是中间目录但实际是个文件 → 报 `TYPE_MISMATCH`。
     *
     * 错误消息只说第几层出问题，不告诉调用方磁盘路径。
     */
    fun resolve(
        root: Path,
        segments: List<String>,
    ): Path {
        var current = root
        segments.forEachIndexed { index, segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw VfsException(
                    VfsErrorCode.STORAGE_ACCESS_DENIED,
                    "symbolic links are not followed (component ${index + 1} of ${segments.size})",
                )
            }
            val isIntermediate = index < segments.lastIndex
            if (isIntermediate &&
                Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw VfsException(
                    VfsErrorCode.TYPE_MISMATCH,
                    "path component ${index + 1} of ${segments.size} is not a directory",
                )
            }
        }
        return current
    }
}
