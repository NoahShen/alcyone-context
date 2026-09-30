package com.github.noahshen.alcyone.context.vfs.core.storage

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.util.Collections

/**
 * 挂载根内的已解码相对路径，表示 Storage 看到的位置。
 *
 * 例：逻辑 `/notes/reports/a.txt` 挂在 `/notes` 上时，Storage 收到 `reports/a.txt`，不是逻辑 URI，
 * 也不是宿主绝对路径。挂载根本身是 [root]（空段列表），不用空字符串表示，
 * 避免被适配层误当成上级目录。
 *
 * 规则来自 T02 §2.1～§2.2 与 T07 第 2.1 节：
 *
 * - 输入已解码，[parse] **不再执行**百分号解码，`%2e%2e` 是普通文件名；
 * - 拒绝绝对路径、空中间段、`.` / `..`、段内分隔符与控制字符、孤立代理字符；
 * - 接受合法 Unicode 与空格等普通字符；
 * - 非法输入抛 [VfsErrorCode.INVALID_URI]：与 [com.github.noahshen.alcyone.context.vfs.VfsPath] 同属路径格式错误。
 *
 * 不可变值对象：构造入参与 [segments] 都不能反向改写内部状态。
 * 物理根边界、符号链接和后端能力不在这里校验，由适配层（T12）负责。
 */
class StoragePath private constructor(
    segments: List<String>,
) {
    /** 已解码的路径段；[root] 为空列表。返回的集合不可修改，改动它不会影响本对象。 */
    val segments: List<String> = Collections.unmodifiableList(ArrayList(segments))

    /** 是否为挂载根本身。 */
    val isRoot: Boolean get() = segments.isEmpty()

    /** 末段名称；[root] 为空字符串。 */
    val name: String get() = segments.lastOrNull() ?: ""

    /** 父级相对路径；[root] 与单段路径没有父级，返回 [root] 表示“仍在挂载根内”。 */
    val parent: StoragePath get() = if (segments.isEmpty()) root else StoragePath(segments.subList(0, segments.size - 1))

    /** 相对 [child] 的直接子路径；用于补齐父级目录，不产生任何 I/O。 */
    fun resolve(child: String): StoragePath = of(segments + child)

    /** 追加全部路径段；入参不会被保留或反向影响本对象。 */
    fun resolveAll(children: List<String>): StoragePath = of(segments + children)

    /**
     * 相对路径的规范文本：段之间用 `/` 连接，挂载根为 `.`。
     *
     * 用 `.` 而不是空字符串，与 T02 §3.1“不将空字符串误当上级目录”一致。
     * 注意这是适配层的交接形式，**不是** URI 编码结果。
     */
    fun toRelativeString(): String = if (segments.isEmpty()) "." else segments.joinToString("/")

    override fun toString(): String = toRelativeString()

    override fun equals(other: Any?): Boolean = this === other || (other is StoragePath && segments == other.segments)

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        /** 挂载根，段列表为空。 */
        val root: StoragePath = StoragePath(emptyList())

        /**
         * 解析挂载根内的相对路径文本。`a/b` 与 `a/b/` 相同；`.` 与空串是挂载根。
         * 绝对路径（如 `/a`）和空中间段被拒绝。
         */
        fun parse(text: String): StoragePath = of(split(text))

        /** 由已解码的路径段构造；段规则与 [parse] 相同，构造后修改入参不影响本对象。 */
        fun of(segments: List<String>): StoragePath = StoragePath(segments.map { validateSegment(it, it) })
    }
}

private fun split(text: String): List<String> {
    if (text.isEmpty() || text == ".") return emptyList()
    if (text.startsWith("/")) throw invalidPath(text, "relative storage path must not be absolute")
    val parts = text.split('/').toMutableList()
    if (parts.last().isEmpty()) parts.removeAt(parts.lastIndex) // 只允许一个可省略的尾部分隔符
    return parts
}

private fun validateSegment(
    segment: String,
    source: String,
): String {
    if (segment.isEmpty()) throw invalidPath(source, "empty path segment")
    if (segment == "." || segment == "..") throw invalidPath(source, "relative path segments are not allowed")
    if (segment.any { isForbiddenSegmentChar(it) }) {
        throw invalidPath(source, "path segment contains '/', '\\', NUL or a control character")
    }
    if (hasUnpairedSurrogate(segment)) throw invalidPath(source, "path segment contains an unpaired surrogate")
    return segment
}

/** 分隔符、反斜线、C0 控制字符、DEL 和 C1 控制字符都不能出现在路径段里。与 VfsPath 的规则一致。 */
private fun isForbiddenSegmentChar(char: Char): Boolean = char == '/' || char == '\\' || char.code < 0x20 || char.code in 0x7F..0x9F

/** 孤立代理字符在 UTF-8 编码时会被替换成 `?`，必须在校验期拒绝。 */
private fun hasUnpairedSurrogate(text: String): Boolean {
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

private fun invalidPath(
    source: String,
    reason: String,
): VfsException = VfsException(VfsErrorCode.INVALID_URI, "Invalid storage path: $reason (input length ${source.length})")
