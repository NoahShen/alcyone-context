package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException

/**
 * 创建本地磁盘存储时的参数。
 *
 * 例：`LocalFsOptions(defaultWriteLimitBytes = 1024)` 让每次写文件都拒绝超过 1 KiB 的内容。
 * 全局默认由 Runtime 后续接上（T15），这里不读系统配置。
 */
data class LocalFsOptions(
    /** 一次写文件最多允许多少字节。超限在动手写之前就拒绝，不会写一半再报错。 */
    val defaultWriteLimitBytes: Long = DEFAULT_WRITE_LIMIT_BYTES,
) {
    init {
        if (defaultWriteLimitBytes < 0) {
            throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "defaultWriteLimitBytes must not be negative")
        }
    }

    companion object {
        /** 首版默认上限：16 MiB（T04 §6 确认）。 */
        const val DEFAULT_WRITE_LIMIT_BYTES: Long = 16L * 1024 * 1024
    }
}
