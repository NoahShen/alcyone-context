package com.github.noahshen.alcyone.context.memory.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ContextRetrievalExceptionTest {
    @Test
    fun `retrieval exposes exactly three safe error codes without cause`() {
        assertEquals(
            listOf("INVALID_REQUEST", "CONFIGURATION", "UNAVAILABLE"),
            ContextRetrievalErrorCode.entries.map { it.name },
        )
        ContextRetrievalErrorCode.entries.forEach { code ->
            val failure = ContextRetrievalException(code, "safe retrieval message")
            assertEquals("[$code] safe retrieval message", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("sk-secret"))
        }
    }
}
