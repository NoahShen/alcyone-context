package com.github.noahshen.alcyone.context.vfs

import java.time.Instant

/**
 * 稳定的变更事件身份，UUIDv7 文本形式；由 Core 生成（`com.github.noahshen.alcyone.context.common.newUuidV7()`），本类型只做格式校验和值语义。
 */
class VfsEventId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = this === other || (other is VfsEventId && value == other.value)

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** 校验并规范化 UUIDv7 文本（接受大写，输出小写）；格式非法抛 [VfsErrorCode.INVALID_ARGUMENT]。 */
        fun parse(text: String): VfsEventId = VfsEventId(requireUuidV7(text, "VfsEventId"))
    }
}

/**
 * 领域事件类型，覆盖成功的状态变更。
 *
 * 懒注册不公开事件，因此首版没有 `NODE_REGISTERED`；可靠消费与重启重放属于后续 E05。
 */
enum class VfsEventType {
    FILE_CREATED,
    FILE_WRITTEN,
    FILE_MOVED,
    DIRECTORY_MOVED,
    FILE_DELETED,
    DIRECTORY_DELETED,
    METADATA_UPDATED,
}

/**
 * 公共事件数据。事件代表已完成的核心变更，不携带文件内容、凭据或 Agent 身份。
 *
 * @param uri 事件发生位置的逻辑 URI；移动事件为目标 URI。
 * @param nodeId 可空；仅查询到逻辑记录的删除事件可能没有有效 Node。
 * @param operationId 关联的操作 ID，仅用于诊断。
 * @param sourceUri / targetUri 仅移动事件填写。
 */
data class VfsEvent(
    val id: VfsEventId,
    val type: VfsEventType,
    val nodeId: NodeId?,
    val occurredAt: Instant,
    val uri: VfsUri,
    val operationId: String?,
    val sourceUri: VfsUri? = null,
    val targetUri: VfsUri? = null,
)
