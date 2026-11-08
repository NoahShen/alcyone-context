package com.github.noahshen.alcyone.context.memory.api

/**
 * Context 子系统统一异常基类。
 *
 * 继承 [RuntimeException]，支持异常消息与可选因由（cause）。
 * 严禁在异常消息中包含 API Key、密码、会话正文等敏感信息；
 * 发生取消时绝不将 [kotlinx.coroutines.CancellationException] 包装为此类异常，保持向外传播。
 */
abstract class ContextException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 配置错误码。 */
enum class ContextConfigErrorCode {
    INVALID_CONFIG,
    MISSING_ENV,
    UNKNOWN_FIELD,
}

/** 配置解析与校验异常。 */
class ContextConfigException(
    val errorCode: ContextConfigErrorCode,
    message: String,
    cause: Throwable? = null,
) : ContextException("[$errorCode] $message", cause)

/** 生命周期错误码。 */
enum class ContextLifecycleErrorCode {
    NOT_STARTED,
    CLOSED,
    START_FAILED,
}

/** 实例生命周期状态冲突与非法操作异常。 */
class ContextLifecycleException(
    val errorCode: ContextLifecycleErrorCode,
    message: String,
    cause: Throwable? = null,
) : ContextException("[$errorCode] $message", cause)

/** 持久化错误码。 */
enum class ContextPersistenceErrorCode {
    PERSISTENCE_UNAVAILABLE,
    DATABASE_NOT_INITIALIZED,
    SCHEMA_VERSION_MISMATCH,
}

/** 数据库与持久化层异常。 */
class ContextPersistenceException(
    val errorCode: ContextPersistenceErrorCode,
    message: String,
    cause: Throwable? = null,
) : ContextException("[$errorCode] $message", cause)

/** 会话轮次入库错误码。 */
enum class ContextIngestionErrorCode {
    INVALID_TURN,
    PAYLOAD_TOO_LARGE,
    TURN_CONFLICT,
    UNAUTHORIZED,
    FORBIDDEN,
}

/** 会话轮次接收与校验异常。 */
class ContextIngestionException(
    val errorCode: ContextIngestionErrorCode,
    message: String,
    cause: Throwable? = null,
) : ContextException("[$errorCode] $message", cause)

/** Resource 只读访问的安全错误码。 */
enum class ContextResourceErrorCode {
    INVALID_URI,
    OUT_OF_SCOPE,
    NOT_FOUND,
    TYPE_MISMATCH,
    LIMIT_EXCEEDED,
    UNAVAILABLE,
}

/**
 * Resource 读取失败。
 *
 * 不携带 cause：VFS/OpenDAL 异常链可能包含物理路径、凭据或后端原文，由 Runtime 在边界脱敏。
 */
class ContextResourceException(
    val errorCode: ContextResourceErrorCode,
    message: String,
) : ContextException("[$errorCode] $message")
