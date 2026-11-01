package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CT08-B5 A08（重定向）：`followRedirects = false` 的行为 + 源码双证据。
 *
 * 第一个真实 server 返回 `302` + `Location` 指向第二个 server；客户端必须**不追随**，
 * 第二 server 收到 0 请求（因此也不会有凭据外发）。
 */
class KtorLlmClientRedirectTest {
    @Test
    fun `a 302 is not followed and the second host receives no request`() =
        runBlocking {
            val target = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("ok"))))
            val redirector =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(
                            status = 302,
                            body = "",
                            headers = mapOf("Location" to "${target.baseUrl}/moved"),
                        ),
                    ),
                )
            val client = clientFor("${redirector.baseUrl}/p", maxAttempts = 3)
            try {
                val failure = assertFailsWith<LlmException> { client.complete(promptRequest()) }
                // 302 属非 2xx 且不可重试（不是 500/502/503/504）。
                assertEquals(LlmErrorCode.PROVIDER_UNAVAILABLE, failure.errorCode)
                assertEquals(302, failure.statusCode)
                assertEquals(1, failure.attempts)
                assertEquals(1, redirector.requestCount(), "第一个 server 只应收到 1 次")
                assertEquals(0, target.requestCount(), "不得追随重定向：第二个 server 必须 0 请求")
            } finally {
                client.closeAsync()
                redirector.close()
                target.close()
            }
        }

    @Test
    fun `the owned http client disables redirects in source`() {
        val root =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: error("无法定位仓库根（settings.gradle.kts）")
        val source =
            File(
                root,
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/llm/KtorLlmClient.kt",
            )
        assertTrue(source.isFile, "缺少 ${source.path}")
        val text = source.readText()
        assertTrue(text.contains("followRedirects = false"), "自有 HttpClient 必须关闭重定向追随")
        assertTrue(text.contains("install(HttpTimeout)"), "自有 HttpClient 必须安装 HttpTimeout（逐请求超时）")
    }

    // ------------------------------------------------------------------ 工具

    private fun clientFor(
        baseUrl: String,
        maxAttempts: Int,
    ): KtorLlmClient =
        KtorLlmClient(
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
                                maxAttempts = maxAttempts,
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

    private companion object {
        const val KEY = "ct08-b5-redirect-key-2a94"
    }
}
