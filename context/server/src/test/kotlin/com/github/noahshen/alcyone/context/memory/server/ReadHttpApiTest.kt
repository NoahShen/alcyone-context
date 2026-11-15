package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.BootstrapContext
import com.github.noahshen.alcyone.context.memory.api.ContextResourceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextResourceException
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import com.github.noahshen.alcyone.context.memory.runtime.IngestionLimitAware
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** A01/A02/A03/A04 HTTP 边界回归。 */
class ReadHttpApiTest {
    @Test
    fun `real Runtime serves every read route and unauthorized ingestion leaves SQLite empty`() =
        testApplication {
            ReadRuntimeFixture().use { fixture ->
                val context = fixture.start()
                try {
                    java.nio.file.Files
                        .writeString(fixture.files.mountRoot.resolve("small.txt"), "real resource")
                    application { installRoutes(context) }
                    val bootstrap = client.get("/v1/context/bootstrap") { bearer(READ) }
                    assertEquals(HttpStatusCode.OK, bootstrap.status)
                    assertContains(bootstrap.bodyAsText(), "memory_map")
                    val results = search("{\"query\":\"organized\"}")
                    assertEquals(HttpStatusCode.OK, results.status)
                    assertContains(results.bodyAsText(), "organized content")
                    assertEquals(2, fixture.modelCalls.get())
                    val noMatch = search("{\"query\":\"no-match\"}")
                    assertEquals(HttpStatusCode.OK, noMatch.status)
                    assertEquals("[]", noMatch.bodyAsText())
                    assertEquals(3, fixture.modelCalls.get(), "empty first-stage candidate IDs short-circuit the second model call")
                    listOf("memories" to "O-1", "raw-memories" to "R-1").forEach { (route, id) ->
                        val hit = client.get("/v1/$route/$id") { bearer(READ) }
                        assertEquals(HttpStatusCode.OK, hit.status)
                        assertContains(hit.bodyAsText(), id)
                        val miss = client.get("/v1/$route/missing") { bearer(READ) }
                        assertEquals(HttpStatusCode.NotFound, miss.status)
                        assertContains(miss.bodyAsText(), "MEMORY_NOT_FOUND")
                    }
                    val meta = client.get("/v1/resources/meta?uri=alcyone://resources/small.txt") { bearer(READ) }
                    assertEquals(HttpStatusCode.OK, meta.status)
                    assertContains(meta.bodyAsText(), "\"size_bytes\":13")
                    val content = client.get("/v1/resources/content?uri=alcyone://resources/small.txt") { bearer(READ) }
                    assertEquals(HttpStatusCode.OK, content.status)
                    assertEquals("real resource", content.bodyAsText())
                    listOf(null, "unknown", READ, ADMIN).forEach { token ->
                        val response =
                            client.post("/v1/conversations/turns") {
                                bearer(token)
                                setBody(HttpTurns.json())
                            }
                        assertEquals(
                            if (token == READ ||
                                token == ADMIN
                            ) {
                                HttpStatusCode.Forbidden
                            } else {
                                HttpStatusCode.Unauthorized
                            },
                            response.status,
                        )
                        assertEquals(0, fixture.turnCount())
                    }
                    listOf("bearer $INGESTION", " Bearer $INGESTION ", "Bearer  $INGESTION").forEachIndexed { index, value ->
                        val response =
                            client.post("/v1/conversations/turns") {
                                header(HttpHeaders.Authorization, value)
                                setBody(HttpTurns.json(sessionTurnId = "compat-$index"))
                            }
                        assertEquals(HttpStatusCode.Created, response.status)
                    }
                    assertEquals(3, fixture.turnCount())
                } finally {
                    context.closeAsync()
                }
            }
        }

