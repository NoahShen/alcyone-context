package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT08-B2 A04：请求体三模式、`responseFormat` 一致性、schema 校验与 `max_tokens` 实际值、URL 形状。
 *
 * 所有发送前失败都必须 **0 次 HTTP**（stub 请求数为 0）。
 */
class KtorLlmClientRequestTest {
    @Test
    fun `json schema request carries name strict and the parsed schema`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val client = clientFor(stub, LlmResponseFormat.JSON_SCHEMA)
            try {
                client.complete(
                    request(
                        task = LlmTask.EXTRACTION,
                        format = LlmResponseFormat.JSON_SCHEMA,
                        schema = """{"type":"object","properties":{"a":{"type":"string"}}}""",
                    ),
                )

                val body = stub.requests().single().jsonBody()
                val responseFormat = body["response_format"]!!.jsonObject
                assertEquals("json_schema", responseFormat["type"]!!.jsonPrimitive.content)
                val jsonSchema = responseFormat["json_schema"]!!.jsonObject
                // name 固定为 Task 名小写。
                assertEquals("extraction", jsonSchema["name"]!!.jsonPrimitive.content)
                assertTrue(jsonSchema["strict"]!!.jsonPrimitive.boolean)
                assertEquals(
                    Json.parseToJsonElement("""{"type":"object","properties":{"a":{"type":"string"}}}"""),
                    jsonSchema["schema"],
                )
                // 预算与请求共用同一份实际发送 schema。
                assertEquals(3_000, body["max_tokens"]!!.jsonPrimitive.int)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `json object request only declares the type and never sends a schema`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val client = clientFor(stub, LlmResponseFormat.JSON_OBJECT)
            try {
                client.complete(
                    request(
                        task = LlmTask.EXTRACTION,
                        format = LlmResponseFormat.JSON_OBJECT,
                        schema = """{"type":"object"}""",
                    ),
                )

                val body = stub.requests().single().jsonBody()
                val responseFormat = body["response_format"]!!.jsonObject
                assertEquals(setOf("type"), responseFormat.keys, "json_object 只能有 type")
                assertEquals("json_object", responseFormat["type"]!!.jsonPrimitive.content)
                assertFalse(body.toString().contains("\"schema\""), "非 JSON_SCHEMA 模式不得发送 schema")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `prompt only request omits response format entirely`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("plain answer"))
            val client = clientFor(stub, LlmResponseFormat.PROMPT_ONLY)
            try {
                val response =
                    client.complete(
                        request(
                            task = LlmTask.EXTRACTION,
                            format = LlmResponseFormat.PROMPT_ONLY,
                            schema = """{"type":"object"}""",
                        ),
                    )
                assertEquals("plain answer", response.content)

                val body = stub.requests().single().jsonBody()
                assertNull(body["response_format"], "prompt_only 必须省略 response_format")
                assertFalse(body.toString().contains("\"schema\""))
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `response format mismatch fails before any request`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val client = clientFor(stub, LlmResponseFormat.JSON_OBJECT)
            try {
                val failure =
                    assertFailsWith<LlmException> {
                        client.complete(request(task = LlmTask.EXTRACTION, format = LlmResponseFormat.PROMPT_ONLY))
                    }
                assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode)
                assertEquals("extraction", failure.profile)
                assertTrue(stub.requests().isEmpty(), "发送前失败必须是 0 次 HTTP")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `json schema mode requires a non blank parseable schema`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val client = clientFor(stub, LlmResponseFormat.JSON_SCHEMA)
            try {
                listOf(null, "", "   ", "{\"type\":", "not json").forEach { schema ->
                    val failure =
                        assertFailsWith<LlmException>("schema=$schema") {
                            client.complete(
                                request(
                                    task = LlmTask.EXTRACTION,
                                    format = LlmResponseFormat.JSON_SCHEMA,
                                    schema = schema,
                                ),
                            )
                        }
                    assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode, "schema=$schema")
                    assertFalse(
                        failure.message.orEmpty().contains("not json"),
                        "错误消息不得回显 schema 原文: ${failure.message}",
                    )
                }
                assertTrue(stub.requests().isEmpty(), "schema 校验失败必须是 0 次 HTTP")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `invalid schema text never leaks into the error or stack trace`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val client = clientFor(stub, LlmResponseFormat.JSON_SCHEMA)
            try {
                val marker = "LEAK_SCHEMA_MARKER_9f31"
                val failure =
                    assertFailsWith<LlmException> {
                        client.complete(
                            request(
                                task = LlmTask.EXTRACTION,
                                format = LlmResponseFormat.JSON_SCHEMA,
                                // 故意半截：JSONDecodingException 原文会包含该 schema 文本。
                                schema = """{"x": $marker""",
                            ),
                        )
                    }
                assertEquals(LlmErrorCode.INVALID_REQUEST, failure.errorCode)
                listOf(
                    "message" to failure.message.orEmpty(),
                    "toString" to failure.toString(),
                    "stackTraceToString" to failure.stackTraceToString(),
                ).forEach { (label, text) ->
                    assertFalse(text.contains(marker), "$label 回显了 schema 原文")
                }
                assertTrue(stub.requests().isEmpty(), "schema 校验失败必须是 0 次 HTTP")
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `max tokens sent is the effective value`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val config =
                LlmConfig(
                    profiles = mapOf("extraction" to profile("extraction", stub.baseUrl, LlmResponseFormat.JSON_OBJECT)),
                    tasks =
                        mapOf(
                            LlmTask.EXTRACTION to
                                LlmTaskConfig(profile = "extraction", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                        ),
                )
            val client = KtorLlmClient(config)
            try {
                // 请求下调 → 发送请求值。
                client.complete(request(LlmTask.EXTRACTION, LlmResponseFormat.JSON_OBJECT, maxOutputTokens = 500))
                // 未指定 → Task 预算。
                client.complete(request(LlmTask.EXTRACTION, LlmResponseFormat.JSON_OBJECT))
                // 请求值高于 Task → Task 封顶。
                client.complete(request(LlmTask.EXTRACTION, LlmResponseFormat.JSON_OBJECT, maxOutputTokens = 9_000))

                val sent = stub.requests().map { it.jsonBody()["max_tokens"]!!.jsonPrimitive.int }
                assertEquals(listOf(500, 3_000, 3_000), sent)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `base url trailing slash and chat completions suffix are normalised`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val config =
                LlmConfig(
                    profiles =
                        mapOf(
                            // 尾斜杠 → 只补一次 /chat/completions。
                            "trailing" to profile("trailing", "${stub.baseUrl}/api/", LlmResponseFormat.JSON_OBJECT),
                            // 已以 /chat/completions 结尾 → 不重复追加。
                            "complete" to
                                profile(
                                    "complete",
                                    "${stub.baseUrl}/v1/chat/completions",
                                    LlmResponseFormat.JSON_OBJECT,
                                ),
                        ),
                    tasks =
                        mapOf(
                            LlmTask.EXTRACTION to
                                LlmTaskConfig(profile = "trailing", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                            LlmTask.GOVERNANCE to
                                LlmTaskConfig(profile = "complete", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                        ),
                )
            val client = KtorLlmClient(config)
            try {
                client.complete(request(LlmTask.EXTRACTION, LlmResponseFormat.JSON_OBJECT))
                client.complete(request(LlmTask.GOVERNANCE, LlmResponseFormat.JSON_OBJECT))

                val paths = stub.requests().map { it.path }
                assertEquals(listOf("/api/chat/completions", "/v1/chat/completions"), paths)
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    @Test
    fun `messages are sent in their original order and shape`() =
        runBlocking {
            val stub = LlmHttpStub(200, chatCompletionBody("""{"ok":true}"""))
            val client = clientFor(stub, LlmResponseFormat.JSON_OBJECT)
            try {
                client.complete(
                    LlmRequest(
                        task = LlmTask.EXTRACTION,
                        messages =
                            listOf(
                                LlmMessage(role = "system", content = "sys"),
                                LlmMessage(role = "user", content = "first"),
                                LlmMessage(role = "assistant", content = "second"),
                            ),
                        responseFormat = LlmResponseFormat.JSON_OBJECT,
                    ),
                )

                val messages =
                    (stub.requests().single().jsonBody()["messages"]!! as JsonArray).map { element ->
                        val message = element.jsonObject
                        message["role"]!!.jsonPrimitive.content to message["content"]!!.jsonPrimitive.content
                    }
                assertEquals(
                    listOf("system" to "sys", "user" to "first", "assistant" to "second"),
                    messages,
                    "messages 必须原顺序、原 shape 发送",
                )
            } finally {
                client.closeAsync()
                stub.close()
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun clientFor(
        stub: LlmHttpStub,
        format: LlmResponseFormat,
    ): KtorLlmClient {
        val config =
            LlmConfig(
                profiles = mapOf("extraction" to profile("extraction", stub.baseUrl, format)),
                tasks =
                    mapOf(
                        LlmTask.EXTRACTION to
                            LlmTaskConfig(profile = "extraction", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                    ),
            )
        return KtorLlmClient(config)
    }

    private fun profile(
        name: String,
        baseUrl: String,
        format: LlmResponseFormat,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = name,
            baseUrl = baseUrl,
            apiKey = KEY,
            model = "fast-model",
            contextWindowTokens = 64_000,
            jsonMode = format,
        )

    private fun request(
        task: LlmTask,
        format: LlmResponseFormat,
        schema: String? = null,
        maxOutputTokens: Int? = null,
    ): LlmRequest =
        LlmRequest(
            task = task,
            messages = listOf(LlmMessage(role = "user", content = "hi")),
            responseFormat = format,
            maxOutputTokens = maxOutputTokens,
            schema = schema,
        )

    private fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body) as JsonObject

    private companion object {
        const val KEY = "ct08-b2-request-key-7a10"
    }
}
