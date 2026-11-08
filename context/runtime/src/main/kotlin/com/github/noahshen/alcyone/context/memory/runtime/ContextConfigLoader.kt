package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionDefaults
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmDefaults
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapDefaults
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationDefaults
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.RetrievalConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.SearchConfig
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 从真实 YAML 文件加载 [ContextConfig]（CT06-B1）。
 *
 * 规则（CT01 §c 冻结 + CT06 方案 §1～§3）：
 * - 负责分区为 `context` / `vfs`；`server` 及其他顶层分区完全忽略；
 * - 相对路径一律以 **YAML 文件所在目录** 为基准解析为绝对路径，不使用 CWD；
 * - 字符串中的 `${ENV_NAME}` 在读取前展开；未定义或 trim 后为空 → `MISSING_ENV`，
 *   消息只含字段路径与 ENV 名，不含值；
 * - 负责分区内未知字段 → `UNKNOWN_FIELD`（含具体路径）；`context.ingestion` 的三个限额字段自
 *   CT07 起读取并严格校验（正整数，非法 `INVALID_CONFIG`），ingestion 内未知字段同样拒绝；
 *   `context.llm` 自 CT08-B1 起**首次消费**：解析 Profile/Task 两层结构、展开 ENV、拒绝未知字段，
 *   并交由 `LlmConfig` 的同一 `validateLlm` 做字段/范围/引用/静态预算校验；
 *   `context.extraction` 自 CT09-B1 起首次消费：解析 `triggers.count.threshold`、
 *   `triggers.idle.enabled` / `.idleTimeout` / `.cron` 四层、以及 CT10 的 `initialWindowTurns`，
 *   并逐层拒绝未知键，交给
 *   [ExtractionConfig] 的同一 `validateExtraction` 校验（固定 `Asia/Shanghai`，无 timezone 键）；
 *   `context.organization` 自 CT16-B1 起严格解析 `triggers.immediate.enabled` 与
 *   `triggers.periodic.enabled/cron`；固定 Asia/Shanghai，出现 `timezone` 即未知键；
 *   `context.memoryMap` 自 CT19-B1 起严格解析 `refresh.periodic.enabled/cron`；固定 Asia/Shanghai，
 *   出现 `timezone` 即未知键；`context.retrieval.search.maxLimit` 自 CT20-B2 起严格解析。
 *   `backup` 仍为蓝图已知但尚未实现的子树，整体接受、不校验、不展开 ENV；
 * - 类型/范围错误 → `INVALID_CONFIG`，消息只带字段路径与原因，不回显原始值；
 * - 解析使用 SnakeYAML `SafeConstructor` 并禁止重复键；
 * - 加载过程只读配置：不建目录、不建文件、不打开数据库、不连网络、不启协程。
 *
 * 本对象不引入 `AlcyoneContext` / 生命周期（CT06-B2）。
 */
object ContextConfigLoader {
    private const val DEFAULT_CONTEXT_DATABASE = "data/context.db"
    private const val DEFAULT_VFS_DATABASE = "data/vfs.db"
    private val DEFAULT_SHUTDOWN_TIMEOUT: Duration = 30.seconds
    private val DEFAULT_CLOSE_GRACE_PERIOD: Duration = 30.seconds
    private const val DEFAULT_EVENT_BUFFER_CAPACITY: Int = AsyncEventNotifier.DEFAULT_CAPACITY
    private const val DEFAULT_LIMIT_BYTES: Long = VfsLimits.DEFAULT_LIMIT_BYTES

