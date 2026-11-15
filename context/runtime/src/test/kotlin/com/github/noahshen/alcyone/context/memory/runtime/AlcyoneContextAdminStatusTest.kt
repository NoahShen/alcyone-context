package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.*
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.DreamingCheckpointTxOpsImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.OrganizationPendingTxOpsImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.RawMemoryTxOpsImpl
import com.github.noahshen.alcyone.context.memory.runtime.backup.BackupConfig
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.*
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlcyoneContextAdminStatusTest {
    @Test
    fun `ready and admin use the real SQLite state and close is terminal`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking {
                assertFalse(context.checkReady())
                assertEquals(ContextLifecycleState.CREATED, context.getAdminStatus().lifecycle)
                assertNull(context.getAdminStatus().workers?.extraction)
                assertNull(context.getAdminStatus().pending)
                assertNull(context.getAdminStatus().llm)
                assertNull(context.getAdminStatus().mapStatus)
                assertNull(context.getAdminStatus().backup)
                assertFalse(context.checkReady())
                context.start()
                assertTrue(context.checkReady())
                var status = context.getAdminStatus()
                assertNotNull((context as AlcyoneContextImpl).databaseForTesting())
                assertNull((context as AlcyoneContextImpl).pipelineForTesting())
                assertEquals(0, status.pending?.count)
                assertNull(status.dreaming)
                assertNull(status.workers?.extraction)
                assertEquals(WorkerState.IDLE, status.backup?.state)
                assertNull(status.workers?.organization)
                assertNull(status.workers?.dreaming)
                fixture.open().use { executor ->
                    val raw = RawMemory("pending-1", "content", MemoryOrigin.EXTRACTION, emptyList(), Instant.EPOCH)
                    executor.write { tx ->
                        RawMemoryTxOpsImpl(tx).insertBatch(listOf(raw))
                        OrganizationPendingTxOpsImpl(tx).enqueue(raw.id)
                        DreamingCheckpointTxOpsImpl(tx).casUpdateCheckpoint(0, 7)
                    }
                }
                status = context.getAdminStatus()
                assertEquals(1, status.pending?.count)
                assertNull(status.dreaming)
                assertNull(status.workers?.extraction)
            }
            context.close()
            runBlocking {
                assertFalse(context.checkReady())
                assertEquals(ContextLifecycleState.CLOSED, context.getAdminStatus().lifecycle)
                assertNull(context.getAdminStatus().workers?.extraction)
                assertNull(context.getAdminStatus().workers?.organization)
                assertNull(context.getAdminStatus().llm)
                assertNull(context.getAdminStatus().pending)
            }
        }

    @Test
    fun `closed managed executor is not ready and admin preserves its lifecycle failure`() {
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking { context.start() }
            val database = assertNotNull((context as AlcyoneContextImpl).databaseForTesting())
            val executor = managedExecutor(database)
            try {
                runBlocking {
                    withTimeout(10_000L) { executor.closeAsync() }
                    assertFalse(context.checkReady())
                    val failure = assertFailsWith<ContextLifecycleException> { context.getAdminStatus() }
                    assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                    assertNull(kotlin.runCatching { context.getAdminStatus().pending }.getOrNull())
                }
            } finally {
                context.close()
            }
        }
    }

    private fun managedExecutor(database: ContextDatabaseImpl): ContextDatabaseExecutor =
        ContextDatabaseImpl::class.java.getDeclaredField("executor").let { field ->
            field.isAccessible = true
            field.get(database) as ContextDatabaseExecutor
        }

    @Test
    fun `configured dreaming reports zero calls until a real client records usage`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(dreaming = DreamingConfig(), llm = dreamingLlm()),
                    fixture.vfsConfig(),
                    AlcyoneContextFactory.StartProbe {},
                )
            runBlocking {
                try {
                    context.start()
                    val status = context.getAdminStatus()
                    assertEquals(emptyList(), status.llm)
                    assertEquals(WorkerState.IDLE, status.workers?.dreaming?.state)
                    assertEquals(0L, status.dreaming?.lastProcessedTurnId)
                    fixture.open().use { executor ->
                        executor.write { DreamingCheckpointTxOpsImpl(it).casUpdateCheckpoint(0, 7) }
                    }
                    assertEquals(7L, context.getAdminStatus().dreaming?.lastProcessedTurnId)
                    assertTrue(context.checkReady())
                } finally {
                    context.close()
                }
            }
        }

    @Test
    fun `map and backup admin facts come from their real runtime readers`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            runBlocking {
                fixture.open().use { executor ->
                    insertOrganized(ContextDatabaseImpl(executor), "O-ADMIN-MAP", Instant.EPOCH)
                }
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val mapConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub))
                val config =
                    mapConfig.copy(
                        backup = BackupConfig(tempDirectory = fixture.directory),
                        llm = LlmConfig(mapConfig.llm!!.profiles, mapConfig.llm!!.tasks + dreamingTasks("map")),
                    )
                val context =
                    AlcyoneContextFactory.create(
                        config,
                        memoryMapVfsConfig(fixture),
                        AlcyoneContextFactory.StartProbe {},
                        memoryMapGenerator = RecordingMemoryMapGenerator { mapArtifact(Instant.EPOCH) },
                    )
                val published: ContextBackupResult
                try {
                    context.start()
                    awaitMemoryMapWiringUntil { context.bootstrap().mapStatus == MapStatus.STALE }
                    val status = context.getAdminStatus()
                    assertEquals(MapStatus.STALE, status.mapStatus)
                    assertEquals(WorkerState.IDLE, status.workers?.memoryMap?.state)
                    assertEquals(WorkerState.IDLE, status.workers?.dreaming?.state)
                    assertNull(status.workers?.extraction)
                    assertNull(status.workers?.organization)
                    assertTrue(context.checkReady())
                    published = context.backupNow()
                    val backup = context.getAdminStatus().backup
                    assertEquals(WorkerState.IDLE, backup?.state)
                    assertEquals(published.uri, backup?.lastUri)
                    assertEquals(published.completedAt, backup?.lastCompletedAt)
                    assertNull(backup?.lastFailureStage)
                } finally {
                    context.close()
                    stub.close()
                }
                val closed = context.getAdminStatus()
                assertEquals(WorkerState.STOPPED, closed.workers?.memoryMap?.state)
                assertEquals(WorkerState.STOPPED, closed.workers?.dreaming?.state)
                assertNull(closed.workers?.extraction)
                assertNull(closed.workers?.organization)
                assertNull(closed.pending)
                assertNull(closed.dreaming)
                assertNull(closed.mapStatus)
                assertNull(closed.llm)
                assertEquals(published.uri, closed.backup?.lastUri)
                assertEquals(published.completedAt, closed.backup?.lastCompletedAt)
                assertEquals(WorkerState.STOPPED, closed.backup?.state)
                val impl = context as AlcyoneContextImpl
                assertNull(impl.llmClientForTesting())
                assertNull(impl.pipelineForTesting())
                assertNull(impl.organizationPipelineForTesting())
                assertNull(impl.memoryMapPipelineForTesting())
            }
        }

    @Test
    fun `real extraction and organization pipelines aggregate and close as stopped`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("unused"))
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(
                        llm = workerLlm(stub),
                        extraction = ExtractionConfig(idleEnabled = false),
                        organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
                    ),
                    fixture.vfsConfig(),
                )
            try {
                runBlocking {
                    context.start()
                    val impl = context as AlcyoneContextImpl
                    assertNotNull(impl.pipelineForTesting())
                    assertNotNull(impl.organizationPipelineForTesting())
                    val workers = assertNotNull(context.getAdminStatus().workers)
                    assertEquals(WorkerState.IDLE, assertNotNull(workers.extraction).state)
                    assertEquals(WorkerState.IDLE, assertNotNull(workers.organization).state)
                    assertEquals(0, stub.requests().size)
                }
            } finally {
                context.close()
                stub.close()
            }
            runBlocking {
                val workers = assertNotNull(context.getAdminStatus().workers)
                assertEquals(WorkerState.STOPPED, assertNotNull(workers.extraction).state)
                assertEquals(WorkerState.STOPPED, assertNotNull(workers.organization).state)
            }
        }

    private fun workerLlm(stub: LlmHttpStub): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "shared" to
                        LlmProfileConfig(
                            name = "shared",
                            baseUrl = stub.baseUrl,
                            apiKey = "not-a-secret",
                            model = "fake-model",
                            contextWindowTokens = 64_000,
                            jsonMode = LlmResponseFormat.JSON_OBJECT,
                            maxAttempts = 1,
                        ),
                ),
            tasks =
                listOf(LlmTask.EXTRACTION, LlmTask.CANDIDATE_SCANNER, LlmTask.GOVERNANCE).associateWith {
                    LlmTaskConfig("shared", 8_000, 2_000)
                },
        )

    private fun dreamingTasks(profile: String): Map<LlmTask, LlmTaskConfig> =
        (listOf(LlmTask.DREAMING_TOPIC, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION) + LlmTask.MEMORY_SEARCH)
            .associateWith { LlmTaskConfig(profile, 4_000, 1_000) }

    @Test
    fun `admin worker slots use legal bindings rather than the presence of an llm config`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val plain = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking {
                val created = plain.getAdminStatus()
                assertNull(created.workers?.extraction)
                assertNull(created.workers?.organization)
                assertNull(created.workers?.memoryMap)
                assertNull(created.workers?.dreaming)
            }
            plain.close()
            val stub = LlmHttpStub(200, chatCompletionBody("unused"))
            val searchOnly =
                fixture.config().copy(
                    llm =
                        LlmConfig(
                            profiles =
                                mapOf(
                                    "search" to
                                        LlmProfileConfig(
                                            "search",
                                            stub.baseUrl,
                                            "not-a-secret",
                                            "fake-model",
                                            64_000,
                                            LlmResponseFormat.PROMPT_ONLY,
                                            maxAttempts = 1,
                                        ),
                                ),
                            tasks = mapOf(LlmTask.MEMORY_SEARCH to LlmTaskConfig("search", 4_000, 1_000)),
                        ),
                )
            val context = AlcyoneContextFactory.create(searchOnly, fixture.vfsConfig())
            try {
                runBlocking {
                    context.start()
                    val status = context.getAdminStatus()
                    assertNull(status.workers?.extraction)
                    assertNull(status.workers?.dreaming)
                    assertNull((context as AlcyoneContextImpl).pipelineForTesting())
                }
            } finally {
                context.close()
                stub.close()
            }
            runBlocking {
                val closed = context.getAdminStatus()
                assertNull(closed.workers?.extraction)
                assertNull(closed.workers?.dreaming)
                assertNull(closed.llm)
                assertNull((context as AlcyoneContextImpl).llmClientForTesting())
            }
        }

    private fun dreamingLlm(): LlmConfig {
        val profile =
            LlmProfileConfig(
                name = "dreaming",
                baseUrl = "http://127.0.0.1:1/v1",
                apiKey = "not-a-secret",
                model = "model",
                contextWindowTokens = 64_000,
                jsonMode = LlmResponseFormat.JSON_SCHEMA,
                maxAttempts = 1,
            )
        return LlmConfig(
            mapOf("dreaming" to profile),
            listOf(LlmTask.DREAMING_TOPIC, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION, LlmTask.MEMORY_SEARCH).associateWith {
                LlmTaskConfig("dreaming", 4_000, 1_000)
            },
        )
    }
}
