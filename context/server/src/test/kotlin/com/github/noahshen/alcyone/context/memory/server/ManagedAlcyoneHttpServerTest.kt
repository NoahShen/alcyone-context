package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.DreamingResult
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import io.ktor.client.request.prepareGet
import io.ktor.server.application.call
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class ManagedAlcyoneHttpServerTest {
    @Test
    fun `concurrent start and close share one context and one cleanup outcome`() =
        runBlocking {
            val context = LifecycleContext()
            context.startGate = CompletableDeferred()
            context.closeGate = CompletableDeferred()
            val port = freePort()
            val server = managed(context, port)
            val starts = List(8) { async(Dispatchers.IO) { server.start() } }
            context.startEntered.await()
            assertEquals(1, context.createCalls.get())
            assertEquals(1, context.startCalls.get())
            context.startGate!!.complete(Unit)
            starts.awaitAll()
            server.start()
            assertEquals(1, context.startCalls.get())

            val closes = List(6) { async(Dispatchers.IO) { server.closeAsync() } }
            context.closeEntered.await()
            assertEquals(1, context.closeCalls.get())
            closes.forEach { assertTrue(it.isActive) }
            context.closeGate!!.complete(Unit)
            closes.awaitAll()
            server.closeAsync()
            assertEquals(1, context.closeCalls.get())
            assertEquals(listOf("start", "listen", "close"), context.events)
        }

    @Test
    fun `controlled listen failure and context start failure clean up in reverse with primary failure retained`() =
        runBlocking {
            val listeningContext = LifecycleContext()
            val config = fakeConfig()
            val server =
                ManagedAlcyoneHttpServer.createForTesting(
                    config,
                    ServerConfig(port = freePort(), adminKey = ADMIN),
                    contextFactory = {
                        listeningContext.createCalls.incrementAndGet()
                        listeningContext
                    },
                    engineFactory = { _, _, _ ->
                        io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) {
                            throw IllegalStateException("controlled-listen-failure")
                        }
                    },
                )
            assertFailsWith<Throwable> { server.start() }
            assertEquals(1, listeningContext.closeCalls.get())
            repeat(2) { assertFailsWith<Throwable> { server.closeAsync() } }
            assertEquals(1, listeningContext.closeCalls.get())

            val failed = LifecycleContext()
            failed.startFailure = IllegalStateException("primary-start")
            failed.closeFailure = IllegalStateException("cleanup-close")
            val partial = managed(failed, freePort())
            val failure = assertFailsWith<IllegalStateException> { partial.start() }
            assertEquals("primary-start", failure.message)
            assertTrue(failure.hasMarker("cleanup-close"))
            assertEquals(1, failed.closeCalls.get())
            assertFalse(failed.engineCreated.get())
        }

    @Test
    fun `real occupied port fails after Context start and repeated close does not release twice`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val process =
            ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.github.noahshen.alcyone.context.memory.server.ManagedAlcyoneHttpServerTest",
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "real bind probe timed out: $output")
        assertEquals(0, process.exitValue(), output)
    }

    internal fun realBindProbe() =
        runBlocking {
            val occupied = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            val context = LifecycleContext()
            val server = managed(context, occupied.localPort)
            try {
                assertFailsWith<Throwable> { server.start() }
                assertEquals(1, context.startCalls.get())
                assertEquals(1, context.closeCalls.get())
                repeat(3) { assertFailsWith<Throwable> { server.closeAsync() } }
                assertEquals(1, context.closeCalls.get())
            } finally {
                occupied.close()
            }
        }

    @Test
    fun `start failure close replays original failure and cleanup only once`() =
        runBlocking {
            val context = LifecycleContext()
            context.startFailure = IllegalStateException("start-failed")
            val server = managed(context, freePort())
            val startFailure = assertFailsWith<Throwable> { server.start() }
            assertEquals(1, context.closeCalls.get())
            val closeFailure = assertFailsWith<Throwable> { server.closeAsync() }
            assertTrue(closeFailure.hasMarker("start-failed"))
            assertFailsWith<Throwable> { server.closeAsync() }
            assertEquals(1, context.closeCalls.get())
            assertTrue(startFailure.hasMarker("start-failed"))
        }

    @Test
    fun `real CIO close drains manual run before runtime and caller cancellation does not cancel cleanup`() =
        runBlocking {
            val context = LifecycleContext()
            val port = freePort()
            val server = managed(context, port)
            server.start()
            val client = HttpClient.newHttpClient()
            val response =
                client.sendAsync(
                    HttpRequest
                        .newBuilder(URI.create("http://127.0.0.1:$port/v1/admin/dreaming"))
                        .header("Authorization", "Bearer $ADMIN")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            context.dreamingEntered.await()

            val closeOwner = async(Dispatchers.IO) { server.closeAsync() }
            withTimeoutOrNull(20) { server.closeAsync() }
            assertTrue(closeOwner.isActive)
            assertEquals(0, context.closeCalls.get())
            context.dreamingGate.complete(Unit)
            assertEquals(200, withContext(Dispatchers.IO) { response.get() }.statusCode())
            withTimeout(5_000) { closeOwner.await() }
            server.closeAsync()
            assertEquals(1, context.closeCalls.get())
            assertEquals(listOf("start", "listen", "dreaming-enter", "dreaming-finish", "close"), context.events)
        }

    @Test
    fun `real CIO streaming finishes normally before runtime close`() =
        runBlocking {
            val context = LifecycleContext(streaming = true)
            val port = freePort()
            val server = managed(context, port)
            server.start()
            io.ktor.client
                .HttpClient(io.ktor.client.engine.cio.CIO) {
                    install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = Long.MAX_VALUE }
                }.use { client ->
                    client
                        .prepareGet("http://127.0.0.1:$port/v1/resources/content?uri=alcyone://resources/a") {
                            headers.append("Authorization", "Bearer $READ")
                        }.execute { response ->
                            assertEquals(io.ktor.http.HttpStatusCode.OK, response.status)
                            val close = async(Dispatchers.IO) { server.closeAsync() }
                            delay(50)
                            assertTrue(close.isActive)
                            assertEquals(0, context.closeCalls.get())
                            context.streamRelease.complete(Unit)
                            withTimeout(2_000) { context.streamFinally.await() }
                            withTimeout(5_000) { close.await() }
                        }
                }
            assertTrue(context.events.indexOf("stream-finally") < context.events.indexOf("close"))
        }

    @Test
    fun `managed real runtime opens and closes SQLite through public factory and YAML uses existing loaders`() =
        runBlocking {
            val temp = createTempDirectory("ct28-managed-real")
            val config =
                ContextConfig(
                    temp.resolve("context.sqlite"),
                    30.seconds,
                    vfsConfig = VfsRuntimeConfig(temp.resolve("vfs.sqlite"), setOf("memory"), emptyList()),
                )
            AlcyoneContextFactory.initializeDatabase(config)
            val port = freePort()
            val server = ManagedAlcyoneHttpServer.createManaged(config, ServerConfig(port = port, adminKey = ADMIN))
            server.start()
            val status = javaGet(port, "/v1/admin/status", ADMIN)
            assertEquals(200, status.statusCode())
            assertTrue(status.body().contains("\"lifecycle\":\"RUNNING\""))
            server.closeAsync()
            DriverManager.getConnection("jdbc:sqlite:${config.databasePath}").use { connection ->
                connection.createStatement().use {
                    assertEquals(
                        1,
                        it.executeQuery("PRAGMA user_version").use { result ->
                            result.next()
                            result.getInt(1)
                        },
                    )
                }
            }
            temp.toFile().deleteRecursively()
            Unit
        }

    private fun fakeConfig(): ContextConfig {
        val temp = createTempDirectory("ct28-managed-fake")
        return ContextConfig(
            temp.resolve("unused.sqlite"),
            30.seconds,
            vfsConfig = VfsRuntimeConfig(temp.resolve("vfs.sqlite"), setOf("memory"), emptyList()),
        )
    }

    @Test
    fun `fromYaml initializes owns starts and closes real Context and CIO`() =
        runBlocking {
            val temp = createTempDirectory("ct28-managed-yaml")
            val port = freePort()
            val yaml =
                temp.resolve("alcyone.yaml").also {
                    Files.writeString(
                        it,
                        """
                        context:
                          database:
                            path: ./data/context.sqlite
                        vfs:
                          database:
                            path: ./data/vfs.sqlite
                          namespaces: [memory]
                        server:
                          host: 127.0.0.1
                          port: $port
                          auth:
                            readKey: yaml-read
                            adminKey: yaml-admin
                        """.trimIndent(),
                    )
                }
            val server = ManagedAlcyoneHttpServer.fromYaml(yaml)
            AlcyoneContextFactory.initializeDatabase(
                com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
                    .load(yaml),
            )
            server.start()
            assertEquals(200, javaGet(port, "/health/live", "unused").statusCode())
            assertEquals(200, javaGet(port, "/v1/admin/status", "yaml-admin").statusCode())
            server.closeAsync()
            temp.toFile().deleteRecursively()
            Unit
        }

    private fun Throwable.hasMarker(marker: String): Boolean =
        message == marker || cause?.hasMarker(marker) == true || suppressed.any { it.hasMarker(marker) }

    private fun managed(
        context: LifecycleContext,
        port: Int,
    ): ManagedAlcyoneHttpServer =
        ManagedAlcyoneHttpServer.createForTesting(
            ContextConfig(
                createTempDirectory("ct28-managed-fake").resolve("unused.sqlite"),
                30.seconds,
                vfsConfig =
                    VfsRuntimeConfig(
                        createTempDirectory("ct28-managed-vfs").resolve("unused.sqlite"),
                        setOf("memory"),
                        emptyList(),
                    ),
            ),
            ServerConfig(port = port, readKey = READ, adminKey = ADMIN, limits = ServerLimits(requestTimeoutMillis = 30_000)),
            contextFactory = {
                context.createCalls.incrementAndGet()
                context
            },
            engineFactory = { config, ctx, registry ->
                context.engineCreated.set(true)
                io.ktor.server.engine
                    .embeddedServer(io.ktor.server.cio.CIO, host = config.host, port = config.port) {
                        this.intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
                            if (!registry.tryEnter()) {
                                call.respondError(io.ktor.http.HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
                                return@intercept
                            }
                            try {
                                proceed()
                            } finally {
                                registry.leave()
                            }
                        }
                        with(AlcyoneHttpServer) { installContextRoutes(ctx, config) }
                    }.also { context.events += "listen" }
            },
        )

    private suspend fun javaGet(
        port: Int,
        path: String,
        key: String,
    ): HttpResponse<String> =
        withContext(Dispatchers.IO) {
            HttpClient.newHttpClient().send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                    .header("Authorization", "Bearer $key")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

    private class LifecycleContext(
        private val streaming: Boolean = false,
    ) : AlcyoneContext {
        val createCalls = AtomicInteger()
        val startCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val engineCreated =
            java.util.concurrent.atomic
                .AtomicBoolean()
        val events = CopyOnWriteArrayList<String>()
        val startEntered = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val dreamingEntered = CompletableDeferred<Unit>()
        val dreamingGate = CompletableDeferred<Unit>()
        val streamFinally = CompletableDeferred<Unit>()
        val streamRelease = CompletableDeferred<Unit>()
        var startGate: CompletableDeferred<Unit>? = null
        var closeGate: CompletableDeferred<Unit>? = null
        var startFailure: Throwable? = null
        var closeFailure: Throwable? = null

        override suspend fun start() {
            startCalls.incrementAndGet()
            startEntered.complete(Unit)
            startGate?.await()
            startFailure?.let { throw it }
            events += "start"
        }

        override suspend fun closeAsync() {
            if (closeCalls.incrementAndGet() == 1) {
                closeEntered.complete(Unit)
                closeGate?.await()
                events += "close"
                closeFailure?.let { throw it }
            }
        }

        override fun close() = runBlocking { closeAsync() }

        override suspend fun runDreaming(): DreamingResult {
            events += "dreaming-enter"
            dreamingEntered.complete(Unit)
            try {
                dreamingGate.await()
                return DreamingResult(0, 0, 0, 0, 0, true)
            } finally {
                events += "dreaming-finish"
            }
        }

        override suspend fun loadMeta(uri: String): ResourceMetadata = ResourceMetadata(uri, null, Instant.EPOCH)

        override fun readStream(uri: String): Flow<ByteArray> =
            flow {
                try {
                    emit(byteArrayOf(65))
                    streamRelease.await()
                } finally {
                    events += "stream-finally"
                    streamFinally.complete(Unit)
                }
            }

        override suspend fun ingestConversation(turn: com.github.noahshen.alcyone.context.memory.api.ConversationTurn): IngestResult =
            error("not used")

        override suspend fun getStatus(): ContextStatus = error("not used")
    }

    companion object {
        private const val ADMIN = "managed-admin"
        private const val READ = "managed-read"

        @JvmStatic
        fun main(args: Array<String>) {
            ManagedAlcyoneHttpServerTest().realBindProbe()
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
