package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.api.StorageStatus
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitter
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGeneratorImpl
import com.github.noahshen.alcyone.context.memory.core.ingestion.DelegatingIngestionService
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionService
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.extraction.DefaultExtractionRunner
import com.github.noahshen.alcyone.context.memory.runtime.extraction.DefaultPreExtractionContextBuilder
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionCallbackMarker
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionPipeline
import com.github.noahshen.alcyone.context.memory.runtime.extraction.IdleTickSource
import com.github.noahshen.alcyone.context.memory.runtime.extraction.JsonExtractionResultValidator
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.llm.KtorLlmClient
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * [AlcyoneContext] 的唯一实现（CT06-B2）。
 *
 * 资源所有权：`start` 内按 `executor → vfs → llm → extraction pipeline` 顺序创建（未配置 `context.llm`
 * 时跳过 llm；未注入 Extraction Runner 时不建 pipeline），全部成功后才发布 `RUNNING`；成功后由本实例持有，
 * 关闭时按「pipeline → llm → vfs → executor」逆序释放。
 * `ContextDatabaseImpl` 无常驻资源，只随执行器释放。`HttpClient` 只在 `start()` 内创建，
 * `create()` 仍零副作用。
 *
 * 关闭协议：
 * - [close] / [closeAsync] 共用同一个 [CompletableDeferred]：谁先到谁发起清理，其余等待同一结果；
 * - 清理本身是挂起路径（`vfs.close()` + `executor.closeAsync()`），同步入口只用 `runBlocking`
 *   桥接，避免在单线程 dispatcher 上阻塞负责恢复在途协程的线程；
 * - 等待在锁外进行，持锁期间不做任何 suspend 调用；
 * - 同步入口被 [Thread.interrupt] 时继续等待真实清理结果，并在结束后恢复中断标志；
 * - 失败终态一律 `CLOSED`：共享结果携带同一主异常（清理失败 `addSuppressed` 或作为主失败），
 *   不永久停留 `STARTING`/`STOPPING`；`CLOSED` 后 close/closeAsync 仍共享此前结果（含失败）。
 *
 * [shutdownTimeout] 只是观察期限，不是强杀上限：本实现始终等待真实 drain 完成后才发布
 * `CLOSED`，仅记录是否超过观察窗。
 */
