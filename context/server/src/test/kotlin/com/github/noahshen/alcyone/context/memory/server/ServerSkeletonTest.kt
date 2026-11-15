package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServerSkeletonTest {
    @Test
    fun `package name and ktor server cio engine resolve properly`() {
        assertEquals("com.github.noahshen.alcyone.context.memory.server", ServerSkeletonTest::class.java.packageName)

        val engineClass = CIO::class.java
        assertNotNull(engineClass)
    }

    @Test
    fun `server keeps health without credentials and never owns external context`() {
        val port = ServerSocket(0).use { it.localPort }
        val context = ExternalContext()
        val server = AlcyoneHttpServer.start(ServerConfig(host = "127.0.0.1", port = port), context)
        try {
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health/live")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, response.statusCode())
            assertEquals("""{"status":"live"}""", response.body())
            assertEquals(0, context.calls)
        } finally {
            server.close()
        }
        assertEquals(0, context.calls)
    }

    @Test
    fun `ktor testApplication executes successfully`() {
        testApplication {
            application {
                routing {
                    get("/skeleton/ping") {
                        call.respondText("pong")
                    }
                }
            }

            val response = client.get("/skeleton/ping")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("pong", response.bodyAsText())
        }
    }

    private class ExternalContext : AlcyoneContext {
        var calls = 0

        override suspend fun checkReady(): Boolean {
            calls++
            return true
        }

        override suspend fun ingestConversation(turn: com.github.noahshen.alcyone.context.memory.api.ConversationTurn): IngestResult =
            error("not used")

        override suspend fun start() {
            calls++
        }

        override fun close() {
            calls++
        }

        override suspend fun closeAsync() {
            calls++
        }

        override suspend fun getStatus(): ContextStatus = error("not used")
    }
}
