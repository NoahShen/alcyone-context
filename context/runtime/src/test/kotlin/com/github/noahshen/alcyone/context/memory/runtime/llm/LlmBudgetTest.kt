package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CT08-B1（A07 的预算部分）：发送前双条件、余量只计一次、输出覆盖优先级与 Long 溢出防护。
 *
 * 这里只验证**纯逻辑**；实际发送值与 0 请求断言属 B2。
 */
class LlmBudgetTest {
    private val estimator = FixedEstimator(promptTokens = 100, schemaTokens = 40)

    @Test
    fun `safety margin is counted exactly once and excludes the output reserve`() {
        val decision =
            LlmBudget.estimate(
                request = request(),
                schema = null,
                task = task(maxOutputTokens = 50),
                profile = profile(),
                safetyMarginPercent = 20,
                estimator = estimator,
            )

        assertEquals(100, decision.estimatedInputTokens)
        assertEquals(50, decision.effectiveMaxOutputTokens)
        // 20% of 100 = 20（不再随输出保留放大）。
        assertEquals(20, decision.safetyMarginTokens)
        assertEquals(170, decision.requiredTotalTokens)
    }

    @Test
    fun `schema tokens are part of the input estimate`() {
        val decision =
            LlmBudget.estimate(
                request = request(),
                schema = "{\"type\":\"object\"}",
                task = task(maxOutputTokens = 50),
                profile = profile(),
                safetyMarginPercent = 20,
                estimator = estimator,
            )

        assertEquals(140, decision.estimatedInputTokens)
        assertEquals(28, decision.safetyMarginTokens)
        assertEquals(218, decision.requiredTotalTokens)
    }

    @Test
    fun `request output tokens can only lower the task budget`() {
        val task = task(maxOutputTokens = 3_000)
        assertEquals(3_000, LlmBudget.effectiveMaxOutputTokens(null, task))
        assertEquals(3_000, LlmBudget.effectiveMaxOutputTokens(5_000, task))
        assertEquals(500, LlmBudget.effectiveMaxOutputTokens(500, task))
        assertEquals(1, LlmBudget.effectiveMaxOutputTokens(1, task))
    }

