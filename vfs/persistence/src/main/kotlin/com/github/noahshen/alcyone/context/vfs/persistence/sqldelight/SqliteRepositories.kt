package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MetadataRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import java.time.Instant

/**
 * 状态库的自动提交 Repository（T07 R1～R11）。
 *
 * 每次调用各自在 IO 调度器上执行一次 JDBC 调用；需要「状态与事件同一事务」的编排走
 * [SqliteUnitOfWork]，不要在这里手工拼多条语句。
 *
 * 时间戳按 epoch 毫秒存取，读回的 [Instant] 精度到毫秒；路径比较大小写敏感。
 */
class SqliteNodeRepository(
    state: VfsStateDatabase,
) : NodeRepository {
    private val queries = state.database.nodeQueries

    override suspend fun findByPath(path: VfsPath): NodeRecord? = stateCall { queries.findByPath(path) }

    override suspend fun findById(id: NodeId): NodeRecord? = stateCall { queries.findById(id) }

    override suspend fun register(record: NodeRecord): NodeRecord = stateCall { queries.register(record) }

    /**
     * 新路径已被其他有效记录占用时，数据库唯一约束拒绝更新，失败映射为 STATE_ERROR。
     * 路径被占用时的「复用既有记录」是 [register] 的语义，这里不重复实现。
     */
    override suspend fun updatePath(
        id: NodeId,
        newPath: VfsPath,
        updatedAt: Instant,
    ) {
        stateCall { queries.updatePath(id, newPath, updatedAt) }
    }

    override suspend fun touch(
        id: NodeId,
        updatedAt: Instant,
    ) {
        stateCall { queries.touch(id, updatedAt) }
    }

    override suspend fun markDeleted(
        ids: Collection<NodeId>,
        deletedAt: Instant,
    ) {
        stateCall { queries.markDeleted(ids, deletedAt) }
    }

    override suspend fun findSubtree(path: VfsPath): List<NodeRecord> = stateCall { queries.findSubtree(path) }

    override suspend fun findByPaths(paths: List<VfsPath>): List<NodeRecord> = stateCall { queries.findByPaths(paths) }
}

/** 元数据仓库（R9）。空元数据按清空处理，见 [writeMetadata]。 */
class SqliteMetadataRepository(
    state: VfsStateDatabase,
) : MetadataRepository {
    private val queries = state.database.metadataQueries

    override suspend fun get(id: NodeId): NodeMetadata? = stateCall { queries.readMetadata(id) }

    override suspend fun put(
        id: NodeId,
        metadata: NodeMetadata,
    ) {
        stateCall { queries.writeMetadata(id, metadata) }
    }

    override suspend fun delete(id: NodeId) {
        stateCall { queries.removeMetadata(id) }
    }
}

/** 事件仓库（R10）。只负责持久化，分发与可靠投递属 T14 / E05。 */
class SqliteEventRepository(
    state: VfsStateDatabase,
) : EventRepository {
    private val queries = state.database.eventQueries

    override suspend fun append(event: EventRecord) {
        stateCall { queries.append(event) }
    }
}

/** 挂载点仓库（R11）。本轮严格只读，映射写入由 T18 负责。 */
class SqliteMountRepository(
    state: VfsStateDatabase,
) : MountRepository {
    private val queries = state.database.mountQueries

    override suspend fun list(): List<MountRecord> =
        stateCall {
            queries.selectAllMounts().executeAsList().map { MountRecord(VfsPath.parse(it.vfs_path), it.storage_key) }
        }
}
