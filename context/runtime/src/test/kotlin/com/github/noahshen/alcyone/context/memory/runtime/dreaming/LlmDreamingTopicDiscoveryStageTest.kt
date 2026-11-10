package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingTopicConsolidationPrompt
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingTopicDiscoveryPrompt
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LlmDreamingTopicDiscoveryStageTest {
    @Test
    fun `zero topics uses one discovery request and skips consolidation`() =
        runBlocking {
            val llm = FakeDreamingTopicLlmClient { _, _ -> dreamingTopicLlmResponse(dreamingTopicResponse()) }
            val stage = stage(llm)
            val result = stage.discover(listOf(dreamingTopicTurn(1)))

            assertEquals(emptyList(), result)
            assertEquals(1, llm.requests.size)
            assertEquals("discovery", requestKind(llm.requests.single()))
        }

    @Test
    fun `one topic uses one discovery request and skips consolidation`() =
        runBlocking {
            val llm =
                FakeDreamingTopicLlmClient {
                    _,
                    _,
                    ->
                    dreamingTopicLlmResponse(dreamingTopicResponse(discoveredTopic(sourceTurnIds = listOf(1))))
                }
            val stage = stage(llm)
            val result = stage.discover(listOf(dreamingTopicTurn(1)))

            assertEquals(1, result.size)
            assertEquals(1, llm.requests.size)
        }

    @Test
    fun `two topics use exactly discovery then consolidation with shared task`() =
        runBlocking {
            val first = discoveredTopic(title = "a", sourceTurnIds = listOf(1))
            val second = discoveredTopic(title = "b", sourceTurnIds = listOf(2))
            val merged = discoveredTopic(title = "merged", sourceTurnIds = listOf(2, 1))
            val llm =
                FakeDreamingTopicLlmClient { _, call ->
                    if (call ==
                        1
                    ) {
                        dreamingTopicLlmResponse(dreamingTopicResponse(first, second))
                    } else {
                        dreamingTopicLlmResponse(dreamingTopicResponse(merged))
                    }
                }
            val stage = stage(llm)
            val result = stage.discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2)))

            assertEquals(listOf("merged"), result.map { it.title })
            assertEquals(listOf("discovery", "consolidation"), llm.requests.map(::requestKind))
            assertEquals(listOf("DREAMING_TOPIC", "DREAMING_TOPIC"), llm.requests.map { it.task.name })
            assertTrue(
                llm.requests[1]
                    .messages
                    .last()
                    .content
                    .contains("<topics count=\"2\">"),
            )
        }

    @Test
    fun `discovery source outside current batch fails before consolidation`() =
        runBlocking {
            val llm =
                FakeDreamingTopicLlmClient {
                    _,
                    _,
                    ->
                    dreamingTopicLlmResponse(dreamingTopicResponse(discoveredTopic(sourceTurnIds = listOf(9))))
                }
            val failure = assertFailsWith<LlmException> { stage(llm).discover(listOf(dreamingTopicTurn(1))) }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertNull(failure.cause)
            assertEquals(1, llm.requests.size)
        }

    @Test
    fun `consolidation source union expansion fails without returning discovery partial result`() =
        runBlocking {
            val first = discoveredTopic(sourceTurnIds = listOf(1))
            val second = discoveredTopic(sourceTurnIds = listOf(2))
            val expanded = discoveredTopic(sourceTurnIds = listOf(1, 2, 3))
            val llm =
                FakeDreamingTopicLlmClient { _, call ->
                    if (call ==
                        1
                    ) {
                        dreamingTopicLlmResponse(dreamingTopicResponse(first, second))
                    } else {
                        dreamingTopicLlmResponse(dreamingTopicResponse(expanded))
                    }
                }
            val failure = assertFailsWith<LlmException> { stage(llm).discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2))) }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertNull(failure.cause)
            assertEquals(2, llm.requests.size)
        }

    @Test
    fun `consolidation zero topics and input plus one are rejected`() =
        runBlocking {
            val first = discoveredTopic(sourceTurnIds = listOf(1))
            val second = discoveredTopic(sourceTurnIds = listOf(2))
            listOf(
                dreamingTopicResponse(),
                dreamingTopicResponse(first, second, discoveredTopic(sourceTurnIds = listOf(1, 2))),
            ).forEach { consolidationPayload ->
                val llm =
                    FakeDreamingTopicLlmClient { _, call ->
                        if (call ==
                            1
                        ) {
                            dreamingTopicLlmResponse(dreamingTopicResponse(first, second))
                        } else {
                            dreamingTopicLlmResponse(consolidationPayload)
                        }
                    }
                val failure = assertFailsWith<LlmException> { stage(llm).discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2))) }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            }
        }

    @Test
    fun `discovery truncation is output truncated and never calls consolidation`() =
        runBlocking {
            val llm =
                FakeDreamingTopicLlmClient { _, _ ->
                    dreamingTopicLlmResponse(dreamingTopicResponse(discoveredTopic(sourceTurnIds = listOf(1))), finishReason = "length")
                }
            val failure = assertFailsWith<LlmException> { stage(llm).discover(listOf(dreamingTopicTurn(1))) }

            assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
            assertNull(failure.cause)
            assertEquals(1, llm.requests.size)
        }

    @Test
    fun `consolidation truncation fails after discovery without partial topics`() =
        runBlocking {
            val llm =
                FakeDreamingTopicLlmClient { _, call ->
                    if (call == 1) {
                        dreamingTopicLlmResponse(
                            dreamingTopicResponse(discoveredTopic(sourceTurnIds = listOf(1)), discoveredTopic(sourceTurnIds = listOf(2))),
                        )
                    } else {
                        dreamingTopicLlmResponse("""{"topics":[]}""", finishReason = "length")
                    }
                }
            val failure = assertFailsWith<LlmException> { stage(llm).discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2))) }

            assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
            assertEquals(2, llm.requests.size)
        }

    @Test
    fun `discovery budget overrun makes zero llm calls and exact boundary passes`() =
        runBlocking {
            val overrunLlm = FakeDreamingTopicLlmClient { _, _ -> fail("must not be called") }
            val overrun =
                stage(
                    overrunLlm,
                    estimator = DreamingTopicCountingEstimator(discoveryBase = 0, discoveryPerTurn = 2),
                    maxInput = 1,
                    maxOutput = 1,
                    contextWindow = 10,
                )
            val overrunFailure = assertFailsWith<LlmException> { overrun.discover(listOf(dreamingTopicTurn(1))) }
            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, overrunFailure.errorCode)
            assertEquals(0, overrunLlm.requests.size)

            val boundaryLlm = FakeDreamingTopicLlmClient { _, _ -> dreamingTopicLlmResponse(dreamingTopicResponse()) }
            val boundary =
                stage(
                    boundaryLlm,
                    estimator = DreamingTopicCountingEstimator(discoveryBase = 1, discoveryPerTurn = 1),
                    maxInput = 2,
                    maxOutput = 1,
                    contextWindow = 3,
                )
            assertEquals(emptyList(), boundary.discover(listOf(dreamingTopicTurn(1))))
            assertEquals(1, boundaryLlm.requests.size)
        }

    @Test
    fun `consolidation budget overrun occurs after discovery and sends no consolidation`() =
        runBlocking {
            val first = discoveredTopic(sourceTurnIds = listOf(1))
            val second = discoveredTopic(sourceTurnIds = listOf(2))
            val llm = FakeDreamingTopicLlmClient { _, _ -> dreamingTopicLlmResponse(dreamingTopicResponse(first, second)) }
            val stage =
                stage(
                    llm,
                    estimator = DreamingTopicCountingEstimator(discoveryBase = 0, discoveryPerTurn = 1, consolidationBase = 100),
                    maxInput = 50,
                    maxOutput = 1,
                    contextWindow = 100,
                )
            val failure = assertFailsWith<LlmException> { stage.discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2))) }

            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(1, llm.requests.size)
            assertEquals("discovery", requestKind(llm.requests.single()))
        }

    @Test
    fun `consolidation exact budget boundary succeeds with two requests`() =
        runBlocking {
            val first = discoveredTopic(sourceTurnIds = listOf(1))
            val second = discoveredTopic(sourceTurnIds = listOf(2))
            val merged = discoveredTopic(title = "merged", sourceTurnIds = listOf(1, 2))
            val llm =
                FakeDreamingTopicLlmClient { _, call ->
                    if (call == 1) {
                        dreamingTopicLlmResponse(dreamingTopicResponse(first, second))
                    } else {
                        dreamingTopicLlmResponse(dreamingTopicResponse(merged))
                    }
                }
            val stage =
                stage(
                    llm,
                    estimator =
                        DreamingTopicCountingEstimator(
                            discoveryBase = 0,
                            discoveryPerTurn = 1,
                            consolidationBase = 8,
                            consolidationPerTopic = 1,
                        ),
                    maxInput = 10,
                    maxOutput = 1,
                    contextWindow = 11,
                )

            assertEquals(listOf("merged"), stage.discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2))).map { it.title })
            assertEquals(2, llm.requests.size)
            assertEquals("consolidation", requestKind(llm.requests[1]))
        }

    @Test
    fun `json schema and object modes apply to discovery and consolidation requests`() =
        runBlocking {
            val first = discoveredTopic(sourceTurnIds = listOf(1))
            val second = discoveredTopic(sourceTurnIds = listOf(2))
            val merged = discoveredTopic(sourceTurnIds = listOf(1, 2))
            val llm =
                FakeDreamingTopicLlmClient { _, call ->
                    if (call == 1) {
                        dreamingTopicLlmResponse(dreamingTopicResponse(first, second))
                    } else {
                        dreamingTopicLlmResponse(dreamingTopicResponse(merged))
                    }
                }
            val schemaStage = stage(llm, mode = LlmResponseFormat.JSON_SCHEMA)
            schemaStage.discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2)))

            assertEquals(2, llm.requests.size)
            assertEquals(DreamingTopicDiscoveryPrompt.schema(), llm.requests[0].schema)
            assertEquals(DreamingTopicConsolidationPrompt.schema(), llm.requests[1].schema)
            assertEquals(listOf(LlmResponseFormat.JSON_SCHEMA, LlmResponseFormat.JSON_SCHEMA), llm.requests.map { it.responseFormat })

            val objectLlm =
                FakeDreamingTopicLlmClient { _, call ->
                    if (call == 1) {
                        dreamingTopicLlmResponse(dreamingTopicResponse(first, second))
                    } else {
                        dreamingTopicLlmResponse(dreamingTopicResponse(merged))
                    }
                }
            val objectStage = stage(objectLlm, mode = LlmResponseFormat.JSON_OBJECT)
            objectStage.discover(listOf(dreamingTopicTurn(1), dreamingTopicTurn(2)))
            assertEquals(2, objectLlm.requests.size)
            assertNull(objectLlm.requests[0].schema)
            assertNull(objectLlm.requests[1].schema)
            assertEquals(listOf(LlmResponseFormat.JSON_OBJECT, LlmResponseFormat.JSON_OBJECT), objectLlm.requests.map { it.responseFormat })
        }

    @Test
    fun `missing task and prompt only fail at construction without calling llm`() {
        val client = FakeDreamingTopicLlmClient { _, _ -> fail("must not be called") }
        val missingTask =
            assertFailsWith<ContextConfigException> { LlmDreamingTopicDiscoveryStage(client, dreamingTopicLlmConfig(includeTask = false)) }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, missingTask.errorCode)
        val promptOnly =
            assertFailsWith<ContextConfigException> {
                LlmDreamingTopicDiscoveryStage(client, dreamingTopicLlmConfig(mode = LlmResponseFormat.PROMPT_ONLY))
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, promptOnly.errorCode)
        assertTrue(promptOnly.message.orEmpty().contains("jsonMode"))
        assertEquals(0, client.requests.size)
    }

    @Test
    fun `cancellation from llm propagates unchanged`() =
        runBlocking {
            val cancellation = CancellationException("ct23-stage-cancel")
            val llm = FakeDreamingTopicLlmClient { _, _ -> throw cancellation }
            assertEquals(cancellation, assertFailsWith<CancellationException> { stage(llm).discover(listOf(dreamingTopicTurn(1))) })
        }

    private fun stage(
        llm: FakeDreamingTopicLlmClient,
        estimator: com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator = DreamingTopicCountingEstimator(),
        mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        maxInput: Int = 32_000,
        maxOutput: Int = 1_000,
        contextWindow: Int = 64_000,
    ): LlmDreamingTopicDiscoveryStage =
        LlmDreamingTopicDiscoveryStage(
            llmClient = llm,
            llmConfig =
                dreamingTopicLlmConfig(
                    mode = mode,
                    maxInputTokens = maxInput,
                    maxOutputTokens = maxOutput,
                    contextWindowTokens = contextWindow,
                ),
            estimator = estimator,
        )

    private fun requestKind(request: LlmRequest): String =
        if (request.messages
                .last()
                .content
                .contains("<topics count=")
        ) {
            "consolidation"
        } else {
            "discovery"
        }
}
