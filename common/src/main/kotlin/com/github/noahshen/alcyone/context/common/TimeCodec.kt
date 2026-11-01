package com.github.noahshen.alcyone.context.common

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 固定 +08:00 偏移的本地时间编解码（公共工具，不含 Context/DB 业务语义）。
 *
 * 所有时间文本严格固定 [DEFAULT_OFFSET]（`+08:00`）与 3 位毫秒，
 * 格式 `uuuu-MM-dd'T'HH:mm:ss.SSS+08:00`（`uuuu` 四位年）。
 *
 * 存储使用固定偏移 [DEFAULT_OFFSET]（`ZoneOffset.ofHours(8)`），明确区分与“业务日历
 *（Asia/Shanghai 上海当地日期/当天毫秒）”，避免 1942/1943 等历史 DST 年份因
 * `ZoneId.of("Asia/Shanghai")` 而输出 `+09:00`。[parse] 严格反解析，并以
 * `format(parsed) == text` 拒绝非规范文本（`Z` / 其他偏移 / 不同小数精度均拒绝）。
 */
object TimeCodec {
    private val DEFAULT_OFFSET: ZoneOffset = ZoneOffset.ofHours(8)

    private val FORMAT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX").withZone(DEFAULT_OFFSET)

    /** 将 [Instant] 格式化为固定 `+08:00` 本地时间文本。 */
    fun format(instant: Instant): String = FORMAT.format(instant)

    /** 严格反解析固定 `+08:00` 本地时间文本为 [Instant]；非规范文本抛 [IllegalArgumentException]。 */
    fun parse(text: String): Instant =
        try {
            val parsed = OffsetDateTime.parse(text, FORMAT)
            val instant = parsed.toInstant()
            require(format(instant) == text) { "Non-canonical +08:00 local time: $text" }
            instant
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Non-canonical +08:00 local time: $text", e)
        }
}
