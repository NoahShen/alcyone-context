package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionRunner
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingPeriodicTickSource
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingRuntime
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.validateDreamingBinding
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionPipeline
import com.github.noahshen.alcyone.context.memory.runtime.extraction.IdleTickSource
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapGeneratorPort
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicTickSource
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPipeline
import com.github.noahshen.alcyone.context.memory.runtime.map.validateMemoryMapBinding
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationMapWakeup
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPeriodicTickSource
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPipeline
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationRoundRunner
import com.github.noahshen.alcyone.context.memory.runtime.organization.validateOrganizationBinding
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration

/**
 * Context 实例工厂（CT06-B2）。
 *
 * - [create]：纯配置接收 + 校验 + 组装，**零副作用**——不打开数据库、不建文件/目录/锁、
 *   不连网络、不启协程/线程；返回 `CREATED` 实例。直接构造的 `ContextConfig` / `VfsRuntimeConfig`
 *   同样要经过数据库同文件与 LocalFs 挂载根暴露校验（复用 [ContextPathIsolation]）。
 * - [initializeDatabase]：显式首次初始化，调用 CT04 [ContextDatabaseInitializer]；
 *   普通 `start` 不会自动建库或迁移。
 */
object AlcyoneContextFactory {
    private val NOTIFICATION_LOGGER = LoggerFactory.getLogger(AlcyoneContextImpl::class.java)

    /**
     * 组装一个尚未启动的 [AlcyoneContext]。
     *
     * 只做纯校验，不产生任何文件系统副作用；真正打开数据库与 VFS 在 [AlcyoneContext.start]。
     */
    fun create(
        contextConfig: ContextConfig,
        vfsConfig: VfsRuntimeConfig,
    ): AlcyoneContext =
        build(
            contextConfig = contextConfig,
            vfsConfig = vfsConfig,
            probe = StartProbe { },
            releaseProbe = ReleaseProbe { },
            startGate = StartGate { },
            clock = Clock.systemUTC(),
            ingestionNotifier = IngestionNotifier { },
            ingestionReporter = defaultNotificationReporter(),
            extractionRunner = null,
            idleTickSource = null,
            pipelineConfigurator = null,
            organizationWakeup = null,
            organizationTickSource = null,
            organizationPipelineConfigurator = null,
            organizationMapWakeup = null,
            organizationRunner = null,
            memoryMapGenerator = null,
            memoryMapTickSource = null,
            memoryMapPipelineConfigurator = null,
            dreamingTickSource = null,
            dreamingRuntimeConfigurator = null,
        )

    /**
     * 同模块测试用的重载：组装探针 + 释放探针，用于制造确定性的竞态与清理失败；
     * 可选注入 Extraction/Memory Map Runner、tick 来源与 pipeline 测试接缝（公共 `create` 恒为 null）。
     */
    internal fun create(
        contextConfig: ContextConfig,
        vfsConfig: VfsRuntimeConfig,
        probe: StartProbe,
        releaseProbe: ReleaseProbe = ReleaseProbe { },
        startGate: StartGate = StartGate { },
        clock: Clock = Clock.systemUTC(),
        ingestionNotifier: IngestionNotifier = IngestionNotifier { },
        ingestionReporter: IngestionNotificationReporter = defaultNotificationReporter(),
        extractionRunner: ExtractionRunner? = null,
        idleTickSource: IdleTickSource? = null,
        pipelineConfigurator: ((ExtractionPipeline) -> Unit)? = null,
        organizationWakeup: OrganizationWakeup? = null,
        organizationTickSource: OrganizationPeriodicTickSource? = null,
        organizationPipelineConfigurator: ((OrganizationPipeline) -> Unit)? = null,
        organizationMapWakeup: OrganizationMapWakeup? = null,
        organizationRunner: OrganizationRoundRunner? = null,
        memoryMapGenerator: MemoryMapGeneratorPort? = null,
        memoryMapTickSource: MemoryMapPeriodicTickSource? = null,
        memoryMapPipelineConfigurator: ((MemoryMapPipeline) -> Unit)? = null,
        dreamingTickSource: DreamingPeriodicTickSource? = null,
        dreamingRuntimeConfigurator: ((DreamingRuntime) -> Unit)? = null,
    ): AlcyoneContext =
        build(
            contextConfig = contextConfig,
            vfsConfig = vfsConfig,
            probe = probe,
            releaseProbe = releaseProbe,
            startGate = startGate,
            clock = clock,
            ingestionNotifier = ingestionNotifier,
            ingestionReporter = ingestionReporter,
            extractionRunner = extractionRunner,
            idleTickSource = idleTickSource,
            pipelineConfigurator = pipelineConfigurator,
            organizationWakeup = organizationWakeup,
            organizationTickSource = organizationTickSource,
            organizationPipelineConfigurator = organizationPipelineConfigurator,
            organizationMapWakeup = organizationMapWakeup,
            organizationRunner = organizationRunner,
            memoryMapGenerator = memoryMapGenerator,
            memoryMapTickSource = memoryMapTickSource,
            memoryMapPipelineConfigurator = memoryMapPipelineConfigurator,
            dreamingTickSource = dreamingTickSource,
            dreamingRuntimeConfigurator = dreamingRuntimeConfigurator,
        )

