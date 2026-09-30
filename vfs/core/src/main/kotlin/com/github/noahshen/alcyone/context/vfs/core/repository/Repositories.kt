package com.github.noahshen.alcyone.context.vfs.core.repository

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsPath
import java.time.Instant

/**
 * 节点状态仓库接口。
 */
interface NodeRepository {
    /**
     * 按当前有效路径查询节点（R1）。
     */
    suspend fun findByPath(path: VfsPath): NodeRecord?

    /**
     * 按稳定标识查询节点（R2）。
     */
    suspend fun findById(id: NodeId): NodeRecord?

    /**
     * 注册节点（R3）。若有效路径已被占用，返回已存在的既有记录（竞争复用保证）。
     */
    suspend fun register(record: NodeRecord): NodeRecord

    /**
     * 变更节点逻辑路径（R4）。
     */
    suspend fun updatePath(
        id: NodeId,
        newPath: VfsPath,
        updatedAt: Instant,
    )

    /**
     * 内容覆盖或触碰时更新节点时间戳（R5）。
     */
    suspend fun touch(
        id: NodeId,
        updatedAt: Instant,
    )

    /**
     * 批量标记删除节点并释放有效路径占用（R6）。
     *
     * 语义：标记后 [findByPath] / [findById] / [findByPaths] 等正常查询不再返回该节点，
     * 其 `VfsPath` 立即可被新节点复用；Metadata 由调用方在同一事务内清理。
     * 数据库字段、删除标记的存储方式与清理顺序由 T11 决定，本接口不规定。
     */
    suspend fun markDeleted(
        ids: Collection<NodeId>,
        deletedAt: Instant,
    )

    /**
     * 查询指定路径的已注册子树，含自身与所有后代（R7）。
     *
     * 约束（G10）：严格按完整段边界匹配，例如 `/notes/a` 匹配 `/notes/a`、`/notes/a/b`，绝不匹配同名前缀兄弟 `/notes/abc`。
     */
    suspend fun findSubtree(path: VfsPath): List<NodeRecord>

    /**
     * 批量按有效路径查询节点，用于 list 结果补全 Node ID（R8）。
     */
    suspend fun findByPaths(paths: List<VfsPath>): List<NodeRecord>
}

/**
 * 元数据仓库接口（R9）。
 */
interface MetadataRepository {
    /**
     * 获取指定 Node 的元数据，未设置时返回 null。
     */
    suspend fun get(id: NodeId): NodeMetadata?

    /**
     * 整体替换元数据；空元数据代表清空。
     */
    suspend fun put(
        id: NodeId,
        metadata: NodeMetadata,
    )

    /**
     * 清理指定 Node 的元数据。
     */
    suspend fun delete(id: NodeId)
}

/**
 * 事件日志持久化仓库接口（R10）。
 */
interface EventRepository {
    /**
     * 追加事件记录。
     */
    suspend fun append(event: EventRecord)
}

/**
 * 挂载点仓库接口（R11）。本轮严格保持纯读，不定义写入方法。
 */
interface MountRepository {
    /**
     * 列出所有挂载点映射。
     */
    suspend fun list(): List<MountRecord>
}
