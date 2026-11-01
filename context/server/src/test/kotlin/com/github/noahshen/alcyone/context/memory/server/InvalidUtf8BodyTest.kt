package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CM1 全面复查 F2：非法 UTF-8 必须 400，而不是被静默替换成 U+FFFD 后写入数据库。
 *
 * 全部用例都是**字节级**探针：直接在请求体里注入非法字节，而不是构造 Kotlin 字符串。
 */
class InvalidUtf8BodyTest {
    @Test
    fun `invalid utf8 bytes are rejected with 400 and never reach the database`() =
        withServer { fixture, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }

                val invalids =
                    listOf(
                        "lone 0xFF" to byteArrayOf(0xFF.toByte()),
                        "lone 0x80 continuation" to byteArrayOf(0x80.toByte()),
                        "truncated 3-byte sequence" to byteArrayOf(0xE4.toByte(), 0xB8.toByte()),
                        "surrogate encoded as CESU" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
                    )
                invalids.forEach { (label, bytes) ->
                    val response = postRaw(HttpTurns.KEY_RUNTIME_1, bodyWithContentBytes(bytes))
                    assertEquals(HttpStatusCode.BadRequest, response.status, "$label 必须 400")
                    assertEquals("""{"error":"INVALID_TURN"}""", response.bodyAsText(), "$label 响应体必须固定脱敏")
                }
            }
            assertEquals(0L, countRows(fixture.contextPath), "非法 UTF-8 请求不得落库")
        }

    @Test
    fun `valid multibyte content including literal U+FFFD is accepted`() =
        withServer { fixture, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }

                val chinese = postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "zh", messages = listOf("user" to "中文记忆")))
                assertEquals(HttpStatusCode.Created, chinese.status)

                val emoji = postTurn(HttpTurns.KEY_RUNTIME_1, HttpTurns.json(sessionTurnId = "emoji", messages = listOf("user" to "🚀🧠")))
                assertEquals(HttpStatusCode.Created, emoji.status)

                // 合法的 U+FFFD（EF BF BD）是真实字符，不能被当成非法字节拒掉。
                val replacementChar =
                    postTurn(
                        HttpTurns.KEY_RUNTIME_1,
                        HttpTurns.json(
                            sessionTurnId = "fffd",
                            messages =
                                listOf(
                                    "user" to "\uFFFD",
                                ),
                        ),
                    )
                assertEquals(HttpStatusCode.Created, replacementChar.status)
            }
            assertEquals(3L, countRows(fixture.contextPath))
        }

    @Test
    fun `byte limit is enforced before utf8 validation`() =
        withServer { fixture, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }

                // 超过 2 MiB 且含非法字节：限额必须先命中，返回 413 而不是 400。
                val oversized = ByteArray(2 * 1024 * 1024 + 16) { 0xFF.toByte() }
                val response = postRaw(HttpTurns.KEY_RUNTIME_1, oversized)
                assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            }
            assertEquals(0L, countRows(fixture.contextPath))
        }

    @Test
    fun `instance small limit still rejects invalid utf8 within the limit as 400`() =
        ServerFixture().use { fixture ->
            fixture.initialize()
            val limits = IngestionLimits(maxTurnBytes = 512, maxMessages = 256, maxIdLength = 256)
            val context = fixture.newContext(limits)
            runBlocking { context.start() }
            try {
                testApplication {
                    application { ingestion(context, HttpTurns.registry()) }
                    // 512 字节限额内但含非法字节 → 400（限额没命中，进入严格解码）。
                    val body = bodyWithContentBytes(byteArrayOf(0xFF.toByte()))
                    assertTrue(body.size < 512, "测试体必须在实例限额内，实际 ${body.size}")
                    assertEquals(HttpStatusCode.BadRequest, postRaw(HttpTurns.KEY_RUNTIME_1, body).status)

                    // 超过 512 且含非法字节 → 413（限额先于解码）。
                    val oversized = ByteArray(600) { 0xFF.toByte() }
                    assertEquals(HttpStatusCode.PayloadTooLarge, postRaw(HttpTurns.KEY_RUNTIME_1, oversized).status)
                }
            } finally {
                context.close()
            }
            assertEquals(0L, countRows(fixture.contextPath))
        }

    @Test
    fun `rejections do not leak exception details or the raw bytes`() =
        withServer { _, context ->
            testApplication {
                application { ingestion(context, HttpTurns.registry()) }
                val response = postRaw(HttpTurns.KEY_RUNTIME_1, bodyWithContentBytes(byteArrayOf(0xFF.toByte())))
                val body = response.bodyAsText()
                assertEquals("""{"error":"INVALID_TURN"}""", body)
                assertFalse(body.contains("Exception"), "响应不得包含异常类名: $body")
                assertFalse(body.contains("jdbc"), "响应不得包含连接串: $body")
                assertFalse(body.contains(HttpTurns.KEY_RUNTIME_1), "响应不得回显 Key: $body")
                assertFalse(body.contains("\uFFFD"), "响应不得回显被替换的正文: $body")
            }
        }

    // ------------------------------------------------------------------ 夹具

    /** 把 JSON 里唯一的内容占位符 `X` 替换成给定的原始字节序列。 */
    private fun bodyWithContentBytes(bytes: ByteArray): ByteArray {
        val json = HttpTurns.json(messages = listOf("user" to "X")).toByteArray(Charsets.UTF_8)
        val index = json.indexOfFirst { it == 'X'.code.toByte() }
        require(index >= 0) { "placeholder not found" }
        return json.copyOfRange(0, index) + bytes + json.copyOfRange(index + 1, json.size)
    }

    private suspend fun ApplicationTestBuilder.postRaw(
        key: String,
        body: ByteArray,
    ): HttpResponse =
        client.post("/v1/conversations/turns") {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.postTurn(
        key: String,
        body: String,
    ): HttpResponse =
        client.post("/v1/conversations/turns") {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
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

    private fun countRows(path: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM conversation_turn").use { rs ->
                    assertTrue(rs.next())
                    rs.getLong(1)
                }
            }
        }
}
