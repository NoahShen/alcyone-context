package com.github.noahshen.alcyone.context.vfs.gitbackup

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmGitProcessRunnerTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("git-runner-test-")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `truncated output is flagged`() {
        val runner = JvmGitProcessRunner(maxOutputChars = 8)
        val result =
            runner.run(
                command = listOf("sh", "-c", "printf abcdefghijklmnopqrst"),
                workingDir = tempDir,
                timeout = Duration.ofSeconds(10),
            )
        assertEquals(0, result.exitCode)
        assertTrue(result.outputTruncated, "Output beyond the limit must be flagged as truncated")
        assertFalse(result.timedOut)
        assertFalse(result.cancelled)
        assertFalse(result.cleanupFailed)
    }

    @Test
    fun `large stdout and stderr are both drained`() {
        val runner = JvmGitProcessRunner(maxOutputChars = 1024)
        // 同时向 stdout 与 stderr 写入远超管道容量与保留上限的内容，验证双流并发排空不挂起。
        val result =
            runner.run(
                command = listOf("sh", "-c", "seq 1 200000; seq 1 200000 1>&2"),
                workingDir = tempDir,
                timeout = Duration.ofSeconds(30),
            )
        assertEquals(0, result.exitCode)
        assertTrue(result.outputTruncated)
        assertFalse(result.timedOut)
        assertFalse(result.cleanupFailed)
    }

    @Test
    fun `reader threads exit after successful command`() {
        val runner = JvmGitProcessRunner(maxOutputChars = 1024)
        val result =
            runner.run(
                command = listOf("sh", "-c", "seq 1 100000"),
                workingDir = tempDir,
                timeout = Duration.ofSeconds(30),
            )
        assertEquals(0, result.exitCode)
        assertTrue(waitUntilNoReaderThreads(Duration.ofSeconds(2)), "Reader threads must exit after run")
    }

    @Test
    fun `timeout kills process and descendants with verified pids`() {
        val parentPidFile = tempDir.resolve("parent.pid")
        val childPidFile = tempDir.resolve("child.pid")
        val script = "sleep 30 & echo \$! > '$childPidFile'; echo \$\$ > '$parentPidFile'; wait"

        val runner = JvmGitProcessRunner()
        try {
            val result =
                runner.run(
                    command = listOf("sh", "-c", script),
                    workingDir = tempDir,
                    timeout = Duration.ofMillis(500),
                )

            assertTrue(result.timedOut, "Long-running command must time out")
            assertFalse(result.cleanupFailed, "Cleanup must be confirmed for a killed process tree")

            val parentPid = readPid(parentPidFile)
            val childPid = readPid(childPidFile)
            assertNotNull(parentPid, "Parent pid file must be written")
            assertNotNull(childPid, "Child pid file must be written")
            assertTrue(waitUntilDead(parentPid), "Parent process must exit after cleanup")
            assertTrue(waitUntilDead(childPid), "Child process must exit after cleanup")
        } finally {
            killPid(readPid(parentPidFile))
            killPid(readPid(childPidFile))
        }
    }

    @Test
    fun `interrupt cancels running command and keeps cancellation semantics`() {
        val runner = JvmGitProcessRunner()
        var result: ProcessResult? = null

        val worker =
            Thread {
                result =
                    runner.run(
                        command = listOf("sleep", "30"),
                        workingDir = tempDir,
                        timeout = Duration.ofSeconds(30),
                    )
            }
        worker.start()
        try {
            Thread.sleep(400)
            worker.interrupt()
            worker.join(5_000)

            assertFalse(worker.isAlive, "Runner thread must exit after interruption")
            val outcome = result
            assertNotNull(outcome, "Runner must return a result")
            assertTrue(outcome.cancelled, "Interruption must be reported as cancelled")
            assertFalse(outcome.timedOut, "Interruption is not a timeout")
            assertFalse(outcome.cleanupFailed, "Killed process must be confirmed exited")
            assertTrue(worker.isInterrupted, "Interrupt flag must be restored after cleanup")
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
    }

    @Test
    fun `interrupt during output wait after parent exit is not lost`() {
        val childPidFile = tempDir.resolve("child.pid")
        // 父 shell 立即退出，后台 sleep 继承 stdout/stderr 管道并保持打开。
        val script = "sleep 30 & echo \$! > '$childPidFile'; exit 0"

        val runner = JvmGitProcessRunner()
        var result: ProcessResult? = null
        val worker =
            Thread {
                result =
                    runner.run(
                        command = listOf("sh", "-c", script),
                        workingDir = tempDir,
                        timeout = Duration.ofSeconds(10),
                    )
            }
        worker.start()
        try {
            assertTrue(waitForFile(childPidFile, Duration.ofSeconds(5)), "Child pid file must appear")
            Thread.sleep(200)
            val start = System.nanoTime()
            worker.interrupt()
            worker.join(5_000)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertFalse(worker.isAlive, "Runner thread must exit after interruption")
            val outcome = result
            assertNotNull(outcome, "Runner must return a result")
            assertTrue(outcome.cancelled, "Interrupt during output wait must be reported as cancelled")
            assertFalse(outcome.timedOut, "Interruption is not a timeout")
            assertTrue(elapsedMs < 5_000, "Must not keep waiting to the original deadline (elapsed=${elapsedMs}ms)")
            assertTrue(worker.isInterrupted, "Interrupt flag must be restored after cleanup")
            assertTrue(outcome.outputTruncated, "Closing a held pipe means output is not fully read")
            assertEquals(
                readerThreadsAlive(),
                outcome.cleanupFailed,
                "cleanupFailed must reflect whether reader threads were confirmed exited",
            )
        } finally {
            killPid(readPid(childPidFile))
            worker.interrupt()
            worker.join(5_000)
        }
    }

    @Test
    fun `parent exit with held pipe marks output incomplete`() {
        val childPidFile = tempDir.resolve("child.pid")
        val script = "sleep 30 & echo \$! > '$childPidFile'; exit 0"

        val runner = JvmGitProcessRunner()
        try {
            val result =
                runner.run(
                    command = listOf("sh", "-c", script),
                    workingDir = tempDir,
                    timeout = Duration.ofMillis(300),
                )

            assertTrue(result.outputTruncated, "Held pipe means output is not fully read")
            assertFalse(result.timedOut, "Parent exited before the deadline")
            assertFalse(result.cancelled)
            assertEquals(
                readerThreadsAlive(),
                result.cleanupFailed,
                "cleanupFailed must reflect whether reader threads were confirmed exited",
            )
        } finally {
            killPid(readPid(childPidFile))
        }
    }

    @Test
    fun `successful command reports complete output`() {
        val runner = JvmGitProcessRunner()
        val result =
            runner.run(
                command = listOf("sh", "-c", "printf hello"),
                workingDir = tempDir,
                timeout = Duration.ofSeconds(10),
            )
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertFalse(result.outputTruncated)
        assertFalse(result.timedOut)
        assertFalse(result.cancelled)
        assertFalse(result.cleanupFailed)
    }

    private fun readPid(file: Path): Long? =
        try {
            Files.readString(file).trim().toLongOrNull()
        } catch (e: Exception) {
            null
        }

    private fun waitForFile(
        file: Path,
        timeout: Duration,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) return true
            Thread.sleep(20)
        }
        return Files.exists(file)
    }

    private fun waitUntilDead(pid: Long): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (!alive) return true
            Thread.sleep(50)
        }
        return ProcessHandle
            .of(pid)
            .map { it.isAlive }
            .orElse(false)
            .not()
    }

    private fun killPid(pid: Long?) {
        if (pid != null) {
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }

    private fun waitUntilNoReaderThreads(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (!readerThreadsAlive()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun readerThreadsAlive(): Boolean =
        Thread.getAllStackTraces().keys.any {
            it.isAlive && (it.name == "git-stdout-drain" || it.name == "git-stderr-drain")
        }
}
