package com.github.noahshen.alcyone.context.vfs.core.repository

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsPath
import java.time.Instant

class InMemoryNodeRepository : NodeRepository {
    // 仅保留当前有效节点：path -> NodeRecord, id -> NodeRecord
    private val byPath = mutableMapOf<VfsPath, NodeRecord>()
    private val byId = mutableMapOf<NodeId, NodeRecord>()

    override suspend fun findByPath(path: VfsPath): NodeRecord? = byPath[path]

    override suspend fun findById(id: NodeId): NodeRecord? = byId[id]

    override suspend fun register(record: NodeRecord): NodeRecord {
        val existing = byPath[record.path]
        if (existing != null) {
            return existing
        }
        byPath[record.path] = record
        byId[record.id] = record
        return record
    }

    override suspend fun updatePath(
        id: NodeId,
        newPath: VfsPath,
        updatedAt: Instant,
    ) {
        val existing = byId[id] ?: return
        byPath.remove(existing.path)
        val updated = existing.copy(path = newPath, updatedAt = updatedAt)
        byPath[newPath] = updated
        byId[id] = updated
    }

    override suspend fun touch(
        id: NodeId,
        updatedAt: Instant,
    ) {
        val existing = byId[id] ?: return
        val updated = existing.copy(updatedAt = updatedAt)
        byPath[existing.path] = updated
        byId[id] = updated
    }

    override suspend fun markDeleted(
        ids: Collection<NodeId>,
        deletedAt: Instant,
    ) {
        for (id in ids) {
            val existing = byId.remove(id)
            if (existing != null) {
                byPath.remove(existing.path)
            }
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
