package com.github.noahshen.alcyone.context.vfs

import java.util.Collections

/**
 * VFS 全局逻辑路径，形式为已解码的绝对路径，例如 `/resources/medical/ct/a.dcm`。
 *
 * 构造入口的输入是已解码文本，不再执行百分号解码，但仍执行与 [VfsUri] 相同的段边界检查；
 * 两个入口不允许使用不同的路径穿越规则。非法输入在本地被拒绝，不触发 Mount 或 Storage。
 *
 * 顶层目录名称不在这里校验：命名空间由后续 Runtime 配置提供，解析成功不表示路径存在、可访问或已挂载。
 * 本对象是不可变值对象：构造输入和 [segments] 都不能反向改写内部状态。
 */
class VfsPath private constructor(
    segments: List<String>,
) {
    /** 已解码的路径段；根目录为空列表。返回的集合不可修改，改动它不会影响本对象。 */
    val segments: List<String> = Collections.unmodifiableList(ArrayList(segments))

    /** 是否为逻辑根 `/`。 */
    val isRoot: Boolean get() = segments.isEmpty()

    /**
     * 是否为单段路径结构，例如 `/notes`。
     * 只描述路径形状，不证明配置中存在该命名空间，也不表示该目录存在或可访问。
     */
    val isNamespaceRoot: Boolean get() = segments.size == 1

    override fun toString(): String = if (segments.isEmpty()) "/" else segments.joinToString(separator = "/", prefix = "/")

    override fun equals(other: Any?): Boolean = this === other || (other is VfsPath && segments == other.segments)

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        /** 逻辑根 `/`。 */
        val root: VfsPath = VfsPath(emptyList())

        /** 解析已解码的绝对逻辑路径；`/resources/a` 与 `/resources/a/` 是同一位置。 */
        fun parse(text: String): VfsPath = of(splitPath(text))

        /** 由已解码的路径段构造，段边界规则与 [parse] 相同；构造后修改输入列表不影响本对象。 */
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

private fun validateSegments(
    rawSegments: List<String>,
    source: String,
): List<String> = rawSegments.map { validateSegment(it, source) }

private fun validateSegment(
    segment: String,
    source: String,
): String {
    if (segment.isEmpty()) throw invalidPath(source, "empty path segment")
    if (segment == "." || segment == "..") throw invalidPath(source, "relative path segments are not allowed")
    if (segment.any { isForbiddenSegmentChar(it) }) {
        throw invalidPath(source, "path segment contains '/', '\\', NUL or a control character")
    }
    if (hasUnpairedSurrogate(segment)) {
        throw invalidPath(source, "path segment contains an unpaired surrogate")
    }
    return segment
}

/** 分隔符、反斜线、C0 控制字符、DEL 和 C1 控制字符都不能出现在路径段里。 */
internal fun isForbiddenSegmentChar(char: Char): Boolean = char == '/' || char == '\\' || char.code < 0x20 || char.code in 0x7F..0x9F

/**
 * 检查是否存在未配对的代理项。孤立的代理字符在 UTF-8 编码时会被替换成 `?`，
 * 使序列化结果与输入不一致，因此必须在编码前拒绝。合法代理对（多数 emoji）不算问题。
 */
internal fun hasUnpairedSurrogate(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return true
                index++
            }

            char.isLowSurrogate() -> return true
        }
        index++
    }
    return false
}

internal fun invalidPath(
    source: String,
    reason: String,
): VfsException = VfsException(VfsErrorCode.INVALID_URI, "Invalid VFS path: $reason (input length ${source.length})")
