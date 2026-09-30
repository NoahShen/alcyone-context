package com.github.noahshen.alcyone.context.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UuidTest {
    @Test
    fun `generated ids are canonical uuid v7`() {
        repeat(100) {
            val text = newUuidV7().toString()
            assertEquals(36, text.length)
            assertTrue(isUuidV7(text), "generated id must be uuid v7: $text")
            assertEquals(text, normalizeUuidV7(text, "generated"))
        }
    }

    @Test
    fun `generated ids do not repeat`() {
        val generated = List(1000) { newUuidV7().toString() }
        assertEquals(generated.size, generated.toSet().size)
    }

    @Test
    fun `generated ids are time ordered`() {
        val first = newUuidV7()
        val second = newUuidV7()
        assertTrue(second >= first, "uuid v7 must be monotonic within one runtime")
    }

    @Test
    fun `mixed case text is accepted and normalized to lower case`() {
        val lower = "018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091"
        val mixed = "018F6A3C-9C1E-7B2D-8F3A-4C5D6E7F8091"
        assertEquals(lower, normalizeUuidV7(mixed, "NodeId"))
        assertTrue(isUuidV7(mixed))
    }

    @Test
    fun `every variant nibble is accepted in both cases`() {
        for (variant in listOf('8', '9', 'a', 'b')) {
            for (written in listOf(variant, variant.uppercaseChar())) {
                val text = "018f6a3c-9c1e-7b2d-${written}f3a-4c5d6e7f8091"
                assertEquals(text.lowercase(), normalizeUuidV7(text, "uuid"), "variant $written must be accepted")
                assertTrue(isUuidV7(text))
            }
        }
    }

    @Test
    fun `uppercase variant a and b are accepted`() {
        assertEquals("018f6a3c-9c1e-7b2d-af3a-4c5d6e7f8091", normalizeUuidV7("018F6A3C-9C1E-7B2D-AF3A-4C5D6E7F8091", "NodeId"))
        assertEquals("018f6a3c-9c1e-7b2d-bf3a-4c5d6e7f8091", normalizeUuidV7("018F6A3C-9C1E-7B2D-BF3A-4C5D6E7F8091", "NodeId"))
    }

    @Test
    fun `non ascii digits and letters are rejected`() {
        assertRejects("\u0660" + "18f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091") // 阿拉伯-印度数字零
        assertRejects("\u06F1" + "18f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091") // 阿拉伯-印度数字一
        assertRejects("018f6a3c-9c1e-7b2d-\uFF2D3a-4c5d6e7f8091") // 全角字母
        assertRejects("018f6a3c-9c1e-7b2d-c\u06603a-4c5d6e7f8091") // 非 ASCII 落在 variant 位
        assertRejects("\uFF11\uFF18f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091")
    }

    @Test
    fun `wrong version and variant bits are rejected`() {
        assertRejects("018f6a3c-9c1e-4b2d-8f3a-4c5d6e7f8091") // version 4
        assertRejects("018f6a3c-9c1e-6b2d-8f3a-4c5d6e7f8091") // version 6
        assertRejects("018f6a3c-9c1e-7b2d-cf3a-4c5d6e7f8091") // variant not RFC 4122
    }

    @Test
    fun `wrong length and non hex input are rejected`() {
        assertRejects("")
        assertRejects("018f6a3c9c1e7b2d8f3a4c5d6e7f8091")
        assertRejects("018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f80912")
        assertRejects("{018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091}")
        assertRejects("018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f809z")
        assertRejects("018f6a3c+9c1e+7b2d-8f3a-4c5d6e7f8091")
    }

    @Test
    fun `error message carries the caller type name and keeps input out`() {
        val error = assertFailsWith<InvalidUuidException> { normalizeUuidV7("nope", "NodeId") }
        assertEquals("NodeId must be a canonical UUIDv7: 4 chars", error.message)
        assertNotEquals(error.message, "VfsEventId must be a canonical UUIDv7: 4 chars")
    }

    private fun assertRejects(text: String) {
        assertFailsWith<InvalidUuidException>("expected rejection for: $text") { normalizeUuidV7(text, "uuid") }
        assertTrue(!isUuidV7(text), "predicate must reject: $text")
    }
}
