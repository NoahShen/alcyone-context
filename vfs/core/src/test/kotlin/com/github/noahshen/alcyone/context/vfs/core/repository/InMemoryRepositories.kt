package com.github.noahshen.alcyone.context.vfs.core.repository

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsPath
import java.time.Instant

class InMemoryNodeRepository(
    /**
     * 支撑 map；可由外部传入以便与别的视图共用同一份状态（T20 的移动测试用它模拟真实栈「同一个 SQLite 表」）。
     * ID 查询直接从这份 map 派生，避免共享时出现第二份索引不同步。
     */
    private val byPath: MutableMap<VfsPath, NodeRecord> = mutableMapOf(),
) : NodeRepository {
    override suspend fun findByPath(path: VfsPath): NodeRecord? = byPath[path]

    override suspend fun findById(id: NodeId): NodeRecord? = byPath.values.firstOrNull { it.id == id }

    override suspend fun register(record: NodeRecord): NodeRecord {
        val existing = byPath[record.path]
        if (existing != null) {
            return existing
        }
        byPath[record.path] = record
        return record
    }

    override suspend fun updatePath(
        id: NodeId,
        newPath: VfsPath,
        updatedAt: Instant,
    ) {
        val existing = findById(id) ?: return
        byPath.remove(existing.path)
        val updated = existing.copy(path = newPath, updatedAt = updatedAt)
        byPath[newPath] = updated
    }

    override suspend fun touch(
        id: NodeId,
        updatedAt: Instant,
    ) {
        val existing = findById(id) ?: return
        val updated = existing.copy(updatedAt = updatedAt)
        byPath[existing.path] = updated
    }

    override suspend fun markDeleted(
        ids: Collection<NodeId>,
        deletedAt: Instant,
    ) {
        for (id in ids) {
            findById(id)?.let { byPath.remove(it.path) }
        }
    }

    override suspend fun findSubtree(path: VfsPath): List<NodeRecord> {
        val prefixSegments = path.segments
        return byPath.values.filter { record ->
            val segments = record.path.segments
            // 含自身或后代段严格匹配
            segments.size >= prefixSegments.size &&
                segments.subList(0, prefixSegments.size) == prefixSegments
        }
    }

    override suspend fun findByPaths(paths: List<VfsPath>): List<NodeRecord> = paths.mapNotNull { byPath[it] }
}

class InMemoryMetadataRepository : MetadataRepository {
    private val store = mutableMapOf<NodeId, NodeMetadata>()

    override suspend fun get(id: NodeId): NodeMetadata? = store[id]

    override suspend fun put(
        id: NodeId,
        metadata: NodeMetadata,
    ) {
        val isEmpty = metadata.tags.isEmpty() && metadata.description == null && metadata.extensions.isEmpty()
        if (isEmpty) {
            store.remove(id)
        } else {
            store[id] = metadata
        }
    }

    override suspend fun delete(id: NodeId) {
        store.remove(id)
    }
}

class InMemoryEventRepository : EventRepository {
    val events = mutableListOf<EventRecord>()

    override suspend fun append(event: EventRecord) {
        events.add(event)
    }
}

class InMemoryMountRepository(
    private val mounts: List<MountRecord> = emptyList(),
) : MountRepository {
    override suspend fun list(): List<MountRecord> = mounts
}
