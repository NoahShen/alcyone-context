package com.github.noahshen.alcyone.context.memory.core.ingestion

import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * CT07-B1 R1：HTTP/JSON 原始时间文本必须显式带 offset；`+08:00` 与 `Z` 等价。
 */
class IngestionTimestampTextTest {
    @Test
    fun `explicit offsets parse to the same instant`() {
        val shanghai = IngestionTimestampText.parseRequiringOffset("2026-10-23T20:15:00+08:00")
        val utc = IngestionTimestampText.parseRequiringOffset("2026-10-23T12:15:00Z")
        assertEquals(Instant.parse("2026-10-23T12:15:00Z"), shanghai)
        assertEquals(shanghai, utc)
    }

    @Test
    fun `texts without an offset date-only or invalid formats are rejected without echoing the value`() {
        listOf(
            "2026-10-23T20:15:00",
            "2026-10-23",
            "not-a-timestamp",
            "",
            "  ",
        ).forEach { text ->
            val failure = assertFailsWith<ContextIngestionException> { IngestionTimestampText.parseRequiringOffset(text) }
            assertEquals(ContextIngestionErrorCode.INVALID_TURN, failure.errorCode)
            if (text.isNotBlank()) {
                assertFalse(failure.message.orEmpty().contains(text), "错误消息不得回显原始时间文本: $text")
            }
        }
    }
}
