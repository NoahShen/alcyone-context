package com.github.noahshen.alcyone.context.vfs.gitbackup

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException

/**
 * 内部 Git 仓库单次同步协调器。
 *
 * 严格按 VALIDATE → STAGE → COMMIT → PUSH 顺序执行，全部失败路径只暴露受控错误码，
 * 不回显外部命令原始输出。任意命令清理失败会把本实例标记为失效（poisoned），
 * 本轮与后续 [syncOnce] 直接拒绝，避免与残留进程并发冲突。
 *
 * 本类不写运行日志：运行日志的唯一责任层是 [GitBackupWorker]，生产链每轮只有一条开始与一条终结记录。
 */
internal class GitRepository(
    val config: GitBackupConfig,
    private val processRunner: GitProcessRunner = JvmGitProcessRunner(),
) {
    @Volatile
    private var poisoned: Boolean = false

    @Volatile
    private var currentStage: SyncStage = SyncStage.VALIDATE

    /**
     * 执行单次完整同步。
     *
     * @param runId 运行批次 ID。
     * @param triggerReason 触发原因（如 "cron", "event" 等）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun syncOnce(
        runId: String,
        triggerReason: String,
    ): SyncResult {
        val startNano = System.nanoTime()

        fun elapsed(): Duration = Duration.ofNanos(System.nanoTime() - startNano)

        if (poisoned) {
            return SyncResult.Failed(SyncStage.VALIDATE, GitSecurityError.PROCESS_CLEANUP_FAILED.name, elapsed())
        }

        return try {
            doSync(::elapsed)
        } catch (failure: GitCommandFailure) {
            SyncResult.Failed(failure.stage, failure.code.name, elapsed(), cancelled = failure.cancelled)
        } catch (e: CancellationException) {
            val code = cancelCode(currentStage, GitSecurityError.UNEXPECTED_FAILURE)
            SyncResult.Failed(currentStage, code.name, elapsed(), cancelled = true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val code = cancelCode(currentStage, GitSecurityError.UNEXPECTED_FAILURE)
            SyncResult.Failed(currentStage, code.name, elapsed(), cancelled = true)
        } catch (e: Exception) {
            SyncResult.Failed(currentStage, GitSecurityError.UNEXPECTED_FAILURE.name, elapsed())
        }
    }

    private fun doSync(elapsed: () -> Duration): SyncResult {
        currentStage = SyncStage.VALIDATE
        validateRepository()

        // STAGE
        currentStage = SyncStage.STAGE
        val stageResult =
            execute(
                stage = SyncStage.STAGE,
                command = listOf(config.gitExecutable, "add", "-A", "--", "."),
                timeout = config.localCommandTimeout,
                timeoutCode = GitSecurityError.STAGE_ADD_TIMED_OUT,
            )
        if (stageResult.exitCode != 0) {
            throw GitCommandFailure(SyncStage.STAGE, GitSecurityError.STAGE_ADD_FAILED)
        }

        // COMMIT
        currentStage = SyncStage.COMMIT
        var commitCreated = false
        if (hasHead()) {
            val diffResult =
                execute(
                    stage = SyncStage.COMMIT,
                    command = listOf(config.gitExecutable, "diff-index", "--quiet", "--cached", "HEAD", "--"),
                    timeout = config.localCommandTimeout,
                    timeoutCode = GitSecurityError.COMMIT_INDEX_DIFF_FAILED,
                )
            when (diffResult.exitCode) {
                0 -> Unit // 无差异：跳过提交，随后仍进入 PUSH 补推。
                1 -> {
                    commit()
                    commitCreated = true
                }
                else -> throw GitCommandFailure(SyncStage.COMMIT, GitSecurityError.COMMIT_INDEX_DIFF_FAILED)
            }
        } else {
            // unborn 分支：先确认暂存区确有内容，避免把 hook 失败误判为无内容。
            // `status --porcelain` 正常无论有无内容都返回 0；非 0 是命令异常，必须失败。
            val statusResult =
                execute(
                    stage = SyncStage.COMMIT,
                    command = listOf(config.gitExecutable, "status", "--porcelain"),
                    timeout = config.localCommandTimeout,
                    timeoutCode = GitSecurityError.COMMIT_HEAD_PROBE_FAILED,
                )
            if (statusResult.exitCode != 0) {
                throw GitCommandFailure(SyncStage.COMMIT, GitSecurityError.COMMIT_HEAD_PROBE_FAILED)
            }
            if (statusResult.stdout.isBlank()) {
                val duration = elapsed()
                return SyncResult.SkippedNoContent(duration)
            }
            commit()
            commitCreated = true
        }

        // PUSH
        currentStage = SyncStage.PUSH
        val shaResult =
            execute(
                stage = SyncStage.PUSH,
                command = listOf(config.gitExecutable, "rev-parse", "HEAD"),
                timeout = config.localCommandTimeout,
                timeoutCode = GitSecurityError.PUSH_REVISION_FAILED,
            )
        if (shaResult.exitCode != 0) {
            throw GitCommandFailure(SyncStage.PUSH, GitSecurityError.PUSH_REVISION_FAILED)
        }
        val commitSha = shaResult.stdout.trim()

        // 即使本轮未新建提交，也要 push，以补足之前 push 失败的提交。
        val pushRefSpec = "HEAD:refs/heads/${config.branch}"
        val pushResult =
            execute(
                stage = SyncStage.PUSH,
                command = listOf(config.gitExecutable, "push", "--", config.remoteName, pushRefSpec),
                timeout = config.pushTimeout,
                timeoutCode = GitSecurityError.PUSH_PUSH_TIMED_OUT,
            )
        if (pushResult.exitCode != 0) {
            throw GitCommandFailure(SyncStage.PUSH, GitSecurityError.PUSH_PUSH_FAILED)
        }

        val duration = elapsed()
        return SyncResult.Success(
            commitCreated = commitCreated,
            commitSha = commitSha,
            pushed = true,
            duration = duration,
        )
    }

    /**
     * 判定是否存在 HEAD。
     *
     * `git rev-parse --verify --quiet HEAD` 的允许退出语义：
     * - 0：存在 HEAD；
     * - 1：正常 unborn 分支（初始空仓库尚无 HEAD），属于预期结果；
     * - 其它（如 128）：命令异常，必须失败。
     */
    private fun hasHead(): Boolean {
        val result =
            execute(
                stage = SyncStage.COMMIT,
                command = listOf(config.gitExecutable, "rev-parse", "--verify", "--quiet", "HEAD"),
                timeout = config.localCommandTimeout,
                timeoutCode = GitSecurityError.COMMIT_HEAD_PROBE_FAILED,
            )
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throw GitCommandFailure(SyncStage.COMMIT, GitSecurityError.COMMIT_HEAD_PROBE_FAILED)
        }
    }

    private fun commit() {
        val message = "Backup ${LocalDateTime.now().format(COMMIT_TIME_FORMATTER)}"
        val result =
            execute(
                stage = SyncStage.COMMIT,
                command = listOf(config.gitExecutable, "commit", "-m", message),
                timeout = config.localCommandTimeout,
                timeoutCode = GitSecurityError.COMMIT_COMMIT_TIMED_OUT,
            )
        if (result.exitCode != 0) {
            throw GitCommandFailure(SyncStage.COMMIT, GitSecurityError.COMMIT_COMMIT_FAILED)
        }
    }

    /**
     * 统一执行：
     * - 取消（[CancellationException] / 中断）单独处理并保留取消语义；
     * - 清理失败、超时、协议输出不完整映射为受控失败；
     * - 退出码交由调用方按命令语义判定。
     */
    private fun execute(
        stage: SyncStage,
        command: List<String>,
        timeout: Duration,
        timeoutCode: GitSecurityError,
    ): ProcessResult {
        val result =
            try {
                processRunner.run(command, config.normalizedRepositoryRoot, timeout)
            } catch (e: CancellationException) {
                throw GitCommandFailure(stage, cancelCode(stage, timeoutCode), cancelled = true)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw GitCommandFailure(stage, cancelCode(stage, timeoutCode), cancelled = true)
            }
        if (result.cleanupFailed) {
            poisoned = true
            throw GitCommandFailure(stage, GitSecurityError.PROCESS_CLEANUP_FAILED)
        }
        if (result.cancelled) {
            throw GitCommandFailure(stage, cancelCode(stage, timeoutCode), cancelled = true)
        }
        if (result.timedOut) {
            throw GitCommandFailure(stage, timeoutCode)
        }
        if (result.outputTruncated) {
            throw GitCommandFailure(stage, GitSecurityError.OUTPUT_TRUNCATED)
        }
        return result
    }

    private fun cancelCode(
        stage: SyncStage,
        fallback: GitSecurityError,
    ): GitSecurityError =
        when (stage) {
            SyncStage.STAGE -> GitSecurityError.STAGE_ADD_CANCELLED
            SyncStage.COMMIT -> GitSecurityError.COMMIT_COMMIT_CANCELLED
            SyncStage.PUSH -> GitSecurityError.PUSH_PUSH_CANCELLED
            SyncStage.VALIDATE -> fallback
        }

    private fun validateRepository() {
        val root = config.normalizedRepositoryRoot
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            fail(GitSecurityError.VALIDATE_ROOT_INVALID)
        }

        // 拒绝 bare 仓库
        val isBare = runValidation(listOf(config.gitExecutable, "rev-parse", "--is-bare-repository"))
        if (isBare.exitCode == 0 && isBare.stdout.trim() == "true") {
            fail(GitSecurityError.VALIDATE_BARE)
        }

        // 必须在普通工作区内
        val insideWorkTree = runValidation(listOf(config.gitExecutable, "rev-parse", "--is-inside-work-tree"))
        if (insideWorkTree.exitCode != 0 || insideWorkTree.stdout.trim() != "true") {
            fail(GitSecurityError.VALIDATE_NOT_GIT)
        }

        // 拒绝 linked worktree / gitdir 链接（.git 为文件）
        if (Files.isRegularFile(root.resolve(".git"))) {
            fail(GitSecurityError.VALIDATE_LINKED_WORKTREE)
        }

        // 仓库根必须与配置一致（非子目录）
        val topLevel = runValidation(listOf(config.gitExecutable, "rev-parse", "--show-toplevel"))
        if (topLevel.exitCode != 0) {
            fail(GitSecurityError.VALIDATE_NOT_GIT)
        }
        val detectedTopLevel = realPathOrNull(topLevel.stdout.trim())
        val realRoot = realPathOrNull(root.toString()) ?: root
        if (detectedTopLevel != realRoot) {
            fail(GitSecurityError.VALIDATE_REPOSITORY_ROOT_MISMATCH)
        }

        // 拒绝 linked worktree：git-dir 与 common-dir 不一致
        val gitDirResult = runValidation(listOf(config.gitExecutable, "rev-parse", "--git-dir"))
        val commonDirResult = runValidation(listOf(config.gitExecutable, "rev-parse", "--git-common-dir"))
        if (gitDirResult.exitCode != 0 || commonDirResult.exitCode != 0) {
            fail(GitSecurityError.VALIDATE_NOT_GIT)
        }
        val gitDir = resolveAgainst(root, gitDirResult.stdout.trim())
        val commonDir = resolveAgainst(root, commonDirResult.stdout.trim())
        if (gitDir != commonDir) {
            fail(GitSecurityError.VALIDATE_LINKED_WORKTREE)
        }

        // 拒绝子模块与嵌套仓库
        if (Files.exists(root.resolve(".gitmodules")) || hasNestedRepository(root)) {
            fail(GitSecurityError.VALIDATE_NESTED_REPO)
        }
        // 拒绝 index 中的 gitlink（mode 160000），即使缺少 .gitmodules
        val lsFiles = runValidation(listOf(config.gitExecutable, "ls-files", "--stage"))
        if (lsFiles.exitCode != 0) {
            fail(GitSecurityError.VALIDATE_NOT_GIT)
        }
        if (lsFiles.stdout.lineSequence().any { it.startsWith(GITLINK_MODE_PREFIX) }) {
            fail(GitSecurityError.VALIDATE_NESTED_REPO)
        }

        // 分支必须与配置一致（detached HEAD 会被 symbolic-ref 拒绝）
        val branchResult = runValidation(listOf(config.gitExecutable, "symbolic-ref", "--short", "-q", "HEAD"))
        if (branchResult.exitCode != 0 || branchResult.stdout.trim() != config.branch) {
            fail(GitSecurityError.VALIDATE_BRANCH_MISMATCH)
        }

        // 拒绝进行中的 merge / rebase / cherry-pick / revert
        if (hasInProgressState(gitDir)) {
            fail(GitSecurityError.VALIDATE_IN_PROGRESS_STATE)
        }

        // remote 必须存在且只解析出一个有效 push URL
        val remoteResult =
            runValidation(listOf(config.gitExecutable, "remote", "get-url", "--push", "--all", config.remoteName))
        if (remoteResult.exitCode != 0) {
            fail(GitSecurityError.VALIDATE_NO_REMOTE_PUSH_URL)
        }
        val pushUrls =
            remoteResult.stdout
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (pushUrls.isEmpty()) {
            fail(GitSecurityError.VALIDATE_NO_REMOTE_PUSH_URL)
        }
        if (pushUrls.size > 1) {
            fail(GitSecurityError.VALIDATE_MULTIPLE_PUSH_URLS)
        }

        // 提交身份必须配置
        val userName = runValidation(listOf(config.gitExecutable, "config", "user.name"))
        if (userName.exitCode != 0 || userName.stdout.isBlank()) {
            fail(GitSecurityError.VALIDATE_MISSING_USER_NAME)
        }
        val userEmail = runValidation(listOf(config.gitExecutable, "config", "user.email"))
        if (userEmail.exitCode != 0 || userEmail.stdout.isBlank()) {
            fail(GitSecurityError.VALIDATE_MISSING_USER_EMAIL)
        }
    }

    private fun runValidation(command: List<String>): ProcessResult =
        execute(
            stage = SyncStage.VALIDATE,
            command = command,
            timeout = config.localCommandTimeout,
            timeoutCode = GitSecurityError.VALIDATE_NOT_GIT,
        )

    private fun hasInProgressState(gitDir: Path): Boolean = IN_PROGRESS_MARKERS.any { Files.exists(gitDir.resolve(it)) }

    /**
     * 扫描实际目录结构识别嵌套仓库：任一非根子目录含 `.git`（文件或目录）。
     * 跳过根自身的 `.git` 目录，仅做仓库状态校验，不做任何文件过滤。
     */
    private fun hasNestedRepository(root: Path): Boolean {
        var found = false
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root) {
                        val name = dir.fileName?.toString()
                        if (name == ".git") {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (Files.exists(dir.resolve(".git"))) {
                            found = true
                            return FileVisitResult.TERMINATE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found
    }

    private fun resolveAgainst(
        base: Path,
        path: String,
    ): Path {
        val parsed = Path.of(path)
        return (if (parsed.isAbsolute) parsed else base.resolve(parsed)).toAbsolutePath().normalize()
    }

    private fun realPathOrNull(path: String): Path? =
        try {
            Path.of(path).toRealPath()
        } catch (e: Exception) {
            null
        }

    private fun fail(code: GitSecurityError): Nothing = throw GitCommandFailure(SyncStage.VALIDATE, code)

    private class GitCommandFailure(
        val stage: SyncStage,
        val code: GitSecurityError,
        val cancelled: Boolean = false,
    ) : Exception(code.name)

    companion object {
        private val COMMIT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val IN_PROGRESS_MARKERS =
            listOf("MERGE_HEAD", "rebase-merge", "rebase-apply", "CHERRY_PICK_HEAD", "REVERT_HEAD")
        private const val GITLINK_MODE_PREFIX = "160000 "
    }
}
