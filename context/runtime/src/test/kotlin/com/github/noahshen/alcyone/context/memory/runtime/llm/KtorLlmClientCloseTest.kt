package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CT08-B4 A06 ①～⑤：取消传播、关闭准入（`CLOSING`/`CLOSED`）、在途真实收尾、清理失败发布与重抛、
 * 等待者可取消。
 */
class KtorLlmClientCloseTest {
    @Test
    fun `cancellation of an in-flight call propagates and stops new requests`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("late"), delayMillis = 5_000)))
            val client = clientFor(stub)
            try {
                val deferred = async(Dispatchers.Default) { client.complete(promptRequest()) }
                awaitRequests(stub, expected = 1)
                delay(100)
                deferred.cancel()
                assertNotWrappedCancellation(assertFailsWith<CancellationException> { deferred.await() })

                val countAfterCancel = stub.requestCount()
                delay(300)
                assertEquals(countAfterCancel, stub.requestCount(), "取消后不得再发请求")
                assertEquals(1, countAfterCancel)
                awaitInFlight(client, expected = 0)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `calls after close are refused without HTTP`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
            val client = clientFor(stub)
            try {
                client.closeAsync()
                assertEquals("CLOSED", client.stateForTesting())

                val failure = assertFailsWith<ContextLifecycleException> { client.complete(promptRequest()) }
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                assertEquals(0, stub.requestCount(), "关闭后新调用不得发起 HTTP")
            } finally {
                stub.close()
            }
        }

    @Test
    fun `calls during closing are refused without HTTP`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("late"), delayMillis = 5_000)))
            val client = clientFor(stub)
            val cleanupEntered = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            client.closeActionForTesting = {
                cleanupEntered.countDown()
                assertTrue(releaseCleanup.await(10, TimeUnit.SECONDS), "清理屏障必须有界放行")
            }
            try {
                val inFlight = async(Dispatchers.Default) { client.complete(promptRequest()) }
                awaitRequests(stub, expected = 1)
                val closing = async(Dispatchers.Default) { client.closeAsync() }
                assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS), "owner 应进入清理最后一步")
                assertEquals("CLOSING", client.stateForTesting())

                val refused = assertFailsWith<ContextLifecycleException> { client.complete(promptRequest()) }
                assertEquals(ContextLifecycleErrorCode.CLOSED, refused.errorCode)
                assertEquals(1, stub.requestCount(), "CLOSING 期间不得新增请求")

                releaseCleanup.countDown()
                closing.await()
                // 关闭取消在途调用：调用方拿到取消，不是 CLOSED。
                assertFailsWith<CancellationException> { inFlight.await() }
                assertEquals("CLOSED", client.stateForTesting())
                assertEquals(0, client.inFlightCountForTesting())
            } finally {
                releaseCleanup.countDown()
                stub.close()
            }
        }

    @Test
    fun `close waits for the in-flight call to finish locally`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("late"), delayMillis = 5_000)))
            val client = clientFor(stub)
            val callerBarrier = CountDownLatch(1)
            val callerFailure = AtomicReference<Throwable?>(null)
            client.closeActionForTesting = {
                // 清理最后一步：此时 join 已完成，调用方的 finally 屏障必须在 closeAsync 返回前执行。
                assertTrue(callerBarrier.await(10, TimeUnit.SECONDS), "调用方 finally 屏障必须在关闭返回前执行")
            }
            val caller =
                launch(Dispatchers.Default) {
                    try {
                        client.complete(promptRequest())
                    } catch (failure: Throwable) {
                        callerFailure.set(failure)
                    } finally {
                        callerBarrier.countDown()
                    }
                }
            try {
                awaitRequests(stub, expected = 1)
                client.closeAsync()
                assertEquals(0, client.inFlightCountForTesting(), "closeAsync 返回时在途集合必须为空")
                assertEquals("CLOSED", client.stateForTesting())
                assertEquals(0L, callerBarrier.count, "closeAsync 返回时屏障已执行")
                caller.join()
                val thrown = callerFailure.get()
                assertTrue(thrown != null, "调用方必须收到异常")
                assertNotWrappedCancellation(thrown!!)
            } finally {
                stub.close()
            }
        }

    @Test
    fun `close failure is published and rethrown as the same instance`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
            val client = clientFor(stub)
            val unique = IllegalStateException("ct08-b4-close-failure-9a71")
            client.closeActionForTesting = { throw unique }
            try {
                val first = assertFailsWith<IllegalStateException> { client.closeAsync() }
                assertSame(unique, first, "owner 必须抛出清理失败本身")
                assertEquals("CLOSED", client.stateForTesting(), "清理失败也必须发布 CLOSED，不得滞留 CLOSING")

                val second = assertFailsWith<IllegalStateException> { client.closeAsync() }
                assertSame(unique, second, "重复关闭必须重抛同一异常实例")
            } finally {
                stub.close()
            }
        }

    @Test
    fun `a cancelled close waiter does not disturb the owner`() =
        runBlocking {
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
            val client = clientFor(stub)
            val cleanupEntered = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            client.closeActionForTesting = {
                cleanupEntered.countDown()
                assertTrue(releaseCleanup.await(10, TimeUnit.SECONDS), "清理屏障必须有界放行")
            }
            try {
                val owner = async(Dispatchers.Default) { client.closeAsync() }
                assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS), "owner 应进入清理")
                val waiter = async(Dispatchers.Default) { client.closeAsync() }
                delay(100)
                waiter.cancel()
                assertFailsWith<CancellationException> { waiter.await() }

                releaseCleanup.countDown()
                owner.await()
                assertEquals("CLOSED", client.stateForTesting())
                // 后续关闭仍正常返回（成功结果共享）。
                client.closeAsync()
            } finally {
                releaseCleanup.countDown()
                stub.close()
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun clientFor(stub: ScriptedHttpStub): KtorLlmClient =
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
                            ),
                    ),
                tasks =
                    mapOf(
                        LlmTask.EXTRACTION to
                            LlmTaskConfig(profile = "extraction", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                    ),
            ),
        )

    private fun promptRequest(): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.PROMPT_ONLY,
        )

    /** 取消必须是 `CancellationException`，且不得被包装成 `LlmException` 或转成 `CLOSED`。 */
    private fun assertNotWrappedCancellation(failure: Throwable) {
        assertTrue(failure !is LlmException, "取消不得包装成 LlmException: $failure")
        assertTrue(failure !is ContextLifecycleException, "在途取消不得转成 CLOSED: $failure")
        assertTrue(failure is CancellationException, "应为 CancellationException，实际 $failure")
    }

    private suspend fun awaitRequests(
        stub: ScriptedHttpStub,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (stub.requestCount() < expected) delay(10)
        }
    }

    private suspend fun awaitInFlight(
        client: KtorLlmClient,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (client.inFlightCountForTesting() != expected) delay(10)
        }
    }

    private companion object {
        const val KEY = "ct08-b4-close-key-5e27"
    }
}
