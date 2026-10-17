package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 生产接线（[RepositorySyncExecutor] → [GitRepository]）的日志与取消行为验证。
 *
 * 运行日志唯一责任层是 [GitBackupWorker]：每轮恰好一条开始与一条终结。
 */
class GitBackupLogCaptureTest {
    private lateinit var tempDir: Path
    private lateinit var repoDir: Path
    private lateinit var remoteDir: Path
    private val runner = JvmGitProcessRunner()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("git-backup-log-")
        repoDir = tempDir.resolve("repo")
        remoteDir = tempDir.resolve("remote.git")
        Files.createDirectories(repoDir)
        runner.run(listOf("git", "init", "--bare", remoteDir.toString()), tempDir, Duration.ofSeconds(10))
        runner.run(listOf("git", "init", "-b", "main", repoDir.toString()), tempDir, Duration.ofSeconds(10))
        runner.run(listOf("git", "config", "user.name", "Test User"), repoDir, Duration.ofSeconds(5))
        runner.run(listOf("git", "config", "user.email", "test@example.com"), repoDir, Duration.ofSeconds(5))
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `production chain logs exactly one start and one terminal without credentials`() {
        val password = "T34_SYNTHETIC_PASSWORD"
        // 单一有效 push URL，但指向不可达端口，push 会失败。
        val remoteUrl = "http://fixture:$password@127.0.0.1:1/repo.git"
        runner.run(listOf("git", "remote", "add", "origin", remoteUrl), repoDir, Duration.ofSeconds(5))
        Files.writeString(repoDir.resolve("data.txt"), "content")

        val config =
            GitBackupConfig(
                repositoryRoot = repoDir,
                eventRoots = setOf(VfsUri.parse("alcyone://notes")),
                remoteName = "origin",
                branch = "main",
                pushTimeout = Duration.ofSeconds(5),
            )
        val logger = RecordingLogger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker =
            GitBackupWorker(
                config = config,
                clock = Clock.systemDefaultZone(),
                scope = scope,
                executor = RepositorySyncExecutor(GitRepository(config)),
                logger = logger,
            )
        try {
            worker.start()
            assertTrue(awaitTerminal(logger, Duration.ofSeconds(30)), "Run must produce a terminal log")

            val messages = logger.messages()
            assertEquals(1, messages.count { it.contains("Git backup started") }, messages.toString())
            assertEquals(1, terminalMessages(messages).size, messages.toString())
            assertTrue(
                messages.any { it.contains(GitSecurityError.PUSH_PUSH_FAILED.name) },
                "Terminal log must contain the controlled error code: $messages",
            )
            for (message in messages) {
                assertFalse(message.contains(password), "Log must never contain the credential: $message")
                assertFalse(message.contains(remoteUrl), "Log must never contain the raw remote URL: $message")
            }
        } finally {
            runBlocking { worker.shutdown() }
            scope.cancel()
        }
    }

    @Test
    fun `real repository executor cancellation stops the loop and logs stage and duration`() =
        runBlocking {
            val blockingRunner = BlockingGitProcessRunner()
            val config =
                GitBackupConfig(
                    repositoryRoot = repoDir,
                    eventRoots = setOf(VfsUri.parse("alcyone://notes")),
                    branch = "main",
                )
            val logger = RecordingLogger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val worker =
                GitBackupWorker(
                    config = config,
                    clock = Clock.systemDefaultZone(),
                    scope = scope,
                    executor = RepositorySyncExecutor(GitRepository(config, blockingRunner)),
                    logger = logger,
                )
            try {
                worker.start()
                assertTrue(blockingRunner.awaitStarted(Duration.ofSeconds(5)), "Blocking runner must start")

                scope.cancel()
                scope.coroutineContext[Job]?.join()

                val messages = logger.messages()
                assertTrue(
                    messages.any {
                        it.contains("Git backup cancelled") && it.contains("stage=") && it.contains("durationMs=")
                    },
                    "Cancellation must log stage and duration: $messages",
                )
                assertEquals(1, messages.count { it.contains("Git backup started") }, messages.toString())
            } finally {
                scope.cancel()
            }
        }

    private fun awaitTerminal(
        logger: RecordingLogger,
        timeout: Duration,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (terminalMessages(logger.messages()).isNotEmpty()) return true
            Thread.sleep(50)
        }
        return terminalMessages(logger.messages()).isNotEmpty()
    }

    private fun terminalMessages(messages: List<String>): List<String> =
        messages.filter {
            it.contains("Git backup completed") ||
                it.contains("Git backup skipped") ||
                it.contains("Git backup failed") ||
                it.contains("Git backup cancelled")
        }

    private class BlockingGitProcessRunner : GitProcessRunner {
        private val started = CountDownLatch(1)

        override fun run(
            command: List<String>,
            workingDir: Path,
            timeout: Duration,
            extraEnv: Map<String, String>,
        ): ProcessResult {
            started.countDown()
            try {
                Thread.sleep(60_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return ProcessResult(exitCode = -1, stdout = "", stderr = "", cancelled = true)
            }
            return ProcessResult(exitCode = 0, stdout = "", stderr = "")
        }

        fun awaitStarted(timeout: Duration): Boolean = started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
