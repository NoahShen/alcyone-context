package com.github.noahshen.alcyone.context.vfs.gitbackup

/**
 * 受控的阶段错误摘要码。
 *
 * 失败日志与结果只使用这些受控代码，绝不回显外部命令的原始 stderr、hook 输出、
 * 未脱敏 URL 或异常堆栈，避免任何凭据泄露。
 */
internal enum class GitSecurityError {
    VALIDATE_ROOT_INVALID,
    VALIDATE_NOT_GIT,
    VALIDATE_BARE,
    VALIDATE_LINKED_WORKTREE,
    VALIDATE_NESTED_REPO,
    VALIDATE_REPOSITORY_ROOT_MISMATCH,
    VALIDATE_BRANCH_MISMATCH,
    VALIDATE_IN_PROGRESS_STATE,
    VALIDATE_NO_REMOTE_PUSH_URL,
    VALIDATE_MULTIPLE_PUSH_URLS,
    VALIDATE_MISSING_USER_NAME,
    VALIDATE_MISSING_USER_EMAIL,

    STAGE_ADD_FAILED,
    STAGE_ADD_TIMED_OUT,
    STAGE_ADD_CANCELLED,

    COMMIT_HEAD_PROBE_FAILED,
    COMMIT_INDEX_DIFF_FAILED,
    COMMIT_COMMIT_FAILED,
    COMMIT_COMMIT_TIMED_OUT,
    COMMIT_COMMIT_CANCELLED,

    PUSH_REVISION_FAILED,
    PUSH_PUSH_FAILED,
    PUSH_PUSH_TIMED_OUT,
    PUSH_PUSH_CANCELLED,

    OUTPUT_TRUNCATED,
    UNEXPECTED_FAILURE,
    PROCESS_CLEANUP_FAILED,
}

/**
 * 安全脱敏处理。
 */
internal object GitSecurity {
    /**
     * 将 URL 中的密码部分替换为 `***`。
     *
     * 例如 `https://fixture:T34_SYNTHETIC_PASSWORD@host/repo.git`
     * 变为 `https://fixture:***@host/repo.git`。
     * 密码分组（第 3 组）只用于匹配，绝不出现在替换结果中。
     */
    fun sanitizeUrl(output: String): String =
        CREDENTIAL_URL_REGEX.replace(output) { match ->
            val scheme = match.groupValues[1]
            val user = match.groupValues[2]
            val host = match.groupValues[4]
            "$scheme$user:***@$host"
        }

    private val CREDENTIAL_URL_REGEX =
        Regex("""([a-zA-Z][a-zA-Z0-9+.\-]*://)([^:/@\s]+):([^@\s]+)@([^/\s]+)""")
}
