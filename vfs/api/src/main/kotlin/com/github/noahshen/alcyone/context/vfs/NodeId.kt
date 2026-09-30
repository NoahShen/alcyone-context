package com.github.noahshen.alcyone.context.vfs

/**
 * 稳定资源身份，UUIDv7 的文本形式，移动、重命名和跨 Mount 移动后保持不变，不是路径的哈希。
 *
 * UUIDv7 由 Core 生成（`com.github.noahshen.alcyone.context.common.newUuidV7()`）；本类型只做格式校验和值语义。
 */
class NodeId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = this === other || (other is NodeId && value == other.value)

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** 校验并规范化 UUIDv7 文本（接受大写，输出小写）；格式非法抛 [VfsErrorCode.INVALID_ARGUMENT]。 */
        fun parse(text: String): NodeId = NodeId(requireUuidV7(text, "NodeId"))
    }
}
