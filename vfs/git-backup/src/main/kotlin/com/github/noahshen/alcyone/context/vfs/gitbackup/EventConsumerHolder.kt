package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsEvent

/**
 * 稳定的轻量回调持有器：只持有可清空的 [GitBackupEventConsumer] 引用。
 *
 * VFS 永久保留 `eventConsumer` 时，被保留的是本持有器而非整个插件；close 清空 [target] 后，
 * 回调链不再持有 Consumer、Worker 或插件。已取到旧 target 的并发回调仍会被 Worker 的 closing 屏障拒绝。
 */
internal class EventConsumerHolder {
    @Volatile
    private var target: GitBackupEventConsumer? = null

    fun attach(consumer: GitBackupEventConsumer) {
        target = consumer
    }

    fun accept(event: VfsEvent) {
        target?.onEvent(event)
    }

    fun clear() {
        target = null
    }
}
