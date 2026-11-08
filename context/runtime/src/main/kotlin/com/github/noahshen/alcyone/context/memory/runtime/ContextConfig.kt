package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.RetrievalConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Context Runtime 的启动配置（CM1 实际生效字段）。
 *
 * 由 [ContextConfigLoader] 从真实 YAML 文件解析、展开 ENV、校验并映射得到：
 * - [databasePath]：Context 权威状态库的绝对路径（`context.database.path`，已按 YAML 目录解析）；
 * - [lifecycleShutdownTimeout]：关闭等待在途退出的观察期限，不是强杀上限（`context.lifecycle.shutdownTimeout`）；
 * - [vfsConfig]：映射后的既有 [VfsRuntimeConfig]（状态库、命名空间、挂载、限额、事件队列、关闭宽限期）；
 * - [llm]：`context.llm` 的不可变快照（CT08-B1）；未配置为 `null`，此时保持 CM1 ingestion-only 行为；
 * - [extraction]：`context.extraction` 的不可变快照（CT09-B1），未配置时取冻结默认值；
 * - [organization]：`context.organization` 的不可变快照（CT16-B1），缺省 `null` 表示不建 pipeline。
 * - [memoryMap]：`context.memoryMap` 的不可变快照（CT19-B1），缺省 `null` 表示不建 Map pipeline。
 * - [retrieval]：`context.retrieval` 的不可变快照（CT20-B2），缺省使用默认 Search 上限。
 *
 * 本类只承载配置事实，不含生命周期或资源句柄（CT06-B1 不引入 `AlcyoneContext` / `start` / `close`）。
 * `toString()` 由数据类生成，但 WebDAV 凭据不会泄漏：`MountBackend.WebDav.toString` 已脱敏。
 */
data class ContextConfig(
    /** Context 状态库绝对路径。 */
    val databasePath: Path,
    /** 关闭等待在途退出的观察期限（不是强杀上限）；由 CT06-B2 的 close 使用。 */
    val lifecycleShutdownTimeout: Duration,
    /** `context.ingestion` 限额（归实例所有的不可变值复制）。 */
    val ingestionLimits: IngestionLimits = IngestionLimits.DEFAULTS,
    /** 映射后的 VFS 配置（含 `vfs.database.path` 解析结果）。 */
    val vfsConfig: VfsRuntimeConfig,
    /**
     * `context.llm` 配置快照（CT08-B1）；`null` 表示未配置。
     *
     * 已校验；`apiKey` 是展开后的真实值，绝不出现在 `toString`/日志/异常中
     * （[LlmConfig] 的 `toString` 恒为 `<redacted>`）。
     */
    val llm: LlmConfig? = null,
    /** `context.extraction` 配置快照（CT09-B1）；未配置时为冻结默认值。 */
    val extraction: ExtractionConfig = ExtractionConfig(),
    /** `context.organization` 配置快照（CT16-B1）；`null` 表示禁用。 */
    val organization: OrganizationConfig? = null,
    /** `context.memoryMap` 配置快照（CT19-B1）；`null` 表示禁用。 */
    val memoryMap: MemoryMapConfig? = null,
    /** `context.retrieval` 配置快照（CT20-B2）。 */
    val retrieval: RetrievalConfig = RetrievalConfig(),
) {
    /** VFS 状态库绝对路径（`vfsConfig.stateDatabase` 的便捷视图）。 */
    val vfsDatabasePath: Path get() = vfsConfig.stateDatabase
}
