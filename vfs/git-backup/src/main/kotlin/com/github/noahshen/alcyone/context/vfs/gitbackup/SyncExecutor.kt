package com.github.noahshen.alcyone.context.vfs.gitbackup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次完整同步的执行边界。
 *
 * 生产实现把阻塞的 [GitRepository.syncOnce] 放到可中断的 IO 线程；测试可注入受控实现，
 * 以便在受控时间/受控交错下验证调度行为，而无需真实 Git。
 */
internal fun interface SyncExecutor {
    suspend fun execute(
        runId: String,
        reason: TriggerReason,
    ): SyncResult
}

/**
 * 取消异常：携带被取消时已完成的同步结果（若阻塞块在中断前已返回）。
 *
 * 继承 [CancellationException]，保持取消语义；[result] 供唯一日志责任层记录阶段与耗时。
 */
internal class SyncCancelledException(
    val result: SyncResult?,
) : CancellationException("git sync cancelled")

/**
 * 生产执行器：在 [Dispatchers.IO] 上可中断地运行一次仓库同步。
 *
 * 取消协程时 [runInterruptible] 会中断工作线程，[JvmGitProcessRunner] 据此清理子进程并返回取消结果；
 * 本类捕获 [CancellationException]，把已得到的 [SyncResult] 包进 [SyncCancelledException] 向上传递，
 * 使运行日志仍能记录取消阶段与耗时。
 */
internal class RepositorySyncExecutor(
    private val repository: GitRepository,
) : SyncExecutor {
    override suspend fun execute(
        runId: String,
        reason: TriggerReason,
    ): SyncResult {
        val captured = AtomicReference<SyncResult?>(null)
        return try {
            runInterruptible(Dispatchers.IO) {
                repository.syncOnce(runId, reason.name).also { captured.set(it) }
            }
        } catch (cancelled: CancellationException) {
            throw SyncCancelledException(captured.get())
        }
    }
}
