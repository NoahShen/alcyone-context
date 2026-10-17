package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Git 本地备份插件的公开入口：一个实例管理一个专用工作区。
 *
 * - 构造只校验参数并建立轻量对象，不启动计时或进程；
 * - [start] 幂等，异步启动一次完整检查（STARTUP 触发），不等待 Git 结果；
 * - [requestSync] 返回 Unit，仅发起/合并请求，不等待执行完毕；
 * - [close] 断开 Consumer、拒绝新请求、停止计时并清理在途 Git，只释放插件自身资源，保持 VFS 可用。
 *
 * 不提供 status()、GitBackupStatus 或请求结果枚举；每次执行结果只写日志。
 *
 * 宿主按既有 `AlcyoneVfs.subscribe` 接线：
 * ```
 * vfs.subscribe(backup.eventConsumer)
 * backup.start()
 * ...
 * backup.close()
 * ```
 */
class GitBackupPlugin internal constructor(
    val config: GitBackupConfig,
    private val runtime: GitBackupRuntime,
) {
    /** 生产入口：只校验配置并建立轻量对象。 */
    constructor(config: GitBackupConfig) : this(config, GitBackupRuntime.production(config))

    private val lock = Any()

    @Volatile
    private var state: State = State.CREATED

    private val consumerHolder = EventConsumerHolder()

    /** 共享清理完成信号：并发 close 等待同一次清理，而不是提前返回。 */
    private val closeCompletion = CompletableDeferred<Unit>()

    private val worker =
        GitBackupWorker(
            config = config,
            clock = runtime.clock,
            scope = runtime.scope,
            executor = runtime.executor,
            logger = runtime.logger,
        )

    /**
     * 稳定的轻量事件回调：只捕获持有器，不捕获插件实例。close 后持有器已清空，立即无操作返回。
     */
    val eventConsumer: (VfsEvent) -> Unit = consumerHolder::accept

    init {
        // 用局部变量直接捕获 Worker，避免 lambda 通过 this 访问字段而捕获整个插件。
        val workerRef = worker
        consumerHolder.attach(
            GitBackupEventConsumer(
                filter = GitBackupEventFilter(config.eventRoots),
                onRelevantEvent = { workerRef.onRelevantEvent() },
            ),
        )
    }

    /**
     * 幂等启动：第一次调用异步启动一次 STARTUP 完整检查与 Cron/防抖调度；重复调用无副作用。
     *
     * @throws IllegalStateException 实例已关闭。
     */
    fun start() {
        synchronized(lock) {
            when (state) {
                State.CLOSED -> throw IllegalStateException("GitBackupPlugin is closed and cannot be restarted")
                State.STARTED -> return
                State.CREATED -> state = State.STARTED
            }
        }
        worker.start()
    }

    /**
     * 请求一次立即完整检查，不等待执行完毕；与其它立即请求合并。
     *
     * @throws IllegalStateException 尚未 [start] 或已 [close]。
     */
    fun requestSync() {
        synchronized(lock) {
            if (state != State.STARTED) {
                throw IllegalStateException("GitBackupPlugin is not started")
            }
        }
        if (worker.terminated) {
            throw IllegalStateException("GitBackupPlugin is terminated")
        }
        worker.requestImmediate(TriggerReason.MANUAL)
    }

    /**
     * 关闭插件：断开 Consumer、拒绝新请求、停止计时并清理在途 Git。
     *
     * - 幂等；并发调用等待同一次清理完成，不提前返回；
     * - 自身清理在 [NonCancellable] 中执行，调用方取消不会跳过清理；`finally` 仍会回收自身 Scope；
     * - 只释放插件自身资源，不关闭 VFS，也不追加退出前 push。
     */
    suspend fun close() {
        val owner =
            synchronized(lock) {
                if (state == State.CLOSED) {
                    false
                } else {
                    state = State.CLOSED
                    true
                }
            }
        if (!owner) {
            closeCompletion.await()
            return
        }
        // 立即停止接纳：清空回调目标，拒绝后续事件与请求。
        consumerHolder.clear()
        try {
            withContext(NonCancellable) {
                worker.shutdown()
                runtime.scope.cancel()
                runtime.scope.coroutineContext[Job]?.join()
            }
        } finally {
            closeCompletion.complete(Unit)
        }
    }

    private enum class State {
        CREATED,
        STARTED,
        CLOSED,
    }
}
