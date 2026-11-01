package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CT08-B2 A03：Task → Profile 路由（路径 / 逐请求 Key / model）与脱敏 `toString`。
 *
 * 真实 HTTP（JDK `HttpServer`），每个用例 `finally` 关闭 stub 与自建 HttpClient。
 */
class KtorLlmClientRoutingTest {
    private companion object {
        const val KEY_A = "ct08-b2-key-a-9f31c4"
        const val KEY_B = "ct08-b2-key-b-9f32d5"
        const val MODEL_A = "fast-model"
        const val MODEL_B = "quality-model"
    }

    @Test
    fun `each task uses its own profile path key and model`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val config =
                LlmConfig(
                    profiles =
                        mapOf(
                            "fast" to profile("fast", "${stub.baseUrl}/p-fast", KEY_A, MODEL_A),
                            "quality" to profile("quality", "${stub.baseUrl}/p-quality", KEY_B, MODEL_B),
                        ),
                    tasks =
                        mapOf(
                            LlmTask.EXTRACTION to LlmTaskConfig(profile = "fast", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                            LlmTask.GOVERNANCE to LlmTaskConfig(profile = "quality", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                        ),
                )
            val client = KtorLlmClient(config)
            try {
                client.complete(request(LlmTask.EXTRACTION))
                client.complete(request(LlmTask.GOVERNANCE))

                val recorded = stub.requests()
                assertEquals(2, recorded.size, "两个 Task 应各发一次请求")

                assertEquals("/p-fast/chat/completions", recorded[0].path)
                assertEquals("Bearer $KEY_A", recorded[0].authorization)
                assertEquals(MODEL_A, recorded[0].jsonBody()["model"]?.jsonPrimitive?.content)

                assertEquals("/p-quality/chat/completions", recorded[1].path)
                assertEquals("Bearer $KEY_B", recorded[1].authorization)
                assertEquals(MODEL_B, recorded[1].jsonBody()["model"]?.jsonPrimitive?.content)

                // 交叉断言：不串 Key / 路径 / model。
                assertFalse(recorded[0].authorization!!.contains(KEY_B))
                assertFalse(recorded[1].authorization!!.contains(KEY_A))
                assertFalse(recorded[0].path.contains("quality"))
                assertFalse(recorded[1].path.contains("fast"))
                assertFalse(recorded[0].jsonBody().toString().contains(MODEL_B))
                assertFalse(recorded[1].jsonBody().toString().contains(MODEL_A))

                // 逐请求头：Content-Type / Accept 都是 application/json。
                recorded.forEach { recordedRequest ->
                    assertTrue(
                        recordedRequest.contentType.orEmpty().startsWith("application/json"),
                        recordedRequest.contentType.orEmpty(),
                    )
                    assertTrue(
                        recordedRequest.accept.orEmpty().startsWith("application/json"),
                        recordedRequest.accept.orEmpty(),
                    )
                }
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `config and profile toString never contain api keys`() {
        val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
        try {
            val config =
                LlmConfig(
                    profiles =
                        mapOf(
                            "fast" to profile("fast", "${stub.baseUrl}/p-fast", KEY_A, MODEL_A),
                            "quality" to profile("quality", "${stub.baseUrl}/p-quality", KEY_B, MODEL_B),
                        ),
                    tasks = mapOf(LlmTask.EXTRACTION to LlmTaskConfig(profile = "fast", maxInputTokens = 1_000, maxOutputTokens = 100)),
                )
            assertFalse(config.toString().contains(KEY_A), "LlmConfig.toString 泄露 Key")
            assertFalse(config.toString().contains(KEY_B), "LlmConfig.toString 泄露 Key")
            config.profiles.values.forEach { profile ->
                assertFalse(profile.toString().contains(KEY_A), "LlmProfileConfig.toString 泄露 Key")
                assertFalse(profile.toString().contains(KEY_B), "LlmProfileConfig.toString 泄露 Key")
            }
        } finally {
            stub.close()
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun request(task: LlmTask): LlmRequest =
        LlmRequest(
            task = task,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.JSON_OBJECT,
        )

    private fun profile(
        name: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            contextWindowTokens = 64_000,
            jsonMode = LlmResponseFormat.JSON_OBJECT,
        )

    private fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body) as JsonObject
}
