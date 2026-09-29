package alcyone.vfs

/** 顶级逻辑命名空间；只接受这两个小写名称作为 VfsPath 的第一段。 */
internal val NAMESPACE_SEGMENTS = setOf("memory", "resources")

/**
 * VFS 全局逻辑路径，形式为已解码的绝对路径，例如 `/resources/medical/ct/a.dcm`。
 *
 * 构造入口的输入是已解码文本，不再执行百分号解码，但仍执行与 [VfsUri] 相同的段边界检查；
 * 两个入口不允许使用不同的路径穿越规则。非法输入在本地被拒绝，不触发 Mount 或 Storage。
 */
class VfsPath private constructor(private val decoded: List<String>) {

    /** 已解码的路径段；根目录为空列表。 */
    val segments: List<String> get() = decoded

    /** 是否为逻辑根 `/`。 */
    val isRoot: Boolean get() = decoded.isEmpty()

    /** 是否为 `/memory` 或 `/resources` 命名空间根。 */
    val isNamespaceRoot: Boolean get() = decoded.size == 1

    override fun toString(): String =
        if (decoded.isEmpty()) "/" else decoded.joinToString(separator = "/", prefix = "/")

    override fun equals(other: Any?): Boolean = this === other || (other is VfsPath && decoded == other.decoded)

    override fun hashCode(): Int = decoded.hashCode()

    companion object {
        /** 逻辑根 `/`。 */
        val root: VfsPath = VfsPath(emptyList())

        /** 解析已解码的绝对逻辑路径；`/resources/a` 与 `/resources/a/` 是同一位置。 */
        fun parse(text: String): VfsPath = of(splitPath(text))

        /** 由已解码的路径段构造，段边界与命名空间规则与 [parse] 相同。 */
        fun of(segments: List<String>): VfsPath = VfsPath(validateSegments(segments, source = segments.joinToString("/")))
    }
}

private fun splitPath(text: String): List<String> {
    if (!text.startsWith("/")) throw invalidPath(text, "logical path must be absolute")
    if (text == "/") return emptyList()
    val parts = text.substring(1).split('/').toMutableList()
    if (parts.last().isEmpty()) parts.removeAt(parts.lastIndex) // 只允许一个可省略的尾部分隔符
    if (parts.any { it.isEmpty() }) throw invalidPath(text, "empty path segment")
    return parts
}

internal fun validateSegments(rawSegments: List<String>, source: String): List<String> {
    val decoded = rawSegments.map { validateSegment(it, source) }
    if (decoded.isNotEmpty() && decoded.first() !in NAMESPACE_SEGMENTS) {
        throw invalidPath(source, "first segment must be 'memory' or 'resources'")
    }
    return decoded
}

private fun validateSegment(segment: String, source: String): String {
    if (segment.isEmpty()) throw invalidPath(source, "empty path segment")
    if (segment == "." || segment == "..") throw invalidPath(source, "relative path segments are not allowed")
    segment.firstOrNull { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F }?.let {
        throw invalidPath(source, "path segment contains '/', '\\', NUL or a control character")
    }
    return segment
}

internal fun invalidPath(source: String, reason: String): VfsException =
    VfsException(VfsErrorCode.INVALID_URI, "Invalid VFS path: $reason (input length ${source.length})")