    private val CONTEXT_KEYS =
        setOf("database", "lifecycle", "ingestion", "llm", "extraction", "organization", "memoryMap", "retrieval", "backup")
    private val CONTEXT_DATABASE_KEYS = setOf("path")
    private val CONTEXT_LIFECYCLE_KEYS = setOf("shutdownTimeout")
    private val CONTEXT_INGESTION_KEYS = setOf("maxTurnBytes", "maxMessages", "maxIdLength")
    private val CONTEXT_LLM_KEYS = setOf("safetyMarginPercent", "profiles", "tasks")
    private val CONTEXT_LLM_PROFILE_KEYS =
        setOf(
            "baseUrl",
            "apiKey",
            "model",
            "contextWindowTokens",
            "jsonMode",
            "connectTimeoutSeconds",
            "requestTimeoutSeconds",
            "socketTimeoutSeconds",
            "maxAttempts",
        )
    private val CONTEXT_LLM_TASK_KEYS = setOf("profile", "maxInputTokens", "maxOutputTokens", "totalTimeoutSeconds")
    private val CONTEXT_EXTRACTION_KEYS = setOf("triggers", "initialWindowTurns")
    private val CONTEXT_EXTRACTION_TRIGGER_KEYS = setOf("count", "idle")
    private val CONTEXT_EXTRACTION_COUNT_KEYS = setOf("threshold")
    private val CONTEXT_EXTRACTION_IDLE_KEYS = setOf("enabled", "idleTimeout", "cron")
    private val CONTEXT_ORGANIZATION_KEYS = setOf("triggers")
    private val CONTEXT_ORGANIZATION_TRIGGER_KEYS = setOf("immediate", "periodic")
    private val CONTEXT_ORGANIZATION_IMMEDIATE_KEYS = setOf("enabled")
    private val CONTEXT_ORGANIZATION_PERIODIC_KEYS = setOf("enabled", "cron")
    private val CONTEXT_MEMORY_MAP_KEYS = setOf("refresh")
    private val CONTEXT_MEMORY_MAP_REFRESH_KEYS = setOf("periodic")
    private val CONTEXT_MEMORY_MAP_PERIODIC_KEYS = setOf("enabled", "cron")
    private val CONTEXT_RETRIEVAL_KEYS = setOf("search")
    private val CONTEXT_RETRIEVAL_SEARCH_KEYS = setOf("maxLimit")
    private val VFS_KEYS =
        setOf("database", "namespaces", "mounts", "limits", "streamTotalLimit", "eventBufferCapacity", "closeGracePeriod")
    private val VFS_DATABASE_KEYS = setOf("path")
    private val VFS_LIMITS_KEYS = setOf("defaultReadMaxBytes", "defaultWriteMaxBytes")
    private val MOUNT_KEYS = setOf("vfsPath", "storageKey", "backend")
    private val LOCAL_BACKEND_KEYS = setOf("type", "root")
    private val WEBDAV_BACKEND_KEYS = setOf("type", "endpoint", "root", "username", "password")

    private val ENV_PATTERN = Regex("\\$\\{([^}]*)}")

    /** 从 [yamlPath] 加载配置；ENV 取进程环境变量。 */
    fun load(yamlPath: Path): ContextConfig = load(yamlPath, System::getenv)

    /**
     * 从 [yamlPath] 加载配置，ENV 由 [environment] 提供。
     *
     * 仅测试需要注入「未定义 / 空白」的环境变量；生产入口使用 [load]。
     */
    internal fun load(
        yamlPath: Path,
        environment: (String) -> String?,
    ): ContextConfig {
        val configFile = yamlPath.toAbsolutePath().normalize()
        val baseDirectory =
            configFile.parent
                ?: throw invalid("config file path has no parent directory")
        val root = readYaml(configFile)

        val contextSection = section(root, "context")
        val vfsSection = section(root, "vfs")
        contextSection?.let { rejectUnknown(it, CONTEXT_KEYS, "context") }
        vfsSection?.let { rejectUnknown(it, VFS_KEYS, "vfs") }

        val contextDatabase =
            parseContextDatabase(contextSection, baseDirectory, environment)
                ?: resolveDefaultPath(baseDirectory, DEFAULT_CONTEXT_DATABASE)
        val shutdownTimeout =
            parseShutdownTimeout(contextSection, environment)
                ?: DEFAULT_SHUTDOWN_TIMEOUT
        val ingestionLimits = parseIngestionLimits(contextSection)
        val llmConfig = parseLlm(contextSection, environment)
        val extractionConfig = parseExtraction(contextSection, environment)
        val organizationConfig = parseOrganization(contextSection, environment)
        val memoryMapConfig = parseMemoryMap(contextSection, environment)
        val retrievalConfig = parseRetrieval(contextSection)

        val vfsDatabase =
            parseVfsDatabase(vfsSection, baseDirectory, environment)
                ?: resolveDefaultPath(baseDirectory, DEFAULT_VFS_DATABASE)
        val namespaces = parseNamespaces(vfsSection, environment)
        val mounts = parseMounts(vfsSection, baseDirectory, environment)
        val limits = parseLimits(vfsSection)
        val streamTotalLimit = parseStreamTotalLimit(vfsSection)
        val eventBufferCapacity = parseEventBufferCapacity(vfsSection)
        val closeGracePeriod = parseCloseGracePeriod(vfsSection, environment)

        val vfsConfig =
            VfsRuntimeConfig(
                stateDatabase = vfsDatabase,
                namespaces = namespaces,
                mounts = mounts,
                limits = limits,
                streamTotalLimit = streamTotalLimit,
                eventBufferCapacity = eventBufferCapacity,
                closeGracePeriod = closeGracePeriod,
            )

        ContextPathIsolation.requireDistinctDatabases(contextDatabase, vfsDatabase)
        ContextPathIsolation.requireOutsideLocalMounts(contextDatabase, mounts, "context.database.path")
        ContextPathIsolation.requireOutsideLocalMounts(vfsDatabase, mounts, "vfs.database.path")

        return ContextConfig(
            databasePath = contextDatabase,
            lifecycleShutdownTimeout = shutdownTimeout,
            ingestionLimits = ingestionLimits,
            vfsConfig = vfsConfig,
            llm = llmConfig,
            extraction = extractionConfig,
            organization = organizationConfig,
            memoryMap = memoryMapConfig,
            retrieval = retrievalConfig,
        )
    }

