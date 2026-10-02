package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 挂载根内的相对路径到物理文件的定位与符号链接闸门（T12 §2.2）。
 *
 * 只用 JDK 本地路径 API 做**路径检查**：文件访问仍然走 OpenDAL。
 * [StoragePath] 已拒绝 `/`、`\`、`.`、`..` 等段内非法字符，所以逐段 `resolve` 不会逃出根目录。
 */
internal object LocalPath {
    /**
     * 逐级定位物理路径，并在每一级做两项检查：
     *
     * 1. 该级是符号链接 → `STORAGE_ACCESS_DENIED`，**不跟随**，不修改链接目标；
     * 2. 该级是中间组件且已存在但不是目录 → `TYPE_MISMATCH`（可确认的文件 / 目录类型冲突）。
     *
     * 错误消息只给组件序号，不含物理路径与路径段名称。
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
