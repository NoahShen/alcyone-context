package com.github.noahshen.alcyone.context.memory.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SessionKeyTest {
    @Test
    fun `合法三元键构造与属性读取正确`() {
        val key = SessionKey("rt-1", "ag-1", "session-1")
        assertEquals("rt-1", key.runtimeId)
        assertEquals("ag-1", key.agentId)
        assertEquals("session-1", key.sessionId)
        assertEquals(key, SessionKey("rt-1", "ag-1", "session-1"))
        assertEquals(key.hashCode(), SessionKey("rt-1", "ag-1", "session-1").hashCode())
    }

    @Test
    fun `空白字段拒绝`() {
        assertFailsWith<IllegalArgumentException> { SessionKey("", "ag", "s") }
        assertFailsWith<IllegalArgumentException> { SessionKey("   ", "ag", "s") }
        assertFailsWith<IllegalArgumentException> { SessionKey("rt", "", "s") }
        assertFailsWith<IllegalArgumentException> { SessionKey("rt", " ", "s") }
        assertFailsWith<IllegalArgumentException> { SessionKey("rt", "ag", "") }
        assertFailsWith<IllegalArgumentException> { SessionKey("rt", "ag", "  ") }
    }

    @Test
    fun `不同字段产生不同 SessionKey`() {
        val k1 = SessionKey("rt-1", "ag-1", "session-1")
        val k2 = SessionKey("rt-2", "ag-1", "session-1")
        assertNotEquals(k1, k2)
    }
}
