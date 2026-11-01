package com.github.noahshen.alcyone.context.memory.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * CT12 集成测试用真实 LLM HTTP stub（JDK `com.sun.net.httpserver`，零新依赖）。
 *
 * - 绑定 `127.0.0.1:0`，按 [responses] 顺序返回完整 HTTP body（用尽后重复最后一条）；
 * - 记录请求次数、path 与 `Authorization`，用于断言"失败后仍继续"与凭据只走 Header；
 * - 响应必须是 **OpenAI 信封**（`choices[0].message.content`），提取 JSON 放在 `content` 内。
 */
internal class ExtractionHttpStub(
    private val responses: List<String>,
) : AutoCloseable {
    init {
        require(responses.isNotEmpty()) { "ExtractionHttpStub 至少需要一条响应" }
    }

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "extraction-stub").apply { isDaemon = true }
        }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0).also { server ->
            server.executor = executor
            server.createContext("/") { exchange -> respond(exchange) }
            server.start()
        }
    private val counter = AtomicInteger(0)
    private val paths = CopyOnWriteArrayList<String>()
    private val authorizations = CopyOnWriteArrayList<String?>()
    private val requestBodies = CopyOnWriteArrayList<String>()

    val baseUrl: String get() = "http://$HOST:${server.address.port}"

    fun requestCount(): Int = counter.get()

    fun paths(): List<String> = paths.toList()

    fun authorizations(): List<String?> = authorizations.toList()

    fun requestBodies(): List<String> = requestBodies.toList()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respond(exchange: HttpExchange) {
        val index = counter.getAndIncrement()
        paths += exchange.requestURI.path
        authorizations += exchange.requestHeaders.getFirst("Authorization")
        requestBodies += exchange.requestBody.use { stream -> String(stream.readBytes(), StandardCharsets.UTF_8) }
        val body = responses[minOf(index, responses.lastIndex)]
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        try {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { stream -> stream.write(bytes) }
        } catch (_: IOException) {
            // 客户端已取消/断开：忽略写回失败。
        } finally {
            exchange.close()
        }
    }

    companion object {
        private const val HOST = "127.0.0.1"

        /** OpenAI 兼容成功信封：`content` 内放提取 JSON（自动转义）。 */
        fun chatCompletionEnvelope(content: String): String =
            "{\"choices\":[{\"message\":{\"content\":\"" +
                escapeJson(content) +
                "\",\"refusal\":null},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6,\"total_tokens\":18}}"

        private fun escapeJson(text: String): String =
            buildString(text.length + 8) {
                text.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                    }
                }
            }
    }
}
