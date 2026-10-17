package com.github.noahshen.alcyone.context.vfs.gitbackup

import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 基于 JDK [ProcessBuilder] / [Process] 的标准 GitProcessRunner 实现。
 *
 * 设计要点：
 * - 参数列表直接启动，严禁拼接 shell；
 * - 仅清理会重定向仓库定位的环境变量（`GIT_DIR` / `GIT_WORK_TREE` / `GIT_INDEX_FILE`），
 *   以及非交互设置（`GIT_TERMINAL_PROMPT=0`、默认 `GIT_SSH_COMMAND`）；不擅自清除宿主的
 *   作者/提交身份变量，也不强加 `GIT_CONFIG_NOSYSTEM`；
 * - 异步双流排空并限量保留；记录截断/读取异常；
 * - 进程等待与双流读取受同一截止时间约束；一旦发生中断，改用有限的确认预算，不再等待到原截止时间；
 * - 主线程在 finally 关闭流，并在有限预算内确认读取线程退出；无法确认则 `cleanupFailed = true`；
 * - 超时、取消、或父进程正常退出但读取线程仍阻塞时，级联清理本进程及其 descendants；
 *   父进程已退出后 descendants 可能不可见，此时以读取线程是否确认为准，不把空枚举当作已确认；
 * - 中断事实在全路径统一记录，清理全部完成后才恢复中断位并返回取消。
 */
