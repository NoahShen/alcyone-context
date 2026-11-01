package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.coroutines.CoroutineContext

/**
 * CT09-B3 Extraction Pipeline 组装（Runtime internal）。
 *
 * 组件关系：`CountTrigger`（作为 `IngestionNotifier`）与 `IdleTrigger` 都只向同一个
 * [ExtractionScheduler] 提交 `SessionKey`；`IdleCron` 仅在 `idle.enabled=false` 时不创建/不启动。
 *
 * **激活门**：包装后的 Runner 先 `await` 内部激活信号，只有 Context 成功发布 `RUNNING` 并调用
 * [activate] 后才真正执行——业务 Runner 不会在 `RUNNING` 之前运行（启动阶段的 tick/请求会等待）。
 *
 * **关闭**：先关 `IdleCron`（停 tick、取消并 join 在途扫描），再关 [ExtractionScheduler]
 * （取消并真实 join Worker/Runner）；两者尽力执行，失败按主失败 + `addSuppressed` 链式保留，最后抛主失败。
 *
 * **准入关闭门**：[beginClosing] 由 Context 在 `RUNNING → STOPPING` 的**同一把状态锁内**调用（普通短同步、
 * 不挂起、不等待、不回调 Context）；门关闭后 Idle/Count 请求直接 no-op，包装 Runner 在 `activation.await()`
 * 之后、真实 Runner 之前再次检查门——因此 STOPPING 之后不会**新启动** Runner（已在途的由 [closeAsync] 取消/等待）。
 *
 * **回调标记**：Runner 执行与 Idle 请求回调安装 [ExtractionCallbackMarker]（owner = Context 实例），
 * 供 Context 在改关闭状态之前具名拒绝「从 extraction runner / scan 回调内关闭自己」。
 *
 * 测试接缝（全部 internal，生产不设置）：[startFailureForTesting]、[closeFailureForTesting]、
 * [closeHookForTesting]、[idleRequestHookForTesting] 与只读组件访问器。
 */
internal class ExtractionPipeline(
    config: ExtractionConfig,
    private val database: ContextDatabase,
    runner: ExtractionRunner,
    clock: Clock,
    idleTickSource: IdleTickSource?,
    private val callbackOwner: Any,
    reporter: IngestionNotificationReporter = IngestionNotificationReporter { },
) {
    private val activation = CompletableDeferred<Unit>()

    /** 准入门锁：只保护一个布尔标志，锁内无挂起/无 I/O/无 Context 调用。 */
    private val admissionLock = Any()

    /** 关闭门：置位后不再接受新的 Idle/Count 请求，也不启动新的 Runner。 */
    private var closing: Boolean = false

    /** 测试接缝：`start()` 直接抛出该失败（模拟 pipeline 启动失败）。 */
    internal var startFailureForTesting: Throwable? = null

    /** 测试接缝：`closeAsync()` 末尾抛出该失败（模拟 pipeline 清理失败）。 */
    internal var closeFailureForTesting: Throwable? = null

    /** 测试接缝：`closeAsync()` **最先**执行该钩子（记录释放顺序）。 */
    internal var closeHookForTesting: (() -> Unit)? = null

    /** 测试接缝：Idle 请求回调内（已安装回调标记）执行的钩子。 */
    internal var idleRequestHookForTesting: (() -> Unit)? = null

    private val scheduler: ExtractionScheduler

    private val idleTrigger: IdleTrigger

    private val idleCron: IdleCron?

    /** Count Trigger：Context 在装配 IngestionService 时使用它作为 notifier。 */
    val ingestionNotifier: IngestionNotifier

    init {
        // 激活门 + 回调标记：RUNNING 之前不执行 Runner。
        val gatedRunner =
            object : ExtractionRunner {
                override suspend fun execute(sessionKey: SessionKey) {
                    activation.await()
                    // 关闭门：STOPPING 之后（含已排队项）不得新启动 Runner。
                    if (!admissionOpen()) return
                    withContext(ExtractionCallbackMarker(callbackOwner)) {
                        runner.execute(sessionKey)
                    }
                }
            }
        scheduler = ExtractionScheduler(gatedRunner)
        idleTrigger =
            IdleTrigger(
                databaseProvider = { database },
                idleTimeout = config.idleTimeout,
                clock = clock,
                request = { sessionKey -> requestFromIdle(sessionKey) },
            )
        idleCron =
            if (config.idleEnabled) {
                IdleCron(idleTrigger = idleTrigger, cron = config.cron, clock = clock, tickSource = idleTickSource)
            } else {
                null
            }
        ingestionNotifier =
            CountTrigger(
                threshold = config.countThreshold,
                databaseProvider = { database },
                request = { sessionKey -> requestFromCount(sessionKey) },
                reporter = reporter,
            )
    }

    /** 测试/诊断用：Idle Cron（`idle.enabled=false` 时为 `null`）。 */
    internal fun idleCronForTesting(): IdleCron? = idleCron

    internal fun schedulerForTesting(): ExtractionScheduler = scheduler

    internal fun idleTriggerForTesting(): IdleTrigger = idleTrigger

    internal fun isActivatedForTesting(): Boolean = activation.isCompleted

    /**
     * 启动 Scheduler，并在 `idle.enabled=true` 时启动 Idle Cron（Runner 仍被激活门挡住）。
     *
     * @throws Throwable 启动失败（由 Context 负责逆序清理）。
     */
    fun start() {
        startFailureForTesting?.let { throw it }
        scheduler.start()
        idleCron?.start()
    }

    /** Context 发布 `RUNNING` 之后调用：放行 Runner 执行（幂等；与关闭门相互独立）。 */
    fun activate() {
        activation.complete(Unit)
    }

    /**
     * 关闭准入门（幂等）：由 Context 在 `RUNNING → STOPPING` 的同一状态锁内调用。
     *
     * 只做普通短同步（一个布尔标志），**不挂起、不等待、不调用 Context**，因此可在状态锁内安全调用。
     */
    fun beginClosing() {
        synchronized(admissionLock) { closing = true }
    }

    internal fun isClosingForTesting(): Boolean = !admissionOpen()

    private fun admissionOpen(): Boolean = synchronized(admissionLock) { !closing }

    /**
     * 关闭：先 Idle Cron（停 tick、join 在途扫描），再 Scheduler（join Worker/Runner）。
     *
     * 两者都尽力执行；失败保留主失败、其余 `addSuppressed`，最后抛主失败（清理失败不隐藏）。
     */
    suspend fun closeAsync() {
        // 覆盖启动失败/直接关闭路径：先封闭准入，再按关闭顺序收尾。
        beginClosing()
        var primary: Throwable? = null
        closeHookForTesting?.let { hook ->
            try {
                hook()
            } catch (failure: Throwable) {
                primary = chain(primary, failure)
            }
        }
        try {
            idleCron?.closeAsync()
        } catch (failure: Throwable) {
            primary = chain(primary, failure)
        }
        try {
            scheduler.closeAsync()
        } catch (failure: Throwable) {
            primary = chain(primary, failure)
        }
        // 无论是否失败都放开激活门：避免任何仍在等待门信号的调用悬挂。
        activation.complete(Unit)
        closeFailureForTesting?.let { injected -> primary = chain(primary, injected) }
        primary?.let { throw it }
    }

    /** Idle 请求：门关闭后直接 no-op；否则在回调标记下提交给 Scheduler。 */
    private fun requestFromIdle(sessionKey: SessionKey): Boolean {
        if (!admissionOpen()) return false
        return ExtractionCallbackMarker.withOwner(callbackOwner) {
            idleRequestHookForTesting?.invoke()
            if (!admissionOpen()) return@withOwner false
            scheduler.request(sessionKey)
        }
    }

    /** Count 请求：门关闭后直接 no-op；否则沿用 CT07/CT08 通知路径只提交 SessionKey。 */
    private fun requestFromCount(sessionKey: SessionKey): Boolean {
        if (!admissionOpen()) return false
        return scheduler.request(sessionKey)
    }

    private fun chain(
        primary: Throwable?,
        next: Throwable,
    ): Throwable {
        if (primary == null) return next
        primary.addSuppressed(next)
        return primary
    }
}

