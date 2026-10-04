package com.github.noahshen.alcyone.context.vfs

import com.github.noahshen.alcyone.context.common.InvalidUuidException
import com.github.noahshen.alcyone.context.common.normalizeUuidV7

/**
 * 稳定错误码。调用方按 code 分支，不解析错误文本，也不依赖 OpenDAL / SQLDelight 异常类型。
 */
enum class VfsErrorCode {
    INVALID_URI,
    INVALID_ARGUMENT,
    READ_ONLY,
    STORAGE_ACCESS_DENIED,
    NOT_FOUND,
    ALREADY_EXISTS,
    TYPE_MISMATCH,
    DIRECTORY_NOT_EMPTY,
    MOUNT_NOT_FOUND,
    UNSUPPORTED_OPERATION,
    LIMIT_EXCEEDED,
    STORAGE_ERROR,
    STATE_ERROR,
    CONFLICT,
    RECOVERY_REQUIRED,
    CLOSED,
}

/**
 * 此次操作已产生的变更范围。effect 不能仅由错误码推断：可能部分完成或结果不明时必须显式声明。
 */
enum class VfsEffect {
    /** 确认没有提交此次操作造成的逻辑或物理变更。 */
    NONE,

    /** 已知存在部分变更，首版不自动修复。 */
    PARTIAL,

    /** 无法确定后端是否完成操作，例如响应前连接中断。 */
    UNKNOWN,
}

/**
 * VFS 领域异常。除 code 和 message 外只携带逻辑诊断信息；
 * 不公开物理 Storage Path、连接串、凭据或原始后端响应。
 *
 * @param uri 可空的逻辑 URI。
 * @param operationId 关联的操作 ID，仅用于诊断，不要求持久化操作日志。
 * @param effect 实际副作用；只在该操作确认没有副作用时才使用默认的 [VfsEffect.NONE]。
 */
class VfsException(
    val code: VfsErrorCode,
    message: String,
    val uri: VfsUri? = null,
    val operationId: String? = null,
    val effect: VfsEffect = VfsEffect.NONE,
) : RuntimeException(message)

/**
 * 换一个 effect 重新抛出同一个错误：code、message、uri、operationId 原样带上，cause 挂回原异常。
 *
 * 编排层发现「之前已经有真实副作用」时用它把 `NONE` 提升成 `PARTIAL`——调用方按 effect 判断要不要补偿。
 * `UNKNOWN` 仍代表后端说不清，不在这里改判。
 */
fun VfsException.withEffect(
    effect: VfsEffect,
    note: String,
): VfsException = VfsException(code, "$message ($note)", uri, operationId, effect).apply { initCause(this@withEffect) }

/**
 * 适配 `com.github.noahshen.alcyone.context.common` 的 UUID 校验异常：底层工具库不知道 VFS 错误契约，由 API 在边界转换，
 * 保证 [NodeId]、[VfsEventId] 对调用方只表现为 [VfsErrorCode.INVALID_ARGUMENT]。
 */
internal fun requireUuidV7(
    text: String,
    typeName: String,
): String =
    try {
        normalizeUuidV7(text, typeName)
    } catch (e: InvalidUuidException) {
        throw VfsException(VfsErrorCode.INVALID_ARGUMENT, e.message ?: "invalid UUIDv7")
    }
