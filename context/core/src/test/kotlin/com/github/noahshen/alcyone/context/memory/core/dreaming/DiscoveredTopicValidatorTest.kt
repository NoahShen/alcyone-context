package com.github.noahshen.alcyone.context.memory.core.dreaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiscoveredTopicValidatorTest {
    @Test
    fun `discovery accepts empty and normalizes duplicates while preserving order`() {
        assertEquals(emptyList(), DiscoveredTopicValidator.validateDiscovery(emptyList(), setOf(1L)))
        val validated =
            DiscoveredTopicValidator.validateDiscovery(
                listOf(DiscoveredTopic("title", "description", listOf("a", "b", "a"), listOf(2L, 1L, 2L))),
                setOf(1L, 2L),
            )

        assertEquals(listOf("a", "b"), validated.single().keywords)
        assertEquals(listOf(2L, 1L), validated.single().sourceTurnIds)
    }

    @Test
    fun `discovery rejects blank title and description without changing original input`() {
        assertInvalid { DiscoveredTopicValidator.validateDiscovery(listOf(DiscoveredTopic(" ", "d", emptyList(), listOf(1L))), setOf(1L)) }
        assertInvalid { DiscoveredTopicValidator.validateDiscovery(listOf(DiscoveredTopic("t", "\t", emptyList(), listOf(1L))), setOf(1L)) }
    }

    @Test
    fun `keywords may be empty but nonblank elements must be preserved and blank rejected`() {
        assertEquals(
            emptyList(),
            DiscoveredTopicValidator
                .validateDiscovery(
                    listOf(DiscoveredTopic("t", "d", emptyList(), listOf(1L))),
                    setOf(1L),
                ).single()
                .keywords,
        )
        assertEquals(
            listOf(" leading "),
            DiscoveredTopicValidator
                .validateDiscovery(
                    listOf(DiscoveredTopic("t", "d", listOf(" leading "), listOf(1L))),
                    setOf(1L),
                ).single()
                .keywords,
        )
        assertInvalid { DiscoveredTopicValidator.validateDiscovery(listOf(DiscoveredTopic("t", "d", listOf(" "), listOf(1L))), setOf(1L)) }
    }

    @Test
    fun `discovery rejects empty negative unknown and cross batch sources`() {
        assertInvalid { DiscoveredTopicValidator.validateDiscovery(listOf(DiscoveredTopic("t", "d", emptyList(), emptyList())), setOf(1L)) }
        assertInvalid { DiscoveredTopicValidator.validateDiscovery(listOf(DiscoveredTopic("t", "d", emptyList(), listOf(-1L))), setOf(1L)) }
        assertInvalid { DiscoveredTopicValidator.validateDiscovery(listOf(DiscoveredTopic("t", "d", emptyList(), listOf(9L))), setOf(1L)) }
    }

    @Test
    fun `discovery accepts Long boundary sources exactly when present in batch`() {
        val source = Long.MAX_VALUE
        val validated =
            DiscoveredTopicValidator.validateDiscovery(
                listOf(DiscoveredTopic("t", "d", emptyList(), listOf(source))),
                setOf(source),
            )
        assertEquals(listOf(source), validated.single().sourceTurnIds)
    }

    @Test
    fun `consolidation preserves model output order and source union`() {
        val input = listOf(DiscoveredTopic("a", "d", listOf("x"), listOf(1L)), DiscoveredTopic("b", "d", emptyList(), listOf(2L)))
        val output = listOf(DiscoveredTopic("merged", "d", listOf("x"), listOf(2L, 1L)))

        val validated = DiscoveredTopicValidator.validateConsolidation(output, input)

        assertEquals(listOf("merged"), validated.map { topic -> topic.title })
        assertEquals(listOf(2L, 1L), validated.single().sourceTurnIds)
    }

    @Test
    fun `consolidation rejects zero and input plus one topic counts`() {
        val input = listOf(DiscoveredTopic("a", "d", emptyList(), listOf(1L)), DiscoveredTopic("b", "d", emptyList(), listOf(2L)))
        assertInvalid { DiscoveredTopicValidator.validateConsolidation(emptyList(), input) }
        assertInvalid {
            DiscoveredTopicValidator.validateConsolidation(
                listOf(
                    DiscoveredTopic("a", "d", emptyList(), listOf(1L)),
                    DiscoveredTopic("b", "d", emptyList(), listOf(2L)),
                    DiscoveredTopic("c", "d", emptyList(), listOf(3L)),
                ),
                input,
            )
        }
    }

    @Test
    fun `consolidation rejects newly introduced source`() {
        val input = listOf(DiscoveredTopic("a", "d", emptyList(), listOf(1L)), DiscoveredTopic("b", "d", emptyList(), listOf(2L)))
        assertInvalid {
            DiscoveredTopicValidator.validateConsolidation(
                listOf(DiscoveredTopic("merged", "d", emptyList(), listOf(1L, 9L))),
                input,
            )
        }
    }

    @Test
    fun `consolidation rejects lost source`() {
        val input = listOf(DiscoveredTopic("a", "d", emptyList(), listOf(1L)), DiscoveredTopic("b", "d", emptyList(), listOf(2L)))
        assertInvalid {
            DiscoveredTopicValidator.validateConsolidation(
                listOf(DiscoveredTopic("merged", "d", emptyList(), listOf(1L))),
                input,
            )
        }
    }

    @Test
    fun `consolidation rejects source outside input union even when union is complete elsewhere`() {
        val input = listOf(DiscoveredTopic("a", "d", emptyList(), listOf(1L)), DiscoveredTopic("b", "d", emptyList(), listOf(2L)))
        assertInvalid {
            DiscoveredTopicValidator.validateConsolidation(
                listOf(
                    DiscoveredTopic("merged", "d", emptyList(), listOf(1L, 2L)),
                    DiscoveredTopic("extra", "d", emptyList(), listOf(1L, 9L)),
                ),
                input,
            )
        }
    }

    private fun assertInvalid(block: () -> Unit) {
        assertFailsWith<DiscoveredTopicValidationException> { block() }
    }
}
