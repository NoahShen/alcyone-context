package alcyone.vfs

/**
 * 读取选项。
 *
 * @param maxBytes 调用方进一步收紧的上限；为空时使用 Runtime 限额，不表示无限。
 */
data class ReadOptions(
    val maxBytes: Long? = null,
)

/** 写入模式：仅创建、仅覆盖已存在文件，或创建或覆盖。 */
enum class WriteMode { CREATE_NEW, REPLACE_EXISTING, UPSERT }

/** 写入选项；覆盖已注册文件保留 Node ID 与 Metadata。 */
data class WriteOptions(
    val mode: WriteMode = WriteMode.UPSERT,
)

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
