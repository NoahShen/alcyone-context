package com.github.noahshen.alcyone.context.vfs.gitbackup

import java.time.Duration

/**
 * 同步执行阶段。
 */
internal enum class SyncStage {
    VALIDATE,
    STAGE,
    COMMIT,
    PUSH,
}

/**
 * 单次备份同步结果。
 */
internal sealed interface SyncResult {
    /**
     * 同步成功。
     *
     * @param commitCreated 本轮是否新建了 commit。
     * @param commitSha 最新 HEAD 的 commit SHA。
     * @param pushed 是否执行了 push（在存在提交的前提下）。
     * @param duration 耗时。
     */
    data class Success(
        val commitCreated: Boolean,
        val commitSha: String,
        val pushed: Boolean,
        val duration: Duration,
    ) : SyncResult

    /**
     * 空仓库无内容可同步，跳过本次同步。
     */
    data class SkippedNoContent(
        val duration: Duration,
    ) : SyncResult

    /**
     * 同步失败。
     *
     * @param stage 失败阶段。
     * @param message 受控错误码（[GitSecurityError] 名称），不含任何外部原始输出。
     * @param duration 耗时。
     * @param cancelled 是否因线程中断/取消而中止；取消后不会再进入后续阶段。
     */
    data class Failed(
        val stage: SyncStage,
        val message: String,
        val duration: Duration,
        val cancelled: Boolean = false,
    ) : SyncResult
}
