package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextResourceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextResourceException
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import com.github.noahshen.alcyone.context.memory.api.StorageStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A04: Resource 提交前映射与提交后中断，以及限额/取消时的 Flow 释放。 */
class ReadHttpResourceTest {
    @Test
    fun `backend marker secrets never reach HTTP errors aborted exception chains or platform logs`() =
        kotlinx.coroutines.runBlocking {
            val markers = listOf("secret-body-marker", "secret-cause-marker", "secret-suppressed-marker")
            ServerLogCapture().use { capture ->
                val context = ResourceContext()

                fun failure() =
                    IllegalStateException(markers[0], IllegalArgumentException(markers[1])).apply {
                        addSuppressed(IllegalStateException(markers[2]))
                    }
                context.stream = flow { throw failure() }
                val server =
                    io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
                        installReadRoutes(context)
                    }
                try {
                    server.start(wait = false)
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .single()
                            .port

                    suspend fun request(expected: Int): String =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val connection =
                                java.net
                                    .URI("http://127.0.0.1:$port/v1/resources/content?uri=alcyone://resources/file")
                                    .toURL()
                                    .openConnection() as java.net.HttpURLConnection
                            try {
                                connection.setRequestProperty("Authorization", "Bearer $READ")
                                connection.connectTimeout = 5000
                                connection.readTimeout = 5000
                                assertEquals(expected, connection.responseCode)
                                if (expected == 500) {
                                    connection.errorStream.use { it.readBytes().decodeToString() }
                                } else {
                                    runCatching { connection.inputStream.use { it.readBytes().decodeToString() } }.getOrDefault("")
                                }
                            } finally {
                                connection.disconnect()
                            }
                        }
                    val early = request(500)
                    markers.forEach { assertFalse(early.contains(it)) }
                    context.stream =
                        flow {
                            emit("first".toByteArray())
                            throw failure()
                        }
                    val late = request(200)
                    markers.forEach { assertFalse(late.contains(it)) }
                    kotlinx.coroutines.withTimeout(5000) {
                        while (capture.logs.none { it.throwable != null }) delay(10)
                    }
                } finally {
                    server.stop(0, 1000)
                }
                val failures = capture.logs.mapNotNull { it.throwable }
                kotlin.test.assertTrue(failures.isNotEmpty(), "actual aborted writer exception must be captured")
                val logText = capture.logs.joinToString("\n") { it.formattedMessage + it.throwable?.stackTraceToString().orEmpty() }
                assertContains(
                    logText,
                    "resource stream aborted",
                    message = "must observe the committed-response failure, not unrelated logs",
                )
                markers.forEach { assertFalse(logText.contains(it), "secret marker escaped into platform logs") }
            }
        }

    @Test
    fun `real Local FS download exceeds sixteen MiB with matching digest`() =
        kotlinx.coroutines.runBlocking {
            ReadRuntimeFixture().use { fixture ->
                val bytes = ByteArray(16 * 1024 * 1024 + 123) { (it % 251).toByte() }
                java.nio.file.Files
                    .write(fixture.files.mountRoot.resolve("large.bin"), bytes)
                val context = fixture.start()
                val server =
                    io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
                        installReadRoutes(context, requestTimeoutMillis = 30000)
                    }
                try {
                    server.start(wait = false)
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .single()
                            .port
                    val actual =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val connection =
                                java.net
                                    .URI("http://127.0.0.1:$port/v1/resources/content?uri=alcyone://resources/large.bin")
                                    .toURL()
                                    .openConnection() as java.net.HttpURLConnection
                            try {
                                connection.setRequestProperty("Authorization", "Bearer $READ")
                                connection.connectTimeout = 5000
                                connection.readTimeout = 10000
                                assertEquals(200, connection.responseCode)
                                connection.inputStream.use { it.readBytes() }
                            } finally {
                                connection.disconnect()
                            }
                        }
                    assertEquals(bytes.size, actual.size)
                    assertEquals(sha256(bytes), sha256(actual))
                } finally {
                    server.stop(0, 1000)
                    context.closeAsync()
                }
            }
        }

    @Test
    fun `real Local FS size above configured limit is refused`() =
        testApplication {
            ReadRuntimeFixture().use { fixture ->
                java.nio.file.Files
                    .write(fixture.files.mountRoot.resolve("large.bin"), ByteArray(16 * 1024 * 1024 + 1))
                val context = fixture.start()
                try {
                    application { installReadRoutes(context, maxResourceBytes = 16L * 1024 * 1024) }
                    val response = resource("alcyone://resources/large.bin")
                    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
                    assertContains(response.bodyAsText(), "RESOURCE_LIMIT_EXCEEDED")
                } finally {
                    context.closeAsync()
                }
            }
        }

    @Test
    fun `real socket disconnect releases Runtime flow and server serves next request`() =
        kotlinx.coroutines.runBlocking {
            ReadRuntimeFixture().use { fixture ->
                java.nio.file.Files
                    .write(fixture.files.mountRoot.resolve("large.bin"), ByteArray(32 * 1024 * 1024) { 7 })
                val real = fixture.start()
                val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val released = kotlinx.coroutines.CompletableDeferred<Unit>()
                val releases = AtomicInteger()
                val readBytes =
                    java.util.concurrent.atomic
                        .AtomicLong()
                val observed =
                    object : AlcyoneContext by real {
                        override fun readStream(uri: String): Flow<ByteArray> =
                            flow {
                                try {
                                    real.readStream(uri).collect { chunk ->
                                        entered.complete(Unit)
                                        readBytes.addAndGet(chunk.size.toLong())
                                        emit(chunk)
                                        delay(2)
                                    }
                                } finally {
                                    releases.incrementAndGet()
                                    released.complete(Unit)
                                }
                            }
                    }
                val server =
                    io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
                        installReadRoutes(observed, requestTimeoutMillis = 30000)
                    }
                try {
                    server.start(wait = false)
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .single()
                            .port
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        java.net.Socket("127.0.0.1", port).use { socket ->
                            socket.soTimeout = 5000
                            val request =
                                "GET /v1/resources/content?uri=alcyone://resources/large.bin HTTP/1.1\r\n" +
                                    "Host: localhost\r\nAuthorization: Bearer $READ\r\n\r\n"
                            socket.getOutputStream().write(request.toByteArray())
                            val input = socket.getInputStream()
                            var header = ""
                            while (!header.endsWith("\r\n\r\n")) {
                                val byte = input.read()
                                check(byte >= 0)
                                header += byte.toChar()
                            }
                            kotlin.test.assertTrue(header.startsWith("HTTP/1.1 200"), header)
                            kotlin.test.assertTrue(input.read() >= 0)
                            socket.setSoLinger(true, 0)
                        }
                    }
                    kotlinx.coroutines.withTimeout(10000) {
                        entered.await()
                        released.await()
                    }
                    assertEquals(1, releases.get())
                    kotlin.test.assertTrue(readBytes.get() < 32L * 1024 * 1024, "disconnect must interrupt the real read before EOF")
                    val status =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val connection =
                                java.net
                                    .URI(
                                        "http://127.0.0.1:$port/v1/resources/meta?uri=alcyone://resources/large.bin",
                                    ).toURL()
                                    .openConnection() as java.net.HttpURLConnection
                            try {
                                connection.setRequestProperty("Authorization", "Bearer $READ")
                                connection.connectTimeout = 5000
                                connection.readTimeout = 5000
                                connection.responseCode.also { connection.inputStream.use { it.readBytes() } }
                            } finally {
                                connection.disconnect()
                            }
                        }
                    assertEquals(200, status)
                } finally {
                    server.stop(0, 1000)
                    real.closeAsync()
                }
            }
        }

    @Test
    fun `resource meta and content first chunk failures have fixed safe mappings`() =
        testApplication {
            val context = ResourceContext()
            application { installReadRoutes(context) }
            val cases =
                listOf(
                    ContextResourceErrorCode.INVALID_URI to (HttpStatusCode.BadRequest to "INVALID_URI"),
                    ContextResourceErrorCode.TYPE_MISMATCH to (HttpStatusCode.BadRequest to "RESOURCE_TYPE_MISMATCH"),
                    ContextResourceErrorCode.NOT_FOUND to (HttpStatusCode.NotFound to "RESOURCE_NOT_FOUND"),
                    ContextResourceErrorCode.UNAVAILABLE to (HttpStatusCode.ServiceUnavailable to "RESOURCE_UNAVAILABLE"),
                    ContextResourceErrorCode.OUT_OF_SCOPE to (HttpStatusCode.Forbidden to "OUT_OF_SCOPE"),
                )
            cases.forEach { (code, expected) ->
                val secret = "secret-${code.name}"
                val failure = ContextResourceException(code, secret)
                listOf("meta", "content").forEach { route ->
                    context.metaFailure = if (route == "meta") failure else null
                    context.stream = if (route == "content") flow { throw failure } else flowOf()
                    val response =
                        client.get("/v1/resources/$route") {
                            header(HttpHeaders.Authorization, "Bearer $READ")
                            url.parameters.append("uri", "alcyone://resources/file")
                        }
                    assertEquals(expected.first, response.status, "$route/$code")
                    val body = response.bodyAsText()
                    assertContains(body, expected.second, message = "$route/$code")
                    assertFalse(body.contains(secret))
                }
            }
        }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    @Test
    fun `empty file succeeds and exact known limit is accepted`() =
        testApplication {
            val context = ResourceContext()
            context.stream = flowOf()
            application { installReadRoutes(context, maxResourceBytes = 3) }

            val empty = resource("alcyone://resources/empty")
            assertEquals(HttpStatusCode.OK, empty.status)
            assertEquals(0, empty.bodyAsBytes().size)
            assertEquals(1, context.streamOpens.getAndSet(0))

            context.metadata = context.metadata.copy(sizeBytes = 3)
            context.stream = flowOf("abc".toByteArray())
            val exact = resource("alcyone://resources/exact")
            assertEquals(HttpStatusCode.OK, exact.status)
            assertEquals("abc", exact.bodyAsText())
            assertEquals(1, context.streamOpens.get())
        }

    @Test
    fun `known oversized stat is rejected without opening stream`() =
        testApplication {
            val context = ResourceContext()
            context.metadata = context.metadata.copy(sizeBytes = 4)
            application { installReadRoutes(context, maxResourceBytes = 3) }

            val response = resource("alcyone://resources/big")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertContains(response.bodyAsText(), "RESOURCE_LIMIT_EXCEEDED")
            assertEquals(0, context.streamOpens.get())
        }

    @Test
    fun `unexpectedly oversized stream aborts after commit and releases flow once`() =
        testApplication {
            val releases = AtomicInteger()
            val context = ResourceContext()
            context.metadata = context.metadata.copy(sizeBytes = null)
            context.stream =
                flow {
                    try {
                        emit("abc".toByteArray())
                        emit("def".toByteArray())
                    } finally {
                        releases.incrementAndGet()
                    }
                }
            application { installReadRoutes(context, maxResourceBytes = 5) }

            val response = resource("alcyone://resources/unknown")
            assertEquals(HttpStatusCode.OK, response.status)
            val bytes = runCatching { response.bodyAsBytes() }.getOrDefault(byteArrayOf())
            assertFalse(bytes.contentEquals("abcdef".toByteArray()), "超限流不得伪装完整下载")
            assertEquals(1, releases.get())
        }

    @Test
    fun `failure before first chunk remains JSON while later failure truncates committed stream`() =
        testApplication {
            val context = ResourceContext()
            context.stream =
                flow {
                    throw ContextResourceException(ContextResourceErrorCode.NOT_FOUND, "secret-path")
                }
            application { installReadRoutes(context) }

            val early = resource("alcyone://resources/missing")
            assertEquals(HttpStatusCode.NotFound, early.status)
            val earlyBody = early.bodyAsText()
            assertContains(earlyBody, "RESOURCE_NOT_FOUND")
            assertFalse(earlyBody.contains("secret-path"))

            val releases = AtomicInteger()
            context.stream =
                flow {
                    try {
                        emit("first".toByteArray())
                        throw ContextResourceException(ContextResourceErrorCode.UNAVAILABLE, "backend-secret")
                    } finally {
                        releases.incrementAndGet()
                    }
                }
            val late = resource("alcyone://resources/later")
            assertEquals(HttpStatusCode.OK, late.status)
            val lateBody = runCatching { late.bodyAsText() }.getOrDefault("")
            assertFalse(lateBody.contains("RESOURCE_UNAVAILABLE"))
            assertFalse(lateBody.contains("backend-secret"))
            assertEquals(1, releases.get())
        }

    @Test
    fun `request deadline aborts committed stream and releases collection once`() =
        testApplication {
            val releases = AtomicInteger()
            val context = ResourceContext()
            context.stream =
                flow {
                    try {
                        emit("first".toByteArray())
                        delay(500)
                        emit("never".toByteArray())
                    } finally {
                        releases.incrementAndGet()
                    }
                }
            application { installReadRoutes(context, requestTimeoutMillis = 25) }

            val response = resource("alcyone://resources/slow")
            assertEquals(HttpStatusCode.OK, response.status)
            runCatching { response.bodyAsBytes() }
            assertEquals(1, releases.get())
        }

    private fun Application.installReadRoutes(
        context: AlcyoneContext,
        maxResourceBytes: Long? = null,
        requestTimeoutMillis: Long = 2_000,
    ) {
        val config =
            ServerConfig(
                clients = emptyMap(),
                readKey = READ,
                adminKey = ADMIN,
                limits = ServerLimits(4_096, maxResourceBytes, requestTimeoutMillis),
            )
        val registry = IngestionClientRegistry.from(config)
        readRoutes(context, registry.auth, config.limits)
    }

    private suspend fun ApplicationTestBuilder.resource(uri: String) =
        client.get("/v1/resources/content") {
            header(HttpHeaders.Authorization, "Bearer $READ")
            url.parameters.append("uri", uri)
        }

    private class ResourceContext : AlcyoneContext {
        val streamOpens = AtomicInteger()
        var metadata = ResourceMetadata("alcyone://resources/file", sizeBytes = null, modifiedAt = null)
        var metaFailure: ContextResourceException? = null
        var stream: Flow<ByteArray> = flowOf()

        override suspend fun loadMeta(uri: String): ResourceMetadata {
            metaFailure?.let { throw it }
            return metadata.copy(uri = uri)
        }

        override fun readStream(uri: String): Flow<ByteArray> {
            streamOpens.incrementAndGet()
            return stream
        }

        override suspend fun ingestConversation(turn: ConversationTurn): IngestResult = error("not used")

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus =
            ContextStatus(ContextLifecycleState.RUNNING, emptyMap(), StorageStatus("test.db", databaseInitialized = true))
    }

    companion object {
        private const val READ = "read-resource-secret"
        private const val ADMIN = "admin-resource-secret"
    }
}
