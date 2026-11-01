package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections

/** `context.llm.*` 的冻结默认值（技术实现设计 §3.5，允许按 Profile/Task 覆盖）。 */
object LlmDefaults {
    const val SAFETY_MARGIN_PERCENT: Int = 20
    const val CONNECT_TIMEOUT_SECONDS: Int = 10
    const val REQUEST_TIMEOUT_SECONDS: Int = 180
    const val SOCKET_TIMEOUT_SECONDS: Int = 120
    const val TOTAL_TIMEOUT_SECONDS: Int = 360
    const val MAX_ATTEMPTS: Int = 2

    /** Token 预算/上下文容量的上限（防御明显错误的配置，不代表真实模型规格）。 */
    const val MAX_TOKEN_BOUND: Int = 10_000_000
}

/**
 * 单个 LLM Profile：地址、凭据、模型与协议能力 + HTTP 超时/重试覆盖。
 *
 * [apiKey] 是**加载期一次性展开后的真实密钥**，只允许出现在内存配置对象与当次请求 Header 中；
 * [toString] 恒输出 `apiKey=<redacted>`，[baseUrl] 只显示 `scheme://host[:port]/…`
 * （折叠 path，且不含 userinfo/query/fragment）。本类不提供任何日志/诊断出口。
 */
data class LlmProfileConfig(
    /** Profile 名，必须与 `profiles` 映射的键一致。 */
    val name: String,
    /** 绝对 http(s) 地址，可含 path。 */
    val baseUrl: String,
    /** 已展开的密钥（不可打印）。 */
    val apiKey: String,
    val model: String,
    /** Provider 上下文窗口容量（必填，不猜测供应商容量）。 */
    val contextWindowTokens: Int,
    /** 结构化输出模式（复用 Core 既有枚举，不新建枚举）。 */
    val jsonMode: LlmResponseFormat,
    val connectTimeoutSeconds: Int = LlmDefaults.CONNECT_TIMEOUT_SECONDS,
    val requestTimeoutSeconds: Int = LlmDefaults.REQUEST_TIMEOUT_SECONDS,
    val socketTimeoutSeconds: Int = LlmDefaults.SOCKET_TIMEOUT_SECONDS,
    /** 含首次请求的最大尝试次数：1 = 不重试。 */
    val maxAttempts: Int = LlmDefaults.MAX_ATTEMPTS,
) {
    override fun toString(): String =
        "LlmProfileConfig(" +
            "name=$name, " +
            "baseUrl=${redactedEndpoint(baseUrl)}, " +
            "apiKey=$REDACTED_API_KEY, " +
            "model=$model, " +
            "contextWindowTokens=$contextWindowTokens, " +
            "jsonMode=$jsonMode, " +
            "connectTimeoutSeconds=$connectTimeoutSeconds, " +
            "requestTimeoutSeconds=$requestTimeoutSeconds, " +
            "socketTimeoutSeconds=$socketTimeoutSeconds, " +
            "maxAttempts=$maxAttempts" +
            ")"

    private companion object {
        const val REDACTED_API_KEY = "<redacted>"

        /** 只保留 `scheme://host[:port]/…`；解析失败时整体脱敏，绝不回显原串。 */
        fun redactedEndpoint(baseUrl: String): String {
            val uri =
                try {
                    URI(baseUrl)
                } catch (failure: URISyntaxException) {
                    return "<redacted>"
                }
            val scheme = uri.scheme ?: return "<redacted>"
            val host = uri.host ?: return "$scheme://<redacted>/…"
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            return "$scheme://$host$port/…"
        }
    }
}

/**
 * 单个 LLM Task 的预算与总期限。Task 只引用 Profile 名，不内联地址/凭据。
 */
data class LlmTaskConfig(
    /** 目标 Profile 名（加载期已校验存在）。 */
    val profile: String,
    /** 输入 Token 预算（发送前校验）。 */
    val maxInputTokens: Int,
    /** 输出 Token 预算上限（请求只能下调，不能超过）。 */
    val maxOutputTokens: Int,
    val totalTimeoutSeconds: Int = LlmDefaults.TOTAL_TIMEOUT_SECONDS,
)

/**
 * `context.llm` 的不可变配置快照（Profile + Task 两层）。
 *
 * - 构造即取**自有副本**并调用 [validateLlm]；YAML（[com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader]）
 *   与直接 Kotlin 构造走**同一套校验**（`init`），Factory 再用 [ownedCopy] 取得第二份快照后校验并使用同一份；
 * - 对外只暴露只读 `Map` 视图，内部副本不允许被绕过修改（[Collections.unmodifiableMap]）；
 * - [toString] 只列 Profile/Task 名与目标 Profile，不含任何密钥。
 */
