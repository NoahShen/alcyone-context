package alcyone.vfs

import java.time.Instant

/**
 * 已注册 Node 的逻辑信息，`id` 必须存在。
 *
 * @param registeredAt Node 注册时间。
 * @param updatedAt VFS 逻辑记录更新时间；两者都不是文件系统创建 / 修改时间。
 */
data class NodeInfo(
    val id: NodeId,
    val uri: VfsUri,
    val type: NodeType,
    val registeredAt: Instant,
    val updatedAt: Instant,
    val storage: StorageStat?,
)
