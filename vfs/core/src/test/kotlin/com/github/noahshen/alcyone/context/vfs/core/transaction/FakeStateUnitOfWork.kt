package com.github.noahshen.alcyone.context.vfs.core.transaction

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MetadataRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import java.time.Instant

/**
 * B5 测试替身：内存态 UnitOfWork，记录调用顺序并支持故障注入。
 *
 * 事务语义用「写时复制」实现：回调内所有写入落到暂存副本，提交时才替换已提交状态，
 * 回滚直接丢弃副本。**这是替身，不是真实数据库事务**（A05 明确禁止把替身结果当作真实事务验证）。
 */
class FakeStateUnitOfWork(
    initialNodes: List<NodeRecord> = emptyList(),
    initialMetadata: Map<NodeId, NodeMetadata> = emptyMap(),
    initialEvents: List<EventRecord> = emptyList(),
    /**
     * 可选的共享 Node 存储。传入时本类的已提交状态就用这份 map（提交也原地写回），
     * 于是外部的自动提交仓库与这里的事务视图看到同一份记录，模拟真实栈里同一个 SQLite 表。
     * 不传就是本类自己的私有状态。
     */
    private val sharedNodeStore: MutableMap<VfsPath, NodeRecord>? = null,
) : UnitOfWork {
    /** 调用顺序记录，供测试断言编排次序。 */
    val calls: MutableList<String> = mutableListOf()

    // 已提交状态
    private val committedNodes: MutableMap<VfsPath, NodeRecord> =
        (sharedNodeStore ?: mutableMapOf()).apply { putAll(indexByPath(initialNodes)) }
    private var committedMetadata: MutableMap<NodeId, NodeMetadata> = initialMetadata.toMutableMap()
    private var committedEvents: MutableList<EventRecord> = initialEvents.toMutableList()

    // 故障注入开关
    var failOnStateWrite: VfsException? = null
    var failOnEventAppend: VfsException? = null

    /**
     * 抛一个**不是** [VfsException] 的失败：用来验证编排把普通异常映射成 `STATE_ERROR` 而不是原样漏给调用方。
     * 真实 SQLite 实现自己就映射好了（见 `mapStateErrors`），所以这条只在替身上注入。
     */
    var failOnEventAppendRaw: Throwable? = null
    var failOnCommit: VfsException? = null

    /** 已提交状态只读快照。 */
    fun snapshot(): Triple<List<NodeRecord>, Map<NodeId, NodeMetadata>, List<EventRecord>> =
        Triple(committedNodes.values.toList(), committedMetadata.toMap(), committedEvents.toList())

    /**
     * 为 R1 受控反例：开启时，事务视图里的 `findById` / `findByPath` 返回 null（模拟「提交阶段看不到源记录」），
     * 但 `committedNodes`（自动提交仓库）仍能看到——模拟「最后一步发现记录不见了」而非「记录真没了」。
     */
    var hideNodesInTransactionView: Boolean = false

    private fun indexByPath(records: List<NodeRecord>): MutableMap<VfsPath, NodeRecord> = records.associateBy { it.path }.toMutableMap()

    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T {
        val stagedNodes = committedNodes.toMutableMap()
        val stagedMetadata = committedMetadata.toMutableMap()
        val stagedEvents = committedEvents.toMutableList()
        var closed = false

        fun ensureOpen() {
            check(!closed) { "TransactionScope used outside of inTransaction block" }
        }

        val scope =
            object : TransactionScope {
                override val nodes: NodeRepository = StagedNodeRepository(stagedNodes, ::ensureOpen)
                override val metadata: MetadataRepository = StagedMetadataRepository(stagedMetadata, ::ensureOpen)
                override val events: EventRepository = StagedEventRepository(stagedEvents, ::ensureOpen)
            }

        val result = block(scope)
        ensureOpen() // 回调若同步关闭了作用域视为逃逸
        closed = true

        calls += "commit"
        failOnCommit?.let {
            calls += "commit-failed"
            throw it
        }
        // 原地写回：共享存储时 map 实例不能换，否则自动提交那份就看不到了。
        committedNodes.clear()
        committedNodes.putAll(stagedNodes)
        committedMetadata = stagedMetadata
        committedEvents = stagedEvents
        return result
    }

    private inner class StagedNodeRepository(
        private val staged: MutableMap<VfsPath, NodeRecord>,
        private val ensureOpen: () -> Unit,
    ) : NodeRepository {
        override suspend fun findByPath(path: VfsPath): NodeRecord? {
            ensureOpen()
            calls += "nodes.findByPath:$path"
            if (hideNodesInTransactionView) return null
            return staged[path]
        }

        override suspend fun findById(id: NodeId): NodeRecord? {
            ensureOpen()
            calls += "nodes.findById:$id"
            if (hideNodesInTransactionView) return null
            return staged.values.firstOrNull { it.id == id }
        }

        override suspend fun register(record: NodeRecord): NodeRecord {
            ensureOpen()
            calls += "nodes.register:$record"
            failOnStateWrite?.let {
                calls += "nodes.register-failed"
                throw it
            }
            val existing = staged[record.path] ?: record.also { staged[record.path] = it }
            return existing
        }

        override suspend fun updatePath(
            id: NodeId,
            newPath: VfsPath,
            updatedAt: Instant,
        ) {
            ensureOpen()
            calls += "nodes.updatePath:$id->$newPath"
            failOnStateWrite?.let {
                calls += "nodes.updatePath-failed"
                throw it
            }
            val existing = staged.values.firstOrNull { it.id == id } ?: return
            staged.remove(existing.path)
            staged[newPath] = existing.copy(path = newPath, updatedAt = updatedAt)
        }

        override suspend fun touch(
            id: NodeId,
            updatedAt: Instant,
        ) {
            ensureOpen()
            calls += "nodes.touch:$id"
            failOnStateWrite?.let {
                calls += "nodes.touch-failed"
                throw it
            }
            val existing = staged.values.firstOrNull { it.id == id } ?: return
            staged[existing.path] = existing.copy(updatedAt = updatedAt)
        }

        override suspend fun markDeleted(
            ids: Collection<NodeId>,
            deletedAt: Instant,
        ) {
            ensureOpen()
            calls += "nodes.markDeleted:$ids"
            failOnStateWrite?.let {
                calls += "nodes.markDeleted-failed"
                throw it
            }
            for (id in ids) {
                staged.values.firstOrNull { it.id == id }?.let { staged.remove(it.path) }
            }
        }

        override suspend fun findSubtree(path: VfsPath): List<NodeRecord> {
            ensureOpen()
            calls += "nodes.findSubtree:$path"
            val prefix = path.segments
            return staged.values.filter { record ->
                val segments = record.path.segments
                segments.size >= prefix.size && segments.subList(0, prefix.size) == prefix
            }
        }

        override suspend fun findByPaths(paths: List<VfsPath>): List<NodeRecord> {
            ensureOpen()
            calls += "nodes.findByPaths:$paths"
            return paths.mapNotNull { staged[it] }
        }
    }

    private inner class StagedMetadataRepository(
        private val staged: MutableMap<NodeId, NodeMetadata>,
        private val ensureOpen: () -> Unit,
    ) : MetadataRepository {
        override suspend fun get(id: NodeId): NodeMetadata? {
            ensureOpen()
            calls += "metadata.get:$id"
            return staged[id]
        }

        override suspend fun put(
            id: NodeId,
            metadata: NodeMetadata,
        ) {
            ensureOpen()
            calls += "metadata.put:$id"
            failOnStateWrite?.let {
                calls += "metadata.put-failed"
                throw it
            }
            val empty = metadata.tags.isEmpty() && metadata.description == null && metadata.extensions.isEmpty()
            if (empty) staged.remove(id) else staged[id] = metadata
        }

        override suspend fun delete(id: NodeId) {
            ensureOpen()
            calls += "metadata.delete:$id"
            failOnStateWrite?.let {
                calls += "metadata.delete-failed"
                throw it
            }
            staged.remove(id)
        }
    }

    private inner class StagedEventRepository(
        private val staged: MutableList<EventRecord>,
        private val ensureOpen: () -> Unit,
    ) : EventRepository {
        override suspend fun append(event: EventRecord) {
            ensureOpen()
            calls += "events.append:$event"
            failOnEventAppend?.let {
                calls += "events.append-failed"
                throw it
            }
            failOnEventAppendRaw?.let {
                calls += "events.append-raw-failed"
                throw it
            }
            staged.add(event)
        }
    }

    companion object {
        fun storageFailure(reason: String): VfsException = VfsException(VfsErrorCode.STORAGE_ERROR, reason)
    }
}
