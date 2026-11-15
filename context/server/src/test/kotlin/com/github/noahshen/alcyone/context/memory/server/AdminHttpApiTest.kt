package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AdminBackupStatus
import com.github.noahshen.alcyone.context.memory.api.AdminDreamingStatus
import com.github.noahshen.alcyone.context.memory.api.AdminLlmTaskUsage
import com.github.noahshen.alcyone.context.memory.api.AdminPendingStatus
import com.github.noahshen.alcyone.context.memory.api.AdminWorkerStatus
import com.github.noahshen.alcyone.context.memory.api.AdminWorkers
import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextAdminStatus
import com.github.noahshen.alcyone.context.memory.api.ContextBackupFailureStage
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminHttpApiTest {
    @Test
    fun `admin projects every safe field with stable snake case and distinguishes null from empty`() =
        testApplication {
            val context = AdminContext()
            context.status = fullStatus(llm = emptyList())
            application { install(context, adminKey = ADMIN) }

            val response = client.get("/v1/admin/status") { bearer(ADMIN) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, context.adminCalls)
            val body = response.bodyAsText()
            val root = Json.parseToJsonElement(body).jsonObject
            assertEquals(
                setOf("lifecycle", "workers", "pending", "dreaming", "map_status", "llm", "backup"),
                root.keys,
            )
            assertEquals("RUNNING", root.getValue("lifecycle").jsonPrimitive.content)
            assertEquals("STALE", root.getValue("map_status").jsonPrimitive.content)
            assertEquals(0, root.getValue("llm").jsonArray.size)
            val workers = root.getValue("workers").jsonObject
            assertEquals(setOf("extraction", "organization", "memory_map", "dreaming"), workers.keys)
            assertTrue("last_failure" in workers.getValue("extraction").jsonObject)
            assertTrue("last_processed_turn_id" in root.getValue("dreaming").jsonObject)
            assertTrue("last_failure_stage" in root.getValue("backup").jsonObject)
            assertFalse(body.contains("Throwable"))
            assertFalse(body.contains("message"))
            assertFalse(body.contains("baseUrl"))
            assertFalse(body.contains("model"))
            assertFalse(body.contains("/tmp/"))

            context.status = ContextAdminStatus(ContextLifecycleState.CREATED, llm = null)
            val emptyProjection = client.get("/v1/admin/status") { bearer(ADMIN) }.bodyAsText()
            val emptyRoot = Json.parseToJsonElement(emptyProjection).jsonObject
            assertEquals("null", emptyRoot.getValue("workers").toString())
            assertEquals("null", emptyRoot.getValue("llm").toString())
        }

    @Test
    fun `admin key is the only accepted class and failures use fixed safe responses`() =
        testApplication {
            val context = AdminContext()
            application { install(context, adminKey = ADMIN) }
            listOf(null, "unknown").forEach { token ->
                assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/status") { bearer(token) }.status)
            }
            listOf(READ, INGESTION).forEach { token ->
                assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/status") { bearer(token) }.status)
            }
            assertEquals(0, context.adminCalls)
            assertEquals(HttpStatusCode.Forbidden, client.get("/v1/context/bootstrap") { bearer(ADMIN) }.status)
            assertEquals(0, context.adminCalls)

            context.failure =
                ContextLifecycleException(com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode.CLOSED, "secret path")
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/admin/status") { bearer(ADMIN) }.status)
            context.failure = ContextPersistenceException(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, "secret SQL")
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/admin/status") { bearer(ADMIN) }.status)
            context.failure = IllegalStateException("secret backend")
            val internal = client.get("/v1/admin/status") { bearer(ADMIN) }
            assertEquals(HttpStatusCode.InternalServerError, internal.status)
            assertEquals("""{"error":"INTERNAL_ERROR"}""", internal.bodyAsText())
            assertEquals(3, context.adminCalls)
        }

    @Test
    fun `admin route is absent when no admin key is configured`() =
        testApplication {
            val context = AdminContext()
            application { install(context, adminKey = null) }
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/admin/status") { bearer(ADMIN) }.status)
            assertEquals(0, context.adminCalls)
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        }

    @Test
    fun `real runtime admin projection and database failure are never fabricated as zero`() =
        testApplication {
            ServerFixture().use { fixture ->
                fixture.initialize()
                val context = fixture.newContext()
                try {
                    runBlocking { context.start() }
                    application { install(context, adminKey = ADMIN) }
                    val running = client.get("/v1/admin/status") { bearer(ADMIN) }
                    assertEquals(HttpStatusCode.OK, running.status)
                    assertTrue(running.bodyAsText().contains("\"pending\":{\"count\":0}"))
                    java.sql.DriverManager.getConnection("jdbc:sqlite:${fixture.contextPath}").use { connection ->
                        connection.createStatement().use { it.execute("DROP TABLE organization_pending") }
                    }
                    val failed = client.get("/v1/admin/status") { bearer(ADMIN) }
                    assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
                    assertEquals("""{"error":"SERVICE_UNAVAILABLE"}""", failed.bodyAsText())
                } finally {
                    context.close()
                }
            }
        }

    private fun Application.install(
        context: AlcyoneContext,
        adminKey: String?,
    ) {
        val registry =
            IngestionClientRegistry.from(
                ServerConfig(
                    clients = mapOf("runtime-1" to IngestionClientConfig("runtime-1", "pi", INGESTION)),
                    readKey = READ,
                    adminKey = adminKey,
                ),
            )
        readRoutes(context, registry.auth, ServerLimits())
        adminAndHealthRoutes(context, registry.auth, ServerLimits())
    }

    private class AdminContext : AlcyoneContext {
        var adminCalls = 0
        var failure: Throwable? = null
        var status: ContextAdminStatus = ContextAdminStatus(ContextLifecycleState.RUNNING)

        override suspend fun getAdminStatus(): ContextAdminStatus {
            adminCalls++
            failure?.let { throw it }
            return status
        }

        override suspend fun ingestConversation(turn: com.github.noahshen.alcyone.context.memory.api.ConversationTurn): IngestResult =
            error("not used")

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus = error("not used")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String?) {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    companion object {
        private const val ADMIN = "admin-secret"
        private const val READ = "read-secret"
        private const val INGESTION = "ingestion-secret"

        private fun fullStatus(llm: List<AdminLlmTaskUsage>?): ContextAdminStatus =
            ContextAdminStatus(
                lifecycle = ContextLifecycleState.RUNNING,
                workers =
                    AdminWorkers(
                        extraction = AdminWorkerStatus(WorkerState.IDLE, false),
                        organization = AdminWorkerStatus(WorkerState.RUNNING, null),
                        memoryMap = AdminWorkerStatus(WorkerState.STOPPED, true),
                        dreaming = null,
                    ),
                pending = AdminPendingStatus(7),
                dreaming = AdminDreamingStatus(42),
                mapStatus = MapStatus.STALE,
                llm = llm,
                backup =
                    AdminBackupStatus(
                        WorkerState.STOPPED,
                        "alcyone://backups/snapshot.db",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        ContextBackupFailureStage.POST_CHECK,
                    ),
            )
    }
}
