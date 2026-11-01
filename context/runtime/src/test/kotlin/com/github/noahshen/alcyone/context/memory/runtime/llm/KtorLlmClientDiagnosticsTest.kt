package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.event.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT08-B5：按 `(task, profile)` 的最小内部统计、`llm.call.*` 真实日志捕获、字段白名单与脱敏、
 * 取消/关闭不计入统计。
 */
class KtorLlmClientDiagnosticsTest {
    @Test
    fun `a successful call records usage and one INFO log`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
                val client = clientFor(stub)
                try {
                    client.complete(promptRequest())

                    val stats = assertNotNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE))
                    assertEquals(1L, stats.calls)
                    assertEquals(1L, stats.successes)
                    assertEquals(0L, stats.failures)
                    assertEquals(1L, stats.attempts)
                    assertEquals(1L, stats.usagePresent)
                    assertEquals(0L, stats.usageMissing)
                    assertEquals(11L, stats.inputTokens)
                    assertEquals(7L, stats.outputTokens)
                    assertEquals(18L, stats.totalTokens)

                    val record = capture.recordsFor(COMPLETED).single()
                    assertEquals(Level.INFO, record.level)
                    assertNull(record.throwable, "日志不得携带 Throwable")
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `a successful call without usage keeps token sums null`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok", usage = null))))
                val client = clientFor(stub)
                try {
                    client.complete(promptRequest())
                    val stats = assertNotNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE))
                    assertEquals(0L, stats.usagePresent)
                    assertEquals(1L, stats.usageMissing)
                    // 无样本必须是 null，不得用 0 冒充。
                    assertNull(stats.inputTokens)
                    assertNull(stats.outputTokens)
                    assertNull(stats.totalTokens)

                    val fields = parseFields(capture.recordsFor(COMPLETED).single().formattedMessage)
                    assertEquals("false", fields["providerUsagePresent"])
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `a retried failure records the real attempt count`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(500, "{}")))
                val client = clientFor(stub, maxAttempts = 2)
                try {
                    val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                    assertEquals(LlmErrorCode.PROVIDER_UNAVAILABLE, failure.errorCode)

                    val stats = assertNotNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE))
                    assertEquals(1L, stats.calls)
                    assertEquals(0L, stats.successes)
                    assertEquals(1L, stats.failures)
                    assertEquals(2L, stats.attempts)
                    assertEquals(0L, stats.usagePresent)
                    assertEquals(0L, stats.usageMissing)

                    val record = capture.recordsFor(FAILED).single()
                    assertEquals(Level.WARN, record.level)
                    val fields = parseFields(record.formattedMessage)
                    assertEquals("2", fields["attempts"])
                    assertEquals("true", fields["retryable"])
                    assertEquals("500", fields["statusCode"])
                    assertNull(record.throwable)
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `a non retryable failure records one attempt`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(400, """{"error":{"code":"invalid_request_error"}}""")))
                val client = clientFor(stub, maxAttempts = 3)
                try {
                    assertFailsWith<LlmException> { client.complete(promptRequest()) }
                    val stats = assertNotNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE))
                    assertEquals(1L, stats.failures)
                    assertEquals(1L, stats.attempts)

                    val fields = parseFields(capture.recordsFor(FAILED).single().formattedMessage)
                    assertEquals("1", fields["attempts"])
                    assertEquals("false", fields["retryable"])
                    assertEquals("400", fields["statusCode"])
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `a pre send budget failure records zero attempts`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
                val client = clientFor(stub, taskMaxInputTokens = 1)
                try {
                    val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                    assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)

                    val stats = assertNotNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE))
                    assertEquals(1L, stats.failures)
                    assertEquals(0L, stats.attempts)
                    assertEquals(0, stub.requestCount(), "发送前失败不得发请求")

                    val fields = parseFields(capture.recordsFor(FAILED).single().formattedMessage)
                    assertEquals("0", fields["attempts"])
                    // 未发起请求 → 无 statusCode；不得用 0 冒充。
                    assertEquals("null", fields["statusCode"])
                    assertEquals("false", fields["retryable"])
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `a cancelled call is neither counted nor logged`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("late"), delayMillis = 5_000)))
                val client = clientFor(stub)
                try {
                    val deferred = async(Dispatchers.Default) { client.complete(promptRequest()) }
                    awaitRequests(stub, expected = 1)
                    delay(100)
                    deferred.cancel()
                    assertFailsWith<CancellationException> { deferred.await() }

                    assertNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE), "取消不计入统计")
                    assertTrue(capture.recordsFor(COMPLETED).isEmpty(), "取消不得写成功日志")
                    assertTrue(capture.recordsFor(FAILED).isEmpty(), "取消不得写失败日志")
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `a call cancelled during backoff is neither counted nor logged`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub =
                    ScriptedHttpStub(
                        listOf(ScriptedReply(500, "{}"), ScriptedReply(200, chatCompletionBody("ok"))),
                    )
                val client = clientFor(stub, maxAttempts = 3)
                try {
                    val deferred = async(Dispatchers.Default) { client.complete(promptRequest()) }
                    awaitRequests(stub, expected = 1)
                    delay(100)
                    deferred.cancel()
                    assertFailsWith<CancellationException> { deferred.await() }

                    assertNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE), "退避中取消不计入统计")
                    assertTrue(capture.recordsFor(COMPLETED).isEmpty())
                    assertTrue(capture.recordsFor(FAILED).isEmpty(), "未到达终态不得写失败日志")
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `a routing failure is logged but not counted`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
                // 只有 Profile、没有 Task 映射：路由前失败（CONFIGURATION）。
                val config =
                    LlmConfig(
                        profiles =
                            mapOf(
                                "fast" to
                                    LlmProfileConfig(
                                        name = "fast",
                                        baseUrl = stub.baseUrl,
                                        apiKey = KEY,
                                        model = MODEL,
                                        contextWindowTokens = 64_000,
                                        jsonMode = LlmResponseFormat.PROMPT_ONLY,
                                    ),
                            ),
                        tasks = emptyMap(),
                    )
                val client = KtorLlmClient(config)
                try {
                    val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                    assertEquals(LlmErrorCode.CONFIGURATION, failure.errorCode)
                    assertNull(client.statsForTesting(LlmTask.EXTRACTION, PROFILE), "路由前失败不计入统计")
                    assertEquals(0, stub.requestCount())

                    val record = capture.recordsFor(FAILED).single()
                    assertEquals(Level.WARN, record.level)
                    val fields = parseFields(record.formattedMessage)
                    assertEquals("<unresolved>", fields["profile"], "未解析 Profile 用固定占位，不回显配置键")
                    assertEquals("0", fields["attempts"])
                    assertEquals("null", fields["statusCode"])
                    assertEquals("null", fields["estimatedInputTokens"])
                    assertNull(record.throwable)
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `statistics are isolated per task and profile`() =
        runBlocking {
            Slf4jCapture().use { _ ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
                val client = clientFor(stub, withSecondProfile = true)
                try {
                    client.complete(promptRequest(LlmTask.EXTRACTION))
                    client.complete(promptRequest(LlmTask.EXTRACTION))
                    client.complete(promptRequest(LlmTask.GOVERNANCE))

                    val fast = assertNotNull(client.statsForTesting(LlmTask.EXTRACTION, "fast"))
                    assertEquals(2L, fast.calls)
                    assertEquals(2L, fast.successes)
                    assertEquals(22L, fast.inputTokens)

                    val quality = assertNotNull(client.statsForTesting(LlmTask.GOVERNANCE, "quality"))
                    assertEquals(1L, quality.calls)
                    assertEquals(11L, quality.inputTokens)

                    assertNull(client.statsForTesting(LlmTask.MEMORY_MAP, "fast"), "无记录必须返回 null")
                    assertNull(client.statsForTesting(LlmTask.GOVERNANCE, "fast"), "不同 profile 不得串计")
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `the success log carries only whitelisted fields with real values`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
                val client = clientFor(stub)
                try {
                    client.complete(promptRequest())
                    val message = capture.recordsFor(COMPLETED).single().formattedMessage
                    assertTrue(message.startsWith("llm.call.completed "), message)

                    val fields = parseFields(message)
                    assertTrue(FIELD_WHITELIST.containsAll(fields.keys), "字段越界: ${fields.keys - FIELD_WHITELIST}")
                    assertEquals("EXTRACTION", fields["task"])
                    assertEquals(PROFILE, fields["profile"])
                    assertEquals(MODEL, fields["model"])
                    assertEquals("1", fields["attempts"])
                    assertEquals("200", fields["statusCode"])
                    assertEquals("true", fields["providerUsagePresent"])
                    assertTrue(fields["elapsedMillis"]!!.toLong() >= 0L)
                    assertTrue(fields["estimatedInputTokens"]!!.toLong() > 0L)
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    @Test
    fun `logs and diagnostics never leak secrets prompts schemas bodies or urls`() =
        runBlocking {
            Slf4jCapture().use { capture ->
                val keyMarker = "ct08-b5-key-marker-71ac"
                val promptMarker = "ct08-b5-prompt-marker-82bd"
                val schemaMarker = "ct08-b5-schema-marker-93ce"
                val bodyMarker = "ct08-b5-body-marker-a4df"
                val urlMarker = "ct08-b5-url-marker-b5e0"
                val stub =
                    ScriptedHttpStub(
                        listOf(
                            // 1) 成功：content 本身是合法 JSON（正文标记作为值）。
                            ScriptedReply(200, chatCompletionBody("""{"marker":"$bodyMarker"}""")),
                            // 2) 失败：响应 body 本身不是 JSON。
                            ScriptedReply(200, "$bodyMarker is not json"),
                        ),
                    )
                val client =
                    clientFor(
                        stub,
                        format = LlmResponseFormat.JSON_SCHEMA,
                        apiKey = keyMarker,
                        baseUrl = "${stub.baseUrl}/$urlMarker",
                    )
                try {
                    client.complete(
                        request(
                            task = LlmTask.EXTRACTION,
                            format = LlmResponseFormat.JSON_SCHEMA,
                            content = promptMarker,
                            schema = """{"marker":"$schemaMarker"}""",
                        ),
                    )
                    assertFailsWith<LlmException> {
                        client.complete(
                            request(
                                task = LlmTask.EXTRACTION,
                                format = LlmResponseFormat.JSON_SCHEMA,
                                content = promptMarker,
                                schema = """{"marker":"$schemaMarker"}""",
                            ),
                        )
                    }

                    val logs = capture.logs.map { it.formattedMessage }
                    assertTrue(logs.any { it.startsWith(COMPLETED) }, logs.toString())
                    assertTrue(logs.any { it.startsWith(FAILED) }, logs.toString())

                    val diagnostics =
                        LlmCallDiagnostics(
                            task = "EXTRACTION",
                            profile = PROFILE,
                            model = MODEL,
                            attempts = 1,
                            statusCode = 200,
                            elapsedMillis = 3,
                            estimatedInputTokens = 42,
                            providerUsagePresent = true,
                        )
                    val everything = logs + diagnostics.toString()
                    listOf(keyMarker, promptMarker, schemaMarker, bodyMarker, urlMarker).forEach { marker ->
                        assertTrue(everything.none { it.contains(marker) }, "日志/诊断不得包含 $marker")
                    }
                    assertTrue(capture.logs.all { it.throwable == null }, "不得把 Throwable 传给 logger")
                    assertTrue(!diagnostics.toString().contains(keyMarker))
                } finally {
                    client.closeAsync()
                    stub.close()
                }
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun clientFor(
        stub: ScriptedHttpStub,
        maxAttempts: Int = 2,
        taskMaxInputTokens: Int = 12_000,
        taskMaxOutputTokens: Int = 3_000,
        format: LlmResponseFormat = LlmResponseFormat.PROMPT_ONLY,
        apiKey: String = KEY,
        baseUrl: String = stub.baseUrl,
        withSecondProfile: Boolean = false,
        estimator: TokenEstimator? = null,
    ): KtorLlmClient {
        val profiles = mutableMapOf("fast" to profile("fast", baseUrl, apiKey, MODEL, format, maxAttempts))
        val tasks =
            mutableMapOf(
                LlmTask.EXTRACTION to
                    LlmTaskConfig(profile = "fast", maxInputTokens = taskMaxInputTokens, maxOutputTokens = taskMaxOutputTokens),
            )
        if (withSecondProfile) {
            profiles["quality"] = profile("quality", baseUrl, apiKey, QUALITY_MODEL, format, maxAttempts)
            tasks[LlmTask.GOVERNANCE] =
                LlmTaskConfig(profile = "quality", maxInputTokens = taskMaxInputTokens, maxOutputTokens = taskMaxOutputTokens)
        }
        val config = LlmConfig(profiles = profiles, tasks = tasks)
        return if (estimator == null) KtorLlmClient(config) else KtorLlmClient(config, estimator)
    }

    private fun profile(
        name: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        format: LlmResponseFormat,
        maxAttempts: Int,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            contextWindowTokens = 64_000,
            jsonMode = format,
            maxAttempts = maxAttempts,
        )

    private fun promptRequest(task: LlmTask = LlmTask.EXTRACTION): LlmRequest =
        LlmRequest(
            task = task,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.PROMPT_ONLY,
        )

    private fun request(
        task: LlmTask,
        format: LlmResponseFormat,
        content: String,
        schema: String? = null,
    ): LlmRequest =
        LlmRequest(
            task = task,
            messages = listOf(LlmMessage(role = "user", content = content)),
            responseFormat = format,
            schema = schema,
        )

    /** 解析 `key=value` 白名单字段（值内不含空格）。 */
    private fun parseFields(message: String): Map<String, String> {
        val body =
            when {
                message.startsWith("$COMPLETED ") -> message.removePrefix("$COMPLETED ")
                message.startsWith("$FAILED ") -> message.removePrefix("$FAILED ")
                else -> message
            }
        return body
            .split(' ')
            .filter { it.contains('=') }
            .associate { token -> token.substringBefore('=') to token.substringAfter('=') }
    }

    private suspend fun awaitRequests(
        stub: ScriptedHttpStub,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (stub.requestCount() < expected) delay(10)
        }
    }

    private companion object {
        const val COMPLETED = "llm.call.completed"
        const val FAILED = "llm.call.failed"
        const val PROFILE = "fast"
        const val MODEL = "fast-model"
        const val QUALITY_MODEL = "quality-model"
        const val KEY = "ct08-b5-diagnostics-key-1f60"

        val FIELD_WHITELIST =
            setOf(
                "task",
                "profile",
                "model",
                "attempts",
                "statusCode",
                "elapsedMillis",
                "estimatedInputTokens",
                "providerUsagePresent",
                "retryable",
            )
    }
}
