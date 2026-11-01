package com.github.noahshen.alcyone.context.memory.core.llm

import com.github.noahshen.alcyone.context.memory.api.ContextException

/**
 * LLM 调用失败分类（技术实现设计 §3.5 冻结分类 + 本地预算码）。
 *
 * 分类由 Adapter 在协议层判定，业务层只按 [LlmException.errorCode] 分支，
 * **不按消息文本分类**。本地预算超限 [LOCAL_BUDGET_EXCEEDED] 与 Provider 返回的
 * [CONTEXT_LIMIT_EXCEEDED] 必须分开：前者是可确定未发送请求的本地拒绝，
 * 后者是远端拒绝，不能据此把业务对象（如 Turn）标记为 failed。
 */
enum class LlmErrorCode {
    /** 配置缺失/非法（如 Task 未映射 Profile）。 */
    CONFIGURATION,

    /** 认证失败（401/403）。 */
    AUTHENTICATION,

    /** 额度/配额耗尽（不重试）。 */
    QUOTA_EXCEEDED,

    /** 被限流（是否重试由 Adapter 判定）。 */
    RATE_LIMITED,

    /** 连接/DNS/传输中断。 */
    NETWORK,

    /** 超时（单次请求或总期限）。 */
    TIMEOUT,

    /** Provider 不可用（5xx）。 */
    PROVIDER_UNAVAILABLE,

    /** 请求本身非法（参数、schema、jsonMode 不一致）。 */
    INVALID_REQUEST,

    /** Provider 判定超出其上下文长度。 */
    CONTEXT_LIMIT_EXCEEDED,

    /** 响应非法（非 JSON、缺 content、空输出）。 */
    INVALID_RESPONSE,

    /** 输出被截断（`finish_reason=length`）。 */
    OUTPUT_TRUNCATED,

    /** 模型明确拒答。 */
    MODEL_REFUSAL,

    /** 本地发送前预算校验未通过（未发起 HTTP）。 */
    LOCAL_BUDGET_EXCEEDED,
}

/**
 * LLM 调用失败（Port 的可分类错误），继承 [ContextException]。
 *
 * 只携带**安全元数据**：[task] / [profile] / [model] / [statusCode] / [attempts]。
 * 严禁把 API Key、Authorization、请求/响应正文、带凭据的 URL 放进 `message`、
 * [cause] 或本对象的任何字段；Provider 的 `code` / `requestId` / `finishReason`
 * 原值同样不作为元数据保存（仅用于分类判定）。
 */
open class LlmException(
    val errorCode: LlmErrorCode,
    message: String,
    /** 任务类型；调用方拿不到时可为 null。 */
    val task: LlmTask? = null,
    /** Profile 名（不是 baseUrl / 凭据）。 */
    val profile: String? = null,
    /** 模型名（由配置给出，不含凭据）。 */
    val model: String? = null,
    /** HTTP 状态码（如有）。 */
    val statusCode: Int? = null,
    /** 已执行的尝试次数（含首次）。 */
    val attempts: Int? = null,
    cause: Throwable? = null,
) : ContextException("[$errorCode] $message", cause) {
    override fun toString(): String {
        val details =
            buildList {
                task?.let { add("task=${it.name}") }
                profile?.let { add("profile=$it") }
                model?.let { add("model=$it") }
                statusCode?.let { add("statusCode=$it") }
                attempts?.let { add("attempts=$it") }
            }
        val suffix = if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
        return "${javaClass.simpleName}(errorCode=$errorCode)$suffix: $message"
    }
}
