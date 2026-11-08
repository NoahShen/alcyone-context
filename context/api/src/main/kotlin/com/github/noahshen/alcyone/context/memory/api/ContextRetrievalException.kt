package com.github.noahshen.alcyone.context.memory.api

/** Retrieval 请求、配置与完整性错误码。 */
enum class ContextRetrievalErrorCode {
    INVALID_REQUEST,
    CONFIGURATION,
    UNAVAILABLE,
}

/** Retrieval 公共错误契约；不挂 cause，消息不得包含 query、Memory 正文、Key 或 URL。 */
class ContextRetrievalException(
    val errorCode: ContextRetrievalErrorCode,
    message: String,
) : ContextException("[$errorCode] $message")