class LlmConfig(
    profiles: Map<String, LlmProfileConfig>,
    tasks: Map<LlmTask, LlmTaskConfig>,
    val safetyMarginPercent: Int = LlmDefaults.SAFETY_MARGIN_PERCENT,
) {
    private val profileSnapshot: Map<String, LlmProfileConfig> =
        Collections.unmodifiableMap(LinkedHashMap(profiles))
    private val taskSnapshot: Map<LlmTask, LlmTaskConfig> =
        Collections.unmodifiableMap(LinkedHashMap(tasks))

    val profiles: Map<String, LlmProfileConfig> get() = profileSnapshot

    val tasks: Map<LlmTask, LlmTaskConfig> get() = taskSnapshot

    init {
        validateLlm(this)
    }

    /** 取归本实例所有的新快照（重新复制并重新校验），供 Factory 与既有 `ownedVfsConfig` 同模式使用。 */
    fun ownedCopy(): LlmConfig = LlmConfig(profileSnapshot, taskSnapshot, safetyMarginPercent)

    override fun toString(): String =
        "LlmConfig(profiles=${profileSnapshot.keys}, " +
            "tasks=${taskSnapshot.entries.joinToString(prefix = "[", postfix = "]") { "${it.key.name}->${it.value.profile}" }}, " +
            "safetyMarginPercent=$safetyMarginPercent)"
}

/**
 * `context.llm` 的唯一校验入口（YAML 解析后与直接 Kotlin 构造共用）。
 *
 * 抛 [ContextConfigException]（`INVALID_CONFIG`）；消息**只含字段路径与区间**，
 * 不回显 Key、URL 凭据、模型正文或原始非法值。
 */
internal fun validateLlm(config: LlmConfig) {
    if (config.safetyMarginPercent !in 0..100) {
        throw invalidConfig("context.llm.safetyMarginPercent must be in 0..100")
    }
    if (config.profiles.isEmpty()) {
        throw invalidConfig("context.llm.profiles is required")
    }
    config.profiles.forEach { (key, profile) ->
        if (key.isBlank()) throw invalidConfig("context.llm.profiles has a blank name")
        val path = "context.llm.profiles.$key"
        if (profile.name != key) throw invalidConfig("$path.name must match the profile key")
        if (profile.baseUrl.isBlank()) throw invalidConfig("$path.baseUrl is required")
        requireHttpUrlWithoutCredentials(profile.baseUrl, "$path.baseUrl")
        if (profile.apiKey.isBlank()) throw invalidConfig("$path.apiKey is required")
        if (profile.model.isBlank()) throw invalidConfig("$path.model is required")
        requireInRange(profile.contextWindowTokens, 1, LlmDefaults.MAX_TOKEN_BOUND, "$path.contextWindowTokens")
        requireInRange(profile.connectTimeoutSeconds, 1, 300, "$path.connectTimeoutSeconds")
        requireInRange(profile.requestTimeoutSeconds, 1, 3600, "$path.requestTimeoutSeconds")
        requireInRange(profile.socketTimeoutSeconds, 1, 3600, "$path.socketTimeoutSeconds")
        requireInRange(profile.maxAttempts, 1, 5, "$path.maxAttempts")
    }
    config.tasks.forEach { (task, taskConfig) ->
        val path = "context.llm.tasks.${task.name}"
        val profile =
            config.profiles[taskConfig.profile]
                ?: throw invalidConfig("$path.profile refers to an unknown profile: ${taskConfig.profile}")
        requireInRange(taskConfig.maxInputTokens, 1, LlmDefaults.MAX_TOKEN_BOUND, "$path.maxInputTokens")
        requireInRange(taskConfig.maxOutputTokens, 1, LlmDefaults.MAX_TOKEN_BOUND, "$path.maxOutputTokens")
        requireInRange(taskConfig.totalTimeoutSeconds, 1, 3600, "$path.totalTimeoutSeconds")
        // 静态预算自洽（不涉及估算）：用 Long 相加，避免 Int 溢出绕过上限。
        val planned = taskConfig.maxInputTokens.toLong() + taskConfig.maxOutputTokens.toLong()
        if (planned > profile.contextWindowTokens.toLong()) {
            throw invalidConfig("$path exceeds the profile context window: ${taskConfig.profile}")
        }
    }
}

private fun requireInRange(
    value: Int,
    min: Int,
    max: Int,
    path: String,
) {
    if (value !in min..max) throw invalidConfig("$path must be in $min..$max")
}

/** 只校验「绝对 http(s) 且无 userinfo/query/fragment」；任何失败都用同一条不含值的消息。 */
private fun requireHttpUrlWithoutCredentials(
    value: String,
    path: String,
) {
    val failure = "$path must be an http(s) URL without userinfo, query or fragment"
    val uri =
        try {
            URI(value)
        } catch (cause: URISyntaxException) {
            throw invalidConfig(failure)
        }
    if (uri.scheme?.lowercase() != "http" && uri.scheme?.lowercase() != "https") throw invalidConfig(failure)
    if (!uri.isAbsolute || uri.host.isNullOrBlank()) throw invalidConfig(failure)
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) throw invalidConfig(failure)
}

private fun invalidConfig(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
