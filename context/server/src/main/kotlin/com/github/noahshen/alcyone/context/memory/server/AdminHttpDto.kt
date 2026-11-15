package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AdminBackupStatus
import com.github.noahshen.alcyone.context.memory.api.AdminDreamingStatus
import com.github.noahshen.alcyone.context.memory.api.AdminLlmTaskUsage
import com.github.noahshen.alcyone.context.memory.api.AdminPendingStatus
import com.github.noahshen.alcyone.context.memory.api.AdminWorkerStatus
import com.github.noahshen.alcyone.context.memory.api.AdminWorkers
import com.github.noahshen.alcyone.context.memory.api.ContextAdminStatus
import com.github.noahshen.alcyone.context.memory.api.ContextBackupResult
import com.github.noahshen.alcyone.context.memory.api.DreamingResult
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.QuickCheckResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Successful bounded Dreaming run; no partial failure payload is exposed. */
@Serializable
internal data class DreamingRunResponse(
    @SerialName("start_checkpoint") val startCheckpoint: Long,
    @SerialName("end_checkpoint") val endCheckpoint: Long,
    @SerialName("scanned_turns") val scannedTurns: Int,
    @SerialName("discovered_topics") val discoveredTopics: Int,
    @SerialName("generated_memories") val generatedMemories: Int,
    val completed: Boolean,
) {
    companion object {
        fun from(result: DreamingResult): DreamingRunResponse =
            DreamingRunResponse(
                result.startCheckpoint,
                result.endCheckpoint,
                result.scannedTurns,
                result.discoveredTopics,
                result.generatedMemories,
                result.completed,
            )
    }
}

@Serializable
internal data class BackupRunResponse(
    val uri: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("completed_at") val completedAt: String,
    @SerialName("quick_check") val quickCheck: List<QuickCheckResponse>,
) {
    companion object {
        fun from(result: ContextBackupResult): BackupRunResponse =
            BackupRunResponse(
                result.uri,
                result.sizeBytes,
                result.completedAt.toString(),
                result.quickCheck.map(QuickCheckResponse::from),
            )
    }
}

@Serializable
internal data class QuickCheckResponse(
    val phase: String,
    val ok: Boolean,
) {
    companion object {
        fun from(result: QuickCheckResult): QuickCheckResponse = QuickCheckResponse(result.phase.name, result.ok)
    }
}

/** Explicit safe wire projection: no exception, storage path, model or backend fields. */
@Serializable
internal data class AdminStatusResponse(
    val lifecycle: String,
    val workers: AdminWorkersResponse? = null,
    val pending: AdminPendingResponse? = null,
    val dreaming: AdminDreamingResponse? = null,
    @SerialName("map_status") val mapStatus: MapStatus? = null,
    val llm: List<AdminLlmUsageResponse>? = null,
    val backup: AdminBackupResponse? = null,
) {
    companion object {
        fun from(status: ContextAdminStatus): AdminStatusResponse =
            AdminStatusResponse(
                lifecycle = status.lifecycle.name,
                workers = status.workers?.let(AdminWorkersResponse::from),
                pending = status.pending?.let(AdminPendingResponse::from),
                dreaming = status.dreaming?.let(AdminDreamingResponse::from),
                mapStatus = status.mapStatus,
                llm = status.llm?.map(AdminLlmUsageResponse::from),
                backup = status.backup?.let(AdminBackupResponse::from),
            )
    }
}

@Serializable
internal data class AdminWorkersResponse(
    val extraction: AdminWorkerResponse? = null,
    val organization: AdminWorkerResponse? = null,
    @SerialName("memory_map") val memoryMap: AdminWorkerResponse? = null,
    val dreaming: AdminWorkerResponse? = null,
) {
    companion object {
        fun from(workers: AdminWorkers): AdminWorkersResponse =
            AdminWorkersResponse(
                extraction = workers.extraction?.let(AdminWorkerResponse::from),
                organization = workers.organization?.let(AdminWorkerResponse::from),
                memoryMap = workers.memoryMap?.let(AdminWorkerResponse::from),
                dreaming = workers.dreaming?.let(AdminWorkerResponse::from),
            )
    }
}

@Serializable
internal data class AdminWorkerResponse(
    val state: String? = null,
    @SerialName("last_failure") val lastFailure: Boolean? = null,
) {
    companion object {
        fun from(worker: AdminWorkerStatus): AdminWorkerResponse = AdminWorkerResponse(worker.state?.name, worker.lastFailure)
    }
}

@Serializable
internal data class AdminPendingResponse(
    val count: Int,
) {
    companion object {
        fun from(pending: AdminPendingStatus): AdminPendingResponse = AdminPendingResponse(pending.count)
    }
}

@Serializable
internal data class AdminDreamingResponse(
    @SerialName("last_processed_turn_id") val lastProcessedTurnId: Long,
) {
    companion object {
        fun from(dreaming: AdminDreamingStatus): AdminDreamingResponse = AdminDreamingResponse(dreaming.lastProcessedTurnId)
    }
}

@Serializable
internal data class AdminBackupResponse(
    val state: String,
    @SerialName("last_uri") val lastUri: String? = null,
    @SerialName("last_completed_at") val lastCompletedAt: String? = null,
    @SerialName("last_failure_stage") val lastFailureStage: String? = null,
) {
    companion object {
        fun from(backup: AdminBackupStatus): AdminBackupResponse =
            AdminBackupResponse(
                state = backup.state.name,
                lastUri = backup.lastUri,
                lastCompletedAt = backup.lastCompletedAt?.toString(),
                lastFailureStage = backup.lastFailureStage?.name,
            )
    }
}

@Serializable
internal data class AdminLlmUsageResponse(
    val task: String,
    val profile: String,
    val calls: Long,
    val successes: Long,
    val failures: Long,
    val attempts: Long,
    @SerialName("usage_present") val usagePresent: Long,
    @SerialName("usage_missing") val usageMissing: Long,
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
) {
    companion object {
        fun from(usage: AdminLlmTaskUsage): AdminLlmUsageResponse =
            AdminLlmUsageResponse(
                task = usage.task,
                profile = usage.profile,
                calls = usage.calls,
                successes = usage.successes,
                failures = usage.failures,
                attempts = usage.attempts,
                usagePresent = usage.usagePresent,
                usageMissing = usage.usageMissing,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                totalTokens = usage.totalTokens,
            )
    }
}
