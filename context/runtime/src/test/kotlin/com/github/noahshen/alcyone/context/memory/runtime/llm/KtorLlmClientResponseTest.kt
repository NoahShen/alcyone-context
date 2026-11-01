package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT08-B2 A07（响应 / Usage 部分）：非法响应不修复、拒答与截断分类、Usage 缺失为 null、
 * 非 2xx 分类与 `attempts = 1`、预算在发送前生效、传输失败分类、最小 `close()`。
 */
class KtorLlmClientResponseTest {
    // ------------------------------------------------------------------ 2xx 响应

    @Test
    fun `non json response body is an invalid response`() =
        runBlocking {
            withClient(responseBody = "not a json document") { client, stub ->
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, stub.requests().size, "非法响应不重试（B2 单次调用）")
            }
        }

    @Test
    fun `missing choices or blank content is an invalid response`() =
        runBlocking {
            listOf(
                "{}",
                """{"choices":[]}""",
                """{"choices":[{"message":{}}]}""",
                """{"choices":[{"message":{"content":"   "}}]}""",
                """{"choices":[{"message":{"content":123}}]}""",
                """{"choices":[{"message":{"content":null}}]}""",
            ).forEach { body ->
                withClient(responseBody = body) { client, stub ->
                    val failure = assertFailsWith<LlmException>("body=$body") { client.complete(promptRequest()) }
                    assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode, "body=$body")
                    assertEquals(1, stub.requests().size)
                }
            }
        }

    @Test
    fun `json mode content must itself be valid json`() =
        runBlocking {
            withClient(
                responseBody = chatCompletionBody("this is not json"),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, stub ->
                val failure = assertFailsWith<LlmException> { client.complete(jsonRequest()) }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
                assertEquals(1, stub.requests().size)
            }
        }

    @Test
    fun `invalid content text never leaks into the error or stack trace`() =
        runBlocking {
            withClient(
                // JSONDecodingException 原文会包含这段 content。
                responseBody = chatCompletionBody("LEAK_CONTENT_MARKER_9f32 is not json"),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, stub ->
                val marker = "LEAK_CONTENT_MARKER_9f32"
                val failure = assertFailsWith<LlmException> { client.complete(jsonRequest()) }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
                listOf(
                    "message" to failure.message.orEmpty(),
                    "toString" to failure.toString(),
                    "stackTraceToString" to failure.stackTraceToString(),
                ).forEach { (label, text) ->
                    assertFalse(text.contains(marker), "$label 回显了响应正文")
                }
                assertEquals(1, stub.requests().size)
            }
        }

    @Test
    fun `fenced json content is rejected without stripping`() =
        runBlocking {
            withClient(
                responseBody = chatCompletionBody("```json\n{\"a\":1}\n```"),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, stub ->
                val failure = assertFailsWith<LlmException> { client.complete(jsonRequest()) }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
                assertTrue(
                    failure.message.orEmpty().contains("fenced", ignoreCase = true),
                    failure.message.orEmpty(),
                )
                assertEquals(1, stub.requests().size)
            }
        }

    @Test
    fun `prompt only mode accepts non json content`() =
        runBlocking {
            withClient(responseBody = chatCompletionBody("plain text answer"), format = LlmResponseFormat.PROMPT_ONLY) { client, _ ->
                val response = client.complete(promptRequest())
                assertEquals("plain text answer", response.content)
                assertEquals("stop", response.finishReason)
            }
        }

    @Test
    fun `length finish reason is output truncated`() =
        runBlocking {
            withClient(
                responseBody = chatCompletionBody("{\"a\":1}", finishReason = "length"),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, _ ->
                val failure = assertFailsWith<LlmException> { client.complete(jsonRequest()) }
                assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
            }
        }

    @Test
    fun `content filter and explicit refusal are model refusals`() =
        runBlocking {
            listOf(
                chatCompletionBody("{\"a\":1}", finishReason = "content_filter"),
                chatCompletionBody("{\"a\":1}", finishReason = "stop", refusal = "I cannot comply"),
                // 拒答优先于截断。
                chatCompletionBody("{\"a\":1}", finishReason = "length", refusal = "I cannot comply"),
            ).forEach { body ->
                withClient(responseBody = body) { client, _ ->
                    val failure = assertFailsWith<LlmException>("body=$body") { client.complete(promptRequest()) }
                    assertEquals(LlmErrorCode.MODEL_REFUSAL, failure.errorCode, "body=$body")
                }
            }
        }

    @Test
    fun `usage is mapped and stays null when missing or non integer`() =
        runBlocking {
            // 完整 usage。
            withClient(
                responseBody = chatCompletionBody("{\"a\":1}"),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, _ ->
                assertEquals(
                    TokenUsage(inputTokens = 11, outputTokens = 7, totalTokens = 18),
                    client.complete(jsonRequest()).usage,
                )
            }
            // usage 缺失 → null（未知，不填 0）。
            withClient(
                responseBody = chatCompletionBody("{\"a\":1}", usage = null),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, _ ->
                val response = client.complete(jsonRequest())
                assertNull(response.usage)
            }
            // 部分缺失 / 非整数 → 对应字段 null。
            withClient(
                responseBody =
                    chatCompletionBody(
                        "{\"a\":1}",
                        usage = buildJsonObject { put("prompt_tokens", 7) },
                    ),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, _ ->
                assertEquals(TokenUsage(inputTokens = 7), client.complete(jsonRequest()).usage)
            }
            withClient(
                responseBody =
                    chatCompletionBody(
                        "{\"a\":1}",
                        usage =
                            buildJsonObject {
                                put("prompt_tokens", "7")
                                put("completion_tokens", true)
                            },
                    ),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, _ ->
                assertEquals(TokenUsage(), client.complete(jsonRequest()).usage)
            }
            // usage 不是对象 → null。
            withClient(
                responseBody =
                    buildJsonObject {
                        putJsonArray("choices") {
                            addJsonObject {
                                putJsonObject("message") { put("content", "{\"a\":1}") }
                            }
                        }
                        put("usage", "n/a")
                    }.toString(),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, _ ->
                assertNull(client.complete(jsonRequest()).usage)
            }
        }

    @Test
    fun `finish reason is returned verbatim on success`() =
        runBlocking {
            withClient(
                responseBody = chatCompletionBody("{\"a\":1}", finishReason = "stop"),
                format = LlmResponseFormat.JSON_OBJECT,
            ) { client, _ ->
                val response: LlmResponse = client.complete(jsonRequest())
                assertEquals("{\"a\":1}", response.content)
                assertEquals("stop", response.finishReason)
            }
        }

    // ------------------------------------------------------------------ 非 2xx / 传输失败

    @Test
    fun `non 2xx statuses map to the frozen error codes`() =
        runBlocking {
            // B3 起 500/429 可重试；本用例只验证分类与单次语义，固定 maxAttempts = 1。
            val maxAttempts = 1
            val cases =
                listOf(
                    Triple(401, "{}", LlmErrorCode.AUTHENTICATION),
                    Triple(
                        429,
                        """{"error":{"code":"insufficient_quota","type":"insufficient_quota"}}""",
                        LlmErrorCode.QUOTA_EXCEEDED,
                    ),
                    Triple(429, """{"error":{"code":"rate_limit_exceeded"}}""", LlmErrorCode.RATE_LIMITED),
                    Triple(500, "{}", LlmErrorCode.PROVIDER_UNAVAILABLE),
                    Triple(400, """{"error":{"code":"invalid_request_error"}}""", LlmErrorCode.INVALID_REQUEST),
                )
            cases.forEach { (status, body, expected) ->
                withClient(status = status, responseBody = body, maxAttempts = maxAttempts) { client, stub ->
                    val failure = assertFailsWith<LlmException>("status=$status") { client.complete(promptRequest()) }
                    assertEquals(expected, failure.errorCode, "status=$status")
                    assertEquals(status, failure.statusCode, "status=$status")
                    assertEquals(1, failure.attempts, "status=$status")
                    assertEquals(1, stub.requests().size, "status=$status 不重试")
                    assertFalse(
                        failure.message.orEmpty().contains("insufficient_quota"),
                        "错误消息不得回显 Provider 正文: ${failure.message}",
                    )
                }
            }
        }

    @Test
    fun `provider context length error is classified separately from local budget`() =
        runBlocking {
            withClient(
                status = 400,
                responseBody = """{"error":{"code":"context_length_exceeded","message":"too long"}}""",
            ) { client, _ ->
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.CONTEXT_LIMIT_EXCEEDED, failure.errorCode)
                assertEquals(400, failure.statusCode)
                assertEquals(1, failure.attempts)
            }
        }

    @Test
    fun `budget failures are rejected before sending`() =
        runBlocking {
            // 输入预算：估算 1000 > maxInputTokens 900。
            withClient(
                responseBody = "",
                taskMaxInputTokens = 900,
                estimator = FixedEstimator(promptTokens = 1_000),
            ) { client, stub ->
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
                assertTrue(stub.requests().isEmpty(), "预算失败必须是 0 次 HTTP")
            }
            // 容量（含余量）：900 + 90 + 180 > 1000。
            withClient(
                responseBody = "",
                taskMaxInputTokens = 900,
                taskMaxOutputTokens = 90,
                contextWindowTokens = 1_000,
                estimator = FixedEstimator(promptTokens = 900),
            ) { client, stub ->
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
                assertTrue(stub.requests().isEmpty(), "预算失败必须是 0 次 HTTP")
            }
        }

    @Test
    fun `transport failure is classified as network`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            // B3 起 NETWORK 可重试；本用例只验证分类与单次语义，固定 maxAttempts = 1。
            val config =
                configFor(
                    baseUrl = "http://127.0.0.1:$port",
                    format = LlmResponseFormat.PROMPT_ONLY,
                    maxAttempts = 1,
                )
            val client = KtorLlmClient(config)
            try {
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.NETWORK, failure.errorCode)
                assertEquals(1, failure.attempts)
            } finally {
                client.closeAsync()
            }
        }

    // ------------------------------------------------------------------ 最小释放路径

    @Test
    fun `close is idempotent and does not close an injected client`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("{\"a\":1}"))
            val injected =
                HttpClient(CIO) {
                    expectSuccess = false
                    followRedirects = false
                }
            val client = KtorLlmClient(configFor(stub.baseUrl, LlmResponseFormat.PROMPT_ONLY), httpClient = injected)
            try {
                client.complete(promptRequest())
                client.closeAsync()
                client.closeAsync()
                val requestsAfterClose = stub.requests().size

                // CT08-B4：CLOSED 后拒绝新调用，且不发起 HTTP。
                val failure = assertFailsWith<ContextLifecycleException> { client.complete(promptRequest()) }
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                assertEquals(requestsAfterClose, stub.requests().size, "关闭后不得再发请求")

                // 注入的底层 HttpClient 未被关闭：仍可直接使用。
                val direct = injected.post("${stub.baseUrl}/direct") { setBody("{}") }
                assertEquals(200, direct.status.value, "注入的 client 不得被本类关闭")
            } finally {
                injected.close()
                stub.close()
            }
        }

    @Test
    fun `owned close is idempotent`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("{\"a\":1}"))
            val client = KtorLlmClient(configFor(stub.baseUrl, LlmResponseFormat.PROMPT_ONLY))
            try {
                client.complete(promptRequest())
                client.closeAsync()
                client.closeAsync()
            } finally {
                stub.close()
            }
        }

    // ------------------------------------------------------------------ 工具

    private suspend fun withClient(
        status: Int = 200,
        responseBody: String,
        format: LlmResponseFormat = LlmResponseFormat.PROMPT_ONLY,
        taskMaxInputTokens: Int = 12_000,
        taskMaxOutputTokens: Int = 3_000,
        contextWindowTokens: Int = 64_000,
        estimator: TokenEstimator? = null,
        maxAttempts: Int = 2,
        block: suspend (KtorLlmClient, LlmHttpStub) -> Unit,
    ) {
        val stub = LlmHttpStub(status, responseBody)
        val config =
            configFor(
                baseUrl = stub.baseUrl,
                format = format,
                taskMaxInputTokens = taskMaxInputTokens,
                taskMaxOutputTokens = taskMaxOutputTokens,
                contextWindowTokens = contextWindowTokens,
                maxAttempts = maxAttempts,
            )
        val client = if (estimator == null) KtorLlmClient(config) else KtorLlmClient(config, estimator)
        try {
            block(client, stub)
        } finally {
            client.closeAsync()
            stub.close()
        }
    }

    private fun configFor(
        baseUrl: String,
        format: LlmResponseFormat,
        taskMaxInputTokens: Int = 12_000,
        taskMaxOutputTokens: Int = 3_000,
        contextWindowTokens: Int = 64_000,
        maxAttempts: Int = 2,
    ): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "extraction" to
                        LlmProfileConfig(
                            name = "extraction",
                            baseUrl = baseUrl,
                            apiKey = KEY,
                            model = "fast-model",
                            contextWindowTokens = contextWindowTokens,
                            jsonMode = format,
                            maxAttempts = maxAttempts,
                        ),
                ),
            tasks =
                mapOf(
                    LlmTask.EXTRACTION to
                        LlmTaskConfig(
                            profile = "extraction",
                            maxInputTokens = taskMaxInputTokens,
                            maxOutputTokens = taskMaxOutputTokens,
                        ),
                ),
        )

    private fun promptRequest(): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.PROMPT_ONLY,
        )

    private fun jsonRequest(): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.JSON_OBJECT,
        )

    /** 固定估算值替身：只用于把预算推到确定边界。 */
    private class FixedEstimator(
        private val promptTokens: Int,
    ) : TokenEstimator {
        override fun estimateText(text: String): Int = promptTokens

        override fun estimatePrompt(messages: List<LlmMessage>): Int = promptTokens

        override fun estimateSchema(schema: String?): Int = 0
    }

    private companion object {
        const val KEY = "ct08-b2-response-key-4b21"
    }
}
