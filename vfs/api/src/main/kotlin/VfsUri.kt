package alcyone.vfs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 调用方使用的逻辑地址，例如 `alcyone://resources/medical/ct/a.dcm`。
 *
 * 规范化、百分号编码、大小写、尾斜线与路径穿越规则见 T02 第 2 节：解析时每段严格解码一次，
 * 序列化时逐段编码并保留 unreserved 字符。`VfsUri` → [VfsPath] → `VfsUri` 结果稳定。
 * `memory` / `resources` 始终作为 [VfsPath] 的第一段保留，不按通用 URL 的 authority 解释。
 */
class VfsUri private constructor(
    val path: VfsPath,
) {
    override fun toString(): String =
        buildString {
            append(VfsProtocol.SCHEME).append("://") // memory/resources 保留为路径第一段，不作为 authority
            if (path.isRoot) return@buildString
            append(path.segments.joinToString("/") { encodeSegment(it) })
            if (path.isNamespaceRoot) append('/') // 两个命名空间根输出尾斜线
        }

    override fun equals(other: Any?): Boolean = this === other || (other is VfsUri && path == other.path)

    override fun hashCode(): Int = path.hashCode()

    companion object {
        /** 解析并规范化逻辑地址；非法输入抛 [VfsErrorCode.INVALID_URI]，不触发任何 I/O。 */
        fun parse(text: String): VfsUri = VfsUri(VfsPath.of(splitUri(text)))
    }
}

private fun splitUri(text: String): List<String> {
    val schemeEnd = text.indexOf("://")
    if (schemeEnd != VfsProtocol.SCHEME.length ||
        !text.regionMatches(0, VfsProtocol.SCHEME, 0, VfsProtocol.SCHEME.length, ignoreCase = true)
    ) {
        throw invalidUri("scheme must be '${VfsProtocol.SCHEME}'")
    }
    val rest = text.substring(schemeEnd + "://".length)
    if (rest.isEmpty()) return emptyList() // alcyone:// → /
    rest.firstOrNull { it.code < 0x20 || it.code == 0x7F }?.let { throw invalidUri("raw control character") }
    if ('\\' in rest) throw invalidUri("raw backslash")
    if ('?' in rest || '#' in rest) throw invalidUri("query and fragment are not supported")
    if (' ' in rest) throw invalidUri("raw space must be encoded as %20")
    val parts = rest.split('/').toMutableList()
    if (parts.last().isEmpty()) parts.removeAt(parts.lastIndex) // 只允许一个可省略的尾部分隔符
    if (parts.any { it.isEmpty() }) throw invalidUri("empty path segment")
    val first = parts.first()
    if (':' in first) throw invalidUri("port or userinfo is not supported")
    if ('@' in first) throw invalidUri("userinfo is not supported")
    return parts.map { percentDecodeOnce(it) }
}

/** 严格按 UTF-8 百分号解码一次；非法转义、非法字节序列和不合法转义字符都被拒绝。 */
internal fun percentDecodeOnce(segment: String): String {
    if ('%' !in segment) return segment
    val out = ByteArrayOutputStream()
    var index = 0
    while (index < segment.length) {
        if (segment[index] != '%') {
            val run = segment.indexOf('%', index).let { if (it < 0) segment.length else it }
            out.write(segment.substring(index, run).toByteArray(Charsets.UTF_8))
            index = run
            continue
        }
        if (index + 2 >= segment.length) throw invalidUri("truncated percent escape")
        val high = hexDigit(segment[index + 1])
        val low = hexDigit(segment[index + 2])
        if (high < 0 || low < 0) throw invalidUri("invalid percent escape")
        out.write((high shl 4) or low)
        index += 3
    }
    return try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray()))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw invalidUri("percent escapes are not valid UTF-8")
    }
}

/** 逐段编码：保留 unreserved 字符，其余按 UTF-8 百分号编码，十六进制大写。 */
internal fun encodeSegment(segment: String): String =
    buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '.' ||
                char == '_' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
    }

private const val HEX = "0123456789ABCDEF"

private fun hexDigit(char: Char): Int =
    when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }

internal fun invalidUri(reason: String): VfsException = VfsException(VfsErrorCode.INVALID_URI, "Invalid VFS URI: $reason")
