package com.github.noahshen.alcyone.context.vfs

/**
 * 读取选项。
 *
 * @param maxBytes 调用方进一步收紧的上限：`null` 表示使用 Runtime 限额（不表示无限），`0` 表示只接受空内容，
 * 正数为字节上限。负数在构造时抛 [VfsErrorCode.INVALID_ARGUMENT]、effect 为 [VfsEffect.NONE]，
 * 构造与 `copy()` 都无法绕过；具体限额与 I/O 执行由 Runtime 负责。
 */
data class ReadOptions(
    val maxBytes: Long? = null,
) {
    init {
        if (maxBytes != null && maxBytes < 0) {
            throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "ReadOptions.maxBytes must not be negative: $maxBytes")
        }
    }
}

/** 写入模式：仅创建、仅覆盖已存在文件，或创建或覆盖。 */
enum class WriteMode { CREATE_NEW, REPLACE_EXISTING, UPSERT }

/** 写入选项；覆盖已注册文件保留 Node ID 与 Metadata。 */
data class WriteOptions(
    val mode: WriteMode = WriteMode.UPSERT,
)

/**
 * 流式发布一个全新文件。
 *
 * [maxBytes] 和 [expectedSizeBytes] 都是硬约束：实际读取超限、短读或额外字节都拒绝。
 * 实现负责在成功、失败和取消时关闭输入流，调用方不得再次关闭。
 */
data class PublishOptions(
    val maxBytes: Long,
    val expectedSizeBytes: Long,
) {
    init {
        if (maxBytes < 0 || expectedSizeBytes < 0 || expectedSizeBytes > maxBytes) {
            throw VfsException(
                VfsErrorCode.INVALID_ARGUMENT,
                "PublishOptions requires 0 <= expectedSizeBytes <= maxBytes: expected=$expectedSizeBytes max=$maxBytes",
            )
        }
    }
}

/**
 * 查询选项。
 *
 * @param includeStorage 为 false 时只允许已有 Node 走逻辑查询；未注册时仍须由 Storage 确认资源存在。
 */
data class StatOptions(
    val includeStorage: Boolean = true,
)

/** 删除选项；非递归删除非空目录返回 DIRECTORY_NOT_EMPTY。 */
data class DeleteOptions(
    val recursive: Boolean = false,
)
