package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingReflectionPrompt
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LlmDreamingReflectionStageTest {
    @Test
    fun `covered topics skip reflection and needs topics call exactly once in order`() =
        runBlocking {
            val turns = listOf(evidenceTurn(1), evidenceTurn(2), evidenceTurn(3))
            val evidence =
                DreamingBatchEvidence(
                    listOf(
                        reflectionTopic(turns[0], DreamingEvidenceDecision.FULLY_COVERED),
                        reflectionTopic(turns[1], DreamingEvidenceDecision.NEEDS_REFLECTION),
                        reflectionTopic(turns[2], DreamingEvidenceDecision.FULLY_COVERED),
                    ),
                )
            val llm = FakeEvidenceLlmClient { _, _ -> response("""{"insights":[]}""") }

            val result = stage(llm).reflect(evidence)

            assertEquals(emptyList(), result)
            assertEquals(1, llm.requests.size)
            assertTrue(
                llm.requests
                    .single()
                    .messages
                    .last()
                    .content
                    .contains("message-2"),
            )
        }

    @Test
    fun `multiple needs topics run serially and flatten validated insights`() =
        runBlocking {
            val turns = listOf(evidenceTurn(1, "one"), evidenceTurn(2, "two"), evidenceTurn(3, "three"))
            val evidence =
                DreamingBatchEvidence(
                    listOf(
                        reflectionTopic(turns[0], DreamingEvidenceDecision.NEEDS_REFLECTION),
                        reflectionTopic(turns[1], DreamingEvidenceDecision.FULLY_COVERED),
                        reflectionTopic(turns[2], DreamingEvidenceDecision.NEEDS_REFLECTION),
                    ),
                )
            val llm =
                FakeEvidenceLlmClient { _, call ->
                    when (call) {
                        1 ->
                            response("""{"insights":[{"content":"first","sourceTurnIds":[1]}]}""")
                        2 ->
                            response("""{"insights":[{"content":"third","sourceTurnIds":[3,3]}]}""")
                        else -> fail("unexpected reflection request")
                    }
                }

            val result = stage(llm).reflect(evidence)

            assertEquals(listOf("first", "third"), result.map { it.content })
            assertEquals(2, llm.requests.size)
            assertTrue(
                llm.requests[0]
                    .messages
                    .last()
                    .content
                    .contains("one"),
            )
            assertTrue(
                llm.requests[1]
                    .messages
                    .last()
                    .content
                    .contains("three"),
            )
            assertEquals(1, result.last().sources.size)
        }

    @Test
    fun `later topic failure fails whole reflection without returning earlier result`() =
        runBlocking {
            val turns = listOf(evidenceTurn(1), evidenceTurn(2))
            val evidence =
                DreamingBatchEvidence(
                    listOf(
                        reflectionTopic(turns[0], DreamingEvidenceDecision.NEEDS_REFLECTION),
                        reflectionTopic(turns[1], DreamingEvidenceDecision.NEEDS_REFLECTION),
                    ),
                )
            val llm =
                FakeEvidenceLlmClient { _, call ->
                    if (call == 1) response("""{"insights":[{"content":"first","sourceTurnIds":[1]}]}""") else response("not json")
                }

            val failure = assertFailsWith<LlmException> { stage(llm).reflect(evidence) }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertEquals(2, llm.requests.size)
        }

    @Test
    fun `unknown and malformed source ids make the complete topic response invalid`() =
        runBlocking {
            listOf(
                """{"insights":[{"content":"valid","sourceTurnIds":[1]},{"content":"bad","sourceTurnIds":[99]}]}""",
                """{"insights":[{"content":"bad","sourceTurnIds":[]}] }""",
            ).forEach { body ->
                val turn = evidenceTurn(1)
                val llm = FakeEvidenceLlmClient { _, _ -> response(body) }
                val failure =
                    assertFailsWith<LlmException> {
                        stage(llm).reflect(
                            DreamingBatchEvidence(listOf(reflectionTopic(turn, DreamingEvidenceDecision.NEEDS_REFLECTION))),
                        )
                    }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
                assertEquals(LlmTask.DREAMING_REFLECTION, failure.task)
                assertEquals("reflection", failure.profile)
                assertEquals("fake-reflection", failure.model)
                assertNull(failure.cause)
                assertFalse(failure.message.orEmpty().contains("bad"))
            }
        }

    @Test
    fun `request carries task format schema output budget and only current topic`() =
        runBlocking {
            val current = evidenceTurn(1, "CURRENT-TOPIC")
            val other = evidenceTurn(2, "OTHER-TOPIC")
            val topic = reflectionTopic(current, DreamingEvidenceDecision.NEEDS_REFLECTION)
            val llm = FakeEvidenceLlmClient { _, _ -> response("""{"insights":[]}""") }

            stage(llm, mode = LlmResponseFormat.JSON_SCHEMA, maxOutput = 123).reflect(
                DreamingBatchEvidence(listOf(reflectionTopic(other, DreamingEvidenceDecision.NEEDS_REFLECTION), topic)),
            )

            assertEquals(2, llm.requests.size)
            val request = llm.requests.last()
            assertEquals(LlmTask.DREAMING_REFLECTION, request.task)
            assertEquals(LlmResponseFormat.JSON_SCHEMA, request.responseFormat)
            assertEquals(DreamingReflectionPrompt.reflectionSchema(), request.schema)
            assertEquals(123, request.maxOutputTokens)
            assertTrue(
                request.messages
                    .last()
                    .content
                    .contains("CURRENT-TOPIC"),
            )
            assertFalse(
                request.messages
                    .last()
                    .content
                    .contains("OTHER-TOPIC"),
            )
        }

    @Test
    fun `json object sends no schema and schema mode counts schema in budget`() =
        runBlocking {
            val turn = evidenceTurn(1)
            val evidence = DreamingBatchEvidence(listOf(reflectionTopic(turn, DreamingEvidenceDecision.NEEDS_REFLECTION)))

            val objectLlm = FakeEvidenceLlmClient { _, _ -> response("""{"insights":[]}""") }
            stage(objectLlm, mode = LlmResponseFormat.JSON_OBJECT).reflect(evidence)
            assertNull(objectLlm.requests.single().schema)

            val schemaLlm = FakeEvidenceLlmClient { _, _ -> response("""{"insights":[]}""") }
            val failure =
                assertFailsWith<LlmException> {
                    stage(
                        schemaLlm,
                        mode = LlmResponseFormat.JSON_SCHEMA,
                        estimator = ReflectionEstimator(prompt = 10, schema = 6),
                        maxInput = 15,
                        maxOutput = 2,
                        contextWindow = 17,
                    ).reflect(evidence)
                }
            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(0, schemaLlm.requests.size)
        }

    @Test
    fun `budget equality passes and one token overrun makes no call`() =
        runBlocking {
            val turn = evidenceTurn(1)
            val evidence = DreamingBatchEvidence(listOf(reflectionTopic(turn, DreamingEvidenceDecision.NEEDS_REFLECTION)))

            val exactLlm = FakeEvidenceLlmClient { _, _ -> response("""{"insights":[]}""") }
            stage(
                exactLlm,
                estimator = ReflectionEstimator(prompt = 5, schema = 0),
                maxInput = 5,
                maxOutput = 5,
                contextWindow = 15,
            ).reflect(evidence)
            assertEquals(1, exactLlm.requests.size)

            val overrunLlm = FakeEvidenceLlmClient { _, _ -> response("""{"insights":[]}""") }
            val failure =
                assertFailsWith<LlmException> {
                    stage(
                        overrunLlm,
                        estimator = ReflectionEstimator(prompt = 6, schema = 0),
                        maxInput = 5,
                        maxOutput = 5,
                        contextWindow = 15,
                    ).reflect(evidence)
                }
            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(LlmTask.DREAMING_REFLECTION, failure.task)
            assertEquals("reflection", failure.profile)
            assertEquals("fake-reflection", failure.model)
            assertNull(failure.cause)
            assertEquals(0, overrunLlm.requests.size)
        }

    @Test
    fun `length finish reason maps to safe output truncated`() =
        runBlocking {
            val marker = "SECRET-PARTIAL-CONTENT"
            val llm = FakeEvidenceLlmClient { _, _ -> evidenceResponse(marker, finishReason = "length") }

            val failure =
                assertFailsWith<LlmException> {
                    stage(llm).reflect(
                        DreamingBatchEvidence(listOf(reflectionTopic(evidenceTurn(1), DreamingEvidenceDecision.NEEDS_REFLECTION))),
                    )
                }

            assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
            assertEquals(LlmTask.DREAMING_REFLECTION, failure.task)
            assertEquals("reflection", failure.profile)
            assertEquals("fake-reflection", failure.model)
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains(marker))
        }

    @Test
    fun `client cancellation propagates unchanged and stops later topics`() =
        runBlocking {
            val cancellation = CancellationException("ct25-reflection-cancel")
            val llm = FakeEvidenceLlmClient { _, call -> if (call == 2) throw cancellation else response("""{"insights":[]}""") }
            val turns = listOf(evidenceTurn(1), evidenceTurn(2), evidenceTurn(3))

            assertEquals(
                cancellation,
                assertFailsWith<CancellationException> {
                    stage(llm).reflect(
                        DreamingBatchEvidence(
                            turns.map { reflectionTopic(it, DreamingEvidenceDecision.NEEDS_REFLECTION) },
                        ),
                    )
                },
            )
            assertEquals(2, llm.requests.size)
        }

    @Test
    fun `binding rejects missing task missing profile and unsupported json modes`() {
        val valid = config(mode = LlmResponseFormat.JSON_OBJECT)
        val missingTaskConfig = LlmTaskConfig("missing", 1_000, 123)
        val cases =
            listOf<() -> LlmConfig>(
                { LlmConfig(valid.profiles, emptyMap(), valid.safetyMarginPercent) },
                {
                    LlmConfig(
                        valid.profiles,
                        valid.tasks + (LlmTask.DREAMING_REFLECTION to missingTaskConfig),
                        valid.safetyMarginPercent,
                    )
                },
                { config(mode = LlmResponseFormat.PROMPT_ONLY) },
            )

        cases.forEach { createConfig ->
            val failure =
                assertFailsWith<ContextConfigException> {
                    stage(FakeEvidenceLlmClient { _, _ -> fail("no call") }, config = createConfig())
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertFalse(failure.message.orEmpty().contains("fake-reflection"))
        }
    }

    private fun stage(
        llm: FakeEvidenceLlmClient,
        config: LlmConfig? = null,
        mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        estimator: TokenEstimator = ReflectionEstimator(prompt = 0, schema = 0),
        maxInput: Int = 32_000,
        maxOutput: Int = 1_000,
        contextWindow: Int = 64_000,
    ): LlmDreamingReflectionStage =
        LlmDreamingReflectionStage(
            llmClient = llm,
            llmConfig =
                config ?: reflectionConfig(mode, maxInput, maxOutput, contextWindow),
            estimator = estimator,
        )

    private fun reflectionTopic(
        turn: com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn,
        decision: DreamingEvidenceDecision,
    ): DreamingTopicEvidence =
        DreamingTopicEvidence(
            topic = evidenceTopic(title = "topic-${turn.id}", sourceTurnIds = listOf(turn.id)),
            decision = decision,
            newConversationTurns = listOf(turn),
            organizedMemories = emptyList(),
            rawMemories = emptyList(),
            historicalConversationTurns = emptyList(),
        )

    private fun response(content: String) = evidenceResponse(content)

    private fun config(mode: LlmResponseFormat): LlmConfig = reflectionConfig(mode)

    private fun reflectionConfig(
        mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        maxInput: Int = 32_000,
        maxOutput: Int = 1_000,
        contextWindow: Int = 64_000,
    ): LlmConfig {
        val profile =
            LlmProfileConfig(
                name = "reflection",
                baseUrl = "http://127.0.0.1:1",
                apiKey = "ct25-safe-key",
                model = "fake-reflection",
                contextWindowTokens = contextWindow,
                jsonMode = mode,
                maxAttempts = 1,
            )
        return LlmConfig(
            profiles = mapOf(profile.name to profile),
            tasks = mapOf(LlmTask.DREAMING_REFLECTION to LlmTaskConfig(profile.name, maxInput, maxOutput)),
            safetyMarginPercent = 0,
        )
    }

    private class ReflectionEstimator(
        private val prompt: Int,
        private val schema: Int,
    ) : TokenEstimator {
        override fun estimateText(text: String): Int = text.length

        override fun estimatePrompt(messages: List<com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage>): Int = prompt

        override fun estimateSchema(schema: String?): Int = if (schema == null) 0 else this.schema
    }
}