    /** 组装探针；生产入口使用空实现，测试用它制造确定性的 start/close 交错。 */
    internal fun interface StartProbe {
        suspend fun afterResourcesAcquired()
    }

    /** 打开数据库之前的测试闸门；生产入口使用空实现，失败路径测试用它确认第二个 start 已在等待。 */
    internal fun interface StartGate {
        suspend fun beforeOpen()
    }

    /** 释放探针；在关闭 VFS / 执行器之前调用，可抛受控异常。生产入口使用空实现。 */
    internal fun interface ReleaseProbe {
        fun beforeRelease(target: ReleaseTarget)
    }

    internal enum class ReleaseTarget {
        VFS,
        EXECUTOR,
    }

    /** 显式初始化 Context 状态库：目标已存在时拒绝，绝不覆盖。 */
    suspend fun initializeDatabase(contextConfig: ContextConfig) {
        ContextDatabaseInitializer.initialize(contextConfig.databasePath.toAbsolutePath().normalize())
    }

    private fun build(
        contextConfig: ContextConfig,
        vfsConfig: VfsRuntimeConfig,
        probe: StartProbe,
        releaseProbe: ReleaseProbe,
        startGate: StartGate,
        clock: Clock,
        ingestionNotifier: IngestionNotifier,
        ingestionReporter: IngestionNotificationReporter,
        extractionRunner: ExtractionRunner?,
        idleTickSource: IdleTickSource?,
        pipelineConfigurator: ((ExtractionPipeline) -> Unit)?,
        organizationWakeup: OrganizationWakeup?,
        organizationTickSource: OrganizationPeriodicTickSource?,
        organizationPipelineConfigurator: ((OrganizationPipeline) -> Unit)?,
        organizationMapWakeup: OrganizationMapWakeup?,
        organizationRunner: OrganizationRoundRunner?,
        memoryMapGenerator: MemoryMapGeneratorPort?,
        memoryMapTickSource: MemoryMapPeriodicTickSource?,
        memoryMapPipelineConfigurator: ((MemoryMapPipeline) -> Unit)?,
        dreamingTickSource: DreamingPeriodicTickSource?,
        dreamingRuntimeConfigurator: ((DreamingRuntime) -> Unit)?,
    ): AlcyoneContext {
        // 先取得归本实例所有的配置快照，再对快照校验；调用者之后的修改不得影响已校验配置。
        val ownedVfsConfig =
            vfsConfig.copy(
                namespaces = vfsConfig.namespaces.toSet(),
                mounts = vfsConfig.mounts.toList(),
            )
        val databasePath = contextConfig.databasePath.toAbsolutePath().normalize()
        // LLM 配置同样先取实例自有快照并校验；未配置时为 null（保持 CM1 ingestion-only）。
        // 这里只持有快照，不创建 client、不发请求、不改变 start/close 顺序（CT08-B4 接线）。
        val ownedLlmConfig = contextConfig.llm?.ownedCopy()
        val ownedMemoryMapConfig: MemoryMapConfig? = contextConfig.memoryMap
        // Organization 显式配置时必须在任何资源打开前完成双 LLM binding 纯校验。
        validateOrganizationBinding(contextConfig.organization, ownedLlmConfig)
        // Memory Map 显式配置时同样在任何资源打开前完成 LLM binding 纯校验。
        validateMemoryMapBinding(ownedMemoryMapConfig, ownedLlmConfig)
        validateDreamingBinding(contextConfig.dreaming, ownedLlmConfig)
        validateContext(databasePath, contextConfig.lifecycleShutdownTimeout)
        validateVfs(ownedVfsConfig)
        ContextPathIsolation.requireDistinctDatabases(databasePath, ownedVfsConfig.stateDatabase)
        ContextPathIsolation.requireOutsideLocalMounts(databasePath, ownedVfsConfig.mounts, "context.database.path")
        ContextPathIsolation.requireOutsideLocalMounts(ownedVfsConfig.stateDatabase, ownedVfsConfig.mounts, "vfs.database.path")
        return AlcyoneContextImpl(
            databasePath = databasePath,
            shutdownTimeout = contextConfig.lifecycleShutdownTimeout,
            vfsConfig = ownedVfsConfig,
            startProbe = probe,
            releaseProbe = releaseProbe,
            startGate = startGate,
            ingestionLimits = contextConfig.ingestionLimits,
            llmConfig = ownedLlmConfig,
            extractionConfig = contextConfig.extraction,
            extractionRunner = extractionRunner,
            extractionIdleTickSource = idleTickSource,
            extractionPipelineConfigurator = pipelineConfigurator,
            organizationWakeup = organizationWakeup,
            organizationConfig = contextConfig.organization,
            organizationTickSource = organizationTickSource,
            organizationPipelineConfigurator = organizationPipelineConfigurator,
            organizationMapWakeup = organizationMapWakeup,
            organizationRunner = organizationRunner,
            memoryMapConfig = ownedMemoryMapConfig,
            memoryMapGenerator = memoryMapGenerator,
            memoryMapTickSource = memoryMapTickSource,
            memoryMapPipelineConfigurator = memoryMapPipelineConfigurator,
            dreamingConfig = contextConfig.dreaming,
            dreamingTickSource = dreamingTickSource,
            dreamingRuntimeConfigurator = dreamingRuntimeConfigurator,
            retrievalConfig = contextConfig.retrieval,
            clock = clock,
            ingestionNotifier = ingestionNotifier,
            ingestionReporter = ingestionReporter,
        )
    }

