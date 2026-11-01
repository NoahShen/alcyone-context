package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * CT08-B2 测试专用真实 HTTP stub（JDK `com.sun.net.httpserver`，零新依赖）。
 *
 * 绑定 `127.0.0.1:0`（随机端口），记录 `path` / `Authorization` / body 原文与到达序号；
 * 固定回复给定状态码与正文。每个用例必须在 `finally` 中 [close]（`stop(0)` +
 * `shutdownNow`），避免线程泄漏。**不输出任何请求正文/凭据**到标准流。
 */
internal class LlmHttpStub(
    private val status: Int,
    private val replyBody: String,
    private val responseContentType: String = "application/json",
) : AutoCloseable {
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "llm-stub").apply { isDaemon = true }
        }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0).also { server ->
            server.executor = executor
            server.createContext("/") { exchange -> exchange.respond() }
            server.start()
        }
    private val arrivals = AtomicInteger(0)

    val port: Int get() = server.address.port

    /** 形如 `http://127.0.0.1:<port>`（不含路径，测试自行拼接）。 */
    val baseUrl: String get() = "http://$HOST:$port"

    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    fun requests(): List<RecordedRequest> = recorded.toList()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun HttpExchange.respond() {
        val body = requestBody.use { stream -> String(stream.readBytes(), StandardCharsets.UTF_8) }
        recorded +=
            RecordedRequest(
                index = arrivals.getAndIncrement(),
                path = requestURI.path,
                method = requestMethod,
                authorization = requestHeaders.getFirst("Authorization"),
                contentType = requestHeaders.getFirst("Content-Type"),
                accept = requestHeaders.getFirst("Accept"),
                body = body,
            )
        val bytes = replyBody.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", responseContentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { stream -> stream.write(bytes) }
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}

internal data class RecordedRequest(
    val index: Int,
    val path: String,
    val method: String,
    val authorization: String?,
    val contentType: String?,
    val accept: String?,
    val body: String,
)

/** 便捷构造：成功信封（content 已由调用方决定，是否合法 JSON 由用例控制）。 */
internal fun chatCompletionBody(
    content: String,
    finishReason: String? = "stop",
    usage: JsonElement? = DEFAULT_USAGE,
    refusal: String? = null,
): String =
    buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("content", content)
                    if (refusal != null) put("refusal", refusal)
                }
                if (finishReason != null) put("finish_reason", finishReason)
            }
        }
        if (usage != null) put("usage", usage)
    }.toString()

private val DEFAULT_USAGE: JsonElement =
    buildJsonObject {
        put("prompt_tokens", 11)
        put("completion_tokens", 7)
        put("total_tokens", 18)
    }
