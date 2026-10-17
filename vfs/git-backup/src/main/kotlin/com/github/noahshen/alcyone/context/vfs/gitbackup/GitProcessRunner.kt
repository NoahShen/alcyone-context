package com.github.noahshen.alcyone.context.vfs.gitbackup

import java.nio.file.Path
import java.time.Duration

/**
 * Git 进程执行契约。
 */
internal interface GitProcessRunner {
    fun run(
        command: List<String>,
        workingDir: Path,
        timeout: Duration,
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessResult
}

/**
 * 进程执行结果。
 *
 * @param exitCode 进程退出码；进程未退出时通常为 -1。
 * @param stdout 标准输出（截断保留）。
 * @param stderr 标准错误（截断保留）。
 * @param timedOut 是否超过截止时间。
 * @param cancelled 是否因线程中断/取消被中止。
 * @param cleanupFailed 销毁进程时无法确认进程及其衍生进程完全退出，则置为 true。
 * @param outputTruncated 输出是否被截断或未完整读取（保留上限、读取未在截止时间内完成、读取异常）。
 */
internal data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val cleanupFailed: Boolean = false,
    val outputTruncated: Boolean = false,
)
