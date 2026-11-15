package com.github.noahshen.alcyone.context.vfs.core.recovery

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import kotlinx.coroutines.CancellationException

enum class RecoveryPresence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

/** 只读物理观察；UNKNOWN 永远不会被解释成 ABSENT。 */
data class RecoveryResourceObservation(
    val uri: VfsUri,
    val presence: RecoveryPresence,
    val type: NodeType? = null,
    val sizeBytes: Long? = null,
    val errorCode: VfsErrorCode? = null,
)

/** 对应逻辑映射观察；null record 表示当前没有有效 Node，不推断历史映射。 */
data class RecoveryNodeObservation(
    val uri: VfsUri,
    val record: NodeRecord?,
    val errorCode: VfsErrorCode? = null,
)

/**
 * 一次 reconcile 的只读报告。当前版本始终给出 RECONCILE_REQUIRED，不自动关闭、重放、补偿或补造事件。
 */
data class RecoveryReport(
    val operation: RecoveryOperation,
    val source: RecoveryResourceObservation?,
    val target: RecoveryResourceObservation,
    val sourceNode: RecoveryNodeObservation?,
    val targetNode: RecoveryNodeObservation,
    val verdict: VfsErrorCode = VfsErrorCode.RECOVERY_REQUIRED,
)

/**
 * 显式恢复核对器。只读 journal、Storage 与当前 Node 映射；Storage / 状态库错误转成受限的 UNKNOWN 观察，
 * 不修改 journal 阶段，不改 Node / Metadata / Event，也不删除任何文件。
 */
class OperationReconciler(
    private val journal: RecoveryJournal,
    private val nodes: NodeRepository,
    private val router: MountRouter,
    private val storages: (String) -> Storage?,
    /** 与 Registry、EventPipeline 和 DefaultVfs 共用，不能让恢复读取越过未提交状态或并发写入。 */
    private val boundary: StateBoundary,
    @Suppress("UNUSED_PARAMETER") capabilities: CapabilitySnapshot? = null,
) {
    suspend fun listPending(): List<RecoveryOperation> =
        boundary.withLock {
            journal.listPending().filter { it.phase != RecoveryPhase.CLOSED }
        }

    suspend fun reconcile(operationId: String): RecoveryReport? =
        boundary.withLock {
            journal
                .find(operationId)
                ?.takeIf { it.phase != RecoveryPhase.CLOSED }
                ?.let { reconcileInsideBoundary(it) }
        }

    suspend fun reconcile(operation: RecoveryOperation): RecoveryReport {
        require(operation.phase != RecoveryPhase.CLOSED) { "closed operation cannot be reconciled" }
        return boundary.withLock { reconcileInsideBoundary(operation) }
    }

    private suspend fun reconcileInsideBoundary(operation: RecoveryOperation): RecoveryReport {
        val source = operation.sourceUri?.let { observe(it, operation.sourceStorageKey) }
        val target = observe(operation.targetUri, operation.targetStorageKey)
        return RecoveryReport(
            operation = operation,
            source = source,
            target = target,
            sourceNode = operation.sourceUri?.let { observeNode(it) },
            targetNode = observeNode(operation.targetUri),
        )
    }

    private suspend fun observe(
        uri: VfsUri,
        expectedStorageKey: String?,
    ): RecoveryResourceObservation {
        val route =
            router.route(uri.path)
                ?: return RecoveryResourceObservation(uri, RecoveryPresence.UNKNOWN, errorCode = VfsErrorCode.MOUNT_NOT_FOUND)
        if (expectedStorageKey == null || route.mount.storageKey != expectedStorageKey) {
            return RecoveryResourceObservation(uri, RecoveryPresence.UNKNOWN, errorCode = VfsErrorCode.CONFLICT)
        }
        val storage =
            storages(route.mount.storageKey)
                ?: return RecoveryResourceObservation(uri, RecoveryPresence.UNKNOWN, errorCode = VfsErrorCode.STATE_ERROR)
        return try {
            val attributes = storage.stat(route.relativePath)
            RecoveryResourceObservation(
                uri = uri,
                presence = RecoveryPresence.PRESENT,
                type = attributes.type,
                sizeBytes = attributes.sizeBytes,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: VfsException) {
            if (failure.code == VfsErrorCode.NOT_FOUND) {
                RecoveryResourceObservation(uri, RecoveryPresence.ABSENT)
            } else {
                RecoveryResourceObservation(uri, RecoveryPresence.UNKNOWN, errorCode = failure.code)
            }
        } catch (_: Exception) {
            RecoveryResourceObservation(uri, RecoveryPresence.UNKNOWN, errorCode = VfsErrorCode.STORAGE_ERROR)
        }
    }

    private suspend fun observeNode(uri: VfsUri): RecoveryNodeObservation =
        try {
            RecoveryNodeObservation(uri, nodes.findByPath(uri.path))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: VfsException) {
            RecoveryNodeObservation(uri, null, failure.code)
        } catch (_: Exception) {
            RecoveryNodeObservation(uri, null, VfsErrorCode.STATE_ERROR)
        }
}
