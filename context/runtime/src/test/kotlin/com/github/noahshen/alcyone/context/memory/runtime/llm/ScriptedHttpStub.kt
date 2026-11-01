package com.github.noahshen.alcyone.context.memory.runtime.llm

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

/** 脚本化 stub 的单次回复（按请求序号依次取用，用尽后重复最后一条）。 */
internal data class ScriptedReply(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** 响应前的人为延迟（毫秒），用于超时/在途取消用例。 */
    val delayMillis: Long = 0,
)

/**
 * CT08-B3 测试专用脚本化真实 HTTP stub（JDK `com.sun.net.httpserver`，零新依赖）。
 *
 * 绑定 `127.0.0.1:0`；按序返回 [replies]（用尽后重复最后一条），记录每次请求的**到达单调时刻**
 * （`System.nanoTime()/1_000_000`），用于相邻请求间隔断言。每个用例必须在 `finally` 中 [close]。
 */
internal class ScriptedHttpStub(
    private val replies: List<ScriptedReply>,
) : AutoCloseable {
    init {
        require(replies.isNotEmpty()) { "ScriptedHttpStub 至少需要一条回复" }
    }

    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "llm-scripted-stub").apply { isDaemon = true }
        }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0).also { server ->
            server.executor = executor
            server.createContext("/") { exchange -> respond(exchange) }
            server.start()
        }
    private val arrivals = CopyOnWriteArrayList<Long>()
    private val counter = AtomicInteger(0)

    val baseUrl: String get() = "http://$HOST:${server.address.port}"

    fun requestCount(): Int = counter.get()

    /** 每次请求到达时的单调毫秒时刻（升序）。 */
    fun arrivalMillis(): List<Long> = arrivals.toList()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respond(exchange: HttpExchange) {
        val index = counter.getAndIncrement()
        arrivals += System.nanoTime() / 1_000_000L
        val reply = replies[minOf(index, replies.lastIndex)]
        try {
            exchange.requestBody.use { it.readBytes() }
            if (reply.delayMillis > 0L) Thread.sleep(reply.delayMillis)
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { stream -> stream.write(bytes) }
        } catch (_: IOException) {
            // 客户端已取消/超时断开：忽略写回失败。
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            exchange.close()
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
