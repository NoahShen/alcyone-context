package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException

/**
 * Local FS Storage 的创建参数。
 *
 * 例：`LocalFsOptions(defaultWriteLimitBytes = 1024)` 让所有 `write` 在写入前拒绝超过 1 KiB 的内容。
 * Runtime 后续把这份配置接到全局默认（T15），Storage 自己不读系统配置。
 */
data class LocalFsOptions(
    /** 单次 `write` 的字节上限；超限在任何存储副作用之前拒绝，不截断、不先写再报错。 */
    val defaultWriteLimitBytes: Long = DEFAULT_WRITE_LIMIT_BYTES,
) {
    init {
        if (defaultWriteLimitBytes < 0) {
            throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "defaultWriteLimitBytes must not be negative")
        }
    }

    companion object {
        /** T04 §6 确认的首版默认上限：16 MiB。 */
        const val DEFAULT_WRITE_LIMIT_BYTES: Long = 16L * 1024 * 1024
    }
}
