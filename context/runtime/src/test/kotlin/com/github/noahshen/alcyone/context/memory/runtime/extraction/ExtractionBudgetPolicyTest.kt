package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudgetDecision
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CT10-B1 A04：预算分类的纯逻辑（边界「等于即通过」、输入/容量超限、`requiredTotal` 透传）
 * 与窗口折半序列。不依赖 DB/LLM。
 */
class ExtractionBudgetPolicyTest {
    private val task = LlmTaskConfig(profile = "extraction", maxInputTokens = 12_000, maxOutputTokens = 3_000)
    private val profile =
        LlmProfileConfig(
            name = "extraction",
            baseUrl = "https://llm.example.com/v1",
            apiKey = "ct10-b1-budget-key-marker",
            model = "fast-model",
            contextWindowTokens = 64_000,
            jsonMode = LlmResponseFormat.JSON_SCHEMA,
        )

    @Test
    fun `both boundaries are inclusive`() {
        // estimatedInput == maxInputTokens 且 requiredTotal == contextWindowTokens → Fits
        assertEquals(
            ExtractionBudgetStatus.Fits,
            ExtractionBudgetPolicy.classify(decision(estimatedInput = 12_000, requiredTotal = 64_000), task, profile),
        )
        // 明显在预算内
        assertEquals(
            ExtractionBudgetStatus.Fits,
            ExtractionBudgetPolicy.classify(decision(estimatedInput = 1, requiredTotal = 3_001), task, profile),
        )
    }

    @Test
    fun `input over the task budget is over`() {
        val status =
            ExtractionBudgetPolicy.classify(decision(estimatedInput = 12_001, requiredTotal = 15_000), task, profile)
        assertEquals(ExtractionBudgetStatus.Over(requiredTotalTokens = 15_000), status)
    }

    @Test
    fun `capacity over the profile window is over`() {
        val status =
            ExtractionBudgetPolicy.classify(decision(estimatedInput = 12_000, requiredTotal = 64_001), task, profile)
        assertEquals(ExtractionBudgetStatus.Over(requiredTotalTokens = 64_001), status)
    }

    @Test
    fun `over reports the full required total including output and margin`() {
        val status =
            ExtractionBudgetPolicy.classify(decision(estimatedInput = 70_000, requiredTotal = 88_000), task, profile)
        assertTrue(status is ExtractionBudgetStatus.Over)
        assertEquals(88_000L, (status as ExtractionBudgetStatus.Over).requiredTotalTokens)
    }

    @Test
    fun `window size halves with a floor of one`() {
        assertEquals(16, ExtractionBudgetPolicy.nextWindowSize(32))
        assertEquals(8, ExtractionBudgetPolicy.nextWindowSize(16))
        assertEquals(4, ExtractionBudgetPolicy.nextWindowSize(8))
        assertEquals(2, ExtractionBudgetPolicy.nextWindowSize(4))
        assertEquals(1, ExtractionBudgetPolicy.nextWindowSize(2))
        assertEquals(1, ExtractionBudgetPolicy.nextWindowSize(1))
        assertEquals(5, ExtractionBudgetPolicy.nextWindowSize(10))
        assertEquals(2, ExtractionBudgetPolicy.nextWindowSize(5))
        // 防御：0/负数不得返回 0 或负数。
        assertEquals(1, ExtractionBudgetPolicy.nextWindowSize(0))
        assertEquals(1, ExtractionBudgetPolicy.nextWindowSize(-7))
    }

    @Test
    fun `classification is a pure function of the decision`() {
        val decision = decision(estimatedInput = 12_001, requiredTotal = 15_000)
        val first = ExtractionBudgetPolicy.classify(decision, task, profile)
        val second = ExtractionBudgetPolicy.classify(decision, task, profile)
        assertEquals(first, second)
    }

    // ------------------------------------------------------------------ 工具

    private fun decision(
        estimatedInput: Long,
        requiredTotal: Long,
    ): LlmBudgetDecision =
        LlmBudgetDecision(
            estimatedInputTokens = estimatedInput,
            effectiveMaxOutputTokens = 3_000,
            safetyMarginTokens = 0,
            requiredTotalTokens = requiredTotal,
        )
}
