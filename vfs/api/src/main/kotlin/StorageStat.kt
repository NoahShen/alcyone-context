package alcyone.vfs

import java.time.Instant

/**
 * 本次操作获取到的底层属性。
 *
 * 字段为空表示后端不提供该属性；目录大小不伪装成准确的递归文件总量。
 */
data class StorageStat(
    val sizeBytes: Long?,
    val modifiedAt: Instant?,
)
