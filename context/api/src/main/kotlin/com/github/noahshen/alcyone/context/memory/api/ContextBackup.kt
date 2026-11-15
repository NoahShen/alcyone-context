package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant

/** 已发布的快照，不包含本地临时目录、密码或后端路径。 */
data class ContextBackupResult(
    val uri: String,
    val sizeBytes: Long,
    val completedAt: Instant,
    val quickCheck: List<QuickCheckResult> = emptyList(),
)

data class QuickCheckResult(
    val phase: QuickCheckPhase,
    val ok: Boolean,
)

enum class QuickCheckPhase { PRE_PUBLISH, POST_PUBLISH }

enum class ContextBackupFailureStage { PRE_CHECK, SNAPSHOT, PUBLISH, POST_CHECK }

/** 进程内快照；旧执行状态不会被新配置意外沿用。 */
data class ContextBackupStatus(
    val state: WorkerState,
    val lastUri: String? = null,
    val lastCompletedAt: Instant? = null,
    val lastFailureStage: ContextBackupFailureStage? = null,
    val lastError: String? = null,
)

enum class ContextBackupErrorCode { UNAVAILABLE, ALREADY_RUNNING, CLOSED, NOT_STARTED, QUICK_CHECK_FAILED, PUBLISH_FAILED }

class ContextBackupException(
    val code: ContextBackupErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
