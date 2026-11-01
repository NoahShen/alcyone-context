package com.github.noahshen.alcyone.context.memory.core.ingestion

import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 带明确 offset 的 ISO-8601 时间文本解析（CT07 Core 内部工具）。
 *
 * 为什么需要它：公开契约 `ConversationTurn.timestamp` 是 [Instant]，SDK 调用方已经丢失原始文本
 * 的 offset 写法；HTTP/JSON 入口持有原始字符串，必须在 Core 边界按同一规则严格解析。
 *
 * 规则：
 * - 只接受带明确 offset 的 ISO-8601（`OffsetDateTime.parse`），`+08:00` 与 `Z` 得到同一 [Instant]；
 * - 无 offset（`2026-10-23T20:15:00`）、纯日期、空白或非法格式 → `ContextIngestionException(INVALID_TURN)`；
 * - 错误消息只给字段类别，不回显原文（与 CT07 脱敏要求一致）。
 *
 * 本工具只依赖 `java.time`，不依赖 HTTP/YAML/JDBC。
 */
object IngestionTimestampText {
    fun parseRequiringOffset(text: String): Instant =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (failure: DateTimeParseException) {
            throw invalidTimestamp()
        } catch (failure: IllegalArgumentException) {
            throw invalidTimestamp()
        }

    private fun invalidTimestamp(): ContextIngestionException =
        ContextIngestionException(
            ContextIngestionErrorCode.INVALID_TURN,
            "conversation turn timestamp must be an ISO-8601 text with an explicit offset",
        )
}