    private fun validateContext(
        databasePath: Path,
        shutdownTimeout: Duration,
    ) {
        if (shutdownTimeout.isNegative()) {
            throw invalid("context.lifecycle.shutdownTimeout must not be negative")
        }
        if (databasePath.fileName == null) {
            throw invalid("context.database.path must point to a file")
        }
    }

    private fun validateVfs(vfsConfig: VfsRuntimeConfig) {
        vfsConfig.streamTotalLimit?.let { limit ->
            if (limit < 0) throw invalid("vfs.streamTotalLimit must be null or greater than or equal to 0")
        }
        if (vfsConfig.closeGracePeriod.isNegative()) {
            throw invalid("vfs.closeGracePeriod must not be negative")
        }
        if (vfsConfig.eventBufferCapacity < 1) {
            throw invalid("vfs.eventBufferCapacity must be at least 1")
        }
        vfsConfig.namespaces.forEach { namespace ->
            if (namespace.isBlank()) throw invalid("vfs.namespaces contains a blank name")
        }
        vfsConfig.mounts.forEachIndexed { index, mount ->
            if (mount.vfsPath.isRoot) {
                throw invalid("vfs.mounts[$index].vfsPath must not be the logical root /")
            }
            if (mount.storageKey.isBlank()) {
                throw invalid("vfs.mounts[$index].storageKey must not be blank")
            }
        }
    }

    private fun defaultNotificationReporter(): IngestionNotificationReporter =
        IngestionNotificationReporter { cause ->
            NOTIFICATION_LOGGER.warn(
                "conversation ingestion notification failed: {}",
                cause.javaClass.simpleName,
            )
        }

    private fun invalid(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
}