internal class AlcyoneContextImpl(
    private val databasePath: Path,
    private val shutdownTimeout: Duration,
    private val vfsConfig: VfsRuntimeConfig,
    /** 同模块测试用的组装探针：资源取得后、发布 RUNNING 前调用；生产入口用空实现。 */
    private val startProbe: AlcyoneContextFactory.StartProbe = AlcyoneContextFactory.StartProbe { },
    /** 同模块测试用的释放探针：在释放 VFS / 执行器前调用，可制造可控清理失败。 */
    private val releaseProbe: AlcyoneContextFactory.ReleaseProbe = AlcyoneContextFactory.ReleaseProbe { },
    /** 打开数据库前的测试闸门；生产入口用空实现。 */
    private val startGate: AlcyoneContextFactory.StartGate = AlcyoneContextFactory.StartGate { },
    /** `context.ingestion` 限额（来自实例配置快照）。 */
    private val ingestionLimits: IngestionLimits = IngestionLimits.DEFAULTS,
    /**
     * `context.llm` 的实例自有快照；`null` = 未配置（保持 ingestion-only）。
     *
     * `start()` 内据此创建 [KtorLlmClient]（受管资源），关闭时最先释放。
     */
    internal val llmConfig: LlmConfig? = null,
    /** `context.extraction` 的实例自有快照（CT09-B3）；默认值已由 `ExtractionConfig` 自校验。 */
    internal val extractionConfig: ExtractionConfig = ExtractionConfig(),
    /** 内部组装入口注入的 Extraction Runner；`null` = 不建 pipeline（公共 `create` 恒为 null）。 */
    internal val extractionRunner: ExtractionRunner? = null,
    /** 内部测试用：Idle Cron 的 tick 来源（生产为 null = 按 cron 真实等待）。 */
    internal val extractionIdleTickSource: IdleTickSource? = null,
    /** 内部测试用：pipeline 构造后、`start()` 前设置接缝。 */
    internal val extractionPipelineConfigurator: ((ExtractionPipeline) -> Unit)? = null,
    /** Organization 唤醒（B3 默认 no-op；CT13 接真实 Trigger；仅 internal 注入）。 */
    internal val organizationWakeup: OrganizationWakeup = OrganizationWakeup { },
    /** 服务端 `created_at` 时钟；生产为系统 UTC，测试可注入固定时钟。 */
    private val clock: Clock = Clock.systemUTC(),
    /** Count Trigger 通知替身；生产默认 no-op，测试可注入记录替身。 */
    private val ingestionNotifier: IngestionNotifier = IngestionNotifier { },
    /** 通知失败诊断；只接收异常对象，生产用 SLF4J 仅记录类名。 */
    private val ingestionReporter: IngestionNotificationReporter = IngestionNotificationReporter { },
) : AlcyoneContext,
    IngestionLimitAware {
    private val notificationLock = Any()

    /** 通知作用域懒创建：create/构造不启动线程；首次 CREATED 通知时才建立（IO 单并发）。 */
    private var notificationScope: CoroutineScope? = null

    private val notificationJobs =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<Job>()

    /** 关闭一旦开始就不再接纳新通知；与 scope/Job 快照同受 [notificationLock] 保护。 */
    private var notificationsClosed: Boolean = false

    /** 同模块测试用：在 CREATED 已提交、通知准入之前执行的确定性闸门（生产为 null）。 */
    internal var notificationLaunchGateForTesting: (() -> Unit)? = null

    /** 同模块测试用：在关闭快照通知 Job 之前执行（锁内；生产为 null）。 */
    internal var beforeNotificationSnapshotForTesting: (() -> Unit)? = null

    /** 只用普通锁保护状态字段；所有可能挂起的等待都在锁外。 */
    private val lock = Any()

    @Volatile
    private var lifecycle: ContextLifecycleState = ContextLifecycleState.CREATED

    /** 「本实例已验证数据库可用」的缓存事实；只由 start 的成功 open 置 true，绝不探测文件。 */
    @Volatile
    private var databaseVerified: Boolean = false

    /** 启动/清理的终态失败；CLOSED 后 close/closeAsync 共享同一失败。 */
    @Volatile
    private var terminalFailure: Throwable? = null

    /** start 是否因 close 获胜而被拒绝；用于区分「正常竞态」与「真实清理失败」。 */
    @Volatile
    private var startAbortedByClose: Boolean = false

    /** 启动共享失败（正常竞态拒绝或真实失败）；并发 start 的等待者读取同一实例。 */
    @Volatile
    private var startFailure: Throwable? = null

    private var startAttempt: CompletableDeferred<Unit>? = null

    private var closeState: CloseState? = null

    private var executor: ContextDatabaseExecutor? = null

    private var database: ContextDatabaseImpl? = null

    private var vfs: AlcyoneVfs? = null

    private var ingestion: DelegatingIngestionService? = null

    /** `start()` 内创建的受管 LLM client（未配置 llm 时为 null）。 */
    private var llm: KtorLlmClient? = null

    /** `start()` 内组装的 Extraction pipeline（未注入 Runner 时为 null）。 */
    private var extractionPipeline: ExtractionPipeline? = null

    private val startWaiters = AtomicInteger()

    private val closeWaiters = AtomicInteger()

    /** 最近一次关闭是否超过观察期限；仅诊断用，不影响释放与状态发布。 */
    @Volatile
    internal var lastShutdownExceededObservationWindow: Boolean = false
        private set

    override suspend fun start() {
        var mine = false
        val shared: CompletableDeferred<Unit> =
            synchronized(lock) {
                when (lifecycle) {
                    ContextLifecycleState.RUNNING -> return
                    ContextLifecycleState.CLOSED, ContextLifecycleState.STOPPING -> throw closed("context is closed or closing")
                    ContextLifecycleState.STARTING -> {
                        startWaiters.incrementAndGet()
                        startAttempt ?: throw closed("context start state is inconsistent")
                    }
                    ContextLifecycleState.CREATED -> {
                        val created = CompletableDeferred<Unit>()
                        startAttempt = created
                        lifecycle = ContextLifecycleState.STARTING
                        mine = true
                        created
                    }
                }
            }
        if (!mine) {
            shared.await()
            startFailure?.let { throw it }
            return
        }
        performStart(shared)
    }

    private suspend fun performStart(signal: CompletableDeferred<Unit>) {
        var acquiredExecutor: ContextDatabaseExecutor? = null
        var acquiredVfs: AlcyoneVfs? = null
        var acquiredLlm: KtorLlmClient? = null
        var acquiredPipeline: ExtractionPipeline? = null
        var published = false
        var failureToThrow: Throwable? = null
        try {
            startGate.beforeOpen()
            acquiredExecutor = ContextDatabaseFactory.open(databasePath)
            val assembledDatabase = ContextDatabaseImpl(acquiredExecutor)
            acquiredVfs = AlcyoneVfs.create(vfsConfig)
            // HttpClient（含引擎）只在这里创建：create() 保持零副作用。
            acquiredLlm = llmConfig?.let { KtorLlmClient(it) }
            // pipeline 只使用已取得的 DB/配置/时钟；Runner 被激活门挡住，RUNNING 发布前不会执行。
            val pipelineRunner = extractionRunner ?: defaultExtractionRunner(assembledDatabase, acquiredLlm)
            acquiredPipeline =
                pipelineRunner?.let { runner ->
                    ExtractionPipeline(
                        config = extractionConfig,
                        database = assembledDatabase,
                        runner = runner,
                        clock = clock,
                        idleTickSource = extractionIdleTickSource,
                        callbackOwner = this,
                        reporter = ingestionReporter,
                    ).also { pipeline -> extractionPipelineConfigurator?.invoke(pipeline) }
                }
            acquiredPipeline?.start()
            startProbe.afterResourcesAcquired()
            published =
                synchronized(lock) {
                    if (lifecycle != ContextLifecycleState.STARTING) {
                        false
                    } else {
                        executor = acquiredExecutor
                        database = assembledDatabase
                        vfs = acquiredVfs
                        llm = acquiredLlm
                        extractionPipeline = acquiredPipeline
                        ingestion =
                            createIngestionService(
                                assembledDatabase,
                                acquiredPipeline?.ingestionNotifier ?: ingestionNotifier,
                            )
                        databaseVerified = true
                        lifecycle = ContextLifecycleState.RUNNING
                        true
                    }
                }
            if (!published) {
                // close 已获胜：不发布 RUNNING，自行逆序释放已取得资源；close 会等这次 start 结束。
                startAbortedByClose = true
                val rejected = closed("context was closed while starting")
                val cleanupFailure = releaseResources(acquiredPipeline, acquiredLlm, acquiredVfs, acquiredExecutor)
                val failure = cleanupFailure ?: rejected
                if (cleanupFailure != null) {
                    // 实际清理失败必须成为共享失败，close 与后续等待者都要观察到它。
                    terminalFailure = cleanupFailure
                }
                startFailure = failure
                synchronized(lock) { if (lifecycle != ContextLifecycleState.CLOSED) lifecycle = ContextLifecycleState.CLOSED }
                signal.complete(Unit)
                failureToThrow = failure
            } else {
                // RUNNING 已发布：先放行 Runner，再通知 start 等待者。
                acquiredPipeline?.activate()
                signal.complete(Unit)
            }
        } catch (failure: Throwable) {
            if (!published) {
                // 取消也可能发生在这里：状态与共享结果必须在 NonCancellable 中落定，不能停在 STARTING。
                withContext(NonCancellable) {
                    val cleanupFailure = releaseResources(acquiredPipeline, acquiredLlm, acquiredVfs, acquiredExecutor)
                    cleanupFailure?.let(failure::addSuppressed)
                    if (terminalFailure == null) terminalFailure = failure
                    if (startFailure == null) startFailure = failure
                    synchronized(lock) { if (lifecycle != ContextLifecycleState.CLOSED) lifecycle = ContextLifecycleState.CLOSED }
                    signal.complete(Unit)
                }
            }
            failureToThrow = failure
        }
        // 失败实例在信号完成前已写入 startFailure；等待者与发起者拿到同一份存储失败。
        failureToThrow?.let { throw it }
    }

    override fun close() {
        rejectIfInsideOwnExtractionCallback("close()")
        rejectIfInsideOwnNotification("close()")
        val (mine, state) = beginClose()
        var interrupted = Thread.interrupted()
        try {
            if (mine) {
                // 同步宿主：清理放在独立线程（中断状态干净），调用线程只做不可中断的等待。
                // 清理线程不会被调用者的 Thread.interrupt 打断，因此不会丢失清理责任。
                val cleanupThread =
                    Thread(
                        { runBlocking { withContext(NonCancellable) { runShutdown(state) } } },
                        "context-close",
                    ).apply { isDaemon = true }
                cleanupThread.start()
                interrupted = awaitUninterruptibly(state.latch, interrupted)
                interrupted = joinUninterruptibly(cleanupThread, interrupted)
            } else {
                interrupted = awaitUninterruptibly(state.latch, interrupted)
            }
        } finally {
            if (interrupted || Thread.currentThread().isInterrupted) Thread.currentThread().interrupt()
        }
        // 失败实例在共享结果里只存一份；在宿主线程直接抛出，避免协程边界复制成不同对象。
        terminalFailure?.let { throw it }
    }

    override suspend fun closeAsync() {
        rejectIfInsideOwnExtractionCallback("closeAsync()")
        rejectIfInsideOwnNotificationAsync("closeAsync()")
        val (mine, state) = beginClose()
        if (!mine) {
            state.completion.await()
        } else {
            // 清理放到 IO 执行器：不阻塞调用者 dispatcher，也不受其线程中断状态影响。
            withContext(NonCancellable + Dispatchers.IO) { runShutdown(state) }
        }
        // 共享结果已发布；取消优先（共享失败仍在 terminalFailure 中供其他等待者读取）。
        currentCoroutineContext().ensureActive()
        terminalFailure?.let { throw it }
    }

    /**
     * 通知内对同一实例调用 `close()` 的具名拒绝。
     *
     * 检查发生在加入共享关闭等待（[beginClose]）**之前**，因此无论外部是否已开始关闭，都不会等待、
     * 也不会自死锁。身份用协程上下文标记 + 执行期线程标记双重确认，其他 Context 实例不受影响。
     */
    private fun rejectIfInsideOwnNotification(entry: String) {
        if (NotificationExecutionMarker.currentOwner() === this) throw insideNotificationClose(entry)
    }

    /**
     * 挂起入口同样读**协程框架托管的线程值**：`ThreadContextElement` 会在挂起/恢复（含 `yield`、
     * `withContext(Dispatchers.IO)`、结构化子协程）时自动安装与还原，因此这里与同步入口判据一致。
     */
    private fun rejectIfInsideOwnNotificationAsync(entry: String) {
        rejectIfInsideOwnNotification(entry)
    }

    /**
     * Extraction Runner / Idle 扫描回调内对同一实例调用 `close()`/`closeAsync()` 的具名拒绝。
     *
     * 检查发生在 [beginClose] 改状态**之前**，因此不会等待自己（自等待），也不影响外部并发关闭。
     */
    private fun rejectIfInsideOwnExtractionCallback(entry: String) {
        ExtractionCallbackMarker.rejectionFor(this, entry)?.let { throw it }
    }

    private fun insideNotificationClose(entry: String): ContextLifecycleException =
        ContextLifecycleException(
            ContextLifecycleErrorCode.CLOSED,
            "$entry cannot be called from inside an ingestion notification; let the notification finish first",
        )

    /** 关闭的共享状态：协程等待用 [completion]，同步等待用 [latch]；两者在同一处完成。 */
    private class CloseState {
        val completion = CompletableDeferred<Unit>()
        val latch = CountDownLatch(1)

        fun finish() {
            completion.complete(Unit)
            latch.countDown()
        }
    }

    /** 首次调用者创建唯一关闭状态；其余调用者拿到同一份（含后续失败）。 */
    private fun beginClose(): Pair<Boolean, CloseState> =
        synchronized(lock) {
            val existing = closeState
            if (existing != null) {
                closeWaiters.incrementAndGet()
                false to existing
            } else {
                val created = CloseState()
                closeState = created
                true to created
            }
        }

    /** 发起者执行真实清理：失败只写入共享结果，不在这里跨协程边界抛异常。 */
    private suspend fun runShutdown(state: CloseState) {
        try {
            shutdown()
        } catch (failure: Throwable) {
            if (terminalFailure == null) terminalFailure = failure
        } finally {
            // 无论成功失败都发布同一完成信号，避免等待者永远挂起。
            state.finish()
        }
    }

    /** 不可中断地等待 latch；记录期间是否被中断（中断唤醒后继续等待真实结果）。 */
    private fun awaitUninterruptibly(
        latch: CountDownLatch,
        interruptedInitially: Boolean,
    ): Boolean {
        var interrupted = interruptedInitially
        while (true) {
            try {
                latch.await()
                return interrupted
            } catch (failure: InterruptedException) {
                interrupted = true
            }
        }
    }

    /** 不可中断地等待清理线程退出；同样保留中断事实。 */
    private fun joinUninterruptibly(
        thread: Thread,
        interruptedInitially: Boolean,
    ): Boolean {
        var interrupted = interruptedInitially
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (failure: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }

    private suspend fun shutdown() {
        val startedAt = System.nanoTime()
        var waitForStart: CompletableDeferred<Unit>? = null
        var pipelineToClose: ExtractionPipeline? = null
        var llmToClose: KtorLlmClient? = null
        var vfsToClose: AlcyoneVfs? = null
        var executorToClose: ContextDatabaseExecutor? = null
        var alreadyClosed = false
        synchronized(lock) {
            when (lifecycle) {
                ContextLifecycleState.CREATED -> {
                    lifecycle = ContextLifecycleState.CLOSED
                    alreadyClosed = true
                }
                ContextLifecycleState.CLOSED -> alreadyClosed = true
                ContextLifecycleState.STARTING -> {
                    lifecycle = ContextLifecycleState.STOPPING
                    waitForStart = startAttempt
                }
                ContextLifecycleState.RUNNING -> {
                    lifecycle = ContextLifecycleState.STOPPING
                    pipelineToClose = extractionPipeline
                    // 与 RUNNING→STOPPING 同锁封闭 extraction 准入：不得等到 cancelNotifications/releaseResources。
                    pipelineToClose?.beginClosing()
                    llmToClose = llm
                    vfsToClose = vfs
                    executorToClose = executor
                    extractionPipeline = null
                    llm = null
                    vfs = null
                    executor = null
                    database = null
                    ingestion = null
                }
                ContextLifecycleState.STOPPING -> waitForStart = startAttempt
            }
        }
        if (alreadyClosed) {
            recordShutdownWindow(startedAt)
            return
        }
        // 顺序很重要：上面已在同一把锁内把状态转为 STOPPING（CREATED 直接 CLOSED），业务准入随之封闭；
        // 之后才取消并**等待**在途通知真实退出（含 finally 与结构化子协程）。
        // 通知取消与等待参与关闭完成，观察窗计时起点包含这段等待。
        cancelNotifications()
        // close 赢得竞争时，start 会在同一次调用内逆序释放自己的资源；这里只等它结束，不抢资源。
        waitForStart?.let { attempt ->
            try {
                attempt.await()
            } catch (failure: Throwable) {
                // 正常竞态拒绝：start 已完成自己的资源释放且未写 terminalFailure，close 保持成功。
                // 真实启动失败：performStart 已写 terminalFailure，这里不覆盖；若缺失则保守记录，
                // 绝不把真实失败当成功。
                if (!startAbortedByClose && terminalFailure == null) terminalFailure = failure
            }
        }
        synchronized(lock) {
            pipelineToClose = pipelineToClose ?: extractionPipeline
            // 兜底：任何路径取到 pipeline 都先封闭准入（幂等、短同步）。
            pipelineToClose?.beginClosing()
            llmToClose = llmToClose ?: llm
            vfsToClose = vfsToClose ?: vfs
            executorToClose = executorToClose ?: executor
            extractionPipeline = null
            llm = null
            vfs = null
            executor = null
            database = null
            ingestion = null
            if (lifecycle != ContextLifecycleState.CLOSED) lifecycle = ContextLifecycleState.STOPPING
        }

        val failures = mutableListOf<Throwable>()
        releaseResources(pipelineToClose, llmToClose, vfsToClose, executorToClose)?.let(failures::add)
        synchronized(lock) { lifecycle = ContextLifecycleState.CLOSED }
        recordShutdownWindow(startedAt)
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    /**
     * 默认提取 Runner（仅在未注入 Runner 且配置了 `context.llm` 时构造，CT11-B3）。
     *
     * 组合：CT10 [DefaultPreExtractionContextBuilder]（含 `requestFor`）→ B1 [JsonExtractionResultValidator] /
     * [ExtractionCommitter] → B2 [DefaultExtractionRunner]，由 CT09 pipeline 驱动。
     * 配置非法（缺 EXTRACTION Task / `PROMPT_ONLY`）在 Builder 构造期抛 `ContextConfigException(INVALID_CONFIG)`，
     * 由 [performStart] 的既有失败路径逆序清理并落 `CLOSED`。
     */
    private fun defaultExtractionRunner(
        database: ContextDatabaseImpl,
        llmClient: KtorLlmClient?,
    ): ExtractionRunner? {
        val config = llmConfig ?: return null
        // 合法性判定（CT11-B3 §2.1）：不合法绑定不构造 Builder/pipeline，保留既有 LLM client 生命周期。
        if (!hasLegalExtractionBinding(config)) return null
        val client = llmClient ?: return null
        val builder =
            DefaultPreExtractionContextBuilder(
                database = database,
                llmConfig = config,
                extractionConfig = extractionConfig,
                estimator = ConservativeTokenEstimator(),
            )
        return DefaultExtractionRunner(
            builder = builder,
            requestFor = builder::requestFor,
            llmClient = client,
            validator = JsonExtractionResultValidator(),
            committer = ExtractionCommitter(database, MemoryIdGeneratorImpl(clock), clock),
            database = database,
            maxWindowIterations = extractionConfig.initialWindowTurns,
            wakeup = organizationWakeup,
        )
    }

    /**
     * 是否存在**合法 extraction 绑定**（CT11-B3 §2.1）：
     * ① 配置了 `LlmTask.EXTRACTION` Task；② 其 Profile 存在；③ `jsonMode` 为 `JSON_SCHEMA`/`JSON_OBJECT`。
     *
     * 三者缺一（无 EXTRACTION Task / Profile 缺失 / `PROMPT_ONLY`）→ **不建 extraction pipeline**，
     * 但保留既有 LLM client 生命周期（CT08 通用 LLM 配置仍可正常 start）。
     * 绑定合法时仍由 [DefaultPreExtractionContextBuilder] 构造期做最终校验，失败按既有 start 失败路径处理（不吞）。
     */
    private fun hasLegalExtractionBinding(config: LlmConfig): Boolean {
        val task = config.tasks[LlmTask.EXTRACTION] ?: return false
        val profile = config.profiles[task.profile] ?: return false
        return profile.jsonMode == LlmResponseFormat.JSON_SCHEMA || profile.jsonMode == LlmResponseFormat.JSON_OBJECT
    }

    /**
     * 逆序释放资源：Extraction pipeline 先关（先 Idle Cron/扫描、再 Scheduler/Runner 真实 join），
     * LLM client 次之（取消在途并等待真实收尾），VFS 再次之（自行停止准入并等在途），
     * 执行器最后关（CT04 在途 drain 后关连接）。
     *
     * 所有资源都尽力释放：任一失败也继续关；首个失败作为主失败返回，其余 `addSuppressed`。
     */
    private suspend fun releaseResources(
        acquiredPipeline: ExtractionPipeline?,
        acquiredLlm: KtorLlmClient?,
        acquiredVfs: AlcyoneVfs?,
        acquiredExecutor: ContextDatabaseExecutor?,
    ): Throwable? {
        var primary: Throwable? = null
        withContext(NonCancellable) {
            acquiredPipeline?.let { pipeline ->
                try {
                    pipeline.closeAsync()
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
            }
            acquiredLlm?.let { client ->
                try {
                    client.closeAsync()
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
            }
            acquiredVfs?.let { running ->
                try {
                    releaseProbe.beforeRelease(AlcyoneContextFactory.ReleaseTarget.VFS)
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
                try {
                    running.close()
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
            }
            acquiredExecutor?.let { databaseExecutor ->
                try {
                    releaseProbe.beforeRelease(AlcyoneContextFactory.ReleaseTarget.EXECUTOR)
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
                try {
                    databaseExecutor.closeAsync()
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
            }
        }
        return primary
    }

    private fun chain(
        primary: Throwable?,
        next: Throwable,
    ): Throwable {
        if (primary == null) return next
        primary.addSuppressed(next)
        return primary
    }

    /** 观察期限只记录事实，不参与任何「提前发布 CLOSED / 抢关连接」的决策。 */
    private fun recordShutdownWindow(startedAt: Long) {
        lastShutdownExceededObservationWindow = System.nanoTime() - startedAt > shutdownTimeout.inWholeNanoseconds
    }

    /** 测试/诊断用：已在 STARTING 上等待的并发 start 次数。 */
    internal fun startWaiterEnteredForTesting(): Int = startWaiters.get()

    /** 测试/诊断用：已在等待共享关闭结果的非 owner 调用次数。 */
    internal fun closeWaiterEnteredForTesting(): Int = closeWaiters.get()

    /** 测试/诊断用：`start()` 内创建的 LLM client（未配置/未运行/已关闭为 null），不对外公开。 */
    internal fun llmClientForTesting(): KtorLlmClient? = synchronized(lock) { llm }

    /** 测试/诊断用：当前组装的 Extraction pipeline（未注入 Runner/未运行/已关闭为 null）。 */
    internal fun pipelineForTesting(): ExtractionPipeline? = synchronized(lock) { extractionPipeline }

    /** 测试/诊断用：当前持有的 VFS（未运行或已关闭为 null），不对外公开。 */
    internal fun vfsForTesting(): AlcyoneVfs? = vfs

    /** 测试/诊断用：共享终态失败（含启动失败与清理失败）。 */
    internal fun terminalFailureForTesting(): Throwable? = terminalFailure

    override val ingestionMaxTurnBytes: Int get() = ingestionLimits.maxTurnBytes

    /** 测试/诊断用：当前线程/协程是否正处于本实例的通知执行中。 */
    internal fun insideNotificationForTesting(): Boolean = NotificationExecutionMarker.currentOwner() === this

    /** 测试/诊断用：在途/排队通知 Job 数量。 */
    internal fun notificationJobCountForTesting(): Int = notificationJobs.size

    /** 测试/诊断用：通知作用域是否已被创建（懒创建判据）。 */
    internal fun notificationScopeCreatedForTesting(): Boolean = synchronized(notificationLock) { notificationScope != null }

    /** 测试/诊断用：当前持有的数据库入口（未运行或已关闭为 null），不对外公开。 */
    internal fun databaseForTesting(): ContextDatabaseImpl? = database

    override suspend fun ingestConversation(turn: ConversationTurn): IngestResult {
        val service =
            when (lifecycle) {
                ContextLifecycleState.CREATED, ContextLifecycleState.STARTING ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.NOT_STARTED,
                        "context is not started; call start() before ingesting conversations",
                    )
                ContextLifecycleState.STOPPING, ContextLifecycleState.CLOSED ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.CLOSED,
                        "context is closing or closed; conversation ingestion is refused",
                    )
                ContextLifecycleState.RUNNING ->
                    ingestion ?: throw ContextLifecycleException(
                        ContextLifecycleErrorCode.NOT_STARTED,
                        "context is not started; call start() before ingesting conversations",
                    )
            }
        val result = service.ingest(turn)
        if (result.status == IngestStatus.CREATED) {
            // 仅在 COMMIT 成功后、事务外启动异步通知；不等待结果，失败交给 reporter 脱敏记录。
            notificationLaunchGateForTesting?.invoke()
            val admitted =
                launchNotification {
                    service.notifyCreated(SessionKey(turn.runtimeId, turn.agentId, turn.sessionId))
                }
            if (!admitted) {
                // close 已获胜：Turn 已提交，但通知未被准入；固定脱敏取消上报，不得静默丢弃。
                reportNotificationCancellation()
            }
        }
        return result
    }

    private fun createIngestionService(
        database: ContextDatabaseImpl,
        notifier: IngestionNotifier,
    ): DelegatingIngestionService = IngestionService.create(ingestionLimits, clock, notifier, ingestionReporter) { database }

    /**
     * 尝试接纳一条通知：close 已获胜时返回 false，不创建 scope/Job（由调用方用固定脱敏取消上报）。
     *
     * Job 以 LAZY 创建并在 start 前加入跟踪集合，确保「close 在解锁后、start 前获胜」也能取消它；
     * 锁内不做任何 suspend。
     */
    private fun launchNotification(block: suspend () -> Unit): Boolean {
        val job: Job =
            synchronized(notificationLock) {
                if (notificationsClosed) return false
                val scope =
                    notificationScope
                        ?: CoroutineScope(
                            SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + NotificationExecutionMarker(this),
                        ).also { notificationScope = it }
                val created = scope.launch(start = CoroutineStart.LAZY) { block() }
                notificationJobs += created
                created
            }
        job.invokeOnCompletion { notificationJobs.remove(job) }
        job.start()
        return true
    }

    /**
     * 停止通知准入、取消在途通知，并**等待它们真实退出**（含 `finally` 与结构化子协程）。
     *
     * 锁内先置 [notificationsClosed] 并快照 scope/Job（此后不会再接纳或新建通知），锁外取消再
     * 逐个 `join()`；**不以 `clear()` 或计数 0 作为退出判据**。对关闭时仍在跑的 Job 上报固定脱敏取消。
     */
    private suspend fun cancelNotifications() {
        val scope: CoroutineScope?
        val pending: List<Job>
        synchronized(notificationLock) {
            if (notificationsClosed) return
            notificationsClosed = true
            scope = notificationScope
            notificationScope = null
            beforeNotificationSnapshotForTesting?.invoke()
            pending = snapshotNotificationJobs()
        }
        scope?.cancel()
        pending.filter { !it.isCompleted }.forEach { reportNotificationCancellation() }
        // join() 覆盖 finally 与结构化子协程：gate 未放行时这里不会返回。
        pending.forEach { it.join() }
    }

    /**
     * 取在途通知 Job 的快照（F1-R1 竞态修复）。
     *
     * **不用** `Iterable.toList()`：它对 `Collection` 先读 `size`，`size == 1` 时走
     * `iterator().next()`；而 `invokeOnCompletion` 会在别的线程并发移除该唯一元素，于是迭代器已空、
     * `next()` 抛 `NoSuchElementException`。这里改成显式迭代 `ConcurrentHashMap.newKeySet()`：
     * 它的迭代器弱一致，移除的元素自然缺席，也不会因并发移除抛异常。
     */
    private fun snapshotNotificationJobs(): List<Job> {
        val snapshot = ArrayList<Job>(notificationJobs.size)
        for (job in notificationJobs) {
            snapshot += job
        }
        return snapshot
    }

    /** 固定脱敏取消：只含固定上下文，不含 SessionKey/正文；reporter 自身失败不影响清理。 */
    private fun reportNotificationCancellation() {
        val cancellation = CancellationException("conversation ingestion notification cancelled")
        runCatching { ingestionReporter.reportFailure(cancellation) }
    }

    override suspend fun getStatus(): ContextStatus =
        ContextStatus(
            lifecycle = lifecycle,
            // CM1 不创建 Worker，不伪造 RUNNING。
            workers = emptyMap(),
            storage =
                StorageStatus(
                    databasePath = databasePath.toString(),
                    databaseInitialized = databaseVerified,
                ),
        )

    private fun closed(message: String): ContextLifecycleException = ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, message)
}
