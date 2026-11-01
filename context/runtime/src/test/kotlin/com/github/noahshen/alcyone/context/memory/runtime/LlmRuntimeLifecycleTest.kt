package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * CT08-B4 A01（完整）+ Runtime 接线：`executor → vfs → llm` 构造、`llm → vfs → executor` 释放、
 * start 不连网、未配置 llm 跳过、LLM 清理失败仍释放其余资源、关闭后 client 拒绝新调用。
 */
class LlmRuntimeLifecycleTest {
    @Test
    fun `context without llm config starts and closes without a client`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                assertNull(impl.llmClientForTesting(), "未配置 context.llm 时不得创建 client")
            } finally {
                context.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `context with llm config creates the client at start without sending requests`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("ok"))
            try {
                val context =
                    AlcyoneContextFactory.create(
                        fixture.config().copy(llm = llmConfig(stub.baseUrl)),
                        fixture.vfsConfig(),
                    )
                val impl = context as AlcyoneContextImpl
                runBlocking { context.start() }
                try {
                    assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                    val client = impl.llmClientForTesting()
                    assertNotNull(client, "配置了 context.llm 后 start 必须创建 client")
                    assertEquals("RUNNING", client.stateForTesting())
                    assertEquals(0, stub.requests().size, "start 不得连网")
                } finally {
                    context.close()
                }
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertNull(impl.llmClientForTesting(), "关闭后不再持有 client")
                assertEquals(0, stub.requests().size)
            } finally {
                stub.close()
            }
        }

    @Test
    fun `llm close failure still closes the context and releases vfs and executor`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("ok"))
            try {
                val released = mutableListOf<AlcyoneContextFactory.ReleaseTarget>()
                val context =
                    AlcyoneContextFactory.create(
                        fixture.config().copy(llm = llmConfig(stub.baseUrl)),
                        fixture.vfsConfig(),
                        probe = AlcyoneContextFactory.StartProbe { },
                        releaseProbe = AlcyoneContextFactory.ReleaseProbe { target -> released += target },
                    )
                val impl = context as AlcyoneContextImpl
                runBlocking { context.start() }
                val client = assertNotNull(impl.llmClientForTesting())
                val unique = IllegalStateException("ct08-b4-llm-release-3d18")
                client.closeActionForTesting = { throw unique }

                val failure = assertFailsWith<IllegalStateException> { context.close() }
                assertSame(unique, failure, "Context.close 必须抛出 LLM 清理失败本身")
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                // 顺序 llm → vfs → executor：LLM 失败不阻断后续两项（不新增 ReleaseTarget.LLM）。
                assertEquals(
                    listOf(AlcyoneContextFactory.ReleaseTarget.VFS, AlcyoneContextFactory.ReleaseTarget.EXECUTOR),
                    released,
                )
                assertNull(impl.vfsForTesting(), "VFS 仍必须被释放")
                assertNull(impl.databaseForTesting(), "执行器仍必须被释放")
                assertNull(impl.llmClientForTesting())
            } finally {
                stub.close()
            }
        }

    @Test
    fun `the client created by the context is closed when the context closes`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("ok"))
            try {
                val context =
                    AlcyoneContextFactory.create(
                        fixture.config().copy(llm = llmConfig(stub.baseUrl)),
                        fixture.vfsConfig(),
                    )
                val impl = context as AlcyoneContextImpl
                runBlocking { context.start() }
                val client = assertNotNull(impl.llmClientForTesting())

                context.close()
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertEquals("CLOSED", client.stateForTesting())

                val failure =
                    assertFailsWith<ContextLifecycleException> {
                        runBlocking { client.complete(promptRequest()) }
                    }
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                assertEquals(0, stub.requests().size, "关闭后不得发起 HTTP")
            } finally {
                stub.close()
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun llmConfig(baseUrl: String): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "extraction" to
                        LlmProfileConfig(
                            name = "extraction",
                            baseUrl = baseUrl,
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
        )

    private fun promptRequest(): LlmRequest =
        LlmRequest(
            task = LlmTask.EXTRACTION,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = LlmResponseFormat.PROMPT_ONLY,
        )

    private companion object {
        const val KEY = "ct08-b4-runtime-key-6f13"
    }
}
