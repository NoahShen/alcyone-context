package com.github.noahshen.alcyone.context.memory.core.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemorySearchIdValidatorTest {
    @Test
    fun `scanner accepts empty and stable deduplicates repeated batch ids`() {
        assertEquals(emptyList(), MemorySearchIdValidator.validateScannerBatch(setOf("A", "B"), emptyList()))
        assertEquals(
            listOf("B", "A"),
            MemorySearchIdValidator.validateScannerBatch(setOf("A", "B"), listOf("B", "A", "B")),
        )
    }

    @Test
    fun `scanner rejects blank and unknown ids`() {
        listOf(listOf(" "), listOf("C")).forEach { returned ->
            assertFailsWith<MemorySearchIdValidationException> {
                MemorySearchIdValidator.validateScannerBatch(setOf("A", "B"), returned)
            }
        }
    }

    @Test
    fun `rerank accepts ordered subset and empty array`() {
        assertEquals(emptyList(), MemorySearchIdValidator.validateRerank(setOf("A", "B"), emptyList()))
        assertEquals(
            listOf("B", "A"),
            MemorySearchIdValidator.validateRerank(setOf("A", "B", "C"), listOf("B", "A")),
        )
    }

    @Test
    fun `rerank rejects blank unknown and duplicate ids`() {
        listOf(listOf(" "), listOf("C"), listOf("A", "A")).forEach { returned ->
            assertFailsWith<MemorySearchIdValidationException> {
                MemorySearchIdValidator.validateRerank(setOf("A", "B"), returned)
            }
        }
    }
}
