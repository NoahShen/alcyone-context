package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class LlmDreamingTopicBatchBudgetTest {
    @Test
    fun `empty and single turn batches use the shared discovery request budget`() =
        runBlocking {
            val llm = FakeDreamingTopicLlmClient { _, _ -> fail("budget calculation must not call llm") }
            val stage =
                LlmDreamingTopicDiscoveryStage(
                    llmClient = llm,
                    llmConfig = dreamingTopicLlmConfig(maxInputTokens = 10, maxOutputTokens = 1, contextWindowTokens = 20),
                    estimator = DreamingTopicCountingEstimator(discoveryBase = 1, discoveryPerTurn = 1),
                )
            val budget = LlmDreamingTopicBatchBudget(stage)

            assertNull(budget.requiredTotalFor(emptyList()))
            assertNull(budget.requiredTotalFor(listOf(dreamingTopicTurn(1))))
            assertEquals(0, llm.requests.size)
        }

    @Test
    fun `overrun returns the same required total and does not call llm`() =
        runBlocking {
            val llm = FakeDreamingTopicLlmClient { _, _ -> fail("budget calculation must not call llm") }
            val stage =
                LlmDreamingTopicDiscoveryStage(
                    llmClient = llm,
                    llmConfig = dreamingTopicLlmConfig(maxInputTokens = 1, maxOutputTokens = 1, contextWindowTokens = 20),
                    estimator = DreamingTopicCountingEstimator(discoveryBase = 0, discoveryPerTurn = 2),
                )
            val budget = LlmDreamingTopicBatchBudget(stage)

            assertEquals(3L, budget.requiredTotalFor(listOf(dreamingTopicTurn(1))))
            assertEquals(0, llm.requests.size)
        }

    @Test
    fun `schema tokens count only for json schema mode and neither mode calls llm`() =
        runBlocking {
            val schemaLlm = FakeDreamingTopicLlmClient { _, _ -> fail("budget calculation must not call llm") }
            val schemaStage =
                LlmDreamingTopicDiscoveryStage(
                    llmClient = schemaLlm,
                    llmConfig =
                        dreamingTopicLlmConfig(
                            mode = LlmResponseFormat.JSON_SCHEMA,
                            maxInputTokens = 4,
                            maxOutputTokens = 1,
                            contextWindowTokens = 100,
                        ),
                    estimator = DreamingTopicCountingEstimator(schemaTokens = 5),
                )
            val schemaBudget = LlmDreamingTopicBatchBudget(schemaStage)

            assertEquals(7L, schemaBudget.requiredTotalFor(listOf(dreamingTopicTurn(1))))
            assertEquals(0, schemaLlm.requests.size)

            val objectLlm = FakeDreamingTopicLlmClient { _, _ -> fail("budget calculation must not call llm") }
            val objectStage =
                LlmDreamingTopicDiscoveryStage(
                    llmClient = objectLlm,
                    llmConfig =
                        dreamingTopicLlmConfig(
                            mode = LlmResponseFormat.JSON_OBJECT,
                            maxInputTokens = 4,
                            maxOutputTokens = 1,
                            contextWindowTokens = 100,
                        ),
                    estimator = DreamingTopicCountingEstimator(schemaTokens = 5),
                )
            val objectBudget = LlmDreamingTopicBatchBudget(objectStage)

            assertNull(objectBudget.requiredTotalFor(listOf(dreamingTopicTurn(1))))
            assertEquals(0, objectLlm.requests.size)
        }
}
