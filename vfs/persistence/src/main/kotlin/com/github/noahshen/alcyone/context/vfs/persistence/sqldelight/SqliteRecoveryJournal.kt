package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryDiagnosticCode
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryJournal
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryOperation
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryOperationKind
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryPhase
import java.time.Instant

/** SQLite recovery journal；每个独立阶段写入都是短事务，并以 generation + current phase 做 CAS。 */
class SqliteRecoveryJournal(
    private val state: VfsStateDatabase,
) : RecoveryJournal {
    private val queries = state.database.recoveryQueries

    override suspend fun begin(operation: RecoveryOperation): RecoveryOperation =
        stateCall {
            state.driver.inRawTransaction {
                queries.insertRecoveryOperation(
                    operation.operationId,
                    operation.kind.name,
                    operation.phase.name,
                    operation.generation,
                    operation.sourceUri?.toString(),
                    operation.targetUri.toString(),
                    operation.sourceStorageKey,
                    operation.targetStorageKey,
                    operation.nodeId?.value,
                    operation.diagnosticCode.name,
                    operation.createdAt.toEpochMilli(),
                    operation.updatedAt.toEpochMilli(),
                )
                queries
                    .selectRecoveryById(operation.operationId)
                    .executeAsOneOrNull()
                    ?.toRecoveryOperation()
                    ?: throw stateFailure("recovery operation ${operation.operationId} disappeared after insert")
            }
        }

    override suspend fun advance(
        operationId: String,
        expectedGeneration: Long,
        nextPhase: RecoveryPhase,
        diagnosticCode: RecoveryDiagnosticCode,
        at: Instant,
    ): RecoveryOperation =
        stateCall {
            state.driver.inRawTransaction {
                val current = requireCurrent(operationId, expectedGeneration)
                val next = current.transitionTo(nextPhase, diagnosticCode, at)
                val changed =
                    queries
                        .advanceRecoveryOperation(
                            next.phase.name,
                            next.generation,
                            next.diagnosticCode.name,
                            next.updatedAt.toEpochMilli(),
                            operationId,
                            current.generation,
                            current.phase.name,
                        ).value
                if (changed != 1L) throw stateFailure("recovery operation $operationId failed generation CAS")
                next
            }
        }

    override suspend fun diagnose(
        operationId: String,
        expectedGeneration: Long,
        diagnosticCode: RecoveryDiagnosticCode,
        at: Instant,
    ): RecoveryOperation =
        stateCall {
            state.driver.inRawTransaction {
                val current = requireCurrent(operationId, expectedGeneration)
                val next = current.withDiagnostic(diagnosticCode, at)
                val changed =
                    queries
                        .diagnoseRecoveryOperation(
                            next.generation,
                            next.diagnosticCode.name,
                            next.updatedAt.toEpochMilli(),
                            operationId,
                            current.generation,
                        ).value
                if (changed != 1L) throw stateFailure("recovery operation $operationId failed diagnostic CAS")
                next
            }
        }

    override suspend fun find(operationId: String): RecoveryOperation? =
        stateCall {
            queries.selectRecoveryById(operationId).executeAsOneOrNull()?.toRecoveryOperation()
        }

    override suspend fun listPending(): List<RecoveryOperation> =
        stateCall {
            queries.selectPendingRecoveries().executeAsList().map { it.toRecoveryOperation() }
        }

    private fun requireCurrent(
        operationId: String,
        expectedGeneration: Long,
    ): RecoveryOperation {
        val current =
            queries.selectRecoveryById(operationId).executeAsOneOrNull()?.toRecoveryOperation()
                ?: throw stateFailure("recovery operation $operationId does not exist")
        if (current.generation != expectedGeneration) {
            throw stateFailure("recovery operation $operationId generation changed before CAS")
        }
        return current
    }

    private fun stateFailure(message: String): VfsException = VfsException(VfsErrorCode.STATE_ERROR, message)
}

/** 持久行到 Core DTO 的唯一映射点。 */
internal fun Recovery_operation.toRecoveryOperation(): RecoveryOperation =
    RecoveryOperation(
        operationId = operation_id,
        kind = RecoveryOperationKind.valueOf(operation_kind),
        phase = RecoveryPhase.valueOf(phase),
        generation = generation,
        sourceUri = source_uri?.let(VfsUri::parse),
        targetUri = VfsUri.parse(target_uri),
        sourceStorageKey = source_storage_key,
        targetStorageKey = target_storage_key,
        nodeId = node_id?.let(NodeId::parse),
        diagnosticCode = RecoveryDiagnosticCode.valueOf(diagnostic_code),
        createdAt = Instant.ofEpochMilli(created_at),
        updatedAt = Instant.ofEpochMilli(updated_at),
    )
