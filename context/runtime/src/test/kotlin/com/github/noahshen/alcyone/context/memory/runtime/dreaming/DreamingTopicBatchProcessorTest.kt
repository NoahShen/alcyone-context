package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DreamingTopicBatchProcessorTest {
    @Test
    fun `processor returns batch tail final topic count and zero memories`() =
        runBlocking {
            val stage =
                DreamingTopicDiscoveryStage { batch ->
                    assertEquals(listOf(1L, 2L), batch.map { it.id })
                    listOf(discoveredTopic(sourceTurnIds = listOf(1L, 2L)))
                }
            val outcome =
                DreamingTopicBatchProcessor(
                    stage,
                ).process(DreamingBatchContext(0, 2, listOf(dreamingTopicTurn(1), dreamingTopicTurn(2))))

            assertEquals(2L, outcome.lastTurnId)
            assertEquals(1, outcome.discoveredTopics)
            assertEquals(0, outcome.generatedMemories)
        }

    @Test
    fun `stage failure propagates from the thin processor adapter`() =
        runBlocking {
            val failure = IllegalStateException("ct23-processor-failure")
            val stage = DreamingTopicDiscoveryStage { throw failure }
            assertEquals(
                failure,
                assertFailsWith<IllegalStateException> {
                    DreamingTopicBatchProcessor(stage).process(DreamingBatchContext(0, 1, listOf(dreamingTopicTurn(1))))
                },
            )
        }

    @Test
    fun `real sqlite coordinator preserves first batch checkpoint on second topic failure`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "one")
            insertDreamingTurn(fixture.database, sessionTurnId = "two")
            val llm =
                FakeDreamingTopicLlmClient { _, call ->
                    if (call == 1) {
                        dreamingTopicLlmResponse(dreamingTopicResponse(discoveredTopic(sourceTurnIds = listOf(1L))))
                    } else {
                        dreamingTopicLlmResponse("not valid json")
                    }
                }
            val config = dreamingTopicLlmConfig(maxInputTokens = 1, maxOutputTokens = 1, contextWindowTokens = 100)
            val stage =
                LlmDreamingTopicDiscoveryStage(
                    llmClient = llm,
                    llmConfig = config,
                    estimator = DreamingTopicCountingEstimator(discoveryBase = 0, discoveryPerTurn = 1),
                )
            val coordinator =
                DreamingRunCoordinator(
                    database = fixture.database,
                    processor = DreamingTopicBatchProcessor(stage),
                    budget = LlmDreamingTopicBatchBudget(stage),
                    pageSize = 1,
                )
            try {
                val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }
                assertEquals(com.github.noahshen.alcyone.context.memory.api.DreamingRunStage.BATCH_PROCESSING, failure.stage)
                assertEquals(0L, failure.partialResult.startCheckpoint)
                assertEquals(1L, failure.partialResult.endCheckpoint)
                assertEquals(1, failure.partialResult.discoveredTopics)
                assertEquals(0, failure.partialResult.generatedMemories)
                assertEquals(
                    com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode.INVALID_RESPONSE,
                    (failure.cause as com.github.noahshen.alcyone.context.memory.core.llm.LlmException).errorCode,
                )
            } finally {
                coordinator.closeAsync()
            }
        }
}
