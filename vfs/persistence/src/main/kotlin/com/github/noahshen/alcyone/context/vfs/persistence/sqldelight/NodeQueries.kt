package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import java.time.Instant

/**
 * `node` 表的读取与写入：只做 SQL 与领域类型的映射，不涉及调度器、事务与错误语义，
 * 由调用方（自动提交 Repository 或事务内视图）决定如何执行。
 *
 * 时间戳按表定义以 epoch 毫秒存取。
 */
internal fun NodeQueries.register(record: NodeRecord): NodeRecord {
    insertActive(
        record.id.value,
        record.path.toString(),
        record.type.name,
        if (record.physical) 1L else 0L,
        record.registeredAt.toEpochMilli(),
        record.updatedAt.toEpochMilli(),
    )
    // 先写后读：唯一性由 node_active_path 部分唯一索引裁定，冲突时这次 INSERT 被忽略，
    // 读回的就是竞争获胜的既有记录（R3），调用方不需要先查后插。
    return selectActiveByPath(record.path.toString()).executeAsOneOrNull()?.toRecord()
        ?: throw VfsException(
            VfsErrorCode.STATE_ERROR,
            "node id is already registered: ${record.id.value}",
        )
}

internal fun NodeQueries.findByPath(path: VfsPath): NodeRecord? = selectActiveByPath(path.toString()).executeAsOneOrNull()?.toRecord()

internal fun NodeQueries.findById(id: NodeId): NodeRecord? = selectActiveById(id.value).executeAsOneOrNull()?.toRecord()

/** 新路径已被其他有效记录占用时，部分唯一索引拒绝这次更新；本层不做复用，复用是 `register` 的语义。 */
internal fun NodeQueries.updatePath(
    id: NodeId,
    newPath: VfsPath,
    updatedAt: Instant,
) {
    updatePathUpdatedAt(newPath.toString(), updatedAt.toEpochMilli(), id.value)
}

internal fun NodeQueries.touch(
    id: NodeId,
    updatedAt: Instant,
) {
    touchUpdatedAt(updatedAt.toEpochMilli(), id.value)
}

internal fun NodeQueries.markDeleted(
    ids: Collection<NodeId>,
    deletedAt: Instant,
) {
    if (ids.isEmpty()) return
    markNodesDeleted(deletedAt.toEpochMilli(), ids.map(NodeId::value))
}

/** 自身 + 全部后代，按路径排序；已删除节点不返回。 */
internal fun NodeQueries.findSubtree(path: VfsPath): List<NodeRecord> {
    // 逻辑根的子树就是全部有效节点：空前缀让 instr 恒命中。
    val prefix = if (path.isRoot) "" else "$path/"
    return selectSubtree(path.toString(), prefix).executeAsList().map { it.toRecord() }
}

/** 批量按有效路径查询；空列表直接返回空（SQLite 的 `IN ()` 是语法错误）。结果顺序未定义。 */
internal fun NodeQueries.findByPaths(paths: List<VfsPath>): List<NodeRecord> {
    if (paths.isEmpty()) return emptyList()
    return selectActiveByPaths(paths.map(VfsPath::toString)).executeAsList().map { it.toRecord() }
}

private fun Node.toRecord(): NodeRecord =
    NodeRecord(
        id = NodeId.parse(id),
        path = VfsPath.parse(vfs_path),
        type = NodeType.valueOf(node_type),
        physical = physical == 1L,
        registeredAt = Instant.ofEpochMilli(registered_at),
        updatedAt = Instant.ofEpochMilli(updated_at),
    )