    @Test
    fun `request output below one is rejected before sending`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { requested ->
            val failure =
                assertFailsWith<LlmException> {
                    LlmBudget.effectiveMaxOutputTokens(requested, task())
                }
            assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("maxOutputTokens"), failure.message.orEmpty())
        }
    }

    @Test
    fun `input over the task budget is a local budget failure`() {
        val failure =
            assertFailsWith<LlmException> {
                LlmBudget.requireWithinBudget(
                    request = request(),
                    schema = null,
                    task = task(maxInputTokens = 99),
                    profile = profile(),
                    safetyMarginPercent = 20,
                    estimator = estimator,
                )
            }
        assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
        assertTrue(failure.message.orEmpty().contains("task input budget"), failure.message.orEmpty())
        assertEquals("extraction", failure.profile)
        assertEquals("fast-model", failure.model)
    }

    @Test
    fun `capacity check includes output reserve and margin`() {
        // 1000 + 200 + 200 = 1400 ≤ 1500：通过。
        val passing =
            LlmBudget.requireWithinBudget(
                request = request(),
                schema = null,
                task = task(maxInputTokens = 1_400, maxOutputTokens = 200),
                profile = profile(contextWindowTokens = 1_500),
                safetyMarginPercent = 20,
                estimator = FixedEstimator(promptTokens = 1_000),
            )
        assertEquals(1_400, passing.requiredTotalTokens)

        // 1100 + 200 + 220 = 1520 > 1500：输入未超 Task 预算，但容量不够。
        val failure =
            assertFailsWith<LlmException> {
                LlmBudget.requireWithinBudget(
                    request = request(),
                    schema = null,
                    task = task(maxInputTokens = 1_400, maxOutputTokens = 200),
                    profile = profile(contextWindowTokens = 1_500),
                    safetyMarginPercent = 20,
                    estimator = FixedEstimator(promptTokens = 1_100),
                )
            }
        assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
        assertTrue(failure.message.orEmpty().contains("context window"), failure.message.orEmpty())
    }

    @Test
    fun `int overflow cannot bypass the limits`() {
        // 两个估算值都取 Int.MAX_VALUE：Int 相加会回绕成负数而“通过”检查，必须由 Long 兜底拒绝。
        val saturated = FixedEstimator(promptTokens = Int.MAX_VALUE, schemaTokens = Int.MAX_VALUE)
        val failure =
            assertFailsWith<LlmException> {
                LlmBudget.requireWithinBudget(
                    request = request(),
                    schema = "schema",
                    task = task(maxInputTokens = 12_000, maxOutputTokens = 3_000),
                    profile = profile(),
                    safetyMarginPercent = 20,
                    estimator = saturated,
                )
            }
        assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)

        val decision =
            LlmBudget.estimate(
                request = request(),
                schema = "schema",
                task = task(),
                profile = profile(),
                safetyMarginPercent = 20,
                estimator = saturated,
            )
        assertEquals(2L * Int.MAX_VALUE, decision.estimatedInputTokens)
        assertTrue(decision.safetyMarginTokens > 0, "余量必须为正: ${decision.safetyMarginTokens}")
        assertTrue(decision.requiredTotalTokens > decision.estimatedInputTokens, "总量必须大于输入")
    }

    @Test
    fun `a negative prompt estimate is an invalid request`() {
        val failure =
            assertFailsWith<LlmException> {
                LlmBudget.estimate(
                    request = request(),
                    schema = null,
                    task = task(),
                    profile = profile(),
                    safetyMarginPercent = 20,
                    estimator = FixedEstimator(promptTokens = -1),
                )
            }
        assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode)
        val message = failure.message.orEmpty()
        assertTrue(message.contains("estimator.estimatePrompt"), message)
        assertTrue(message.contains("non-negative"), message)
        // 安全边界：不回显请求正文、Key 与 URL。
        assertTrue(!message.contains("hi"), message)
        assertTrue(!message.contains(CREDENTIAL_MARKER), message)
        assertTrue(!message.contains("llm.example.com"), message)
    }

    @Test
    fun `a negative schema estimate is an invalid request rather than a budget failure`() {
        listOf(-1, Int.MIN_VALUE).forEach { schemaTokens ->
            val failure =
                assertFailsWith<LlmException> {
                    LlmBudget.estimate(
                        request = request(),
                        schema = SCHEMA,
                        task = task(),
                        profile = profile(),
                        safetyMarginPercent = 20,
                        estimator = FixedEstimator(promptTokens = 1, schemaTokens = schemaTokens),
                    )
                }
            // 必须是入参错误，不能被饱和算术误归为“本地预算超限”。
            assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode)
            assertTrue(failure.errorCode != LlmErrorCode.LOCAL_BUDGET_EXCEEDED)
            val message = failure.message.orEmpty()
            assertTrue(message.contains("estimator.estimateSchema"), message)
            assertTrue(message.contains("non-negative"), message)
            // 安全边界：不回显 Schema 原文。
            assertTrue(!message.contains("object"), message)
        }
    }

    @Test
    fun `zero estimates stay legal`() {
        val zero = FixedEstimator(promptTokens = 0, schemaTokens = 0)
        val decision =
            LlmBudget.estimate(
                request = request(),
                schema = SCHEMA,
                task = task(maxOutputTokens = 3_000),
                profile = profile(),
                safetyMarginPercent = 20,
                estimator = zero,
            )
        assertEquals(0, decision.estimatedInputTokens)
        assertEquals(0, decision.safetyMarginTokens)
        assertEquals(3_000, decision.requiredTotalTokens)

        val passing =
            LlmBudget.requireWithinBudget(
                request = request(),
                schema = SCHEMA,
                task = task(maxOutputTokens = 3_000),
                profile = profile(),
                safetyMarginPercent = 20,
                estimator = zero,
            )
        assertEquals(decision, passing)
    }

    @Test
    fun `max int estimates saturate positive and still exceed the budget`() {
        val saturated = FixedEstimator(promptTokens = Int.MAX_VALUE, schemaTokens = Int.MAX_VALUE)
        val decision =
            LlmBudget.estimate(
                request = request(),
                schema = SCHEMA,
                task = task(),
                profile = profile(),
                safetyMarginPercent = 20,
                estimator = saturated,
            )
        assertEquals(2L * Int.MAX_VALUE, decision.estimatedInputTokens)
        assertTrue(decision.estimatedInputTokens > 0, "饱和后不得变负: ${decision.estimatedInputTokens}")
        assertTrue(decision.safetyMarginTokens > 0, "余量必须为正: ${decision.safetyMarginTokens}")
        assertTrue(
            decision.requiredTotalTokens > decision.estimatedInputTokens,
            "总量必须大于输入: ${decision.requiredTotalTokens}",
        )

        val failure =
            assertFailsWith<LlmException> {
                LlmBudget.requireWithinBudget(
                    request = request(),
                    schema = SCHEMA,
                    task = task(),
                    profile = profile(),
                    safetyMarginPercent = 20,
                    estimator = saturated,
                )
            }
        assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
    }

    @Test
    fun `a negative prompt estimate cannot bypass require within budget`() {
        // 修复前：estimatedInput=-1、requiredTotal=2999，两条预算检查均通过 → 静默返回。
        val failure =
            assertFailsWith<LlmException> {
                LlmBudget.requireWithinBudget(
                    request = request(),
                    schema = null,
                    task = task(maxInputTokens = 12_000, maxOutputTokens = 3_000),
                    profile = profile(contextWindowTokens = 64_000),
                    safetyMarginPercent = 20,
                    estimator = FixedEstimator(promptTokens = -1),
                )
            }
        assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode)
        assertTrue(failure.errorCode != LlmErrorCode.LOCAL_BUDGET_EXCEEDED)
        val message = failure.message.orEmpty()
        assertTrue(message.contains("estimator.estimatePrompt"), message)
        assertTrue(message.contains("non-negative"), message)
    }

    @Test
    fun `margin percent outside zero to one hundred is a configuration error`() {
        listOf(-1, 101).forEach { percent ->
            val failure =
                assertFailsWith<LlmException> {
                    LlmBudget.estimate(
                        request = request(),
                        schema = null,
                        task = task(),
                        profile = profile(),
                        safetyMarginPercent = percent,
                        estimator = estimator,
                    )
                }
            assertEquals(LlmErrorCode.CONFIGURATION, failure.errorCode)
        }
    }

    @Test
    fun `margin rounds up`() {
        // 101 * 20 / 100 = 20.2 → 21。
        assertEquals(21, LlmBudget.marginTokens(101, 20))
        assertEquals(0, LlmBudget.marginTokens(0, 20))
        assertEquals(0, LlmBudget.marginTokens(100, 0))
        assertEquals(100, LlmBudget.marginTokens(100, 100))
    }

    // ------------------------------------------------------------------ 工具

    private companion object {
        const val SCHEMA = "{\"type\":\"object\"}"
        const val CREDENTIAL_MARKER = "ct08-b1-budget-key-marker"
    }

    private fun request(maxOutputTokens: Int? = null): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.JSON_SCHEMA,
            maxOutputTokens = maxOutputTokens,
        )

    private fun task(
        maxInputTokens: Int = 12_000,
        maxOutputTokens: Int = 3_000,
    ): LlmTaskConfig =
        LlmTaskConfig(
            profile = "extraction",
            maxInputTokens = maxInputTokens,
            maxOutputTokens = maxOutputTokens,
        )

    private fun profile(contextWindowTokens: Int = 64_000): LlmProfileConfig =
        LlmProfileConfig(
            name = "extraction",
            baseUrl = "https://llm.example.com/v1",
            apiKey = CREDENTIAL_MARKER,
            model = "fast-model",
            contextWindowTokens = contextWindowTokens,
            jsonMode = LlmResponseFormat.JSON_SCHEMA,
        )

    /** 固定返回值替身：只用于验证预算算术，不涉及真实文本。 */
    private class FixedEstimator(
        private val promptTokens: Int,
        private val schemaTokens: Int = 0,
    ) : TokenEstimator {
        override fun estimateText(text: String): Int = promptTokens

        override fun estimatePrompt(messages: List<LlmMessage>): Int = promptTokens

        override fun estimateSchema(schema: String?): Int = if (schema == null) 0 else schemaTokens
    }
}
