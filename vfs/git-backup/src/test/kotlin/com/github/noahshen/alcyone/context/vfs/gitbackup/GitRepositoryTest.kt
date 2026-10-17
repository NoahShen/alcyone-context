package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitRepositoryTest {
    private lateinit var tempDir: Path
    private lateinit var repoDir: Path
    private lateinit var remoteDir: Path
    private val runner = JvmGitProcessRunner()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("git-backup-test-")
        repoDir = tempDir.resolve("repo")
        remoteDir = tempDir.resolve("remote.git")
        Files.createDirectories(repoDir)

        val initRemote =
            runner.run(
                command = listOf("git", "init", "--bare", remoteDir.toString()),
                workingDir = tempDir,
                timeout = Duration.ofSeconds(10),
            )
        assertEquals(0, initRemote.exitCode, "Failed to init bare remote")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun git(
        command: List<String>,
        dir: Path = repoDir,
    ) = runner.run(command, dir, Duration.ofSeconds(10))

    private fun initLocalRepo(
        branch: String = "main",
        userName: String? = "Test User",
        userEmail: String? = "test@example.com",
        remoteUrl: String = remoteDir.toString(),
    ) {
        git(listOf("git", "init", "-b", branch, repoDir.toString()), tempDir)
        if (userName != null) git(listOf("git", "config", "user.name", userName))
        if (userEmail != null) git(listOf("git", "config", "user.email", userEmail))
        if (remoteUrl.isNotBlank()) git(listOf("git", "remote", "add", "origin", remoteUrl))
    }

    private fun config(
        branch: String = "main",
        remoteName: String = "origin",
        pushTimeout: Duration = Duration.ofSeconds(30),
        root: Path = repoDir,
    ): GitBackupConfig =
        GitBackupConfig(
            repositoryRoot = root,
            eventRoots = setOf(VfsUri.parse("alcyone://notes")),
            remoteName = remoteName,
            branch = branch,
            pushTimeout = pushTimeout,
        )

    private fun failure(result: SyncResult): SyncResult.Failed {
        assertIs<SyncResult.Failed>(result)
        return result
    }

    // --- A01: 校验阶段 ---

    @Test
    fun `validate non git directory fails`() {
        val repo = GitRepository(config(), runner)
        val result = failure(repo.syncOnce("run-1", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_NOT_GIT.name, result.message)
    }

    @Test
    fun `validate configured root sub-directory of git repo fails`() {
        initLocalRepo()
        val subDir = repoDir.resolve("subfolder")
        Files.createDirectories(subDir)

        val repo = GitRepository(config(root = subDir), runner)
        val result = failure(repo.syncOnce("run-2", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_REPOSITORY_ROOT_MISMATCH.name, result.message)
    }

    @Test
    fun `validate bare repository as source fails`() {
        val repo = GitRepository(config(root = remoteDir), runner)
        val result = failure(repo.syncOnce("run-3", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_BARE.name, result.message)
    }

    @Test
    fun `validate branch mismatch fails`() {
        initLocalRepo(branch = "master")
        val repo = GitRepository(config(branch = "main"), runner)
        val result = failure(repo.syncOnce("run-4", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_BRANCH_MISMATCH.name, result.message)
    }

    @Test
    fun `validate missing user name or email fails`() {
        val isolatedHome = tempDir.resolve("isolated-home")
        Files.createDirectories(isolatedHome)
        val emptyGlobal = isolatedHome.resolve(".gitconfig")
        Files.writeString(emptyGlobal, "")

        val isolatedRunner =
            object : GitProcessRunner {
                override fun run(
                    command: List<String>,
                    workingDir: Path,
                    timeout: Duration,
                    extraEnv: Map<String, String>,
                ): ProcessResult {
                    val env =
                        extraEnv.toMutableMap().apply {
                            put("HOME", isolatedHome.toString())
                            put("GIT_CONFIG_GLOBAL", emptyGlobal.toString())
                        }
                    return runner.run(command, workingDir, timeout, env)
                }
            }

        initLocalRepo(userName = null, userEmail = null)
        val repo = GitRepository(config(), isolatedRunner)
        val result = failure(repo.syncOnce("run-5", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertTrue(
            result.message == GitSecurityError.VALIDATE_MISSING_USER_NAME.name ||
                result.message == GitSecurityError.VALIDATE_MISSING_USER_EMAIL.name,
        )
    }

    @Test
    fun `validate in-progress merge or rebase fails`() {
        initLocalRepo()
        val mergeHead = repoDir.resolve(".git/MERGE_HEAD")
        Files.writeString(mergeHead, "dummy-sha")

        val repo = GitRepository(config(), runner)
        val mergeResult = failure(repo.syncOnce("run-6", "test"))
        assertEquals(SyncStage.VALIDATE, mergeResult.stage)
        assertEquals(GitSecurityError.VALIDATE_IN_PROGRESS_STATE.name, mergeResult.message)

        Files.delete(mergeHead)
        Files.createDirectories(repoDir.resolve(".git/rebase-merge"))
        val rebaseResult = failure(repo.syncOnce("run-7", "test"))
        assertEquals(GitSecurityError.VALIDATE_IN_PROGRESS_STATE.name, rebaseResult.message)
    }

    @Test
    fun `validate multiple push URLs fails`() {
        initLocalRepo()
        git(listOf("git", "remote", "set-url", "--add", "--push", "origin", "http://push-a.git"))
        git(listOf("git", "remote", "set-url", "--add", "--push", "origin", "http://push-b.git"))

        val repo = GitRepository(config(), runner)
        val result = failure(repo.syncOnce("run-8", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_MULTIPLE_PUSH_URLS.name, result.message)
    }

    @Test
    fun `validate multiple fetch URLs fails`() {
        initLocalRepo()
        git(listOf("git", "remote", "set-url", "--add", "origin", "http://second-url.git"))

        val repo = GitRepository(config(), runner)
        val result = failure(repo.syncOnce("run-9", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_MULTIPLE_PUSH_URLS.name, result.message)
    }

    @Test
    fun `validate linked worktree as source fails but main worktree is accepted`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("a.txt"), "hello")
        git(listOf("git", "add", "-A"))
        git(listOf("git", "commit", "-m", "init"))

        val worktreeDir = tempDir.resolve("linked-wt")
        val addWt = git(listOf("git", "worktree", "add", "-b", "wtbranch", worktreeDir.toString()))
        assertEquals(0, addWt.exitCode, "worktree add must succeed")

        // linked worktree 作为源必须被拒绝
        val linkedRepo = GitRepository(config(root = worktreeDir), runner)
        val linkedResult = failure(linkedRepo.syncOnce("run-wt", "test"))
        assertEquals(SyncStage.VALIDATE, linkedResult.stage)
        assertEquals(GitSecurityError.VALIDATE_LINKED_WORKTREE.name, linkedResult.message)

        // 主工作区本身不能被误判为 linked worktree
        val mainRepo = GitRepository(config(), runner)
        val mainResult = mainRepo.syncOnce("run-main", "test")
        assertFalse(
            mainResult is SyncResult.Failed && mainResult.message == GitSecurityError.VALIDATE_LINKED_WORKTREE.name,
            "Main worktree must not be rejected as linked worktree",
        )
    }

    @Test
    fun `validate nested repository fails`() {
        initLocalRepo()
        val nested = repoDir.resolve("nested")
        Files.createDirectories(nested)
        git(listOf("git", "init", "-b", "main", nested.toString()), tempDir)

        val repo = GitRepository(config(), runner)
        val result = failure(repo.syncOnce("run-nested", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_NESTED_REPO.name, result.message)
    }

    @Test
    fun `validate staged gitlink without gitmodules fails`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("a.txt"), "hello")
        git(listOf("git", "add", "a.txt"))
        git(listOf("git", "commit", "-m", "init"))
        val sha = git(listOf("git", "rev-parse", "HEAD")).stdout.trim()
        // 仅暂存一条 gitlink 记录，不创建 .gitmodules，也不创建嵌套目录
        val updateIndex = git(listOf("git", "update-index", "--add", "--cacheinfo", "160000,$sha,vendor/sub"))
        assertEquals(0, updateIndex.exitCode, "Staging a gitlink must succeed")
        assertFalse(Files.exists(repoDir.resolve(".gitmodules")))
        assertFalse(Files.exists(repoDir.resolve("vendor/sub")))

        val repo = GitRepository(config(), runner)
        val result = failure(repo.syncOnce("run-gitlink", "test"))
        assertEquals(SyncStage.VALIDATE, result.stage)
        assertEquals(GitSecurityError.VALIDATE_NESTED_REPO.name, result.message)
    }

    // --- A02: 暂存、提交与推送 ---

    @Test
    fun `initial empty repository with no files skips cleanly`() {
        initLocalRepo()
        val repo = GitRepository(config(), runner)

        val result = repo.syncOnce("run-empty", "cron")
        assertIs<SyncResult.SkippedNoContent>(result)

        val remoteHead = git(listOf("git", "rev-parse", "--verify", "refs/heads/main"), remoteDir)
        assertFalse(remoteHead.exitCode == 0, "Remote must not receive a branch for empty repo")
    }

    @Test
    fun `pre-commit hook failure on first commit is a commit failure not skipped`() {
        initLocalRepo()
        val hook = repoDir.resolve(".git/hooks/pre-commit")
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        hook.toFile().setExecutable(true)
        Files.writeString(repoDir.resolve("data.txt"), "content")

        val repo = GitRepository(config(), runner)
        val result = failure(repo.syncOnce("run-hook", "test"))
        assertEquals(SyncStage.COMMIT, result.stage)
        assertEquals(GitSecurityError.COMMIT_COMMIT_FAILED.name, result.message)
    }

    @Test
    fun `first commit and push to bare remote`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("notes.txt"), "First note content")

        val repo = GitRepository(config(), runner)
        val result = repo.syncOnce("run-first", "event")
        assertIs<SyncResult.Success>(result)
        assertTrue(result.commitCreated)
        assertTrue(result.pushed)
        assertTrue(result.commitSha.isNotBlank())

        val remoteHead = git(listOf("git", "rev-parse", "refs/heads/main"), remoteDir)
        assertEquals(0, remoteHead.exitCode)
        assertEquals(result.commitSha, remoteHead.stdout.trim())
    }

    @Test
    fun `file modifications additions deletions gitignore and no-change behavior`() {
        initLocalRepo()
        val repo = GitRepository(config(), runner)

        Files.writeString(repoDir.resolve(".gitignore"), "*.tmp\n")
        val tracked = repoDir.resolve("data.txt")
        Files.writeString(tracked, "Initial data")
        Files.writeString(repoDir.resolve("ignored.tmp"), "Ignore this")

        val first = repo.syncOnce("run-sub1", "event")
        assertIs<SyncResult.Success>(first)
        assertTrue(first.commitCreated)

        val lsTracked = git(listOf("git", "ls-files"))
        assertTrue(lsTracked.stdout.contains("data.txt"))
        assertFalse(lsTracked.stdout.contains("ignored.tmp"))

        // 无改动：不生成空提交，但依然成功 push
        val second = repo.syncOnce("run-sub2", "cron")
        assertIs<SyncResult.Success>(second)
        assertFalse(second.commitCreated)
        assertEquals(first.commitSha, second.commitSha)

        // 修改
        Files.writeString(tracked, "Modified data")
        val third = repo.syncOnce("run-sub3", "event")
        assertIs<SyncResult.Success>(third)
        assertTrue(third.commitCreated)
        assertTrue(third.commitSha != second.commitSha)

        // 删除
        Files.delete(tracked)
        val fourth = repo.syncOnce("run-sub4", "event")
        assertIs<SyncResult.Success>(fourth)
        assertTrue(fourth.commitCreated)
        assertFalse(git(listOf("git", "ls-files")).stdout.contains("data.txt"))
    }

    // --- A03: 提交成功但 push 失败与补偿 ---

    @Test
    fun `commit succeeds but push fails retains local commit and compensates on next run`() {
        initLocalRepo(remoteUrl = "file:///invalid/nonexistent/path.git")
        Files.writeString(repoDir.resolve("critical.txt"), "Critical backup data")

        val repo = GitRepository(config(), runner)
        val first = failure(repo.syncOnce("run-fail-push", "event"))
        assertEquals(SyncStage.PUSH, first.stage)
        assertEquals(GitSecurityError.PUSH_PUSH_FAILED.name, first.message)

        val localSha = git(listOf("git", "rev-parse", "HEAD")).stdout.trim()
        assertTrue(localSha.isNotBlank())

        git(listOf("git", "remote", "set-url", "origin", remoteDir.toString()))

        val second = repo.syncOnce("run-compensate-push", "cron")
        assertIs<SyncResult.Success>(second)
        assertFalse(second.commitCreated)
        assertEquals(localSha, second.commitSha)

        val remoteSha = git(listOf("git", "rev-parse", "refs/heads/main"), remoteDir).stdout.trim()
        assertEquals(localSha, remoteSha)
    }

    // --- A05: 大输出排空 ---

    @Test
    fun `large output draining does not deadlock process`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("large.txt"), "Line of data\n".repeat(20000))

        val repo = GitRepository(config(), runner)
        val result = repo.syncOnce("run-large", "event")
        assertIs<SyncResult.Success>(result)
    }

    // --- A06: 超时与取消清理 ---

    @Test
    fun `command timeout cleans up process safely`() {
        val result =
            runner.run(
                command = listOf("sleep", "10"),
                workingDir = tempDir,
                timeout = Duration.ofMillis(200),
            )
        assertTrue(result.timedOut)
        assertFalse(result.cancelled)
        assertFalse(result.cleanupFailed)
    }

    // --- R3: 清理失败阻断 ---

    @Test
    fun `cleanup failure poisons repository and blocks subsequent syncs`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("a.txt"), "content")

        var calls = 0
        val poisoningRunner =
            object : GitProcessRunner {
                override fun run(
                    command: List<String>,
                    workingDir: Path,
                    timeout: Duration,
                    extraEnv: Map<String, String>,
                ): ProcessResult {
                    calls++
                    val result = runner.run(command, workingDir, timeout, extraEnv)
                    return result.copy(cleanupFailed = true)
                }
            }

        val repo = GitRepository(config(), poisoningRunner)

        val first = failure(repo.syncOnce("run-poison-1", "test"))
        assertEquals(GitSecurityError.PROCESS_CLEANUP_FAILED.name, first.message)
        val callsAfterFirst = calls
        assertTrue(callsAfterFirst >= 1)

        val second = failure(repo.syncOnce("run-poison-2", "test"))
        assertEquals(GitSecurityError.PROCESS_CLEANUP_FAILED.name, second.message)
        assertEquals(callsAfterFirst, calls, "Poisoned repository must not start new commands")
    }

    @Test
    fun `cancellation exception during stage aborts with cancelled semantics`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("a.txt"), "content")

        val cancellingRunner =
            object : GitProcessRunner {
                override fun run(
                    command: List<String>,
                    workingDir: Path,
                    timeout: Duration,
                    extraEnv: Map<String, String>,
                ): ProcessResult {
                    if (command.contains("add")) {
                        throw java.util.concurrent.CancellationException("cancelled")
                    }
                    return runner.run(command, workingDir, timeout, extraEnv)
                }
            }

        val repo = GitRepository(config(), cancellingRunner)
        val result = failure(repo.syncOnce("run-cancel", "test"))
        assertEquals(SyncStage.STAGE, result.stage)
        assertTrue(result.cancelled)
        assertEquals(GitSecurityError.STAGE_ADD_CANCELLED.name, result.message)
    }

    @Test
    fun `status failure is a commit head probe failure not skipped`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("a.txt"), "content")

        val failingRunner =
            object : GitProcessRunner {
                override fun run(
                    command: List<String>,
                    workingDir: Path,
                    timeout: Duration,
                    extraEnv: Map<String, String>,
                ): ProcessResult {
                    if (command.contains("status")) {
                        return ProcessResult(exitCode = 128, stdout = "", stderr = "boom")
                    }
                    return runner.run(command, workingDir, timeout, extraEnv)
                }
            }

        val repo = GitRepository(config(), failingRunner)
        val result = failure(repo.syncOnce("run-status-fail", "test"))
        assertEquals(SyncStage.COMMIT, result.stage)
        assertEquals(GitSecurityError.COMMIT_HEAD_PROBE_FAILED.name, result.message)
    }

    @Test
    fun `head probe failure is a commit head probe failure`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("a.txt"), "content")

        val failingRunner =
            object : GitProcessRunner {
                override fun run(
                    command: List<String>,
                    workingDir: Path,
                    timeout: Duration,
                    extraEnv: Map<String, String>,
                ): ProcessResult {
                    if (command.contains("rev-parse") && command.contains("HEAD")) {
                        return ProcessResult(exitCode = 128, stdout = "", stderr = "corrupt")
                    }
                    return runner.run(command, workingDir, timeout, extraEnv)
                }
            }

        val repo = GitRepository(config(), failingRunner)
        val result = failure(repo.syncOnce("run-head-fail", "test"))
        assertEquals(SyncStage.COMMIT, result.stage)
        assertEquals(GitSecurityError.COMMIT_HEAD_PROBE_FAILED.name, result.message)
    }

    @Test
    fun `truncated protocol output is rejected`() {
        initLocalRepo()
        Files.writeString(repoDir.resolve("a.txt"), "content")

        val truncatingRunner =
            object : GitProcessRunner {
                override fun run(
                    command: List<String>,
                    workingDir: Path,
                    timeout: Duration,
                    extraEnv: Map<String, String>,
                ): ProcessResult {
                    val result = runner.run(command, workingDir, timeout, extraEnv)
                    return if (command.contains("add")) result.copy(outputTruncated = true) else result
                }
            }

        val repo = GitRepository(config(), truncatingRunner)
        val result = failure(repo.syncOnce("run-truncated", "test"))
        assertEquals(SyncStage.STAGE, result.stage)
        assertEquals(GitSecurityError.OUTPUT_TRUNCATED.name, result.message)
    }
}
