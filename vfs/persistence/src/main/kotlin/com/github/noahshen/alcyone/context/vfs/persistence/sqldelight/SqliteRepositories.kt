package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.EventCursorStart
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MetadataRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.ReliableEventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.SequencedEventRecord
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

/** SQLite 可靠事件读取；每次起点/读批/推进都是独立的短事务。 */
class SqliteReliableEventRepository(
    private val state: VfsStateDatabase,
) : ReliableEventRepository {
    override suspend fun initializeCursor(
        consumerId: String,
        start: EventCursorStart,
    ): Long {
        require(consumerId.isNotBlank()) { "consumerId must not be blank" }
        return stateCall {
            state.driver.inRawTransaction {
                val queries = state.database.cursorQueries
                queries.selectConsumerCursor(consumerId).executeAsOneOrNull()
                    ?: run {
                        val latest =
                            state.database.eventQueries
                                .selectLatestEventSequence()
                                .executeAsOne()
                        val next = if (start == EventCursorStart.EARLIEST) 1L else latest + 1
                        queries.insertConsumerCursor(consumerId, next)
                        queries.selectConsumerCursor(consumerId).executeAsOne()
                    }
            }
        }
    }

    override suspend fun readAfter(
        afterExclusive: Long,
        limit: Int,
    ): List<SequencedEventRecord> {
        require(afterExclusive >= 0) { "afterExclusive must be non-negative" }
        require(limit in 1..1000) { "limit must be in 1..1000: $limit" }
        return stateCall {
            state.database.eventQueries.selectEventsAfter(afterExclusive, limit.toLong()).executeAsList().map { row ->
                SequencedEventRecord(row.sequence, row.toEventRecord())
            }
        }
    }

    override suspend fun advanceCursor(
        consumerId: String,
        expectedNextSequence: Long,
        nextSequence: Long,
    ) {
        require(nextSequence > expectedNextSequence) { "cursor must advance" }
        stateCall {
            state.driver.inRawTransaction {
                val changed =
                    state.database.cursorQueries
                        .advanceConsumerCursor(nextSequence, consumerId, expectedNextSequence)
                        .value
                if (changed != 1L) {
                    throw VfsException(VfsErrorCode.STATE_ERROR, "consumer cursor changed before batch commit: $consumerId")
                }
            }
        }
    }
}

/** 挂载点仓库（R11）。Core 端口只读；写入入口放在这里（T18），SQL 不外泄。 */
class SqliteMountRepository(
    state: VfsStateDatabase,
) : MountRepository {
    private val state = state
    private val queries = state.database.mountQueries

    override suspend fun list(): List<MountRecord> =
        stateCall {
            queries.selectAllMounts().executeAsList().map {
                MountRecord(VfsPath.parse(it.vfs_path), it.storage_key, it.backend_type, it.physical_root)
            }
        }

    /**
     * 整批替换挂载映射（首启保存、后续只增不改时都走这里），**一个事务里先清后写**。
     *
     * 例：首次启动把 `[("resources", "local", "local-fs", "/data/disk")]` 写进空库；
     * 中途插入失败时这一批整体回滚，不会留下改了一半的映射。
     *
     * 调用方（Runtime）必须先核对旧映射再调这里：这里只负责「把已经确认可接受的一批写进去」，
     * 冲突判定不在 persistence。
     */
    suspend fun replaceAll(records: List<MountRecord>) {
        stateCall {
            state.driver.inRawTransaction {
                queries.deleteAllMounts()
                records.forEach { record ->
                    queries.insertMount(record.path.toString(), record.storageKey, record.backendType, record.physicalRoot)
                }
            }
        }
    }
}
