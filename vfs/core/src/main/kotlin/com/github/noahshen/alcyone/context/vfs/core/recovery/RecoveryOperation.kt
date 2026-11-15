package com.github.noahshen.alcyone.context.vfs.core.recovery

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.time.Instant

/** 当前恢复范围只覆盖已经批准的四个文件操作。 */
enum class RecoveryOperationKind {
    SAFE_REPLACE,
    PUBLISH_NEW,
    NATIVE_FILE_MOVE,
    COPY_FILE_MOVE,
}

/** 持久阶段表达已经观察到的事实，不把未确认的失败推断成成功或回滚。 */
enum class RecoveryPhase {
    STORAGE_REPLACE_REQUESTED,
    STORAGE_REPLACE_OBSERVED,
    PUBLISH_REQUESTED,
    PUBLISH_OBSERVED,
    NATIVE_MOVE_REQUESTED,
    NATIVE_MOVE_OBSERVED,
    TARGET_WRITE_REQUESTED,
    TARGET_WRITE_OBSERVED,
    SOURCE_DELETE_REQUESTED,
    SOURCE_DELETE_OBSERVED,
    CLOSED,
}

/** 可持久化诊断码白名单；异常正文、后端响应和凭据不进入 journal。 */
enum class RecoveryDiagnosticCode {
    NONE,
    STORAGE_FAILURE,
    STATE_COMMIT_FAILURE,
    CANCELLED,
    RECONCILE_REQUIRED,
}

/**
 * 一条操作的持久事实记录。generation 从 0 单调增加，每次成功写入都做 expected-generation CAS。
 * CLOSED 是终态，之后任何阶段或诊断更新都必须失败。
 */
data class RecoveryOperation(
    val operationId: String,
    val kind: RecoveryOperationKind,
    val phase: RecoveryPhase,
    val generation: Long,
    val sourceUri: VfsUri?,
    val targetUri: VfsUri,
    val sourceStorageKey: String?,
    val targetStorageKey: String,
    val nodeId: NodeId?,
    val diagnosticCode: RecoveryDiagnosticCode,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(generation >= 0) { "generation must not be negative" }
        require(kind.initialPhase == phase || generation > 0 || phase == kind.initialPhase) {
            "a new ${kind.name} operation must start at ${kind.initialPhase}"
        }
        require(phase != RecoveryPhase.CLOSED || diagnosticCode == RecoveryDiagnosticCode.NONE) {
            "a closed operation cannot retain a pending diagnostic"
        }
    }

    /** 返回一次合法迁移的新值；持久层仍必须用 [generation] 作为 CAS 条件。 */
    fun transitionTo(
        nextPhase: RecoveryPhase,
        diagnosticCode: RecoveryDiagnosticCode = RecoveryDiagnosticCode.NONE,
        at: Instant,
    ): RecoveryOperation {
        check(phase != RecoveryPhase.CLOSED) { "closed recovery operation cannot transition" }
        check(nextPhase in phase.allowedNextPhases) { "illegal recovery transition: $phase -> $nextPhase" }
        check(nextPhase != RecoveryPhase.CLOSED || diagnosticCode == RecoveryDiagnosticCode.NONE) {
            "closing an operation clears the pending diagnostic"
        }
        return copy(
            phase = nextPhase,
            generation = generation + 1,
            diagnosticCode = diagnosticCode,
            updatedAt = at,
        )
    }

    /** 仅更新白名单诊断码，阶段事实保持不变。 */
    fun withDiagnostic(
        diagnosticCode: RecoveryDiagnosticCode,
        at: Instant,
    ): RecoveryOperation {
        check(phase != RecoveryPhase.CLOSED) { "closed recovery operation is immutable" }
        return copy(
            generation = generation + 1,
            diagnosticCode = diagnosticCode,
            updatedAt = at,
        )
    }

    companion object {
        /** 新操作 generation=0，阶段由 kind 唯一确定。 */
        fun begin(
            operationId: String,
            kind: RecoveryOperationKind,
            sourceUri: VfsUri?,
            targetUri: VfsUri,
            sourceStorageKey: String?,
            targetStorageKey: String,
            nodeId: NodeId?,
            at: Instant,
        ): RecoveryOperation =
            RecoveryOperation(
                operationId = operationId,
                kind = kind,
                phase = kind.initialPhase,
                generation = 0,
                sourceUri = sourceUri,
                targetUri = targetUri,
                sourceStorageKey = sourceStorageKey,
                targetStorageKey = targetStorageKey,
                nodeId = nodeId,
                diagnosticCode = RecoveryDiagnosticCode.NONE,
                createdAt = at,
                updatedAt = at,
            )
    }
}

internal val RecoveryOperationKind.initialPhase: RecoveryPhase
    get() =
        when (this) {
            RecoveryOperationKind.SAFE_REPLACE -> RecoveryPhase.STORAGE_REPLACE_REQUESTED
            RecoveryOperationKind.PUBLISH_NEW -> RecoveryPhase.PUBLISH_REQUESTED
            RecoveryOperationKind.NATIVE_FILE_MOVE -> RecoveryPhase.NATIVE_MOVE_REQUESTED
            RecoveryOperationKind.COPY_FILE_MOVE -> RecoveryPhase.TARGET_WRITE_REQUESTED
        }

internal val RecoveryPhase.allowedNextPhases: Set<RecoveryPhase>
    get() =
        when (this) {
            RecoveryPhase.STORAGE_REPLACE_REQUESTED -> setOf(RecoveryPhase.STORAGE_REPLACE_OBSERVED)
            RecoveryPhase.STORAGE_REPLACE_OBSERVED -> setOf(RecoveryPhase.CLOSED)
            RecoveryPhase.PUBLISH_REQUESTED -> setOf(RecoveryPhase.PUBLISH_OBSERVED)
            RecoveryPhase.PUBLISH_OBSERVED -> setOf(RecoveryPhase.CLOSED)
            RecoveryPhase.NATIVE_MOVE_REQUESTED -> setOf(RecoveryPhase.NATIVE_MOVE_OBSERVED)
            RecoveryPhase.NATIVE_MOVE_OBSERVED -> setOf(RecoveryPhase.CLOSED)
            RecoveryPhase.TARGET_WRITE_REQUESTED -> setOf(RecoveryPhase.TARGET_WRITE_OBSERVED)
            RecoveryPhase.TARGET_WRITE_OBSERVED -> setOf(RecoveryPhase.SOURCE_DELETE_REQUESTED)
            RecoveryPhase.SOURCE_DELETE_REQUESTED -> setOf(RecoveryPhase.SOURCE_DELETE_OBSERVED)
            RecoveryPhase.SOURCE_DELETE_OBSERVED -> setOf(RecoveryPhase.CLOSED)
            RecoveryPhase.CLOSED -> emptySet()
        }

internal fun illegalRecoveryTransition(operation: RecoveryOperation): VfsException =
    VfsException(
        VfsErrorCode.STATE_ERROR,
        "recovery operation ${operation.operationId} cannot advance from ${operation.phase}",
    )