/**
 * Extraction 回调「来源」标记（复用既有 `NotificationExecutionMarker` 模式）。
 *
 * - 协程上下文元素 + [ThreadContextElement]：覆盖 Runner 的 `suspend` 执行（跨 `yield`/`withContext` 传播）；
 * - [withOwner]：覆盖 `IdleTrigger.request` 这类**非挂起**同步回调（Idle 请求）。
 *
 * 身份用对象引用比较：只有「发起该回调的那个 Context 实例」才算重入。
 */
internal class ExtractionCallbackMarker(
    private val owner: Any,
) : ThreadContextElement<Any?>,
    CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Any? {
        val previous = CURRENT_THREAD_MARKER.get()
        CURRENT_THREAD_MARKER.set(owner)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Any?,
    ) {
        if (oldState == null) {
            CURRENT_THREAD_MARKER.remove()
        } else {
            CURRENT_THREAD_MARKER.set(oldState)
        }
    }

    companion object Key : CoroutineContext.Key<ExtractionCallbackMarker> {
        private val CURRENT_THREAD_MARKER = ThreadLocal<Any?>()

        /** 当前线程正在执行的 extraction 回调归属；非回调期间为 null。 */
        fun currentOwner(): Any? = CURRENT_THREAD_MARKER.get()

        /** 在同步回调内安装标记（含嵌套/异常的可靠恢复）。 */
        fun <T> withOwner(
            owner: Any,
            block: () -> T,
        ): T {
            val previous = CURRENT_THREAD_MARKER.get()
            CURRENT_THREAD_MARKER.set(owner)
            try {
                return block()
            } finally {
                if (previous == null) CURRENT_THREAD_MARKER.remove() else CURRENT_THREAD_MARKER.set(previous)
            }
        }

        /** Context 在改关闭状态前使用的具名拒绝（仅匹配自己的回调）。 */
        fun rejectionFor(
            owner: Any,
            entry: String,
        ): ContextLifecycleException? {
            if (currentOwner() !== owner) return null
            return ContextLifecycleException(
                ContextLifecycleErrorCode.CLOSED,
                "$entry cannot be called from inside an extraction runner/scan callback; let the callback finish first",
            )
        }
    }
}
