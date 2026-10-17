package com.github.noahshen.alcyone.context.vfs.gitbackup

/**
 * 触发一次完整检查的原因，仅用于日志关联。
 */
internal enum class TriggerReason {
    EVENT,
    CRON,
    STARTUP,
    MANUAL,
}
