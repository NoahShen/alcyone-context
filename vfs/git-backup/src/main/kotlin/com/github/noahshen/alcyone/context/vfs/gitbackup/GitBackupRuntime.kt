package com.github.noahshen.alcyone.context.vfs.gitbackup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * 插件内部运行资源集合：私有 Scope、本机日历 Clock、同步执行器与日志。
 *
 * 生产实现由 [GitBackupPlugin] 的公开构造函数创建；测试通过 internal 构造函数注入受控 Scope、
 * Clock、执行器与 Logger，从而在不真实等待 60 秒或凌晨的情况下建立调度判据。
 */
internal class GitBackupRuntime(
    val scope: CoroutineScope,
    val clock: Clock,
    val executor: SyncExecutor,
    val logger: Logger,
) {
    companion object {
        fun production(config: GitBackupConfig): GitBackupRuntime =
            GitBackupRuntime(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                clock = Clock.systemDefaultZone(),
                executor = RepositorySyncExecutor(GitRepository(config)),
                logger = LoggerFactory.getLogger(GitBackupPlugin::class.java),
            )
    }
}
