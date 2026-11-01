package com.github.noahshen.alcyone.context.common

import java.time.Instant
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeCodecTest {
    private val canonical = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+08:00$")

    @Test
    fun `format 产生固定 +08 偏移三位毫秒文本`() {
        val instant = Instant.parse("2026-10-23T10:30:25.123Z") // 上海 18:30:25.123
        val text = TimeCodec.format(instant)
        assertTrue(canonical.matcher(text).matches(), "实际文本: $text")
        assertEquals("2026-10-23T18:30:25.123+08:00", text)
    }

    @Test
    fun `parse 与 format 可往返绝对时刻不变`() {
        val original = Instant.parse("2026-10-23T18:30:25.123+08:00")
        val text = TimeCodec.format(original)
        val parsed = TimeCodec.parse(text)
        assertEquals(original, parsed)
        assertEquals(text, TimeCodec.format(parsed))
    }

    @Test
    fun `Z 偏移文本被拒绝必须统一 +08`() {
        assertFailsWith<IllegalArgumentException> {
            TimeCodec.parse("2026-10-23T18:30:25.123Z")
        }
    }

    @Test
    fun `其他偏移文本被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            TimeCodec.parse("2026-10-23T18:30:25.123+09:00")
        }
    }

    @Test
    fun `非规范文本被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            TimeCodec.parse("2026-10-23T18:30:25.1+08:00")
        }
        assertFailsWith<IllegalArgumentException> {
            TimeCodec.parse("2026-10-23 18:30:25.123+08:00")
        }
    }
}
