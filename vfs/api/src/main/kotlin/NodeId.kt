package alcyone.vfs

/**
 * 稳定资源身份，UUIDv7 的文本形式，移动、重命名和跨 Mount 移动后保持不变，不是路径的哈希。
 *
 * UUIDv7 由 Core 生成；`vfs/api` 不引入 UUID 库，本类型只做格式校验和值语义。
 */
class NodeId private constructor(val value: String) {

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = this === other || (other is NodeId && value == other.value)

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** 校验并规范化 UUIDv7 文本（接受大写，输出小写）；格式非法抛 [VfsErrorCode.INVALID_ARGUMENT]。 */
        fun parse(text: String): NodeId = NodeId(normalizeUuidV7(text, "NodeId"))
    }
}

internal fun normalizeUuidV7(text: String, type: String): String {
    val invalid = { VfsException(VfsErrorCode.INVALID_ARGUMENT, "$type must be a canonical UUIDv7: ${text.length} chars") }
    if (text.length != 36) throw invalid()
    for (index in intArrayOf(8, 13, 18, 23)) if (text[index] != '-') throw invalid()
    val hex = text.filterIndexed { index, _ -> index !in intArrayOf(8, 13, 18, 23) }
    if (hex.any { it.digitToIntOrNull(16) == null }) throw invalid()
    if (text[14] != '7') throw invalid() // version
    if (text[19] !in "89ab") throw invalid() // variant
    return text.lowercase()
}
