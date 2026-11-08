package com.github.noahshen.alcyone.context.memory.core.organization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CT13-B1 A03（Core 纯逻辑）：批内候选 ID 校验——合法子集、空数组、未知/跨批/空白拒绝、
 * 重复按首次出现稳定去重。
 */
class CandidateIdValidatorTest {
    private val batchIds = setOf("O-MEM-0001", "O-MEM-0002", "O-MEM-0003")

    @Test
    fun `a subset of the sent batch is accepted in model order`() {
        assertEquals(
            listOf("O-MEM-0003", "O-MEM-0001"),
            CandidateIdValidator.validate(batchIds, listOf("O-MEM-0003", "O-MEM-0001")),
        )
    }

    @Test
    fun `an empty candidate list is legal`() {
        assertEquals(emptyList(), CandidateIdValidator.validate(batchIds, emptyList()))
    }

    @Test
    fun `an unknown or cross batch id is rejected`() {
        listOf("O-MEM-9999", "O-MEM-0004").forEach { id ->
            val failure =
                assertFailsWith<CandidateIdValidationException> {
                    CandidateIdValidator.validate(batchIds, listOf("O-MEM-0001", id))
                }
            assertTrue(failure.message.orEmpty().contains("not part of the sent batch"), failure.message.orEmpty())
        }
    }

    @Test
    fun `a blank id is rejected`() {
        listOf("", "   ", "\n").forEach { id ->
            val failure =
                assertFailsWith<CandidateIdValidationException> {
                    CandidateIdValidator.validate(batchIds, listOf(id))
                }
            assertTrue(failure.message.orEmpty().contains("must not be blank"), failure.message.orEmpty())
        }
    }

    @Test
    fun `duplicate ids are deduplicated by first occurrence`() {
        assertEquals(
            listOf("O-MEM-0002", "O-MEM-0001"),
            CandidateIdValidator.validate(
                batchIds,
                listOf("O-MEM-0002", "O-MEM-0001", "O-MEM-0002", "O-MEM-0001"),
            ),
        )
    }

    @Test
    fun `ids are compared verbatim including case and spacing`() {
        val failure =
            assertFailsWith<CandidateIdValidationException> {
                CandidateIdValidator.validate(batchIds, listOf("o-mem-0001"))
            }
        assertTrue(failure.message.orEmpty().contains("not part of the sent batch"), failure.message.orEmpty())
        val padded =
            assertFailsWith<CandidateIdValidationException> {
                CandidateIdValidator.validate(batchIds, listOf(" O-MEM-0001"))
            }
        assertTrue(padded.message.orEmpty().contains("not part of the sent batch"), padded.message.orEmpty())
    }
}