internal class JvmGitProcessRunner(
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
) : GitProcessRunner {
    override fun run(
        command: List<String>,
        workingDir: Path,
        timeout: Duration,
        extraEnv: Map<String, String>,
    ): ProcessResult {
        require(command.isNotEmpty()) { "Command list must not be empty" }

        val processBuilder = ProcessBuilder(command).directory(workingDir.toFile())
        val env = processBuilder.environment()
        // 只清理会重定向仓库定位的变量，保持宿主身份与认证环境。
        env.remove("GIT_DIR")
        env.remove("GIT_WORK_TREE")
        env.remove("GIT_INDEX_FILE")
        env["GIT_TERMINAL_PROMPT"] = "0"
        if (!extraEnv.containsKey("GIT_SSH_COMMAND")) {
            env["GIT_SSH_COMMAND"] = "ssh -o BatchMode=yes"
        }
        env.putAll(extraEnv)

        val process =
            try {
                processBuilder.start()
            } catch (e: IOException) {
                return ProcessResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "process start failed",
                    timedOut = false,
                    cancelled = false,
                    cleanupFailed = false,
                    outputTruncated = false,
                )
            }

        val deadlineNanos = System.nanoTime() + timeout.toNanos()

        val stdoutCollector = StreamCollector(maxOutputChars)
        val stderrCollector = StreamCollector(maxOutputChars)
        val stdoutThread = daemonThread("git-stdout-drain") { drain(process.inputStream, stdoutCollector) }
        val stderrThread = daemonThread("git-stderr-drain") { drain(process.errorStream, stderrCollector) }

        val state = RunState()
        var timedOut = false
        var cleanupFailed = false
        var stdoutJoined = false
        var stderrJoined = false
        var requiredClose = false

        try {
            // 阶段 A：等待进程到截止时间。
            try {
                val remainingNanos = deadlineNanos - System.nanoTime()
                val finished =
                    if (remainingNanos <= 0) {
                        false
                    } else {
                        process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)
                    }
                if (!finished) {
                    timedOut = true
                }
            } catch (e: InterruptedException) {
                markInterrupted(state)
            } catch (e: CancellationException) {
                state.cancelled = true
            }

            // 阶段 B：超时或取消时级联清理进程树。
            if (timedOut || state.cancelled) {
                cleanupFailed = cleanUpProcess(process, state)
            }

            // 阶段 C：确认读取线程。中断后不再等待到原截止时间，改用有限确认预算。
            val readerDeadline =
                if (timedOut || state.cancelled) {
                    System.nanoTime() + nanos(CONFIRM_TIMEOUT_MS)
                } else {
                    deadlineNanos
                }
            stdoutJoined = joinThread(stdoutThread, readerDeadline, state)
            val stderrDeadline =
                if (state.cancelled) {
                    System.nanoTime() + nanos(CONFIRM_TIMEOUT_MS)
                } else {
                    readerDeadline
                }
            stderrJoined = joinThread(stderrThread, stderrDeadline, state)

            if (!stdoutJoined || !stderrJoined) {
                // 读取未确认：尽力清理进程树（父进程已退出时 descendants 可能不可见）。
                cleanupFailed = cleanUpProcess(process, state) || cleanupFailed
            }

            // 若需靠关闭流来收尾，则输出可能不完整；这与“资源是否确认”分开记录。
            requiredClose = !stdoutJoined || !stderrJoined
        } finally {
            // 主线程负责关闭所有管道，并把关闭也纳入有界确认策略。
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }

            val confirmDeadline = System.nanoTime() + nanos(CONFIRM_TIMEOUT_MS)
            if (!stdoutJoined) {
                stdoutJoined = joinThread(stdoutThread, confirmDeadline, state)
            }
            if (!stderrJoined) {
                stderrJoined = joinThread(stderrThread, confirmDeadline, state)
            }
            if (!stdoutJoined || !stderrJoined) {
                // 读取任务无法在有限预算内确认退出：标记清理失败，由上层阻断后续命令。
                cleanupFailed = true
            }
            if (state.interrupted) {
                Thread.currentThread().interrupt()
            }
        }

        val outputTruncated =
            stdoutCollector.truncated ||
                stderrCollector.truncated ||
                stdoutCollector.ioFailed ||
                stderrCollector.ioFailed ||
                requiredClose

        val exitCode =
            if (!process.isAlive) {
                try {
                    process.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
            } else {
                -1
            }

        return ProcessResult(
            exitCode = exitCode,
            stdout = stdoutCollector.text(),
            stderr = stderrCollector.text(),
            timedOut = timedOut,
            cancelled = state.cancelled,
            cleanupFailed = cleanupFailed,
            outputTruncated = outputTruncated,
        )
    }

    /**
     * 级联清理本进程及其所有 descendants，返回是否清理失败（存在无法确认退出的进程）。
     *
     * descendants 枚举失败或 isAlive 查询失败都视为“无法确认”，返回 true。
     * 父进程已退出时 descendants 可能为空，这只是“无当前可追踪子进程”，
     * 并不代表此前派生的不可追踪进程已退出；该边界由读取线程确认与上层阻断兜底。
     */
    private fun cleanUpProcess(
        process: Process,
        state: RunState,
    ): Boolean {
        val parent = process.toHandle()
        val descendants: List<ProcessHandle> =
            try {
                parent.descendants().toList()
            } catch (e: Exception) {
                logger.error("Failed to enumerate descendants for pid={}", parent.pid())
                return true
            }

        descendants.forEach { runCatching { it.destroy() } }
        runCatching { parent.destroy() }

        // 父进程与每个子进程分别做独立的有界等待。
        waitForExit(parent, GRACEFUL_SHUTDOWN_TIMEOUT_MS, state)
        descendants.forEach { waitForExit(it, GRACEFUL_SHUTDOWN_TIMEOUT_MS, state) }

        if (aliveOrUncertain(parent) || descendants.any { aliveOrUncertain(it) }) {
            descendants.forEach { runCatching { it.destroyForcibly() } }
            runCatching { parent.destroyForcibly() }
            waitForExit(parent, FORCEFUL_SHUTDOWN_TIMEOUT_MS, state)
            descendants.forEach { waitForExit(it, FORCEFUL_SHUTDOWN_TIMEOUT_MS, state) }
        }

        val parentAlive = aliveOrUncertain(parent)
        val survivor = descendants.any { aliveOrUncertain(it) }
        if (parentAlive || survivor) {
            logger.error("Failed to confirm git process cleanup; pid={}", parent.pid())
            return true
        }
        return false
    }

    /**
     * `isAlive` 查询失败时不能当作已退出，返回 true 表示“存活或无法确认”。
     */
    private fun aliveOrUncertain(handle: ProcessHandle): Boolean =
        try {
            handle.isAlive
        } catch (e: Exception) {
            true
        }

    private fun waitForExit(
        handle: ProcessHandle,
        millis: Long,
        state: RunState,
    ) {
        try {
            handle.onExit().get(millis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // 清理期间的中断也要记录，不能吞掉；中断位在全部清理结束后统一恢复。
            markInterrupted(state)
        } catch (e: Exception) {
            // 超时或已退出：由调用方通过 isAlive 确认。
        }
    }

    /**
     * 等待读取线程退出；返回 true 表示线程确实已退出。
     * 捕获 InterruptedException 时记录取消事实，不在此处恢复中断位。
     */
    private fun joinThread(
        thread: Thread,
        deadlineNanos: Long,
        state: RunState,
    ): Boolean {
        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
        val waitMs = maxOf(1L, remainingMs)
        return try {
            thread.join(waitMs)
            !thread.isAlive
        } catch (e: InterruptedException) {
            markInterrupted(state)
            !thread.isAlive
        }
    }

    private fun markInterrupted(state: RunState) {
        state.cancelled = true
        state.interrupted = true
    }

    private fun nanos(millis: Long): Long = millis * 1_000_000

    private fun daemonThread(
        name: String,
        body: () -> Unit,
    ): Thread =
        Thread(body, name).apply {
            isDaemon = true
            start()
        }

    private fun drain(
        stream: InputStream,
        collector: StreamCollector,
    ) {
        try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    collector.append(buffer, read)
                }
            }
        } catch (e: IOException) {
            collector.markIoFailed()
        } catch (e: RuntimeException) {
            collector.markIoFailed()
        }
    }

    private class StreamCollector(
        private val maxChars: Int,
    ) {
        private val buffer = StringBuilder()

        @Volatile
        var truncated: Boolean = false
            private set

        @Volatile
        var ioFailed: Boolean = false
            private set

        fun append(
            chars: CharArray,
            length: Int,
        ) {
            synchronized(this) {
                if (buffer.length >= maxChars) {
                    truncated = true
                    return
                }
                val remaining = maxChars - buffer.length
                if (length > remaining) {
                    buffer.append(chars, 0, remaining)
                    truncated = true
                } else {
                    buffer.append(chars, 0, length)
                }
            }
        }

        fun markIoFailed() {
            ioFailed = true
        }

        fun text(): String = synchronized(this) { buffer.toString() }
    }

    private class RunState {
        var cancelled: Boolean = false
        var interrupted: Boolean = false
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JvmGitProcessRunner::class.java)
        private const val DEFAULT_MAX_OUTPUT_CHARS = 64 * 1024 // 64 KB
        private const val DEFAULT_BUFFER_SIZE = 4096
        private const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 500L
        private const val FORCEFUL_SHUTDOWN_TIMEOUT_MS = 1000L
        private const val CONFIRM_TIMEOUT_MS = 500L
    }
}
