package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextBackupErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextBackupException
import com.github.noahshen.alcyone.context.memory.api.ContextBackupResult
import com.github.noahshen.alcyone.context.memory.api.ContextDreamingErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextDreamingException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.DreamingResult
import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import com.github.noahshen.alcyone.context.memory.api.DreamingRunStage
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.QuickCheckPhase
import com.github.noahshen.alcyone.context.memory.api.QuickCheckResult
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.backup.BackupConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AdminManualHttpApiTest {
    @Test
    fun `empty object and empty body succeed with stable dreaming and backup DTOs`() =
        testApplication {
            val context = ManualContext()
            application { install(context) }
            val dreaming =
                client.post("/v1/admin/dreaming") {
                    admin()
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, dreaming.status)
            val dream = Json.parseToJsonElement(dreaming.bodyAsText()).jsonObject
            assertEquals(
                setOf("start_checkpoint", "end_checkpoint", "scanned_turns", "discovered_topics", "generated_memories", "completed"),
                dream.keys,
            )
            assertEquals(
                7,
                dream
                    .getValue("end_checkpoint")
                    .jsonPrimitive.content
                    .toInt(),
            )
            val backup = client.post("/v1/admin/backup") { admin() }
            assertEquals(HttpStatusCode.OK, backup.status)
            val body = Json.parseToJsonElement(backup.bodyAsText()).jsonObject
            assertEquals(setOf("uri", "size_bytes", "completed_at", "quick_check"), body.keys)
            assertEquals(
                12,
                body
                    .getValue("size_bytes")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(2, body.getValue("quick_check").jsonArray.size)
            assertEquals(1, context.dreamingCalls)
            assertEquals(1, context.backupCalls)
        }

    @Test
    fun `authentication precedes body and invalid or oversized bodies never reach SDK`() =
        testApplication {
            val context = ManualContext()
            application { install(context) }
            for (path in listOf("dreaming", "backup")) {
                assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/$path") { setBody("secret") }.status)
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client
                        .post("/v1/admin/$path") {
                            bearer(READ)
                            setBody("secret")
                        }.status,
                )
            }
            assertEquals(0, context.totalCalls)
            for (body in listOf("null", "[]", "{\"x\":1}", "{\"x\":1,\"x\":2}", "{} garbage", " ", "\"text\"")) {
                val response =
                    client.post("/v1/admin/dreaming") {
                        admin()
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("""{"error":"INVALID_REQUEST"}""", response.bodyAsText())
            }
            val oversized =
                client.post("/v1/admin/backup") {
                    admin()
                    setBody("x".repeat(65))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            assertEquals(0, context.totalCalls)
        }

    @Test
    fun `manual routes are absent without admin key`() =
        testApplication {
            val context = ManualContext()
            application { install(context, admin = null) }
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/dreaming") { admin() }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/backup") { admin() }.status)
            assertEquals(0, context.totalCalls)
        }

    @Test
    fun `dreaming failures map to stable safe statuses`() =
        testApplication {
            val context = ManualContext()
            application { install(context) }
            val cases =
                listOf(
                    ContextDreamingException(ContextDreamingErrorCode.UNAVAILABLE, "secret") to (503 to "DREAMING_UNAVAILABLE"),
                    ContextDreamingException(ContextDreamingErrorCode.ALREADY_RUNNING, "secret") to (409 to "DREAMING_ALREADY_RUNNING"),
                    DreamingRunException(DreamingRunStage.FIXED_RANGE, partial(), "secret") to (500 to "DREAMING_FIXED_RANGE"),
                    DreamingRunException(DreamingRunStage.PAGE_READ, partial(), "secret") to (500 to "DREAMING_PAGE_READ"),
                    DreamingRunException(DreamingRunStage.BUDGET, partial(), "secret") to (500 to "DREAMING_BUDGET"),
                    DreamingRunException(DreamingRunStage.BATCH_PROCESSING, partial(), "secret") to (500 to "DREAMING_BATCH_PROCESSING"),
                    ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "secret") to (503 to "SERVICE_UNAVAILABLE"),
                    ContextPersistenceException(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, "secret") to
                        (503 to "SERVICE_UNAVAILABLE"),
                    IllegalStateException("secret") to (500 to "INTERNAL_ERROR"),
                )
            for ((failure, expected) in cases) {
                context.dreamingFailure = failure
                val response = client.post("/v1/admin/dreaming") { admin() }
                assertEquals(expected.first, response.status.value)
                assertEquals("""{"error":"${expected.second}"}""", response.bodyAsText())
                assertTrue(!response.bodyAsText().contains("secret"))
            }
        }

    @Test
    fun `backup failures map to stable safe statuses`() =
        testApplication {
            val context = ManualContext()
            application { install(context) }
            val cases =
                listOf(
                    ContextBackupErrorCode.UNAVAILABLE to (503 to "BACKUP_UNAVAILABLE"),
                    ContextBackupErrorCode.ALREADY_RUNNING to (409 to "BACKUP_ALREADY_RUNNING"),
                    ContextBackupErrorCode.CLOSED to (503 to "SERVICE_UNAVAILABLE"),
                    ContextBackupErrorCode.NOT_STARTED to (503 to "SERVICE_UNAVAILABLE"),
                    ContextBackupErrorCode.QUICK_CHECK_FAILED to (500 to "BACKUP_QUICK_CHECK_FAILED"),
                    ContextBackupErrorCode.PUBLISH_FAILED to (500 to "BACKUP_PUBLISH_FAILED"),
                )
            for ((code, expected) in cases) {
                context.backupFailure = ContextBackupException(code, "secret")
                val response = client.post("/v1/admin/backup") { admin() }
                assertEquals(expected.first, response.status.value)
                assertEquals("""{"error":"${expected.second}"}""", response.bodyAsText())
                assertTrue(!response.bodyAsText().contains("secret"))
            }
            context.backupFailure = IllegalStateException("controlled pre-check")
            assertEquals("BACKUP_PRE_CHECK_FAILED", errorCode(client.post("/v1/admin/backup") { admin() }.bodyAsText()))
        }

    @Test
    fun `server deadline returns 504`() =
        testApplication {
            val context = ManualContext()
            context.dreamingGate = CompletableDeferred()
            application { install(context, timeout = 10) }
            val response = client.post("/v1/admin/dreaming") { admin() }
            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertEquals(1, context.dreamingCalls)
        }

    @Test
    fun `sdk timeout cancellation propagates`() =
        testApplication {
            val context = ManualContext()
            val sdkTimeout = runBlocking { assertFailsWith<TimeoutCancellationException> { withTimeout(1) { delay(100) } } }
            val propagated = CompletableDeferred<Throwable>()
            context.dreamingFailure = sdkTimeout
            application {
                intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
                    try {
                        proceed()
                    } catch (failure: Throwable) {
                        propagated.complete(failure)
                        throw failure
                    }
                }
                install(context)
            }
            client.post("/v1/admin/dreaming") { admin() }
            assertTrue(propagated.await() is TimeoutCancellationException)
        }

    @Test
    fun `real local backup can be opened independently with quick check and committed content`() =
        testApplication {
            val temp = createTempDirectory("ct28-http-backup")
            val database = temp.resolve("context.sqlite")
            val root = Files.createDirectory(temp.resolve("published"))
            val vfs =
                VfsRuntimeConfig(
                    temp.resolve("vfs.sqlite"),
                    setOf("memory"),
                    listOf(MountConfig(VfsPath.parse("/memory/backups"), "backups", root)),
                )
            val config =
                ContextConfig(
                    database,
                    30.seconds,
                    vfsConfig = vfs,
                    backup = BackupConfig(tempDirectory = Files.createDirectory(temp.resolve("work"))),
                )
            AlcyoneContextFactory.initializeDatabase(config)
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) VALUES ('http-raw', 'committed', 'EXTRACTION', '[]', '2026-01-01T00:00:00Z')",
                    )
                }
            }
            val context = AlcyoneContextFactory.create(config, vfs)
            try {
                runBlocking { context.start() }
                application { install(context) }
                val response =
                    client.post("/v1/admin/backup") {
                        admin()
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val uri = body.getValue("uri").jsonPrimitive.content
                val snapshot = root.resolve(uri.substringAfter("/backups/"))
                assertTrue(Files.isRegularFile(snapshot))
                DriverManager.getConnection("jdbc:sqlite:${snapshot.toUri()}?mode=ro").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA quick_check").use { assertEquals("ok", it.apply { next() }.getString(1)) }
                        statement.executeQuery("SELECT content FROM raw_memory WHERE id='http-raw'").use {
                            assertTrue(it.next())
                            assertEquals("committed", it.getString(1))
                        }
                    }
                }
            } finally {
                context.close()
                temp.toFile().deleteRecursively()
            }
        }

    private fun Application.install(
        context: AlcyoneContext,
        admin: String? = ADMIN,
        timeout: Long = 2_000,
    ) {
        val registry = IngestionClientRegistry.from(ServerConfig(readKey = READ, adminKey = admin))
        adminAndHealthRoutes(context, registry.auth, ServerLimits(maxSearchBodyBytes = 64, requestTimeoutMillis = timeout))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.admin() = bearer(ADMIN)

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun errorCode(body: String): String =
        Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("error")
            .jsonPrimitive.content

    private class ManualContext : AlcyoneContext {
        var dreamingCalls = 0
        var backupCalls = 0
        var dreamingFailure: Throwable? = null
        var backupFailure: Throwable? = null
        var dreamingGate: CompletableDeferred<Unit>? = null
        val totalCalls get() = dreamingCalls + backupCalls

        override suspend fun runDreaming(): DreamingResult {
            dreamingCalls++
            dreamingGate?.await()
            dreamingFailure?.let { throw it }
            return DreamingResult(2, 7, 5, 3, 1, true)
        }

        override suspend fun backupNow(): ContextBackupResult {
            backupCalls++
            backupFailure?.let { throw it }
            return ContextBackupResult(
                "alcyone://memory/backups/snapshot.sqlite",
                12,
                Instant.parse("2026-01-01T00:00:00Z"),
                listOf(QuickCheckResult(QuickCheckPhase.PRE_PUBLISH, true), QuickCheckResult(QuickCheckPhase.POST_PUBLISH, true)),
            )
        }

        override suspend fun ingestConversation(turn: com.github.noahshen.alcyone.context.memory.api.ConversationTurn): IngestResult =
            error("not used")

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus = error("not used")
    }

    companion object {
        private const val ADMIN = "admin-secret"
        private const val READ = "read-secret"

        private fun partial() = DreamingResult(0, 1, 1, 0, 0, false)
    }
}
