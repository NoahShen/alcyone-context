package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.BootstrapContext
import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.DreamingResult
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.api.StorageStatus
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.api.WorkerStatus
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
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitter
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingConfig
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingPeriodicTickSource
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingRuntime
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.validateDreamingBinding
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
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapGenerator
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapGeneratorPort
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicTickSource
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPipeline
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPublisher
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapService
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapWorkerStatus
import com.github.noahshen.alcyone.context.memory.runtime.organization.DefaultOrganizationWorker
import com.github.noahshen.alcyone.context.memory.runtime.organization.LlmCandidateRetriever
import com.github.noahshen.alcyone.context.memory.runtime.organization.LlmGovernanceService
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationCommitOperation
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationGovernanceDecider
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationMapWakeup
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPeriodicTickSource
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPipeline
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationRoundRunner
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationSourceGuard
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationWorkerStatus
import com.github.noahshen.alcyone.context.memory.runtime.resource.ContextResourceAccess
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.MemorySearchService
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.RetrievalConfig
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
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * [AlcyoneContext] 的唯一实现（CT06-B2）。
 *
 * 资源所有权：`start` 内按 `executor → vfs → llm → organization → memory map → extraction`
 * 顺序创建，全部成功后才发布 `RUNNING`；成功后由本实例持有，关闭时按
 * 「extraction → organization → memory map → llm → vfs → executor」顺序释放。
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
     * `start()` 内据此创建 [KtorLlmClient]（受管资源），关闭时在三条 pipeline 之后释放。
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
    /** 显式测试注入的 Extraction → Organization wakeup；null 时使用真实 OrganizationPipeline.wakeup。 */
    internal val organizationWakeup: OrganizationWakeup? = null,
    /** `context.organization` 配置快照；null 表示不创建 Organization pipeline。 */
    internal val organizationConfig: OrganizationConfig? = null,
    /** 内部测试用：Periodic Cron tick 来源。 */
    internal val organizationTickSource: OrganizationPeriodicTickSource? = null,
    /** 内部测试用：Organization pipeline 构造后、start 前接缝。 */
    internal val organizationPipelineConfigurator: ((OrganizationPipeline) -> Unit)? = null,
    /** 测试可替换 Map 通知；null 时使用当前 MemoryMapPipeline 的生产刷新闭包。 */
    internal val organizationMapWakeup: OrganizationMapWakeup? = null,
    /** 内部测试用：替换真实 Organization Worker，Scheduler/Cron/Pipeline 仍为生产实现。 */
    internal val organizationRunner: OrganizationRoundRunner? = null,
    /** `context.memoryMap` 配置快照；null 表示不创建 Map pipeline。 */
    internal val memoryMapConfig: MemoryMapConfig? = null,
    /** 内部测试用：替换真实 Memory Map Generator；null 时使用生产 Generator。 */
    internal val memoryMapGenerator: MemoryMapGeneratorPort? = null,
    /** 内部测试用：Periodic Map Cron tick 来源。 */
    internal val memoryMapTickSource: MemoryMapPeriodicTickSource? = null,
    /** 内部测试用：Memory Map pipeline 构造后、start 前接缝。 */
    internal val memoryMapPipelineConfigurator: ((MemoryMapPipeline) -> Unit)? = null,
    internal val dreamingConfig: DreamingConfig = DreamingConfig(),
    internal val dreamingTickSource: DreamingPeriodicTickSource? = null,
    internal val dreamingRuntimeConfigurator: ((DreamingRuntime) -> Unit)? = null,
    /** `context.retrieval` 配置快照（CT20-B2）。 */
    internal val retrievalConfig: RetrievalConfig = RetrievalConfig(),
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

    /** RUNNING 时绑定当前 VFS；关闭开始后立即清空，冷 Flow 不会继续取得旧引用。 */
    private var resourceAccess: ContextResourceAccess? = null

    private var ingestion: DelegatingIngestionService? = null

    /** `start()` 内创建的受管 LLM client（未配置 llm 时为 null）。 */
    private var llm: KtorLlmClient? = null

    /** `start()` 内组装的 Extraction pipeline（未注入 Runner 时为 null）。 */
    private var extractionPipeline: ExtractionPipeline? = null

    /** `start()` 内组装的 Organization pipeline（未配置 organization 时为 null）。 */
    private var organizationPipeline: OrganizationPipeline? = null

    /** `start()` 内组装的 Memory Map pipeline（未配置 memoryMap 时为 null）。 */
    private var memoryMapPipeline: MemoryMapPipeline? = null
    private var dreamingRuntime: DreamingRuntime? = null

    /** 生产默认 Map wakeup：仅在当前实例持有 pipeline 时合并一次刷新信号。 */
    private val effectiveOrganizationMapWakeup: OrganizationMapWakeup =
        organizationMapWakeup ?: OrganizationMapWakeup { currentMemoryMapPipeline()?.requestRefresh() }

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
        var acquiredOrganizationPipeline: OrganizationPipeline? = null
        var acquiredMemoryMapPipeline: MemoryMapPipeline? = null
        var acquiredDreamingRuntime: DreamingRuntime? = null
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
            acquiredOrganizationPipeline =
                defaultOrganizationPipeline(assembledDatabase, acquiredLlm)
                    ?.also { pipeline -> organizationPipelineConfigurator?.invoke(pipeline) }
            acquiredMemoryMapPipeline =
                defaultMemoryMapPipeline(assembledDatabase, acquiredLlm, acquiredVfs)
                    ?.also { pipeline -> memoryMapPipelineConfigurator?.invoke(pipeline) }
            val organizationWakeup =
                organizationWakeup ?: acquiredOrganizationPipeline?.wakeup ?: OrganizationWakeup { }
            acquiredDreamingRuntime =
                defaultDreamingRuntime(
                    assembledDatabase,
                    acquiredLlm,
                    organizationWakeup,
                )?.also { dreamingRuntimeConfigurator?.invoke(it) }
            // pipeline 只使用已取得的 DB/配置/时钟；Runner 被激活门挡住，RUNNING 发布前不会执行。
            val pipelineRunner = extractionRunner ?: defaultExtractionRunner(assembledDatabase, acquiredLlm, organizationWakeup)
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
            acquiredOrganizationPipeline?.start()
            acquiredMemoryMapPipeline?.start()
            acquiredDreamingRuntime?.start()
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
                        resourceAccess = acquiredVfs?.let(::ContextResourceAccess)
                        llm = acquiredLlm
                        organizationPipeline = acquiredOrganizationPipeline
                        memoryMapPipeline = acquiredMemoryMapPipeline
                        dreamingRuntime = acquiredDreamingRuntime
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
                val cleanupFailure =
                    releaseResources(
                        acquiredPipeline,
                        acquiredOrganizationPipeline,
                        acquiredMemoryMapPipeline,
                        acquiredDreamingRuntime,
                        acquiredLlm,
                        acquiredVfs,
                        acquiredExecutor,
                    )
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
                acquiredOrganizationPipeline?.activate()
                acquiredPipeline?.activate()
                acquiredMemoryMapPipeline?.activate()
                signal.complete(Unit)
            }
        } catch (failure: Throwable) {
            if (!published) {
                // 取消也可能发生在这里：状态与共享结果必须在 NonCancellable 中落定，不能停在 STARTING。
                withContext(NonCancellable) {
                    val cleanupFailure =
                        releaseResources(
                            acquiredPipeline,
                            acquiredOrganizationPipeline,
                            acquiredMemoryMapPipeline,
                            acquiredDreamingRuntime,
                            acquiredLlm,
                            acquiredVfs,
                            acquiredExecutor,
                        )
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
        rejectIfInsideOwnOrganizationCallback("close()")
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
        rejectIfInsideOwnOrganizationCallback("closeAsync()")
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

    private fun rejectIfInsideOwnOrganizationCallback(entry: String) {
        OrganizationCallbackMarker.rejectionFor(this, entry)?.let { throw it }
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
        var organizationPipelineToClose: OrganizationPipeline? = null
        var memoryMapPipelineToClose: MemoryMapPipeline? = null
        var dreamingRuntimeToClose: DreamingRuntime? = null
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
                    organizationPipelineToClose = organizationPipeline
                    memoryMapPipelineToClose = memoryMapPipeline
                    dreamingRuntimeToClose = dreamingRuntime
                    // 与 RUNNING→STOPPING 同锁封闭三条 pipeline 准入。
                    pipelineToClose?.beginClosing()
                    organizationPipelineToClose?.beginClosing()
                    memoryMapPipelineToClose?.beginClosing()
                    dreamingRuntimeToClose?.beginClosing()
                    llmToClose = llm
                    vfsToClose = vfs
                    executorToClose = executor
                    extractionPipeline = null
                    organizationPipeline = null
                    memoryMapPipeline = null
                    dreamingRuntime = null
                    llm = null
                    vfs = null
                    resourceAccess = null
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
            organizationPipelineToClose = organizationPipelineToClose ?: organizationPipeline
            memoryMapPipelineToClose = memoryMapPipelineToClose ?: memoryMapPipeline
            dreamingRuntimeToClose = dreamingRuntimeToClose ?: dreamingRuntime
            // 兜底：任何路径取到 pipeline 都先封闭准入（幂等、短同步）。
            pipelineToClose?.beginClosing()
            organizationPipelineToClose?.beginClosing()
            memoryMapPipelineToClose?.beginClosing()
            dreamingRuntimeToClose?.beginClosing()
            llmToClose = llmToClose ?: llm
            vfsToClose = vfsToClose ?: vfs
            executorToClose = executorToClose ?: executor
            extractionPipeline = null
            organizationPipeline = null
            memoryMapPipeline = null
            dreamingRuntime = null
            llm = null
            vfs = null
            resourceAccess = null
            executor = null
            database = null
            ingestion = null
            if (lifecycle != ContextLifecycleState.CLOSED) lifecycle = ContextLifecycleState.STOPPING
        }

        val failures = mutableListOf<Throwable>()
        releaseResources(
            pipelineToClose,
            organizationPipelineToClose,
            memoryMapPipelineToClose,
            dreamingRuntimeToClose,
            llmToClose,
            vfsToClose,
            executorToClose,
        )?.let(failures::add)
        synchronized(lock) { lifecycle = ContextLifecycleState.CLOSED }
        recordShutdownWindow(startedAt)
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    /** 组装真实 Organization Pipeline；测试可用 Runner 替换业务 Worker，但 Scheduler/Cron/门仍走生产实现。 */
    private fun defaultOrganizationPipeline(
        database: ContextDatabaseImpl,
        llmClient: KtorLlmClient?,
    ): OrganizationPipeline? {
        val config = organizationConfig ?: return null
        val ownedLlm = llmConfig ?: throw invalidOrganizationConfig("context.llm is required for organization")
        val client = llmClient ?: throw invalidOrganizationConfig("context.llm client is required for organization")
        val candidateRetriever = LlmCandidateRetriever(database, client, ownedLlm)
        val governanceService = LlmGovernanceService(client, ownedLlm)
        val committer = OrganizationCommitter(database, MemoryIdGeneratorImpl(clock), clock)
        val productionWorker =
            DefaultOrganizationWorker(
                database = database,
                sourceGuard = OrganizationSourceGuard(database),
                candidateRetriever = candidateRetriever,
                governanceDecider =
                    OrganizationGovernanceDecider { rawMemory, candidates ->
                        governanceService.decide(rawMemory, candidates)
                    },
                commit =
                    OrganizationCommitOperation { rawId, candidates, decision ->
                        committer.commit(rawId, candidates, decision)
                    },
                mapWakeup = effectiveOrganizationMapWakeup,
            )
        val selectedRunner = organizationRunner ?: productionWorker
        val callbackRunner =
            OrganizationRoundRunner {
                withContext(OrganizationCallbackMarker(this@AlcyoneContextImpl)) {
                    selectedRunner.runRound()
                }
            }
        return OrganizationPipeline(
            config = config,
            database = database,
            runner = callbackRunner,
            clock = clock,
            tickSource = organizationTickSource,
        )
    }

    /** 组装生产 Memory Map Pipeline；Generator/Publisher/Service 均只使用本次 start 已取得的资源。 */
    private fun defaultMemoryMapPipeline(
        database: ContextDatabaseImpl,
        llmClient: KtorLlmClient?,
        vfs: AlcyoneVfs?,
    ): MemoryMapPipeline? {
        val config = memoryMapConfig ?: return null
        val ownedLlm = llmConfig ?: throw invalidMemoryMapConfig("context.llm is required for memory map")
        val client = llmClient ?: throw invalidMemoryMapConfig("context.llm client is required for memory map")
        val runningVfs = vfs ?: throw invalidMemoryMapConfig("vfs is required for memory map")
        val generator = memoryMapGenerator ?: MemoryMapGenerator(database, client, ownedLlm, clock)
        val service = MemoryMapService(generator, MemoryMapPublisher(runningVfs))
        return MemoryMapPipeline(
            config = config,
            database = database,
            vfs = runningVfs,
            service = service,
            clock = clock,
            tickSource = memoryMapTickSource,
        )
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
        organizationWakeup: OrganizationWakeup,
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

    /** Four complete bindings enable manual runs even without Cron; partial bindings fail before use. */
    private fun defaultDreamingRuntime(
        database: ContextDatabaseImpl,
        llmClient: KtorLlmClient?,
        wakeup: OrganizationWakeup,
    ): DreamingRuntime? {
        val ownedLlm = llmConfig ?: return null
        val client = llmClient ?: return null
        validateDreamingBinding(dreamingConfig, ownedLlm)
        // 无 DREAMING 专属 Task 且无 Cron 时不建 Runtime（manual-only 无绑定兼容）。
        val hasDreamingTasks =
            ownedLlm.tasks.keys.any { it in setOf(LlmTask.DREAMING_TOPIC, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION) }
        if (dreamingConfig.cron == null && !hasDreamingTasks) return null
        return DreamingRuntime(
            config = dreamingConfig,
            database = database,
            llmClient = client,
            llmConfig = ownedLlm,
            organizationWakeup = wakeup,
            clock = clock,
            idGenerator = MemoryIdGeneratorImpl(clock),
            tickSource = dreamingTickSource,
        )
    }

    override suspend fun runDreaming(): DreamingResult {
        val runtime =
            synchronized(lock) {
                if (lifecycle != ContextLifecycleState.RUNNING) throw closed("runDreaming() requires RUNNING context")
                dreamingRuntime ?: throw invalidOrganizationConfig("dreaming requires all four LLM task bindings")
            }
        return runtime.runOnce()
    }

    private fun invalidOrganizationConfig(detail: String): ContextConfigException =
        ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)

    private fun invalidMemoryMapConfig(detail: String): ContextConfigException =
        ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)

    /**
     * 固定释放顺序：Extraction → Organization → MemoryMap → Dreaming → LLM → VFS → executor。
     *
     * 所有资源都尽力释放：任一失败也继续关；首个失败作为主失败返回，其余 `addSuppressed`。
     */
    private suspend fun releaseResources(
        acquiredPipeline: ExtractionPipeline?,
        acquiredOrganizationPipeline: OrganizationPipeline?,
        acquiredMemoryMapPipeline: MemoryMapPipeline?,
        acquiredDreamingRuntime: DreamingRuntime?,
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
            acquiredOrganizationPipeline?.let { pipeline ->
                try {
                    pipeline.closeAsync()
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
            }
            acquiredMemoryMapPipeline?.let { pipeline ->
                try {
                    pipeline.closeAsync()
                } catch (failure: Throwable) {
                    primary = chain(primary, failure)
                }
            }
            acquiredDreamingRuntime?.let { runtime ->
                try {
                    runtime.closeAsync()
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

    /** 测试/诊断用：当前组装的 Organization pipeline（未配置/未运行/已关闭为 null）。 */
    internal fun organizationPipelineForTesting(): OrganizationPipeline? = synchronized(lock) { organizationPipeline }

    /** 测试/诊断用：当前组装的 Memory Map pipeline（未配置/未运行/已关闭为 null）。 */
    internal fun memoryMapPipelineForTesting(): MemoryMapPipeline? = synchronized(lock) { memoryMapPipeline }

    /** 测试/诊断用：生产 Organization 提交后实际使用的 Map wakeup。 */
    internal fun organizationMapWakeupForTesting(): OrganizationMapWakeup = effectiveOrganizationMapWakeup

    /** 测试/诊断用：当前持有的 VFS（未运行或已关闭为 null），不对外公开。 */
    internal fun vfsForTesting(): AlcyoneVfs? = vfs

    /** 测试/诊断用：当前 Resource access（未运行或已关闭为 null），不对外公开。 */
    internal fun resourceAccessForTesting(): ContextResourceAccess? = synchronized(lock) { resourceAccess }

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

    override suspend fun loadMeta(uri: String): ResourceMetadata = currentResourceAccess("load resource metadata").loadMeta(uri)

    override suspend fun read(uri: String): ByteArray = currentResourceAccess("read resource").read(uri)

    override fun readStream(uri: String): Flow<ByteArray> =
        flow {
            emitAll(currentResourceAccess("read resource stream").readStream(uri))
        }

    override suspend fun searchMemory(
        query: String,
        domains: List<String>?,
        limit: Int,
    ): List<OrganizedMemory> {
        val runtime = currentRetrievalRuntime("search memory")
        return MemorySearchService(
            database = runtime.database,
            llmClient = runtime.llmClient,
            llmConfig = runtime.llmConfig,
            maxLimit = retrievalConfig.search.maxLimit,
        ).search(query = query, domains = domains, limit = limit)
    }

    override suspend fun getMemory(id: String): OrganizedMemory? {
        val database = currentRetrievalRuntime("get organized memory").database
        val normalizedId = normalizeRetrievalId(id)
        return database.read { read -> read.organizedMemories.findById(normalizedId) }
    }

    override suspend fun getRawMemory(id: String): RawMemory? {
        val database = currentRetrievalRuntime("get raw memory").database
        val normalizedId = normalizeRetrievalId(id)
        return database.read { read -> read.rawMemories.findById(normalizedId) }
    }

    /** 在状态锁内取得当前 RUNNING 资源快照；DB/LLM 调用必须在锁外执行。 */
    private fun currentRetrievalRuntime(operation: String): RetrievalRuntime =
        synchronized(lock) {
            when (lifecycle) {
                ContextLifecycleState.CREATED, ContextLifecycleState.STARTING ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.NOT_STARTED,
                        "context is not started; call start() before attempting to $operation",
                    )
                ContextLifecycleState.STOPPING, ContextLifecycleState.CLOSED ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.CLOSED,
                        "context is closing or closed; attempting to $operation is refused",
                    )
                ContextLifecycleState.RUNNING ->
                    RetrievalRuntime(
                        database =
                            database ?: throw ContextLifecycleException(
                                ContextLifecycleErrorCode.NOT_STARTED,
                                "context retrieval database is unavailable",
                            ),
                        llmClient = llm,
                        llmConfig = llmConfig,
                    )
            }
        }

    private fun normalizeRetrievalId(id: String): String {
        val normalized = id.trim()
        if (normalized.isEmpty()) {
            throw ContextRetrievalException(ContextRetrievalErrorCode.INVALID_REQUEST, "memory id must not be blank")
        }
        return normalized
    }

    /** 在状态锁内取得当前 RUNNING access，保证关闭清空与准入没有半开窗口。 */
    private fun currentResourceAccess(operation: String): ContextResourceAccess =
        synchronized(lock) {
            when (lifecycle) {
                ContextLifecycleState.CREATED, ContextLifecycleState.STARTING ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.NOT_STARTED,
                        "context is not started; call start() before attempting to $operation",
                    )
                ContextLifecycleState.STOPPING, ContextLifecycleState.CLOSED ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.CLOSED,
                        "context is closing or closed; attempting to $operation is refused",
                    )
                ContextLifecycleState.RUNNING ->
                    resourceAccess ?: throw ContextLifecycleException(
                        ContextLifecycleErrorCode.NOT_STARTED,
                        "context resource access is unavailable; call start() before attempting to $operation",
                    )
            }
        }

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

    override suspend fun bootstrap(): BootstrapContext =
        synchronized(lock) {
            when (lifecycle) {
                ContextLifecycleState.CREATED, ContextLifecycleState.STARTING ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.NOT_STARTED,
                        "context is not started; call start() before reading bootstrap",
                    )
                ContextLifecycleState.STOPPING, ContextLifecycleState.CLOSED ->
                    throw ContextLifecycleException(
                        ContextLifecycleErrorCode.CLOSED,
                        "context is closing or closed; bootstrap is refused",
                    )
                ContextLifecycleState.RUNNING -> {
                    if (memoryMapConfig == null) {
                        BootstrapContext(null, MapStatus.MISSING, BootstrapContext.DEFAULT_CAPABILITIES)
                    } else {
                        val state = memoryMapPipeline?.snapshot()
                        BootstrapContext(
                            memoryMap = state?.view,
                            mapStatus = state?.status ?: MapStatus.UNKNOWN,
                            capabilities = BootstrapContext.DEFAULT_CAPABILITIES,
                        )
                    }
                }
            }
        }

    override suspend fun getStatus(): ContextStatus =
        ContextStatus(
            lifecycle = lifecycle,
            workers = workerStatuses(),
            storage =
                StorageStatus(
                    databasePath = databasePath.toString(),
                    databaseInitialized = databaseVerified,
                ),
        )

    private fun organizationWorkerStatus(): Map<String, WorkerStatus> {
        if (organizationConfig == null) return emptyMap()
        val pipeline = synchronized(lock) { organizationPipeline }
        val state =
            when (lifecycle) {
                ContextLifecycleState.STOPPING, ContextLifecycleState.CLOSED -> WorkerState.STOPPED
                ContextLifecycleState.RUNNING ->
                    pipeline?.workerStatusForTesting()?.toPublicWorkerState() ?: WorkerState.IDLE
                ContextLifecycleState.CREATED, ContextLifecycleState.STARTING -> WorkerState.IDLE
            }
        return mapOf("organization" to WorkerStatus(state = state))
    }

    private fun memoryMapWorkerStatus(): Map<String, WorkerStatus> {
        if (memoryMapConfig == null) return emptyMap()
        val pipeline = synchronized(lock) { memoryMapPipeline }
        val state =
            when (lifecycle) {
                ContextLifecycleState.STOPPING, ContextLifecycleState.CLOSED -> WorkerState.STOPPED
                ContextLifecycleState.RUNNING ->
                    pipeline?.coordinatorForTesting()?.statusForTesting()?.toPublicWorkerState() ?: WorkerState.IDLE
                ContextLifecycleState.CREATED, ContextLifecycleState.STARTING -> WorkerState.IDLE
            }
        return mapOf("memoryMap" to WorkerStatus(state = state))
    }

    private fun workerStatuses(): Map<String, WorkerStatus> =
        buildMap {
            putAll(organizationWorkerStatus())
            putAll(memoryMapWorkerStatus())
        }

    private fun OrganizationWorkerStatus.toPublicWorkerState(): WorkerState =
        when (this) {
            OrganizationWorkerStatus.IDLE -> WorkerState.IDLE
            OrganizationWorkerStatus.RUNNING -> WorkerState.RUNNING
            OrganizationWorkerStatus.STOPPED -> WorkerState.STOPPED
            OrganizationWorkerStatus.FAILED -> WorkerState.FAILED
        }

    private fun MemoryMapWorkerStatus.toPublicWorkerState(): WorkerState =
        when (this) {
            MemoryMapWorkerStatus.IDLE -> WorkerState.IDLE
            MemoryMapWorkerStatus.RUNNING -> WorkerState.RUNNING
            MemoryMapWorkerStatus.STOPPED -> WorkerState.STOPPED
            MemoryMapWorkerStatus.FAILED -> WorkerState.FAILED
        }

    private fun currentMemoryMapPipeline(): MemoryMapPipeline? = synchronized(lock) { memoryMapPipeline }

    /** B1 Pipeline 未直接暴露 requestRefresh；复用其 Coordinator 的同一 CONFLATED 准入。 */
    private fun MemoryMapPipeline.requestRefresh(): Boolean = coordinatorForTesting().requestRefresh()

    private fun closed(message: String): ContextLifecycleException = ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, message)

    private data class RetrievalRuntime(
        val database: ContextDatabaseImpl,
        val llmClient: KtorLlmClient?,
        val llmConfig: LlmConfig?,
    )
}

/** Organization Worker 回调来源标记，用于阻止在自身 Round 内调用 Context.close() 造成自等待。 */
internal class OrganizationCallbackMarker(
    private val owner: Any,
) : ThreadContextElement<Any?>,
    CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Any? {
        val previous = CURRENT_OWNER.get()
        CURRENT_OWNER.set(owner)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Any?,
    ) {
        if (oldState == null) CURRENT_OWNER.remove() else CURRENT_OWNER.set(oldState)
    }

    companion object Key : CoroutineContext.Key<OrganizationCallbackMarker> {
        private val CURRENT_OWNER = ThreadLocal<Any?>()

        fun currentOwner(): Any? = CURRENT_OWNER.get()

        fun rejectionFor(
            owner: Any,
            entry: String,
        ): ContextLifecycleException? {
            if (currentOwner() !== owner) return null
            return ContextLifecycleException(
                ContextLifecycleErrorCode.CLOSED,
                "$entry cannot be called from inside an organization worker callback; let the callback finish first",
            )
        }
    }
}
