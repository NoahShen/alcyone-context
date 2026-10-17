package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsEvent

/**
 * 轻量事件消费者：只做筛选与转发，不访问磁盘、不执行 Git、不等待同步结果。
 *
 * 稳定实例可登记在 VFS 订阅上；close 时由插件清空对它的引用。即使并发回调已取到旧引用，
 * 转发目标 [GitBackupWorker] 也会在 closing 后拒绝新工作。
 */
internal class GitBackupEventConsumer(
    private val filter: GitBackupEventFilter,
    private val onRelevantEvent: () -> Unit,
) {
    fun onEvent(event: VfsEvent) {
        if (filter.matches(event)) {
            onRelevantEvent()
        }
    }
}
