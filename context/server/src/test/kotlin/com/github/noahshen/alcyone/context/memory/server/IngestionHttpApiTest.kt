package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.runtime.IngestionLimitAware
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CT07-B2 最小 HTTP：认证、幂等/冲突、strict JSON、限额、生命周期与并发。
 */
class IngestionHttpApiTest {
    @Test
    fun `bearer authentication matrix and runtime binding`() =
        withServer { fixture, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }

                assertEquals(HttpStatusCode.Unauthorized, postTurn(null, HttpTurns.json()).status)
                assertEquals(HttpStatusCode.Unauthorized, postTurn("unknown-key", HttpTurns.json()).status)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(), scheme = "Token").status,
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(runtimeId = "runtime-2")).status,
                )
                // 同 Runtime 不同 agent 允许。
                assertEquals(
                    HttpStatusCode.Created,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(agentId = "another-agent", sessionTurnId = "t-2")).status,
                )
                // 另一个 Runtime 的注册各自隔离。
                assertEquals(
                    HttpStatusCode.Created,
                    postTurn(HttpTurns.KEY_RUNTIME_2, HttpTurns.json(runtimeId = "runtime-2", sessionTurnId = "t-3")).status,
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    postTurn(HttpTurns.KEY_RUNTIME_2, HttpTurns.json(runtimeId = "runtime-1", sessionTurnId = "t-4")).status,
                )
            }
            assertEquals(2L, countRows(fixture.contextPath))
        }

    @Test
    fun `created duplicate conflict strict json and timestamps`() =
        withServer { fixture, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }

                val created = postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json())
                assertEquals(HttpStatusCode.Created, created.status)
                val createdId = idOf(created)
                assertTrue(createdId > 0)
                assertFalse(created.bodyAsText().contains("hello"), "响应不得回显正文")

                val duplicate = postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json())
                assertEquals(HttpStatusCode.OK, duplicate.status)
                assertEquals(createdId, idOf(duplicate), "DUPLICATE 必须返回同一 id")

                val conflict =
                    postTurn(
                        HttpTurns.KEY_RUNTIME_1,
                        HttpTurns.json(messages = listOf("user" to "different")),
                    )
                assertEquals(HttpStatusCode.Conflict, conflict.status)

                val unknownTop =
                    """{"runtime_id":"runtime-1","agent_id":"a","session_id":"s","session_turn_id":"t-6",""" +
                        """"timestamp":"2026-10-29T08:00:00+08:00","messages":[{"role":"user","content":"x"}],"unknown":1}"""
                assertEquals(HttpStatusCode.BadRequest, postTurn(HttpTurns.KEY_RUNTIME_1, unknownTop).status)

                val unknownMessage =
                    """{"runtime_id":"runtime-1","agent_id":"a","session_id":"s","session_turn_id":"t-7",""" +
                        """"timestamp":"2026-10-29T08:00:00+08:00",""" +
                        """"messages":[{"role":"user","content":"x","extra":1}]}"""
                assertEquals(HttpStatusCode.BadRequest, postTurn(HttpTurns.KEY_RUNTIME_1, unknownMessage).status)

                assertEquals(
                    HttpStatusCode.BadRequest,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-8", timestamp = "2026-10-29T08:00:00")).status,
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-9", timestamp = "2026-10-29")).status,
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-10", timestamp = "not-a-time")).status,
                )

                // +08:00 与等价 Z 视为同一条。
                val shanghai =
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-11", timestamp = "2026-10-29T20:15:00+08:00"))
                assertEquals(HttpStatusCode.Created, shanghai.status)
                val utc =
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-11", timestamp = "2026-10-29T12:15:00Z"))
                assertEquals(HttpStatusCode.OK, utc.status)
                assertEquals(idOf(shanghai), idOf(utc))

                assertEquals(
                    HttpStatusCode.BadRequest,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-12", agentId = " ")).status,
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-13", messages = emptyList())).status,
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "t-14", messages = listOf("system" to "x"))).status,
                )
            }
            assertEquals(2L, countRows(fixture.contextPath), "只有合法提交的两条 Turn")
        }

    @Test
    fun `limits reject oversized bodies before parsing and leave no rows`() =
        withServer { fixture, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }

                val huge = "a".repeat(IngestionLimits.DEFAULT_MAX_TURN_BYTES + 10)
                assertEquals(
                    HttpStatusCode.PayloadTooLarge,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "huge", messages = listOf("user" to huge))).status,
                )

                // 无 Content-Length 的流式请求同样被上限拦截。
                val streamed = "b".repeat(IngestionLimits.DEFAULT_MAX_TURN_BYTES + 10)
                val response =
                    client.post("/v1/conversations/turns") {
                        header(HttpHeaders.Authorization, "Bearer ${HttpTurns.KEY_RUNTIME_1}")
                        setBody(ChunkedJson(HttpTurns.json(sessionTurnId = "huge-stream", messages = listOf("user" to streamed))))
                    }
                assertEquals(HttpStatusCode.PayloadTooLarge, response.status)

                val tooManyMessages = List(257) { "user" to "x" }
                assertEquals(
                    HttpStatusCode.PayloadTooLarge,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "many", messages = tooManyMessages)).status,
                )
                assertEquals(
                    HttpStatusCode.PayloadTooLarge,
                    postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "i".repeat(257))).status,
                )
            }
            assertEquals(0L, countRows(fixture.contextPath), "超限请求不得留下数据库行")
        }

    @Test
    fun `unstarted and closed contexts answer 503`() =
        ServerFixture().use { fixture ->
            fixture.initialize()
            val context = fixture.newContext()
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }
                assertEquals(HttpStatusCode.ServiceUnavailable, postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json()).status)
            }

            runBlocking { context.start() }
            context.close()
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }
                assertEquals(HttpStatusCode.ServiceUnavailable, postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json()).status)
            }
        }

    @Test
    fun `closing while a request is accepted does not deadlock`() =
        ServerFixture().use { fixture ->
            fixture.initialize()
            val context = fixture.newContext()
            runBlocking { context.start() }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gated = GateContext(context, entered, release)
            try {
                testApplication {
                    application { ingestion(gated, HttpTurns.registry()) }
                    coroutineScope {
                        val pending = async { postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json()) }
                        withContext(Dispatchers.IO) {
                            assertTrue(entered.await(10, TimeUnit.SECONDS), "请求必须已被接纳并完成提交")
                            context.close()
                        }
                        release.countDown()
                        assertEquals(HttpStatusCode.Created, pending.await().status, "已提交的请求仍返回 201")
                    }
                }
            } finally {
                release.countDown()
            }
            assertEquals(1L, countRows(fixture.contextPath))
        }

    @Test
    fun `concurrent identical http turns yield one 201 and one 200 with one row`() =
        withServer { fixture, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }
                coroutineScope {
                    val first = async { postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json()) }
                    val second = async { postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json()) }
                    val statuses = setOf(first.await().status, second.await().status)
                    assertEquals(setOf(HttpStatusCode.Created, HttpStatusCode.OK), statuses)
                }
            }
            assertEquals(1L, countRows(fixture.contextPath))
        }

    @Test
    fun `http uses the context instance maxTurnBytes before parsing`() =
        ServerFixture().use { fixture ->
            val limits = IngestionLimits(maxTurnBytes = 256, maxMessages = 256, maxIdLength = 256)
            fixture.initialize()
            val context = fixture.newContext(limits)
            assertEquals(256, (context as IngestionLimitAware).ingestionMaxTurnBytes, "访问器必须给出实例真实限额")
            runBlocking { context.start() }
            try {
                testApplication {
                    application { ingestion(context, HttpTurns.registry()) }

                    // 超过实例限额 256 但远小于 2 MiB：必须在反序列化前被 413 拦截。
                    val oversizedValid =
                        HttpTurns.json(sessionTurnId = "big", messages = listOf("user" to "x".repeat(300)))
                    assertTrue(oversizedValid.toByteArray().size > 256)
                    assertEquals(HttpStatusCode.PayloadTooLarge, postTurn(HttpTurns.KEY_RUNTIME_1, oversizedValid).status)

                    // 非法 JSON 但只要超限即先返回 413，证明限额检查早于完整解析。
                    val oversizedInvalid = "{" + "x".repeat(300)
                    assertEquals(HttpStatusCode.PayloadTooLarge, postTurn(HttpTurns.KEY_RUNTIME_1, oversizedInvalid).status)

                    // 在实例限额内的正常请求仍可提交。
                    assertEquals(HttpStatusCode.Created, postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "small")).status)
                }
            } finally {
                context.close()
            }
            assertEquals(1L, countRows(fixture.contextPath), "超限请求不得落库")
        }

    @Test
    fun `http server wrapper listens and maps lifecycle to 503 then 201`() =
        ServerFixture().use { fixture ->
            fixture.initialize()
            val context = fixture.newContext()
            val port = java.net.ServerSocket(0).use { it.localPort }
            val config =
                ServerConfig(
                    port = port,
                    clients = mapOf("runtime-1" to IngestionClientConfig("runtime-1", "pi", HttpTurns.KEY_RUNTIME_1)),
                )
            val server = AlcyoneHttpServer.start(config, context)
            try {
                val (unavailableStatus, unavailableBody) = httpPost(port, HttpTurns.KEY_RUNTIME_1, HttpTurns.json())
                assertEquals(503, unavailableStatus)
                assertFalse(unavailableBody.contains(HttpTurns.KEY_RUNTIME_1))

                runBlocking { context.start() }
                val (createdStatus, createdBody) = httpPost(port, HttpTurns.KEY_RUNTIME_1, HttpTurns.json())
                assertEquals(201, createdStatus)
                assertTrue(createdBody.contains("\"id\":"))
                assertFalse(createdBody.contains(HttpTurns.KEY_RUNTIME_1))
            } finally {
                server.close()
                context.close()
            }
        }

    private fun httpPost(
        port: Int,
        key: String,
        body: String,
    ): Pair<Int, String> {
        val connection =
            java.net.URL("http://127.0.0.1:$port/v1/conversations/turns").openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun withServer(block: suspend (ServerFixture, AlcyoneContext) -> Unit) {
        ServerFixture().use { fixture ->
            fixture.initialize()
            val context = fixture.newContext()
            runBlocking { context.start() }
            try {
                runBlocking { block(fixture, context) }
            } finally {
                context.close()
            }
        }
    }

    private suspend fun ApplicationTestBuilder.postTurn(
        key: String?,
        body: String,
        scheme: String = "Bearer",
    ): HttpResponse =
        client.post("/v1/conversations/turns") {
            if (key != null) header(HttpHeaders.Authorization, "$scheme $key")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }

    private fun idOf(response: HttpResponse): Long =
        runBlocking {
            Regex("\"id\":(\\d+)")
                .find(response.bodyAsText())
                ?.groupValues
                ?.get(1)
                ?.toLong() ?: -1L
        }

    private fun countRows(path: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM conversation_turn").use { rs ->
                    assertTrue(rs.next())
                    rs.getLong(1)
                }
            }
        }

    /** 已接纳请求：先完成真实 ingest（COMMIT），再在返回前等待，用于关闭竞态测试。 */
    private class GateContext(
        private val delegate: AlcyoneContext,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : AlcyoneContext,
        IngestionLimitAware {
        override val ingestionMaxTurnBytes: Int
            get() =
                (delegate as? IngestionLimitAware)?.ingestionMaxTurnBytes
                    ?: throw IllegalStateException("delegate does not expose ingestion limits")

        override suspend fun ingestConversation(turn: ConversationTurn): IngestResult {
            val result = delegate.ingestConversation(turn)
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return result
        }

        override suspend fun start() = delegate.start()

        override fun close() = delegate.close()

        override suspend fun closeAsync() = delegate.closeAsync()

        override suspend fun getStatus(): ContextStatus = delegate.getStatus()
    }

    private class ChunkedJson(
        private val body: String,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long? = null

        override val contentType: ContentType = ContentType.Application.Json

        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(body.toByteArray())
        }
    }
}
