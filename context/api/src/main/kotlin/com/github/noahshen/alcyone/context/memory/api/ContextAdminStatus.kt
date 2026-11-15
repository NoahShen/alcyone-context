package com.github.noahshen.alcyone.context.memory.api

/**
 * Process-local administrative facts. Null means unknown or not configured; an empty list means
 * that the configured LLM client has not recorded any calls.
 */
class ContextAdminStatus(
    val lifecycle: ContextLifecycleState,
    val workers: AdminWorkers? = null,
    val pending: AdminPendingStatus? = null,
    val dreaming: AdminDreamingStatus? = null,
    val mapStatus: MapStatus? = null,
    llm: List<AdminLlmTaskUsage>? = null,
    val backup: AdminBackupStatus? = null,
) {
    val llm: List<AdminLlmTaskUsage>? = llm?.toList()
}

/** Stable worker slots; a null slot means that its capability is not configured. */
data class AdminWorkers(
    val extraction: AdminWorkerStatus? = null,
    val organization: AdminWorkerStatus? = null,
    val memoryMap: AdminWorkerStatus? = null,
    val dreaming: AdminWorkerStatus? = null,
)

data class AdminWorkerStatus(
    val state: WorkerState? = null,
    val lastFailure: Boolean? = null,
)

data class AdminPendingStatus(
    val count: Int,
)

data class AdminDreamingStatus(
    val lastProcessedTurnId: Long,
)

/** Backup process-local facts without exception class/message or backend connection details. */
data class AdminBackupStatus(
    val state: WorkerState,
    val lastUri: String? = null,
    val lastCompletedAt: java.time.Instant? = null,
    val lastFailureStage: ContextBackupFailureStage? = null,
)

data class AdminLlmTaskUsage(
    val task: String,
    val profile: String,
    val calls: Long,
    val successes: Long,
    val failures: Long,
    val attempts: Long,
    val usagePresent: Long,
    val usageMissing: Long,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
)
