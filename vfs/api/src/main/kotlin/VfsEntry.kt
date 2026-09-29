package alcyone.vfs

/** `list()` 返回的一个目录条目，是查询结果而不是持久化实体。`nodeId == null` 表示该条目未注册。 */
data class VfsEntry(
    val uri: VfsUri,
    val type: NodeType,
    val nodeId: NodeId?,
    val storage: StorageStat?,
)