    // ------------------------------------------------------------------ YAML 读取

    private fun readYaml(configFile: Path): Map<String, Any?> {
        val text =
            try {
                Files.readString(configFile)
            } catch (failure: IOException) {
                throw invalid("cannot read config file: $configFile")
            }
        val loaded =
            try {
                Yaml(SafeConstructor(LoaderOptions().apply { setAllowDuplicateKeys(false) })).load<Any?>(text)
            } catch (failure: YAMLException) {
                throw invalid("config file is not valid YAML: $configFile")
            }
        if (loaded == null) return emptyMap()
        return asStringMap(loaded, "config root")
    }

    private fun section(
        parent: Map<String, Any?>,
        key: String,
    ): Map<String, Any?>? {
        if (!parent.containsKey(key)) return null
        return asStringMap(parent[key] ?: throw invalid("$key must be a mapping"), key)
    }

    private fun asStringMap(
        value: Any?,
        path: String,
    ): Map<String, Any?> {
        val map = value as? Map<*, *> ?: throw invalid("$path must be a mapping")
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, item) ->
            val name = key as? String ?: throw invalid("$path contains a non-string key")
            result[name] = item
        }
        return result
    }

    private fun rejectUnknown(
        map: Map<String, Any?>,
        allowed: Set<String>,
        path: String,
    ) {
        val unknown = map.keys.firstOrNull { it !in allowed } ?: return
        throw unknownField("$path.$unknown")
    }

    // ------------------------------------------------------------------ context

    private fun parseContextDatabase(
        context: Map<String, Any?>?,
        baseDirectory: Path,
        environment: (String) -> String?,
    ): Path? {
        val database = context?.let { section(it, "database") } ?: return null
        rejectUnknown(database, CONTEXT_DATABASE_KEYS, "context.database")
        val text = stringAt(database, "path", "context.database.path", environment) ?: return null
        return resolvePath(baseDirectory, text, "context.database.path")
    }

    private fun parseShutdownTimeout(
        context: Map<String, Any?>?,
        environment: (String) -> String?,
    ): Duration? {
        val lifecycle = context?.let { section(it, "lifecycle") } ?: return null
        rejectUnknown(lifecycle, CONTEXT_LIFECYCLE_KEYS, "context.lifecycle")
        val text = stringAt(lifecycle, "shutdownTimeout", "context.lifecycle.shutdownTimeout", environment) ?: return null
        val timeout = parseDuration(text, "context.lifecycle.shutdownTimeout")
        if (timeout.isNegative()) throw invalid("context.lifecycle.shutdownTimeout must not be negative")
        return timeout
    }

    private fun parseIngestionLimits(context: Map<String, Any?>?): IngestionLimits {
        val ingestion = context?.let { section(it, "ingestion") } ?: return IngestionLimits.DEFAULTS
        rejectUnknown(ingestion, CONTEXT_INGESTION_KEYS, "context.ingestion")
        val maxTurnBytes = intAt(ingestion, "maxTurnBytes", "context.ingestion.maxTurnBytes") ?: IngestionLimits.DEFAULT_MAX_TURN_BYTES
        val maxMessages = intAt(ingestion, "maxMessages", "context.ingestion.maxMessages") ?: IngestionLimits.DEFAULT_MAX_MESSAGES
        val maxIdLength = intAt(ingestion, "maxIdLength", "context.ingestion.maxIdLength") ?: IngestionLimits.DEFAULT_MAX_ID_LENGTH
        if (maxTurnBytes <= 0) throw invalid("context.ingestion.maxTurnBytes must be a positive integer")
        if (maxMessages <= 0) throw invalid("context.ingestion.maxMessages must be a positive integer")
        if (maxIdLength <= 0) throw invalid("context.ingestion.maxIdLength must be a positive integer")
        // 不可变值复制：后续对原 YAML 结构的改动不影响本实例。
        return IngestionLimits(maxTurnBytes = maxTurnBytes, maxMessages = maxMessages, maxIdLength = maxIdLength)
    }

    // ------------------------------------------------------------------ context.llm

    /**
     * 解析 `context.llm`（CT08-B1：首次消费）。
     *
     * 本方法只负责「结构 + 类型 + 已知键 + 枚举 + 必填存在性」；字段范围、URL 形状、
     * Profile 引用与静态预算自洽统一由 [LlmConfig] 的 `validateLlm` 校验，因此 YAML 与
     * 直接 Kotlin 构造使用同一套规则。返回的 `LlmConfig` 已是实例自有快照。
     */
    private fun parseLlm(
        context: Map<String, Any?>?,
        environment: (String) -> String?,
    ): LlmConfig? {
        val llm = context?.let { section(it, "llm") } ?: return null
        rejectUnknown(llm, CONTEXT_LLM_KEYS, "context.llm")
        val safetyMarginPercent =
            intAt(llm, "safetyMarginPercent", "context.llm.safetyMarginPercent")
                ?: LlmDefaults.SAFETY_MARGIN_PERCENT

        val profilesValue = llm["profiles"] ?: throw invalid("context.llm.profiles is required")
        val profileEntries = asStringMap(profilesValue, "context.llm.profiles")
        if (profileEntries.isEmpty()) throw invalid("context.llm.profiles is required")
        val profiles = LinkedHashMap<String, LlmProfileConfig>(profileEntries.size)
        profileEntries.forEach { (name, value) ->
            if (name.isBlank()) throw invalid("context.llm.profiles has a blank name")
            profiles[name] = parseLlmProfile(name, value, environment)
        }

        val tasks = LinkedHashMap<LlmTask, LlmTaskConfig>()
        if (llm.containsKey("tasks")) {
            val taskEntries = asStringMap(llm["tasks"], "context.llm.tasks")
            taskEntries.forEach { (declaredName, value) ->
                val task =
                    LlmTask.entries.firstOrNull { it.name.equals(declaredName, ignoreCase = true) }
                        ?: throw invalid("context.llm.tasks.$declaredName is not a known LLM task")
                if (tasks.containsKey(task)) {
                    throw invalid("context.llm.tasks.${task.name} is defined more than once")
                }
                tasks[task] = parseLlmTask(task, value, environment)
            }
        }

        return LlmConfig(profiles = profiles, tasks = tasks, safetyMarginPercent = safetyMarginPercent)
    }

    // ------------------------------------------------------------------ context.extraction

    /**
     * 解析 `context.extraction`（CT09-B1：首次消费）。
     *
     * 本方法只负责「结构 + 类型 + 逐层已知键」；`threshold`/`idleTimeout`/`cron` 的取值规则统一由
     * [ExtractionConfig] 的 `validateExtraction` 校验，因此 YAML 与直接 Kotlin 构造共用同一套规则。
     * `idle.enabled=false` 不豁免其余字段校验。
     */
    private fun parseExtraction(
        context: Map<String, Any?>?,
        environment: (String) -> String?,
    ): ExtractionConfig {
        val extraction = context?.let { section(it, "extraction") } ?: return ExtractionConfig()
        rejectUnknown(extraction, CONTEXT_EXTRACTION_KEYS, "context.extraction")

        val triggers = extraction["triggers"]?.let { asStringMap(it, "context.extraction.triggers") }
        triggers?.let { rejectUnknown(it, CONTEXT_EXTRACTION_TRIGGER_KEYS, "context.extraction.triggers") }

        val count = triggers?.get("count")?.let { asStringMap(it, "context.extraction.triggers.count") }
        count?.let { rejectUnknown(it, CONTEXT_EXTRACTION_COUNT_KEYS, "context.extraction.triggers.count") }

        val idle = triggers?.get("idle")?.let { asStringMap(it, "context.extraction.triggers.idle") }
        idle?.let { rejectUnknown(it, CONTEXT_EXTRACTION_IDLE_KEYS, "context.extraction.triggers.idle") }

        val threshold =
            count?.let { intAt(it, "threshold", "context.extraction.triggers.count.threshold") }
                ?: ExtractionDefaults.COUNT_THRESHOLD
        val enabled =
            idle?.let { booleanAt(it, "enabled", "context.extraction.triggers.idle.enabled") }
                ?: ExtractionDefaults.IDLE_ENABLED
        val idleTimeout =
            idle
                ?.let {
                    stringAt(it, "idleTimeout", "context.extraction.triggers.idle.idleTimeout", environment)
                }?.let { parseDuration(it, "context.extraction.triggers.idle.idleTimeout") }
                ?: ExtractionDefaults.IDLE_TIMEOUT
        val cron =
            idle?.let { stringAt(it, "cron", "context.extraction.triggers.idle.cron", environment) }
                ?: ExtractionDefaults.CRON
        val initialWindowTurns =
            intAt(extraction, "initialWindowTurns", "context.extraction.initialWindowTurns")
                ?: ExtractionDefaults.INITIAL_WINDOW_TURNS

        // 构造即校验（与直接 Kotlin 构造同一路径）。
        return ExtractionConfig(
            countThreshold = threshold,
            idleEnabled = enabled,
            idleTimeout = idleTimeout,
            cron = cron,
            initialWindowTurns = initialWindowTurns,
        )
    }

    // ------------------------------------------------------------------ context.organization

    /**
     * 解析 `context.organization`（CT16-B1）。
     *
     * 缺省返回 `null`；出现空映射返回默认 Trigger。所有层级严格拒绝未知键，`timezone` 不例外。
     * cron 支持 ENV 展开，最终由 [OrganizationConfig] 的构造期校验统一验证。
     */
    private fun parseOrganization(
        context: Map<String, Any?>?,
        environment: (String) -> String?,
    ): OrganizationConfig? {
        if (context == null || !context.containsKey("organization")) return null
        val organization = asStringMap(context["organization"], "context.organization")
        rejectUnknown(organization, CONTEXT_ORGANIZATION_KEYS, "context.organization")

        val triggers = organization["triggers"]?.let { asStringMap(it, "context.organization.triggers") } ?: emptyMap()
        rejectUnknown(triggers, CONTEXT_ORGANIZATION_TRIGGER_KEYS, "context.organization.triggers")

        val immediate = triggers["immediate"]?.let { asStringMap(it, "context.organization.triggers.immediate") } ?: emptyMap()
        rejectUnknown(immediate, CONTEXT_ORGANIZATION_IMMEDIATE_KEYS, "context.organization.triggers.immediate")

        val periodic = triggers["periodic"]?.let { asStringMap(it, "context.organization.triggers.periodic") } ?: emptyMap()
        rejectUnknown(periodic, CONTEXT_ORGANIZATION_PERIODIC_KEYS, "context.organization.triggers.periodic")

        val immediateEnabled =
            booleanAt(immediate, "enabled", "context.organization.triggers.immediate.enabled")
                ?: OrganizationDefaults.IMMEDIATE_ENABLED
        val periodicEnabled =
            booleanAt(periodic, "enabled", "context.organization.triggers.periodic.enabled")
                ?: OrganizationDefaults.PERIODIC_ENABLED
        val cron =
            stringAt(periodic, "cron", "context.organization.triggers.periodic.cron", environment)
                ?: OrganizationDefaults.CRON

        return OrganizationConfig(
            immediateEnabled = immediateEnabled,
            periodicEnabled = periodicEnabled,
            cron = cron,
        )
    }

    // ------------------------------------------------------------------ context.memoryMap

    /**
     * 解析 `context.memoryMap`（CT19-B1）。
     *
     * 缺省返回 `null`；出现空映射返回默认 Periodic 配置。所有层级严格拒绝未知键，
     * `timezone` 不例外；cron 支持 ENV 展开并由 [MemoryMapConfig] 构造期统一校验。
     */
    private fun parseMemoryMap(
        context: Map<String, Any?>?,
        environment: (String) -> String?,
    ): MemoryMapConfig? {
        if (context == null || !context.containsKey("memoryMap")) return null
        val memoryMap = asStringMap(context["memoryMap"], "context.memoryMap")
        rejectUnknown(memoryMap, CONTEXT_MEMORY_MAP_KEYS, "context.memoryMap")

        val refresh = memoryMap["refresh"]?.let { asStringMap(it, "context.memoryMap.refresh") } ?: emptyMap()
        rejectUnknown(refresh, CONTEXT_MEMORY_MAP_REFRESH_KEYS, "context.memoryMap.refresh")

        val periodic = refresh["periodic"]?.let { asStringMap(it, "context.memoryMap.refresh.periodic") } ?: emptyMap()
        rejectUnknown(periodic, CONTEXT_MEMORY_MAP_PERIODIC_KEYS, "context.memoryMap.refresh.periodic")

        val enabled =
            booleanAt(periodic, "enabled", "context.memoryMap.refresh.periodic.enabled")
                ?: MemoryMapDefaults.PERIODIC_ENABLED
        val cron =
            stringAt(periodic, "cron", "context.memoryMap.refresh.periodic.cron", environment)
                ?: MemoryMapDefaults.CRON
        return MemoryMapConfig(periodic = MemoryMapPeriodicConfig(enabled = enabled, cron = cron))
    }

    // ------------------------------------------------------------------ context.retrieval

    /** 解析 `context.retrieval.search.maxLimit`；缺省/空 mapping 使用默认 50。 */
    private fun parseRetrieval(context: Map<String, Any?>?): RetrievalConfig {
        val retrieval = context?.let { section(it, "retrieval") } ?: return RetrievalConfig()
        rejectUnknown(retrieval, CONTEXT_RETRIEVAL_KEYS, "context.retrieval")
        val search = retrieval["search"]?.let { asStringMap(it, "context.retrieval.search") } ?: emptyMap()
        rejectUnknown(search, CONTEXT_RETRIEVAL_SEARCH_KEYS, "context.retrieval.search")
        val maxLimit =
            intAt(search, "maxLimit", "context.retrieval.search.maxLimit")
                ?: SearchConfig().maxLimit
        return RetrievalConfig(search = SearchConfig(maxLimit = maxLimit))
    }

    private fun parseLlmProfile(
        name: String,
        value: Any?,
        environment: (String) -> String?,
    ): LlmProfileConfig {
        val path = "context.llm.profiles.$name"
        val profile = asStringMap(value, path)
        rejectUnknown(profile, CONTEXT_LLM_PROFILE_KEYS, path)
        val baseUrl = stringAt(profile, "baseUrl", "$path.baseUrl", environment) ?: throw invalid("$path.baseUrl is required")
        val apiKey = stringAt(profile, "apiKey", "$path.apiKey", environment) ?: throw invalid("$path.apiKey is required")
        val model = stringAt(profile, "model", "$path.model", environment) ?: throw invalid("$path.model is required")
        val contextWindowTokens =
            intAt(profile, "contextWindowTokens", "$path.contextWindowTokens")
                ?: throw invalid("$path.contextWindowTokens is required")
        val jsonMode = parseJsonMode(profile, "$path.jsonMode", environment)
        val connectTimeoutSeconds =
            intAt(profile, "connectTimeoutSeconds", "$path.connectTimeoutSeconds")
                ?: LlmDefaults.CONNECT_TIMEOUT_SECONDS
        val requestTimeoutSeconds =
            intAt(profile, "requestTimeoutSeconds", "$path.requestTimeoutSeconds")
                ?: LlmDefaults.REQUEST_TIMEOUT_SECONDS
        val socketTimeoutSeconds =
            intAt(profile, "socketTimeoutSeconds", "$path.socketTimeoutSeconds")
                ?: LlmDefaults.SOCKET_TIMEOUT_SECONDS
        val maxAttempts = intAt(profile, "maxAttempts", "$path.maxAttempts") ?: LlmDefaults.MAX_ATTEMPTS
        return LlmProfileConfig(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            contextWindowTokens = contextWindowTokens,
            jsonMode = jsonMode,
            connectTimeoutSeconds = connectTimeoutSeconds,
            requestTimeoutSeconds = requestTimeoutSeconds,
            socketTimeoutSeconds = socketTimeoutSeconds,
            maxAttempts = maxAttempts,
        )
    }

    private fun parseJsonMode(
        profile: Map<String, Any?>,
        path: String,
        environment: (String) -> String?,
    ): LlmResponseFormat {
        val text = stringAt(profile, "jsonMode", path, environment) ?: throw invalid("$path is required")
        return when (text.trim().lowercase()) {
            "json_schema" -> LlmResponseFormat.JSON_SCHEMA
            "json_object" -> LlmResponseFormat.JSON_OBJECT
            "prompt_only" -> LlmResponseFormat.PROMPT_ONLY
            else -> throw invalid("$path must be one of json_schema, json_object, prompt_only")
        }
    }

    private fun parseLlmTask(
        task: LlmTask,
        value: Any?,
        environment: (String) -> String?,
    ): LlmTaskConfig {
        val path = "context.llm.tasks.${task.name}"
        val mapping = asStringMap(value, path)
        rejectUnknown(mapping, CONTEXT_LLM_TASK_KEYS, path)
        val profile = stringAt(mapping, "profile", "$path.profile", environment) ?: throw invalid("$path.profile is required")
        val maxInputTokens =
            intAt(mapping, "maxInputTokens", "$path.maxInputTokens") ?: throw invalid("$path.maxInputTokens is required")
        val maxOutputTokens =
            intAt(mapping, "maxOutputTokens", "$path.maxOutputTokens") ?: throw invalid("$path.maxOutputTokens is required")
        val totalTimeoutSeconds =
            intAt(mapping, "totalTimeoutSeconds", "$path.totalTimeoutSeconds") ?: LlmDefaults.TOTAL_TIMEOUT_SECONDS
        return LlmTaskConfig(
            profile = profile,
            maxInputTokens = maxInputTokens,
            maxOutputTokens = maxOutputTokens,
            totalTimeoutSeconds = totalTimeoutSeconds,
        )
    }

    // ------------------------------------------------------------------ vfs

    private fun parseVfsDatabase(
        vfs: Map<String, Any?>?,
        baseDirectory: Path,
        environment: (String) -> String?,
    ): Path? {
        val database = vfs?.let { section(it, "database") } ?: return null
        rejectUnknown(database, VFS_DATABASE_KEYS, "vfs.database")
        val text = stringAt(database, "path", "vfs.database.path", environment) ?: return null
        return resolvePath(baseDirectory, text, "vfs.database.path")
    }

    private fun parseNamespaces(
        vfs: Map<String, Any?>?,
        environment: (String) -> String?,
    ): Set<String> {
        if (vfs == null || !vfs.containsKey("namespaces")) return emptySet()
        val value = vfs["namespaces"] ?: throw invalid("vfs.namespaces must be a list")
        val list = value as? List<*> ?: throw invalid("vfs.namespaces must be a list")
        return list
            .mapIndexed { index, element ->
                val path = "vfs.namespaces[$index]"
                val text = element as? String ?: throw invalid("$path must be a string")
                val expanded = expandEnv(text, path, environment)
                if (expanded.isBlank()) throw invalid("$path must not be blank")
                expanded
            }.toSet()
    }

    private fun parseMounts(
        vfs: Map<String, Any?>?,
        baseDirectory: Path,
        environment: (String) -> String?,
    ): List<MountConfig> {
        if (vfs == null || !vfs.containsKey("mounts")) return emptyList()
        val value = vfs["mounts"] ?: throw invalid("vfs.mounts must be a list")
        val list = value as? List<*> ?: throw invalid("vfs.mounts must be a list")
        return list.mapIndexed { index, element -> parseMount(element, index, baseDirectory, environment) }
    }

    private fun parseMount(
        element: Any?,
        index: Int,
        baseDirectory: Path,
        environment: (String) -> String?,
    ): MountConfig {
        val path = "vfs.mounts[$index]"
        val mount = asStringMap(element, path)
        rejectUnknown(mount, MOUNT_KEYS, path)

        val vfsPathText = stringAt(mount, "vfsPath", "$path.vfsPath", environment) ?: throw invalid("$path.vfsPath is required")
        val vfsPath =
            try {
                VfsPath.parse(vfsPathText)
            } catch (failure: VfsException) {
                throw invalid("$path.vfsPath is not a valid logical path")
            }
        if (vfsPath.isRoot) throw invalid("$path.vfsPath must not be the logical root /")

        val storageKey = stringAt(mount, "storageKey", "$path.storageKey", environment) ?: throw invalid("$path.storageKey is required")
        if (storageKey.isBlank()) throw invalid("$path.storageKey must not be blank")

        val backendPath = "$path.backend"
        val backend = asStringMap(mount["backend"] ?: throw invalid("$backendPath is required"), backendPath)
        val type = stringAt(backend, "type", "$backendPath.type", environment) ?: throw invalid("$backendPath.type is required")
        val parsedBackend =
            when (type) {
                "local" -> parseLocalBackend(backend, backendPath, baseDirectory, environment)
                "webdav" -> parseWebDavBackend(backend, backendPath, environment)
                else -> throw invalid("$backendPath.type must be 'local' or 'webdav'")
            }
        return MountConfig(vfsPath = vfsPath, storageKey = storageKey, backend = parsedBackend)
    }

    private fun parseLocalBackend(
        backend: Map<String, Any?>,
        path: String,
        baseDirectory: Path,
        environment: (String) -> String?,
    ): MountBackend.LocalFs {
        rejectUnknown(backend, LOCAL_BACKEND_KEYS, path)
        val text = stringAt(backend, "root", "$path.root", environment) ?: throw invalid("$path.root is required")
        return MountBackend.LocalFs(resolvePath(baseDirectory, text, "$path.root"))
    }

    private fun parseWebDavBackend(
        backend: Map<String, Any?>,
        path: String,
        environment: (String) -> String?,
    ): MountBackend.WebDav {
        rejectUnknown(backend, WEBDAV_BACKEND_KEYS, path)
        val endpoint = stringAt(backend, "endpoint", "$path.endpoint", environment) ?: throw invalid("$path.endpoint is required")
        if (endpoint.isBlank()) throw invalid("$path.endpoint must not be blank")
        val root = stringAt(backend, "root", "$path.root", environment) ?: throw invalid("$path.root is required")
        if (root.isBlank()) throw invalid("$path.root must not be blank")
        val username = stringAt(backend, "username", "$path.username", environment) ?: ""
        val password = stringAt(backend, "password", "$path.password", environment) ?: ""
        return MountBackend.WebDav(endpoint = endpoint, root = root, username = username, password = password)
    }

    private fun parseLimits(vfs: Map<String, Any?>?): VfsLimits {
        val limits = vfs?.let { section(it, "limits") } ?: return VfsLimits()
        rejectUnknown(limits, VFS_LIMITS_KEYS, "vfs.limits")
        val readLimit = longAt(limits, "defaultReadMaxBytes", "vfs.limits.defaultReadMaxBytes") ?: DEFAULT_LIMIT_BYTES
        val writeLimit = longAt(limits, "defaultWriteMaxBytes", "vfs.limits.defaultWriteMaxBytes") ?: DEFAULT_LIMIT_BYTES
        if (readLimit <= 0) throw invalid("vfs.limits.defaultReadMaxBytes must be greater than 0")
        if (writeLimit <= 0) throw invalid("vfs.limits.defaultWriteMaxBytes must be greater than 0")
        return VfsLimits(defaultReadMaxBytes = readLimit, defaultWriteMaxBytes = writeLimit)
    }

    private fun parseStreamTotalLimit(vfs: Map<String, Any?>?): Long? {
        if (vfs == null || !vfs.containsKey("streamTotalLimit")) return null
        if (vfs["streamTotalLimit"] == null) return null
        val limit = longAt(vfs, "streamTotalLimit", "vfs.streamTotalLimit") ?: return null
        if (limit < 0) throw invalid("vfs.streamTotalLimit must be null or greater than or equal to 0")
        return limit
    }

    private fun parseEventBufferCapacity(vfs: Map<String, Any?>?): Int {
        val capacity = vfs?.let { intAt(it, "eventBufferCapacity", "vfs.eventBufferCapacity") } ?: DEFAULT_EVENT_BUFFER_CAPACITY
        if (capacity < 1) throw invalid("vfs.eventBufferCapacity must be at least 1")
        return capacity
    }

    private fun parseCloseGracePeriod(
        vfs: Map<String, Any?>?,
        environment: (String) -> String?,
    ): Duration {
        val text = vfs?.let { stringAt(it, "closeGracePeriod", "vfs.closeGracePeriod", environment) } ?: return DEFAULT_CLOSE_GRACE_PERIOD
        val period = parseDuration(text, "vfs.closeGracePeriod")
        if (period.isNegative()) throw invalid("vfs.closeGracePeriod must not be negative")
        return period
    }

    // ------------------------------------------------------------------ 标量解析

    private fun stringAt(
        map: Map<String, Any?>,
        key: String,
        path: String,
        environment: (String) -> String?,
    ): String? {
        if (!map.containsKey(key)) return null
        val raw = map[key] ?: throw invalid("$path must be a string")
        val text = raw as? String ?: throw invalid("$path must be a string")
        return expandEnv(text, path, environment)
    }

    private fun booleanAt(
        map: Map<String, Any?>,
        key: String,
        path: String,
    ): Boolean? {
        if (!map.containsKey(key)) return null
        return map[key] as? Boolean ?: throw invalid("$path must be a boolean")
    }

    private fun longAt(
        map: Map<String, Any?>,
        key: String,
        path: String,
    ): Long? {
        if (!map.containsKey(key)) return null
        return integralAt(map[key], path)
    }

    private fun intAt(
        map: Map<String, Any?>,
        key: String,
        path: String,
    ): Int? {
        if (!map.containsKey(key)) return null
        val value = integralAt(map[key], path)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw invalid("$path must fit in a 32-bit integer")
        return value.toInt()
    }

    private fun integralAt(
        value: Any?,
        path: String,
    ): Long =
        when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is BigInteger ->
                try {
                    value.longValueExact()
                } catch (failure: ArithmeticException) {
                    throw invalid("$path must be a 64-bit integer")
                }
            else -> throw invalid("$path must be an integer")
        }

    private fun parseDuration(
        text: String,
        path: String,
    ): Duration =
        try {
            Duration.parse(text)
        } catch (failure: IllegalArgumentException) {
            throw invalid("$path must be a duration such as 30s")
        }

    private fun expandEnv(
        text: String,
        path: String,
        environment: (String) -> String?,
    ): String =
        ENV_PATTERN.replace(text) { match ->
            val name = match.groupValues[1]
            val value = environment(name)
            if (value == null || value.trim().isEmpty()) {
                throw ContextConfigException(
                    ContextConfigErrorCode.MISSING_ENV,
                    "environment variable '$name' referenced by $path is not set or blank",
                )
            }
            value
        }

    // ------------------------------------------------------------------ 路径与隔离

    private fun resolvePath(
        baseDirectory: Path,
        text: String,
        path: String,
    ): Path {
        if (text.isBlank()) throw invalid("$path must not be blank")
        val candidate =
            try {
                Path.of(text)
            } catch (failure: InvalidPathException) {
                throw invalid("$path is not a valid file path")
            }
        return (if (candidate.isAbsolute) candidate else baseDirectory.resolve(candidate)).normalize()
    }

    private fun resolveDefaultPath(
        baseDirectory: Path,
        relative: String,
    ): Path = baseDirectory.resolve(relative).normalize()

    private fun invalid(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)

    private fun unknownField(path: String): ContextConfigException =
        ContextConfigException(ContextConfigErrorCode.UNKNOWN_FIELD, "unknown field in a Context-managed section: $path")
}
