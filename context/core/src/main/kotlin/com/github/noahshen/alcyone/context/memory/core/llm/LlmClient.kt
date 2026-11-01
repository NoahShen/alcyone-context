package com.github.noahshen.alcyone.context.memory.core.llm

/** Core 统一 LLM 调用抽象（Port）：业务按 LlmTask 发起，不直接使用 Provider URL、密钥或厂商 SDK。 */
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

/** LLM 任务类型，用于 Task → Profile 路由。 */
enum class LlmTask {
    EXTRACTION,
    CANDIDATE_SCANNER,
    GOVERNANCE,
    MEMORY_SEARCH,
    MEMORY_MAP,
    DREAMING_TOPIC,
    DREAMING_EVIDENCE,
    DREAMING_REFLECTION,
}

/** 结构化输出模式（Profile 显式声明）。 */
enum class LlmResponseFormat {
    JSON_SCHEMA,
    JSON_OBJECT,
    PROMPT_ONLY,
}

data class LlmMessage(
    val role: String,
    val content: String,
)

data class LlmRequest(
    val task: LlmTask,
    val messages: List<LlmMessage>,
    val responseFormat: LlmResponseFormat,
    val maxOutputTokens: Int? = null,
    /**
     * 仅 `JSON_SCHEMA` 模式使用：将作为 `response_format.json_schema.schema` 发送的 JSON 文本。
     *
     * 追加在末尾并带默认值，保持既有 Kotlin 源码调用兼容（**不宣称 JVM ABI 不变**）。
     * `JSON_OBJECT` / `PROMPT_ONLY` 忽略该字段，不发送。
     */
    val schema: String? = null,
)

data class LlmResponse(
    val content: String,
    val usage: TokenUsage?,
    val finishReason: String?,
)

/** Token 使用量；缺失表示未知而非实际零。 */
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
)
