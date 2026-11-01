package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CT08-B3 A05（真实 HTTP）：总期限、有限重试、`Retry-After`、退避与取消传播。
 *
 * 全部走 [ScriptedHttpStub]（按序回复 + 延迟 + 到达时刻），时间断言用**单调耗时**上下限 + slack，
 * 不用 sleep 猜；测试总时长控制在 ~15s 内。
 */
class KtorLlmClientRetryTest {
    @Test
    fun `500 then 500 then 200 retries until success`() =
        runBlocking {
            val stub =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(500, "{}"),
                        ScriptedReply(500, "{}"),
                        ScriptedReply(200, chatCompletionBody("ok")),
                    ),
                )
            val client = clientFor(stub, maxAttempts = 3)
            try {
                assertEquals("ok", client.complete(promptRequest()).content)
                assertEquals(3, stub.requestCount(), "maxAttempts=3 应重试到成功")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `persistent 500 stops at max attempts`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(500, "{}")))
            val client = clientFor(stub, maxAttempts = 2)
            try {
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.PROVIDER_UNAVAILABLE, failure.errorCode)
                assertEquals(2, failure.attempts)
                assertEquals(2, stub.requestCount())
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `400 is not retried`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(400, """{"error":{"code":"invalid_request_error"}}""")))
            val client = clientFor(stub, maxAttempts = 3)
            try {
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, stub.requestCount())
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `408 is retried as a timeout`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(408, "{}"), ScriptedReply(200, chatCompletionBody("ok"))))
            val client = clientFor(stub, maxAttempts = 2)
            try {
                assertEquals("ok", client.complete(promptRequest()).content)
                assertEquals(2, stub.requestCount())
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `429 with retry after waits exactly before the next attempt`() =
        runBlocking {
            val stub =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(429, "{}", headers = mapOf("Retry-After" to "1")),
                        ScriptedReply(200, chatCompletionBody("ok")),
                    ),
                )
            val client = clientFor(stub, maxAttempts = 2)
            try {
                assertEquals("ok", client.complete(promptRequest()).content)
                val arrivals = stub.arrivalMillis()
                assertEquals(2, arrivals.size)
                val interval = arrivals[1] - arrivals[0]
                // 精确等待 1000ms（无 jitter）；允许调度抖动下限 950ms。
                assertTrue(interval >= 950L, "相邻请求间隔必须 ≥ 950ms，实际 $interval")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `retry after beyond remaining fails immediately without a second request`() =
        runBlocking {
            val stub =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(429, "{}", headers = mapOf("Retry-After" to "100")),
                        ScriptedReply(200, chatCompletionBody("ok")),
                    ),
                )
            val client = clientFor(stub, maxAttempts = 3, totalTimeoutSeconds = 2)
            try {
                val startedAt = monotonicMillis()
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                val elapsed = monotonicMillis() - startedAt
                assertEquals(LlmErrorCode.RATE_LIMITED, failure.errorCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, stub.requestCount(), "Retry-After > remaining 不得再发请求")
                assertTrue(elapsed < 2_000L, "必须立即失败，实际耗时 $elapsed")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `quota exhaustion wins over retry after and is not retried`() =
        runBlocking {
            val stub =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(
                            429,
                            """{"error":{"code":"insufficient_quota","type":"insufficient_quota"}}""",
                            headers = mapOf("Retry-After" to "1"),
                        ),
                        ScriptedReply(200, chatCompletionBody("ok")),
                    ),
                )
            val client = clientFor(stub, maxAttempts = 3)
            try {
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.QUOTA_EXCEEDED, failure.errorCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, stub.requestCount())
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `429 without a safe signal is not retried`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(429, "{}"), ScriptedReply(200, chatCompletionBody("ok"))))
            val client = clientFor(stub, maxAttempts = 3)
            try {
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.RATE_LIMITED, failure.errorCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, stub.requestCount(), "无法安全判定的 429 不重试")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `total deadline bounds the whole retry loop`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(500, "{}")))
            val client = clientFor(stub, maxAttempts = 5, totalTimeoutSeconds = 1)
            try {
                val startedAt = monotonicMillis()
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                val elapsed = monotonicMillis() - startedAt
                assertTrue(
                    failure.errorCode == LlmErrorCode.TIMEOUT || failure.errorCode == LlmErrorCode.PROVIDER_UNAVAILABLE,
                    "总期限到期码应为 TIMEOUT 或 PROVIDER_UNAVAILABLE，实际 ${failure.errorCode}",
                )
                assertTrue(elapsed < 3_000L, "必须在总期限 + slack 内返回，实际 $elapsed")
                assertTrue(stub.requestCount() <= 5, "请求数不得超过 maxAttempts，实际 ${stub.requestCount()}")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `retry after larger than the fresh remaining fails without retry`() =
        runBlocking {
            // 首个响应延迟 1.5s（totalTimeout=2s）→ 响应后 remaining ≈ 0.5s < Retry-After 1s。
            val stub =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(429, "{}", headers = mapOf("Retry-After" to "1"), delayMillis = 1_500),
                        ScriptedReply(200, chatCompletionBody("ok")),
                    ),
                )
            val client = clientFor(stub, maxAttempts = 3, totalTimeoutSeconds = 2)
            try {
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                assertEquals(LlmErrorCode.RATE_LIMITED, failure.errorCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, stub.requestCount(), "remaining 不足时不得发送第 2 次请求")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `cancellation during the backoff wait propagates and stops retries`() =
        runBlocking {
            val stub =
                ScriptedHttpStub(
                    listOf(ScriptedReply(500, "{}"), ScriptedReply(200, chatCompletionBody("ok"))),
                )
            val client = clientFor(stub, maxAttempts = 3)
            try {
                val deferred = async(Dispatchers.Default) { client.complete(promptRequest()) }
                awaitRequests(stub, expected = 1)
                // 此时处于首次退避等待（~1s）中。
                delay(100)
                deferred.cancel()
                // 必须是取消（不是 LlmException 包装），且不再发起请求。
                assertFailsWith<CancellationException> { deferred.await() }
                val countAfterCancel = stub.requestCount()
                delay(300)
                assertEquals(countAfterCancel, stub.requestCount(), "取消后不得再发请求")
                assertEquals(1, countAfterCancel)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `cancellation while a request is in flight propagates without retry`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("late"), delayMillis = 5_000)))
            val client = clientFor(stub, maxAttempts = 3)
            try {
                val deferred = async(Dispatchers.Default) { client.complete(promptRequest()) }
                awaitRequests(stub, expected = 1)
                delay(100)
                deferred.cancel()
                // 必须是取消（不是 LlmException 包装），且不再发起请求。
                assertFailsWith<CancellationException> { deferred.await() }
                val countAfterCancel = stub.requestCount()
                delay(300)
                assertEquals(countAfterCancel, stub.requestCount(), "在途取消后不得重试")
                assertEquals(1, countAfterCancel)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `a single request timeout is a retryable TIMEOUT`() =
        runBlocking {
            // 单次请求超时 1s，stub 延迟 3s；maxAttempts=1 → 只请求 1 次后 TIMEOUT。
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("late"), delayMillis = 3_000)))
            val client = clientFor(stub, maxAttempts = 1, requestTimeoutSeconds = 1)
            try {
                val startedAt = monotonicMillis()
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                val elapsed = monotonicMillis() - startedAt
                assertEquals(LlmErrorCode.TIMEOUT, failure.errorCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, stub.requestCount())
                assertTrue(elapsed < 3_000L, "单次请求超时应约 1s 返回，实际 $elapsed")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun clientFor(
        stub: ScriptedHttpStub,
        maxAttempts: Int = 2,
        totalTimeoutSeconds: Int = 10,
        requestTimeoutSeconds: Int = 180,
    ): KtorLlmClient =
        KtorLlmClient(
            LlmConfig(
                profiles =
                    mapOf(
                        "extraction" to
                            LlmProfileConfig(
                                name = "extraction",
                                baseUrl = stub.baseUrl,
                                apiKey = KEY,
                                model = "fast-model",
                                contextWindowTokens = 64_000,
                                jsonMode = LlmResponseFormat.PROMPT_ONLY,
                                requestTimeoutSeconds = requestTimeoutSeconds,
                                maxAttempts = maxAttempts,
                            ),
                    ),
                tasks =
                    mapOf(
                        LlmTask.EXTRACTION to
                            LlmTaskConfig(
                                profile = "extraction",
                                maxInputTokens = 12_000,
                                maxOutputTokens = 3_000,
                                totalTimeoutSeconds = totalTimeoutSeconds,
                            ),
                    ),
            ),
        )

    private fun promptRequest(): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.PROMPT_ONLY,
        )

    private suspend fun awaitRequests(
        stub: ScriptedHttpStub,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (stub.requestCount() < expected) delay(10)
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val KEY = "ct08-b3-retry-key-7c41"
    }
}