    @Test
    fun `every protected route rejects missing and wrong credentials before SDK work and accepts its own class`() =
        testApplication {
            val context = FakeContext()
            application { installRoutes(context) }

            val routes =
                listOf(
                    ProtectedRoute(
                        name = "GET /v1/context/bootstrap",
                        allowedToken = READ,
                        forbiddenTokens = listOf(INGESTION, ADMIN),
                        expectedSuccess = HttpStatusCode.OK,
                        expectedSdkAccesses = 1,
                    ) { token ->
                        client.get("/v1/context/bootstrap") { bearer(token) }
                    },
                    ProtectedRoute(
                        name = "POST /v1/memories/search",
                        allowedToken = READ,
                        forbiddenTokens = listOf(INGESTION, ADMIN),
                        expectedSuccess = HttpStatusCode.OK,
                        expectedSdkAccesses = 1,
                    ) { token ->
                        client.post("/v1/memories/search") {
                            bearer(token)
                            setBody("{\"query\":\"needle\"}")
                        }
                    },
                    ProtectedRoute(
                        name = "GET /v1/memories/{id}",
                        allowedToken = READ,
                        forbiddenTokens = listOf(INGESTION, ADMIN),
                        expectedSuccess = HttpStatusCode.OK,
                        expectedSdkAccesses = 1,
                    ) { token ->
                        client.get("/v1/memories/organized-1") { bearer(token) }
                    },
                    ProtectedRoute(
                        name = "GET /v1/raw-memories/{id}",
                        allowedToken = READ,
                        forbiddenTokens = listOf(INGESTION, ADMIN),
                        expectedSuccess = HttpStatusCode.OK,
                        expectedSdkAccesses = 1,
                    ) { token ->
                        client.get("/v1/raw-memories/raw-1") { bearer(token) }
                    },
                    ProtectedRoute(
                        name = "GET /v1/resources/meta",
                        allowedToken = READ,
                        forbiddenTokens = listOf(INGESTION, ADMIN),
                        expectedSuccess = HttpStatusCode.OK,
                        expectedSdkAccesses = 1,
                    ) { token ->
                        client.get("/v1/resources/meta?uri=alcyone%3A%2F%2Fresources%2Ffile") { bearer(token) }
                    },
                    ProtectedRoute(
                        name = "GET /v1/resources/content",
                        allowedToken = READ,
                        forbiddenTokens = listOf(INGESTION, ADMIN),
                        expectedSuccess = HttpStatusCode.OK,
                        expectedSdkAccesses = 2,
                    ) { token ->
                        client.get("/v1/resources/content?uri=alcyone%3A%2F%2Fresources%2Ffile") { bearer(token) }
                    },
                    ProtectedRoute(
                        name = "POST /v1/conversations/turns",
                        allowedToken = INGESTION,
                        forbiddenTokens = listOf(READ, ADMIN),
                        expectedSuccess = HttpStatusCode.Created,
                        expectedSdkAccesses = 1,
                    ) { token ->
                        client.post("/v1/conversations/turns") {
                            bearer(token)
                            setBody(HttpTurns.json())
                        }
                    },
                )

            routes.forEach { route ->
                val rejectedBefore = context.sdkAccessCount()
                assertEquals(HttpStatusCode.Unauthorized, route.call(this, null).status, "${route.name}: missing token")
                assertEquals(rejectedBefore, context.sdkAccessCount(), "${route.name}: missing token reached SDK")

                assertEquals(
                    HttpStatusCode.Unauthorized,
                    route.call(this, "not-a-registered-token").status,
                    "${route.name}: unknown token",
                )
                assertEquals(
                    rejectedBefore,
                    context.sdkAccessCount(),
                    "${route.name}: unknown token reached SDK",
                )

                route.forbiddenTokens.forEach { token ->
                    assertEquals(HttpStatusCode.Forbidden, route.call(this, token).status, "${route.name}: wrong credential class")
                    assertEquals(
                        rejectedBefore,
                        context.sdkAccessCount(),
                        "${route.name}: forbidden token reached SDK",
                    )
                }

                val acceptedBefore = context.sdkAccessCount()
                assertEquals(route.expectedSuccess, route.call(this, route.allowedToken).status, "${route.name}: allowed class")
                assertEquals(
                    acceptedBefore + route.expectedSdkAccesses,
                    context.sdkAccessCount(),
                    "${route.name}: allowed request did not reach the expected SDK operation exactly once",
                )
            }
        }

