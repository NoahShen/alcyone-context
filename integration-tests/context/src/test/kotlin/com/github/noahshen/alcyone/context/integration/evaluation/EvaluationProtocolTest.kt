package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.EvaluationCallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvaluationProtocolTest {
    private val root =
        com.github.noahshen.alcyone.context.memory.integration.evaluation.DefaultEvaluationRoot
            .find()
    private val sample = loadEvalSamples(root).first { it.task == "EXTRACTION" }

    @Test
    fun `judge semantic failure is not replaced by protocol success`() =
        runBlocking {
            val judge = SemanticJudge(FixedJudge("""{"pass":false,"reason":"required attribution was omitted"}"""), sample)
            val result = SampleResult(sample, checks = listOf(Check("fixture", Verdict.FAIL, "deterministic negative fixture")))
            val checks = semanticReview(sample, result, judge)
            assertEquals("FAIL", SampleResult(sample, checks = checks).status)
            assertEquals("required attribution was omitted", checks.first { it.id == "judge_required_facts" }.evidence)
        }

    @Test
    fun `protocol failure cannot become semantic pass`() =
        runBlocking {
            val judge = SemanticJudge(FixedJudge("""{"pass":true,"reason":"must not be invoked"}"""), sample)
            val checks = semanticReview(sample, SampleResult(sample, error = "LlmException:INVALID_RESPONSE"), judge)
            assertEquals("PROTOCOL_FAILURE", SampleResult(sample, checks = checks, error = "LlmException:INVALID_RESPONSE").status)
            assertTrue(judge.calls.isEmpty())
        }

    @Test
    fun `judge schema and request are isolated from production`() =
        runBlocking {
            val judge = SemanticJudge(FixedJudge("""{"pass":true,"reason":"grounded"}"""), sample)
            val result = SampleResult(sample, checks = listOf(Check("protocol", Verdict.PASS, "protocol parsed")))
            val checks = semanticReview(sample, result, judge)
            assertEquals("PASS", SampleResult(sample, checks = checks).status)
            judge.calls.forEach {
                assertTrue(it.input.contains(sample.id))
                assertTrue(it.rawResponse.isNotBlank())
            }
            val schema =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(SemanticJudge.JUDGE_SCHEMA)
                    .jsonObject
            assertEquals(false, schema.getValue("additionalProperties").toString().toBoolean())
        }

    @Test fun `empty execution is not a semantic pass`() =
        runBlocking {
            val judge = SemanticJudge(FixedJudge("""{"pass":true,"reason":"must not be invoked"}"""), sample)
            val checks = semanticReview(sample, SampleResult(sample), judge)
            assertEquals("NOT_RUN", SampleResult(sample, checks = checks).status)
            assertTrue(judge.calls.isEmpty())
        }

    @Test
    fun `judge cancellation is propagated`() {
        assertFailsWith<CancellationException> {
            runBlocking { SemanticJudge(CancellingJudge(), sample).judge("cancel", "test", "actual", "expected") }
        }
    }

    @Test
    fun `judge cancellation preserves completed production calls in cancelled result`() =
        runBlocking {
            val completed =
                SampleResult(
                    sample,
                    calls =
                        listOf(
                            CallRecord(
                                task = sample.task,
                                profile = "production-${sample.task}",
                                model = "test-model",
                                promptVersion = "v1",
                                schemaVersion = "v1",
                                promptSha256 = sha256Bytes("prompt".toByteArray()),
                                estimatedInputTokens = 1,
                                usage = null,
                                latencyMs = 1,
                                attempts = 1,
                                finishReason = "stop",
                                truncated = false,
                                output = kotlinx.serialization.json.JsonObject(emptyMap()),
                            ),
                        ),
                    checks = listOf(Check("protocol", Verdict.PASS, "production response parsed")),
                )
            val judge = SemanticJudge(CancellingJudge(), sample)
            val failure =
                assertFailsWith<EvaluationStageCancelled> {
                    reviewCompletedSample(sample, completed, judge)
                }
            assertEquals(completed.calls, failure.calls)

            val cancelled = cancelledSampleResult(sample, failure, completed)
            assertEquals(completed.calls, cancelled.calls)
            assertTrue(cancelled.checks.any { it.id == "protocol" })
            assertTrue(cancelled.checks.any { it.id == "evaluation_cancelled" })
            assertEquals(EvaluationCallState.CANCELLED, judge.calls.single().state)
        }

    @Test
    fun `offline loader verifies all canonical references`() {
        val samples = loadEvalSamples(root)
        assertEquals(72, samples.size)
        assertEquals(72, samples.map { it.id }.toSet().size)
    }

    @Test
    fun `yaml profile is resolved and unbound tasks receive the production binding`() {
        val yaml = Files.createTempDirectory("ct31-config-").resolve("model.yaml")
        Files.writeString(
            yaml,
            """
            context:
              llm:
                profiles:
                  eval:
                    baseUrl: https://example.invalid/v1
                    apiKey: ${'$'}{CT31_TEST_KEY}
                    model: group/free
                    contextWindowTokens: 32768
                    jsonMode: JSON_SCHEMA
            """.trimIndent(),
        )
        RealModelConfig.fromEnvironment(yaml, mapOf("CT31_TEST_KEY" to "not-a-real-secret")).use { config ->
            assertEquals("group/free", config.model)
            assertEquals(LlmResponseFormat.JSON_SCHEMA, config.responseFormat)
            assertEquals(8, config.llmConfig.tasks.size)
            assertEquals(
                1,
                config.llmConfig.profiles
                    .getValue("eval")
                    .maxAttempts,
            )
            assertEquals(
                setOf("eval"),
                config.llmConfig.tasks.values
                    .map { it.profile }
                    .toSet(),
            )
            assertTrue(!config.url.contains("not-a-real-secret"))
        }
    }
}

private class FixedJudge(
    private val response: String,
) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        check(request.responseFormat == LlmResponseFormat.JSON_SCHEMA)
        check(request.schema == SemanticJudge.JUDGE_SCHEMA)
        check(request.maxOutputTokens == 2048)
        return LlmResponse(response, TokenUsage(10, 10, 20), "stop")
    }
}

private class CancellingJudge : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse = throw CancellationException("cancelled")
}
