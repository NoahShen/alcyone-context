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
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KtorLlmClientRawExchangeTest {
    @Test
    fun `successful exchange reports the exact request response and metadata once`() =
        runBlocking {
            val body = chatCompletionBody("ok")
            val stub = LlmHttpStub(200, body)
            val client = clientFor(stub.baseUrl)
            val observations = mutableListOf<RawExchangeObservation>()
            val request = promptRequest()
            try {
                client.rawExchangeObserver = RawExchangeObserver { observations += it }

                val response = client.complete(request)

                assertEquals("ok", response.content)
                val observation = observations.single()
                assertSame(request, observation.request)
                assertEquals(PROFILE, observation.profile)
                assertEquals(MODEL, observation.model)
                assertEquals(1, observation.attempt)
                assertEquals(200, observation.httpStatus)
                assertEquals(body, observation.rawResponse)
                assertNull(observation.failureKind)
                assertNull(observation.errorType)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `2xx malformed response is reported with its raw body`() =
        runBlocking {
            val body = "not-json"
            val stub = LlmHttpStub(200, body)
            val client = clientFor(stub.baseUrl)
            val observations = mutableListOf<RawExchangeObservation>()
            try {
                client.rawExchangeObserver = RawExchangeObserver { observations += it }

                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }

                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
                val observation = observations.single()
                assertEquals(200, observation.httpStatus)
                assertEquals(body, observation.rawResponse)
                assertEquals(RawExchangeFailureKind.INVALID_RESPONSE, observation.failureKind)
                assertEquals(failure.errorCode.name, observation.errorType)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `non 2xx response is reported with status and raw body`() =
        runBlocking {
            val body = """{"error":{"message":"unavailable"}}"""
            val stub = LlmHttpStub(503, body)
            val client = clientFor(stub.baseUrl)
            val observations = mutableListOf<RawExchangeObservation>()
            try {
                client.rawExchangeObserver = RawExchangeObserver { observations += it }

                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }

                assertEquals(LlmErrorCode.PROVIDER_UNAVAILABLE, failure.errorCode)
                val observation = observations.single()
                assertEquals(503, observation.httpStatus)
                assertEquals(body, observation.rawResponse)
                assertEquals(RawExchangeFailureKind.HTTP_STATUS, observation.failureKind)
                assertNull(observation.errorType)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `transport failure is reported without an HTTP response`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val client = clientFor("http://127.0.0.1:$port")
            val observations = mutableListOf<RawExchangeObservation>()
            try {
                client.rawExchangeObserver = RawExchangeObserver { observations += it }

                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }

                assertEquals(LlmErrorCode.NETWORK, failure.errorCode)
                val observation = observations.single()
                assertEquals(1, observation.attempt)
                assertNull(observation.httpStatus)
                assertNull(observation.rawResponse)
                assertEquals(RawExchangeFailureKind.TRANSPORT, observation.failureKind)
                assertEquals("ConnectException", observation.errorType)
            } finally {
                client.closeAsync()
            }
        }

    @Test
    fun `cancellation during the request is reported and still propagates`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("late"), delayMillis = 5_000)))
            val client = clientFor(stub.baseUrl)
            val observations = mutableListOf<RawExchangeObservation>()
            try {
                client.rawExchangeObserver = RawExchangeObserver { observations += it }

                val deferred = async(Dispatchers.Default) { client.complete(promptRequest()) }
                withTimeout(5_000L) {
                    while (stub.requestCount() < 1) delay(10)
                }
                delay(100)
                deferred.cancel()

                val failure: Throwable = assertFailsWith<CancellationException> { deferred.await() }
                assertTrue(failure !is LlmException, "取消不得包装成 LlmException: $failure")
                val observation = observations.single()
                assertEquals(1, observation.attempt)
                assertNull(observation.httpStatus)
                assertNull(observation.rawResponse)
                assertEquals(RawExchangeFailureKind.CANCELLATION, observation.failureKind)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `default observer is a no-op and installing does not change the outcome`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("ok"))
            val client = clientFor(stub.baseUrl)
            try {
                // No observer installed: the default no-op seam must keep the success path unchanged.
                assertEquals("ok", client.complete(promptRequest()).content)
                assertEquals(1, stub.requests().size)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `observer failure cannot replace the production result`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("ok"))
            val client = clientFor(stub.baseUrl)
            try {
                client.rawExchangeObserver = RawExchangeObserver { error("observer failure") }

                val response = client.complete(promptRequest())

                assertEquals("ok", response.content)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `interleaved logical calls keep their own attempts and bodies`() =
        runBlocking {
            // Deterministic overlap: MARK-A answers immediately, MARK-B only after a delay. The first call
            // finishes (and used to clear the client-wide observer) while the second is still in flight.
            val server =
                com.sun.net.httpserver.HttpServer
                    .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            server.executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(4)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
                val marker = Regex("MARK-[AB]").find(body)?.value ?: "MARK-?"
                if (marker == "MARK-B") Thread.sleep(400)
                val bytes = chatCompletionBody(marker).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val client = clientFor("http://127.0.0.1:${server.address.port}")
            val recorder =
                com.github.noahshen.alcyone.context.memory.runtime.evaluation
                    .ProductionRecordingClient(client)
            try {
                val slow = async(Dispatchers.IO) { recorder.complete(markedRequest("MARK-B")) }
                val fast = async(Dispatchers.IO) { recorder.complete(markedRequest("MARK-A")) }

                val responses = listOf(fast.await(), slow.await()).map { it.content }.toSet()
                assertEquals(setOf("MARK-A", "MARK-B"), responses)

                val calls = recorder.logicalCalls()
                assertEquals(2, calls.size)
                assertTrue(calls.all { it.state.name == "SUCCEEDED" }, calls.toString())
                // Every logical call must keep exactly its own single attempt, in either completion order.
                assertEquals(listOf(1, 1), calls.map { it.attempts.size })
                calls.forEach { call ->
                    val marker = call.messages.map { it.content }.single { it.startsWith("MARK-") }
                    val other = if (marker == "MARK-A") "MARK-B" else "MARK-A"
                    val body = requireNotNull(call.attempts.single().rawResponse) { "missing body for $marker" }
                    assertTrue(body.contains(marker), "attempt of $marker lost its own body: $body")
                    assertTrue(!body.contains(other), "attempt of $marker was cross-attributed: $body")
                }
            } finally {
                client.closeAsync()
                server.stop(0)
                (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
            }
        }

    private fun markedRequest(marker: String): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = marker)),
            responseFormat = LlmResponseFormat.PROMPT_ONLY,
        )

    private fun clientFor(baseUrl: String): KtorLlmClient {
        val config =
            LlmConfig(
                profiles =
                    mapOf(
                        PROFILE to
                            LlmProfileConfig(
                                name = PROFILE,
                                baseUrl = baseUrl,
                                apiKey = API_KEY,
                                model = MODEL,
                                contextWindowTokens = 64_000,
                                jsonMode = LlmResponseFormat.PROMPT_ONLY,
                                maxAttempts = 1,
                            ),
                    ),
                tasks =
                    mapOf(
                        LlmTask.EXTRACTION to
                            LlmTaskConfig(profile = PROFILE, maxInputTokens = 12_000, maxOutputTokens = 3_000),
                    ),
            )
        return KtorLlmClient(config)
    }

    private fun promptRequest(): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.PROMPT_ONLY,
        )

    private companion object {
        const val PROFILE = "extraction"
        const val MODEL = "fast-model"
        const val API_KEY = "ct31-raw-exchange-key"
    }
}
