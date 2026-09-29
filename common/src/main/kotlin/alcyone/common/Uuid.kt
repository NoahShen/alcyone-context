package alcyone.common

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

/**
 * UUIDv7 文本格式非法。
 *
 * `common` 是底层工具库，不能依赖上层领域类型，因此这里抛自身异常；
 * 调用方（例如 `alcyone.vfs.NodeId`）按自己的错误契约转换，保持外部行为不变。
 */
class InvalidUuidException(
    message: String,
) : IllegalArgumentException(message)

/**
 * 生成按时间有序的 UUIDv7，用于 Node ID 和事件 ID。
 *
 * uuid-creator 6.x：`getTimeOrdered()` 是 UUIDv6，`getTimeOrderedEpoch()` 才是 version 7；
 * 单元测试会断言 version 位，避免再次选错入口。
 */
fun newUuidV7(): UUID = UuidCreator.getTimeOrderedEpoch()

/** 判断文本是否为规范 UUIDv7，不抛异常。 */
fun isUuidV7(text: String): Boolean = runCatching { normalizeUuidV7(text, "uuid") }.isSuccess

/**
 * 校验并规范化 UUIDv7 文本：36 字符、连字符位于 8/13/18/23、ASCII 十六进制字符、
 * version 位为 `7`、variant 位属于 `89ab`。大小写十六进制都能输入，统一输出小写；
 * 非 ASCII 的数字或字母一律拒绝。
 *
 * @param typeName 调用方类型名，例如 `NodeId`，只进入错误消息。
 * @throws InvalidUuidException 格式非法。
 */
fun normalizeUuidV7(
    text: String,
    typeName: String,
): String {
    val invalid = { InvalidUuidException("$typeName must be a canonical UUIDv7: ${text.length} chars") }
    if (text.length != 36) throw invalid()
    for (index in DASH_POSITIONS) if (text[index] != '-') throw invalid()
    val hex = text.filterIndexed { index, _ -> index !in DASH_POSITIONS }
    if (hex.any { !it.isAsciiHexDigit() }) throw invalid()
    if (text[VERSION_POS] != '7') throw invalid()
    if (text[VARIANT_POS].lowercaseChar() !in "89ab") throw invalid()
    return text.lowercase()
}

/** 只接受 ASCII 十六进制；`Character.digit` 一类的 API 会把非 ASCII 数字也算作合法。 */
private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private val DASH_POSITIONS = intArrayOf(8, 13, 18, 23)
private const val VERSION_POS = 14
private const val VARIANT_POS = 19
