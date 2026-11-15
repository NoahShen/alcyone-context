package com.github.noahshen.alcyone.context.vfs.core.recovery

import java.time.Instant

/**
 * Recovery journal Port。begin / 阶段推进 / 诊断都使用短事务和 CAS；事务内的 CLOSED 写入走
 * [com.github.noahshen.alcyone.context.vfs.core.transaction.RecoveryCloseRepository]。
 */
interface RecoveryJournal {
    /** 持久化 generation=0 的请求阶段；失败时调用方不得发起对应外部写。 */
    suspend fun begin(operation: RecoveryOperation): RecoveryOperation

    /** 以 expectedGeneration 做 CAS，按状态机推进阶段。 */
    suspend fun advance(
        operationId: String,
        expectedGeneration: Long,
        nextPhase: RecoveryPhase,
        diagnosticCode: RecoveryDiagnosticCode = RecoveryDiagnosticCode.NONE,
        at: Instant,
    ): RecoveryOperation

    /** 不改阶段，只记录白名单诊断码；同样单调推进 generation。 */
    suspend fun diagnose(
        operationId: String,
        expectedGeneration: Long,
        diagnosticCode: RecoveryDiagnosticCode,
        at: Instant,
    ): RecoveryOperation

    suspend fun find(operationId: String): RecoveryOperation?

    suspend fun listPending(): List<RecoveryOperation>
}
