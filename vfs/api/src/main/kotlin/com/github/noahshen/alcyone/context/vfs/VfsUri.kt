package com.github.noahshen.alcyone.context.vfs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 调用方使用的逻辑地址，例如 `alcyone://resources/medical/ct/a.dcm`。
 *
 * 规范化、百分号编码、大小写、尾斜线与路径穿越规则见 T02 第 2 节：解析时每段严格解码一次，
 * 序列化时逐段编码并保留 unreserved 字符。`VfsUri` → [VfsPath] → `VfsUri` 结果稳定。
 * 解码后的段不允许再出现字面 `%`，双重编码（如 `%252e`）在解析层拒绝（T02 §2.2 第 3 条）。
 * 第一段是普通路径段而非 authority，原样保留；其名称不构成白名单，
 * 解析成功不表示路径存在、可访问或已挂载。
 */
class VfsUri private constructor(
    val path: VfsPath,
) {
    override fun toString(): String =
        buildString {
            append(VfsProtocol.SCHEME).append("://") // 第一段是路径，不按通用 URL 的 authority 解释
            if (path.isRoot) return@buildString
            append(path.segments.joinToString("/") { encodeSegment(it) })
            if (path.isNamespaceRoot) append('/') // 单段路径输出尾斜线
        }

    override fun equals(other: Any?): Boolean = this === other || (other is VfsUri && path == other.path)

    override fun hashCode(): Int = path.hashCode()

    companion object {
        /** 解析并规范化逻辑地址；非法输入抛 [VfsErrorCode.INVALID_URI]，不触发任何 I/O。 */
        fun parse(text: String): VfsUri = VfsUri(VfsPath.of(splitUri(text)))

        /**
         * 由已经解析好的逻辑路径造一个 URI：只把路径包进来，**不改一个字符**，百分号编码发生在 [toString] 输出时。
         *
         * 例：文件名带空格是合法的（`/resources/my notes.txt`），原样放进 [path]，
         * 打印出来是 `alcyone://resources/my%20notes.txt`，再 [parse] 回来还是同一个路径。
         *
         * 有了这个入口，Core 组装 `NodeInfo`、Runtime 接 SDK 时就不必自己再抄一遍 URI 序列化规则
         * （scheme 怎么写、哪些字符要编码），也就不会把规则抄错。
         */
        fun of(path: VfsPath): VfsUri = VfsUri(path)
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
    // 原始文本先检查：解码会按 UTF-8 重新编码，非法字符若放过就会被静默替换。
    if (hasUnpairedSurrogate(rest)) throw invalidUri("unpaired surrogate")
    rest.firstOrNull { isForbiddenSegmentChar(it) && it != '/' }?.let { throw invalidUri("raw control character") }
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
            out.write(strictUtf8(segment.substring(index, run)))
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
    val decoded =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            throw invalidUri("percent escapes are not valid UTF-8")
        }
    // 原始 '%' 必然来自 %25；解码后仍带 '%' 说明是双重编码，下游后端再次解码会得到另一层含义。
    if ('%' in decoded) throw invalidUri("double encoding is rejected: decoded segment contains a literal '%'")
    return decoded
}

/** 逐段编码：保留 unreserved 字符，其余按 UTF-8 百分号编码，十六进制大写。 */
internal fun encodeSegment(segment: String): String =
    buildString {
        for (byte in strictUtf8(segment)) {
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

/**
 * 严格 UTF-8 编码：拒绝非法字符而不是替换成 `?`，避免规范化结果与输入不同。
 * 路径入口已经校验过字符，这里是第二道防线。
 */
private fun strictUtf8(text: String): ByteArray =
    try {
        val buffer =
            Charsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (e: CharacterCodingException) {
        throw IllegalStateException("reached encoding with unencodable text", e)
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
