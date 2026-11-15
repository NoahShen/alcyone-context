package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextAdminStatus
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HealthHttpApiTest {
    @Test
    fun `live never touches runtime and ready uses only checkReady for true false and failures`() =
        testApplication {
            val context = HealthContext()
            application { install(context) }
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            assertEquals("""{"status":"live"}""", client.get("/health/live").bodyAsText())
            assertEquals(0, context.readyCalls)

            context.ready = true
            val ready = client.get("/health/ready") { header(HttpHeaders.Authorization, "Bearer arbitrary") }
            assertEquals(HttpStatusCode.OK, ready.status)
            assertEquals("""{"status":"ready"}""", ready.bodyAsText())
            context.ready = false
            assertNotReady(client.get("/health/ready").let { it.status to it.bodyAsText() })
            context.failure = IllegalStateException("secret database path")
            assertNotReady(client.get("/health/ready").let { it.status to it.bodyAsText() })
            assertEquals(3, context.readyCalls)
            assertEquals(0, context.otherCalls)
        }

    @Test
    fun `sdk timeout cancellation propagates unchanged`() =
        testApplication {
            val context = HealthContext()
            val sdkTimeout =
                runBlocking {
                    assertFailsWith<TimeoutCancellationException> { withTimeout(1) { delay(1_000) } }
                }
            val propagated = CompletableDeferred<Throwable>()
            context.readyDelayMillis = 0
            context.failure = sdkTimeout
            application {
                intercept(ApplicationCallPipeline.Call) {
                    try {
                        proceed()
                    } catch (failure: Throwable) {
                        propagated.complete(failure)
                        throw failure
                    }
                }
                install(context, readyTimeoutMillis = 5_000)
            }
            client.get("/health/ready")
            assertIs<TimeoutCancellationException>(propagated.await())
        }

    @Test
    fun `ready deadline is independent from request timeout`() =
        testApplication {
            val context = HealthContext()
            context.readyDelayMillis = 100
            application { install(context, readyTimeoutMillis = 10, requestTimeoutMillis = 5_000) }
            assertNotReady(client.get("/health/ready").let { it.status to it.bodyAsText() })
            assertEquals(1, context.readyCalls)
        }

    @Test
    fun `real runtime readiness succeeds fails on dropped table and closed context`() =
        testApplication {
            ServerFixture().use { fixture ->
                fixture.initialize()
                val context = fixture.newContext()
                try {
                    runBlocking { context.start() }
                    application { install(context) }
                    assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
                    DriverManager.getConnection("jdbc:sqlite:${fixture.contextPath}").use { connection ->
                        connection.createStatement().use { it.execute("DROP TABLE dreaming_checkpoint") }
                    }
                    assertNotReady(client.get("/health/ready").let { it.status to it.bodyAsText() })
                } finally {
                    context.close()
                }
                assertNotReady(client.get("/health/ready").let { it.status to it.bodyAsText() })
            }
        }

    @Test
    fun `ready does not infer health from pending map or llm history`() =
        testApplication {
            val context = HealthContext()
            context.ready = true
            context.adminStatus = ContextAdminStatus(com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState.RUNNING)
            application { install(context) }
            assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
            assertEquals(1, context.readyCalls)
            assertEquals(0, context.otherCalls)
            assertTrue(context.adminStatus != null)
        }

    private fun Application.install(
        context: AlcyoneContext,
        readyTimeoutMillis: Long = 2_000,
        requestTimeoutMillis: Long = 60_000,
    ) {
        adminAndHealthRoutes(
            context,
            IngestionClientRegistry.from(ServerConfig()).auth,
            ServerLimits(requestTimeoutMillis = requestTimeoutMillis, readyTimeoutMillis = readyTimeoutMillis),
        )
    }

    private fun assertNotReady(response: Pair<HttpStatusCode, String>) {
        assertEquals(HttpStatusCode.ServiceUnavailable, response.first)
        assertEquals("""{"error":"NOT_READY"}""", response.second)
    }

    private class HealthContext : AlcyoneContext {
        var ready = false
        var readyDelayMillis = 0L
        var failure: Throwable? = null
        var readyCalls = 0
        var otherCalls = 0
        var adminStatus: ContextAdminStatus? = null

        override suspend fun checkReady(): Boolean {
            readyCalls++
            failure?.let { throw it }
            if (readyDelayMillis > 0) delay(readyDelayMillis)
            return ready
        }

        override suspend fun getAdminStatus(): ContextAdminStatus {
            otherCalls++
            return adminStatus ?: ContextAdminStatus(com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState.RUNNING)
        }

        override suspend fun getStatus(): ContextStatus {
            otherCalls++
            error("ready must not call getStatus")
        }

        override suspend fun ingestConversation(turn: com.github.noahshen.alcyone.context.memory.api.ConversationTurn): IngestResult =
            error("not used")

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit
    }
}
