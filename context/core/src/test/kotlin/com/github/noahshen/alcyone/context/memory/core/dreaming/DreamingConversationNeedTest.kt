package com.github.noahshen.alcyone.context.memory.core.dreaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DreamingConversationNeedTest {
    @Test
    fun `empty need is valid and ids are defensively copied`() {
        val source = mutableListOf<String>()
        val need = DreamingConversationNeed(source)
        source += "R-2"

        assertEquals(emptyList(), need.memoryIds)
        assertEquals(emptyList(), DreamingConversationNeedValidator.validate(need, emptySet()).memoryIds)
    }

    @Test
    fun `duplicate ids are deduplicated by first occurrence`() {
        val validated =
            DreamingConversationNeedValidator.validate(
                DreamingConversationNeed(listOf("R-2", "R-1", "R-2", "R-1")),
                setOf("R-1", "R-2"),
            )

        assertEquals(listOf("R-2", "R-1"), validated.memoryIds)
    }

    @Test
    fun `blank and unknown ids fail with safe messages`() {
        val markers = listOf("", " ", "R-UNKNOWN")
        markers.forEach { marker ->
            val failure =
                assertFailsWith<DreamingConversationNeedValidationException> {
                    DreamingConversationNeedValidator.validate(
                        DreamingConversationNeed(listOf(marker)),
                        setOf("R-1"),
                    )
                }
            assertFalse(failure.message.orEmpty().contains(marker.takeIf { it.isNotBlank() } ?: "R-SECRET"))
            assertFalse(failure.message.orEmpty().contains("R-1"))
        }
    }

    @Test
    fun `toString exposes only count`() {
        val rendered = DreamingConversationNeed(listOf("R-SECRET")).toString()

        assertEquals("DreamingConversationNeed(memoryIdCount=1)", rendered)
        assertFalse(rendered.contains("R-SECRET"))
    }
}