    @Test
    fun `auth classes are checked before body query or SDK work`() =
        testApplication {
            val context = FakeContext()
            application { installRoutes(context) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/context/bootstrap").status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/v1/context/bootstrap") { header(HttpHeaders.Authorization, "Bearer $INGESTION") }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/v1/context/bootstrap") { header(HttpHeaders.Authorization, "Bearer $ADMIN") }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .post("/v1/conversations/turns") {
                        header(HttpHeaders.Authorization, "Bearer $READ")
                        setBody("{}")
                    }.status,
            )
            assertEquals(0, context.ingestCalls)
            assertEquals(0, context.bootstrapCalls)

            val success =
                client.get("/v1/context/bootstrap") { header(HttpHeaders.Authorization, "Bearer $READ") }
            assertEquals(HttpStatusCode.OK, success.status)
            assertContains(success.bodyAsText(), "\"memory_map\":null")
            assertEquals(1, context.bootstrapCalls)
        }

    @Test
    fun `search exact byte limits and all domain forms reach SDK with precise defaults`() =
        testApplication {
            val context = FakeContext()
            application { installRoutes(context, limits(maxSearchBodyBytes = 128)) }
            val forms =
                listOf(
                    "" to null,
                    ",\"domains\":null" to null,
                    ",\"domains\":[]" to emptyList(),
                    ",\"domains\":[\"a\",\"b\"]" to listOf("a", "b"),
                )
            forms.forEach { (field, expected) ->
                assertEquals(HttpStatusCode.OK, search("{\"query\":\"q\"$field}").status)
                assertEquals("q" to expected, context.lastSearch)
                assertEquals(10, context.lastLimit)
            }
            assertEquals(HttpStatusCode.OK, search("{\"query\":\"q\",\"limit\":3}").status)
            assertEquals(3, context.lastLimit)
            val exact = "{\"query\":\"q\"}".padEnd(128)
            listOf<Long?>(128, null).forEach { declared ->
                val before = context.searchCalls
                val accepted =
                    client.post("/v1/memories/search") {
                        bearer(READ)
                        setBody(RawBody(exact.toByteArray(), declared))
                    }
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertEquals(before + 1, context.searchCalls)
            }
            listOf<Long?>(129, null, 1).forEach { declared ->
                val before = context.searchCalls
                val rejected =
                    client.post("/v1/memories/search") {
                        bearer(READ)
                        setBody(RawBody((exact + " ").toByteArray(), declared))
                    }
                assertEquals(HttpStatusCode.PayloadTooLarge, rejected.status)
                assertEquals(before, context.searchCalls)
            }
        }

    @Test
    fun `search rejects duplicate unknown and malformed keys before SDK dispatch`() =
        testApplication {
            val context = FakeContext()
            application { installRoutes(context, limits(maxSearchBodyBytes = 512)) }

            val invalidBodies =
                listOf(
                    "{\"query\":\"a\",\"query\":\"b\"}",
                    "{\"query\":\"a\",\"extra\":1}",
                    "{\"query\":\"a\"} trailing",
                    "{\"query\":7}",
                    "{\"query\":\"a\",\"domains\":\"domain\"}",
                )
            invalidBodies.forEach { body ->
                val response = search(body)
                assertEquals(HttpStatusCode.BadRequest, response.status, "body=$body")
                assertEquals(0, context.searchCalls)
            }
        }

    @Test
    fun `search counts actual chunked bytes and delegates valid filters`() =
        testApplication {
            val context = FakeContext()
            val small = "{\"query\":\"needle\",\"domains\":[\"work\"],\"limit\":1}"
            application { installRoutes(context, limits(maxSearchBodyBytes = small.toByteArray().size)) }

            val body = "x".repeat(small.toByteArray().size + 1)
            val tooLarge =
                client.post("/v1/memories/search") {
                    header(HttpHeaders.Authorization, "Bearer $READ")
                    setBody(RawBody(body.toByteArray(), declaredLength = null))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertEquals(0, context.searchCalls)

            val success = search(small)
            assertEquals(HttpStatusCode.OK, success.status)
            assertEquals(1, context.searchCalls)
            assertEquals("needle" to listOf("work"), context.lastSearch)
            assertContains(success.bodyAsText(), "\"id\":\"organized-1\"")
        }

    @Test
    fun `search body and SDK calls share the server request deadline`() =
        testApplication {
            val context = FakeContext(searchDelayMillis = 60)
            application { installRoutes(context, limits(requestTimeoutMillis = 100)) }

            val slowBody =
                client.post("/v1/memories/search") {
                    header(HttpHeaders.Authorization, "Bearer $READ")
                    setBody(RawBody("{\"query\":\"late\"}".toByteArray(), declaredLength = null, delayMillis = 500))
                }
            assertEquals(HttpStatusCode.GatewayTimeout, slowBody.status)
            assertEquals(0, context.searchCalls)

            val delayedBody =
                client.post("/v1/memories/search") {
                    header(HttpHeaders.Authorization, "Bearer $READ")
                    setBody(RawBody("{\"query\":\"late\"}".toByteArray(), declaredLength = null, delayMillis = 60))
                }
            assertEquals(HttpStatusCode.GatewayTimeout, delayedBody.status)
            assertEquals(1, context.searchCalls, "body 与 SDK 必须消费同一个请求期限")
        }

    @Test
    fun `server deadline classification never swallows SDK timeout cancellation`() =
        runBlocking {
            val sdkTimeout =
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(1) { delay(1_000) }
                }
            val context = FakeContext()
            context.bootstrapFailure = sdkTimeout

            val propagated =
                assertFailsWith<TimeoutCancellationException> {
                    withServerRequestDeadline(5_000) { context.bootstrap() }
                }
            assertEquals(sdkTimeout.message, propagated.message, "SDK 自身 TCE 必须以相同语义向上传播")

            val immediate =
                assertFailsWith<TimeoutCancellationException> {
                    withServerRequestDeadline(1) { throw sdkTimeout }
                }
            assertEquals(sdkTimeout.message, immediate.message, "已完成的 SDK 结果必须优先于 watchdog")

            val requestDeadline =
                assertFails {
                    withServerRequestDeadline(5) {
                        withTimeout(5_000) { delay(1_000) }
                    }
                }
            assertIs<ServerRequestDeadlineException>(requestDeadline)

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) {
                    withServerRequestDeadline(5_000) { awaitCancellation() }
                }
            }
            Unit
        }

    @Test
    fun `SDK-owned timeout cancellation is propagated unchanged`() {
        testApplication {
            val sdkTimeout =
                runBlocking {
                    assertFailsWith<TimeoutCancellationException> {
                        withTimeout(1) { delay(1_000) }
                    }
                }
            val propagated = CompletableDeferred<Throwable>()
            val context = FakeContext()
            context.bootstrapFailure = sdkTimeout
            application {
                intercept(ApplicationCallPipeline.Call) {
                    try {
                        proceed()
                    } catch (failure: Throwable) {
                        propagated.complete(failure)
                        throw failure
                    }
                }
                installRoutes(context, limits(requestTimeoutMillis = 5_000))
            }

            client.get("/v1/context/bootstrap") { header(HttpHeaders.Authorization, "Bearer $READ") }
            val routeFailure = propagated.await()
            assertIs<TimeoutCancellationException>(routeFailure)
            assertEquals(1, context.bootstrapCalls)
        }
    }

    @Test
    fun `retrieval DTOs use stable snake case and source discriminators`() =
        testApplication {
            val context = FakeContext()
            application { installRoutes(context) }

            val raw = client.get("/v1/raw-memories/raw-1") { header(HttpHeaders.Authorization, "Bearer $READ") }
            assertEquals(HttpStatusCode.OK, raw.status)
            val rawText = raw.bodyAsText()
            assertContains(rawText, "\"generated_by\":\"EXTRACTION\"")
            assertContains(rawText, "\"type\":\"conversation_turn\"")
            assertContains(rawText, "\"session_turn_id\":\"turn-1\"")
            assertContains(rawText, "\"created_at\":")

            val missing = client.get("/v1/memories/missing") { header(HttpHeaders.Authorization, "Bearer $READ") }
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertContains(missing.bodyAsText(), "\"MEMORY_NOT_FOUND\"")
        }

    @Test
    fun `resource metadata failures map without exposing backend messages`() =
        testApplication {
            val context = FakeContext()
            context.metaFailure = ContextResourceException(ContextResourceErrorCode.OUT_OF_SCOPE, "secret path")
            application { installRoutes(context) }

            val response =
                client.get("/v1/resources/meta?uri=alcyone%3A%2F%2Foutside") {
                    header(HttpHeaders.Authorization, "Bearer $READ")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            val text = response.bodyAsText()
            assertContains(text, "OUT_OF_SCOPE")
            assertFalse(text.contains("secret path"))
        }

    @Test
    fun `resource content prefetches and refuses oversized first chunk before commit`() =
        testApplication {
            val context = FakeContext()
            context.metadata = ResourceMetadata("alcyone://resources/big", sizeBytes = null, modifiedAt = null)
            context.stream = flowOf("too-big".toByteArray())
            application { installRoutes(context, limits(maxResourceBytes = 3)) }

            val oversized =
                client.get("/v1/resources/content?uri=alcyone%3A%2F%2Fresources%2Fbig") {
                    header(HttpHeaders.Authorization, "Bearer $READ")
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            assertContains(oversized.bodyAsText(), "RESOURCE_LIMIT_EXCEEDED")
            assertEquals(1, context.streamOpens)
        }

    @Test
    fun `search retrieval failures map to fixed unavailable response without secret text`() =
        testApplication {
            val context = FakeContext()
            application { installRoutes(context) }
            val secret = "retrieval-secret-marker"
            listOf(ContextRetrievalErrorCode.UNAVAILABLE, ContextRetrievalErrorCode.CONFIGURATION).forEach { code ->
                context.searchFailure = ContextRetrievalException(code, secret)
                val response = search("{\"query\":\"must-not-be-empty\"}")
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                val body = response.bodyAsText()
                assertContains(body, "RETRIEVAL_UNAVAILABLE")
                assertFalse(body.contains("\"id\":"))
                assertFalse(body.contains(secret))
            }
        }

    @Test
    fun `search rejects missing query null query and malformed UTF8 without SDK calls`() =
        testApplication {
            val context = FakeContext()
            application { installRoutes(context) }
            val invalidBodies =
                listOf(
                    "{}".toByteArray(),
                    "{\"query\":null}".toByteArray(),
                    byteArrayOf(
                        '{'.code.toByte(),
                        '"'.code.toByte(),
                        'q'.code.toByte(),
                        'u'.code.toByte(),
                        'e'.code.toByte(),
                        'r'.code.toByte(),
                        'y'.code.toByte(),
                        '"'.code.toByte(),
                        ':'.code.toByte(),
                        '"'.code.toByte(),
                        0xE4.toByte(),
                        0xB8.toByte(),
                        '"'.code.toByte(),
                        '}'.code.toByte(),
                    ),
                    byteArrayOf(
                        '{'.code.toByte(),
                        '"'.code.toByte(),
                        'q'.code.toByte(),
                        'u'.code.toByte(),
                        'e'.code.toByte(),
                        'r'.code.toByte(),
                        'y'.code.toByte(),
                        '"'.code.toByte(),
                        ':'.code.toByte(),
                        '"'.code.toByte(),
                        0xFF.toByte(),
                        '"'.code.toByte(),
                        '}'.code.toByte(),
                    ),
                )
            invalidBodies.forEach { bytes ->
                val before = context.sdkAccessCount()
                val response =
                    client.post("/v1/memories/search") {
                        bearer(READ)
                        setBody(RawBody(bytes, declaredLength = null))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertContains(response.bodyAsText(), "INVALID_REQUEST")
                assertEquals(before, context.sdkAccessCount())
                assertEquals(0, context.searchCalls)
            }
        }

    @Test
    fun `resource first chunk failure returns JSON but later failure aborts the committed stream`() =
        testApplication {
            val context = FakeContext()
            context.stream =
                flow<ByteArray> {
                    throw ContextResourceException(ContextResourceErrorCode.NOT_FOUND, "missing")
                }
            application { installRoutes(context) }

            val early =
                client.get("/v1/resources/content?uri=alcyone%3A%2F%2Fresources%2Fmissing") {
                    header(HttpHeaders.Authorization, "Bearer $READ")
                }
            assertEquals(HttpStatusCode.NotFound, early.status)
            assertContains(early.bodyAsText(), "RESOURCE_NOT_FOUND")

            context.stream =
                flow {
                    emit("first".toByteArray())
                    throw ContextResourceException(ContextResourceErrorCode.UNAVAILABLE, "later")
                }
            val late =
                client.get("/v1/resources/content?uri=alcyone%3A%2F%2Fresources%2Flater") {
                    header(HttpHeaders.Authorization, "Bearer $READ")
                }
            assertEquals(HttpStatusCode.OK, late.status)
            val lateBody = runCatching { late.bodyAsText() }.getOrNull().orEmpty()
            assertFalse(lateBody.contains("RESOURCE_UNAVAILABLE"), "headers 提交后不得回写 JSON 错误")
            assertFalse(lateBody.contains("later"), "不得泄露后端异常文本")
        }

    private fun Application.installRoutes(
        context: AlcyoneContext,
        limits: ServerLimits = defaultLimits,
    ) {
        val config =
            ServerConfig(
                clients = mapOf("runtime-1" to IngestionClientConfig("runtime-1", "pi", INGESTION)),
                readKey = READ,
                adminKey = ADMIN,
                limits = limits,
            )
        val registry = IngestionClientRegistry.from(config)
        ingestion(context, registry)
        readRoutes(context, registry.auth, limits)
    }

    private class RawBody(
        private val bytes: ByteArray,
        private val declaredLength: Long?,
        private val delayMillis: Long = 0,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json
        override val contentLength: Long? = declaredLength

        override suspend fun writeTo(channel: ByteWriteChannel) {
            if (delayMillis > 0) delay(delayMillis)
            channel.writeFully(bytes)
        }
    }

    private class ProtectedRoute(
        val name: String,
        val allowedToken: String,
        val forbiddenTokens: List<String>,
        val expectedSuccess: HttpStatusCode,
        val expectedSdkAccesses: Int,
        val call: suspend ApplicationTestBuilder.(token: String?) -> HttpResponse,
    )

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String?) {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private class FakeContext(
        private val bootstrapDelayMillis: Long = 0,
        var searchDelayMillis: Long = 0,
        var searchFailure: Throwable? = null,
    ) : AlcyoneContext,
        IngestionLimitAware {
        override val ingestionMaxTurnBytes: Int = 1_000
        var bootstrapCalls = 0
        var ingestCalls = 0
        var searchCalls = 0
        var getMemoryCalls = 0
        var rawMemoryCalls = 0
        var loadMetaCalls = 0
        var streamOpens = 0
        var lastLimit: Int? = null
        var lastSearch: Pair<String, List<String>?>? = null
        var metadata = ResourceMetadata("alcyone://resources/file", sizeBytes = 5, modifiedAt = null)
        var metaFailure: Throwable? = null
        var bootstrapFailure: Throwable? = null
        var stream: Flow<ByteArray> = flowOf("hello".toByteArray())

        override suspend fun bootstrap(): BootstrapContext {
            bootstrapCalls++
            bootstrapFailure?.let { throw it }
            if (bootstrapDelayMillis > 0) delay(bootstrapDelayMillis)
            return BootstrapContext(null, MapStatus.UNKNOWN, BootstrapContext.DEFAULT_CAPABILITIES)
        }

        override suspend fun ingestConversation(turn: ConversationTurn): IngestResult {
            ingestCalls++
            return IngestResult(1L, IngestStatus.CREATED)
        }

        override suspend fun searchMemory(
            query: String,
            domains: List<String>?,
            limit: Int,
        ): List<OrganizedMemory> {
            searchCalls++
            lastSearch = query to domains
            lastLimit = limit
            if (searchDelayMillis > 0) delay(searchDelayMillis)
            searchFailure?.let { throw it }
            return listOf(ORGANIZED)
        }

        override suspend fun getMemory(id: String): OrganizedMemory? {
            getMemoryCalls++
            return if (id == "missing") null else ORGANIZED
        }

        override suspend fun getRawMemory(id: String): RawMemory? {
            rawMemoryCalls++
            return RAW
        }

        override suspend fun loadMeta(uri: String): ResourceMetadata {
            loadMetaCalls++
            metaFailure?.let { throw it }
            return metadata.copy(uri = uri)
        }

        override fun readStream(uri: String): Flow<ByteArray> {
            streamOpens++
            return stream
        }

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus = error("not used")

        fun sdkAccessCount(): Int =
            bootstrapCalls + ingestCalls + searchCalls + getMemoryCalls + rawMemoryCalls + loadMetaCalls + streamOpens
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.search(body: String) =
        client.post("/v1/memories/search") {
            header(HttpHeaders.Authorization, "Bearer $READ")
            setBody(RawBody(body.toByteArray(), declaredLength = null))
        }

    companion object {
        private const val INGESTION = "ingestion-secret"
        private const val READ = "read-secret"
        private const val ADMIN = "admin-secret"

        private fun limits(
            maxSearchBodyBytes: Int = 4_096,
            maxResourceBytes: Long? = null,
            requestTimeoutMillis: Long = 2_000,
        ) = ServerLimits(maxSearchBodyBytes, maxResourceBytes, requestTimeoutMillis)

        private val defaultLimits = limits()
        private val ORGANIZED =
            OrganizedMemory(
                id = "organized-1",
                content = "remembered",
                domains = listOf("work"),
                rawMemoryIds = listOf("raw-1"),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )
        private val RAW =
            RawMemory(
                id = "raw-1",
                content = "source",
                generatedBy = MemoryOrigin.EXTRACTION,
                sources = listOf(MemorySourceRef.ConversationTurnRef("runtime-1", "agent-1", "session-1", "turn-1")),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
    }
}
