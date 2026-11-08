package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.BootstrapContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** CT19 B2 A08：公共 Runtime + 真实 SQLite/LocalFs + JDK HTTP stub 的 Map → Bootstrap 闭环。 */
class MemoryMapContextEndToEndTest {
    private val updatedAt: Instant = Instant.parse("2026-11-08T03:59:00Z")

    @Test
    fun `public factory publishes a map and bootstrap reads only the cached result`() =
        runBlocking {
            val stub =
                ExtractionHttpStub(
                    listOf(
                        ExtractionHttpStub.chatCompletionEnvelope(
                            """{"overview":"E2E overview","navigation":"E2E navigation","domains":["domain"]}""",
                        ),
                    ),
                )
            val directory = Files.createTempDirectory("ct19-map-bootstrap-e2e-")
            var context: com.github.noahshen.alcyone.context.memory.api.AlcyoneContext? = null
            try {
                val contextPath = directory.resolve("data/context.db")
                ContextDatabaseInitializer.initialize(contextPath)
                seedOrganized(contextPath)
                val mountRoot = Files.createDirectories(directory.resolve("memory"))
                val vfsConfig =
                    VfsRuntimeConfig(
                        stateDatabase = directory.resolve("data/vfs.db"),
                        namespaces = setOf("memory"),
                        mounts =
                            listOf(
                                MountConfig(
                                    vfsPath = VfsPath.parse("/memory"),
                                    storageKey = "ct19-map-bootstrap-e2e",
                                    root = mountRoot,
                                ),
                            ),
                    )
                val config =
                    ContextConfig(
                        databasePath = contextPath,
                        lifecycleShutdownTimeout = 30.seconds,
                        vfsConfig = vfsConfig,
                        llm = memoryMapLlm(stub),
                        extraction = ExtractionConfig(idleEnabled = false),
                        memoryMap = MemoryMapConfig(MemoryMapPeriodicConfig(enabled = false)),
                    )
                val created = AlcyoneContextFactory.create(config, vfsConfig)
                context = created
                created.start()

                awaitUntil {
                    val bootstrap = created.bootstrap()
                    bootstrap.memoryMap != null &&
                        (bootstrap.mapStatus == MapStatus.STALE || bootstrap.mapStatus == MapStatus.CURRENT)
                }
                val first = created.bootstrap()
                val view = assertNotNull(first.memoryMap)
                assertTrue(view.content.startsWith("---\n"))
                assertTrue(view.content.contains("# Memory Overview"))
                assertTrue(view.content.contains("E2E overview"))
                assertEquals(BootstrapContext.DEFAULT_CAPABILITIES, first.capabilities)
                assertEquals(updatedAt, view.sourceUpdatedAt)
                assertEquals(1, stub.requestCount())

                val mapBytes = Files.readAllBytes(mountRoot.resolve("map.md"))
                assertEquals(view.content, mapBytes.toString(Charsets.UTF_8))
                val status = created.getStatus()
                assertEquals(ContextLifecycleState.RUNNING, status.lifecycle)
                assertTrue(status.workers.getValue("memoryMap").state in setOf(WorkerState.IDLE, WorkerState.RUNNING))

                repeat(20) { assertEquals(first, created.bootstrap()) }
                assertEquals(1, stub.requestCount(), "重复 bootstrap 不得新增 LLM 请求")
            } finally {
                runCatching { context?.close() }
                val closed = assertFailsWith<ContextLifecycleException> { context?.bootstrap() }
                assertEquals(ContextLifecycleErrorCode.CLOSED, closed.errorCode)
                val countAfterClose = stub.requestCount()
                delay(150)
                assertEquals(countAfterClose, stub.requestCount(), "close 后不得残留后台生成")
                stub.close()
                deleteTree(directory)
            }
        }

    private suspend fun seedOrganized(path: Path) {
        val executor = ContextDatabaseFactory.open(path)
        try {
            val database = ContextDatabaseImpl(executor)
            database.write { tx ->
                assertEquals(
                    1,
                    tx.organizedMemories.insertNew(
                        OrganizedMemory(
                            id = "O-MAP-E2E-1",
                            content = "real SQLite organized memory",
                            domains = listOf("domain"),
                            rawMemoryIds = emptyList(),
                            createdAt = updatedAt.minusSeconds(1),
                            updatedAt = updatedAt,
                        ),
                    ),
                )
            }
        } finally {
            executor.close()
        }
    }

    private fun memoryMapLlm(stub: ExtractionHttpStub): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "map" to
                        LlmProfileConfig(
                            name = "map",
                            baseUrl = stub.baseUrl,
                            apiKey = "ct19-b2-e2e-key-marker",
                            model = "fake-map",
                            contextWindowTokens = 64_000,
                            jsonMode = LlmResponseFormat.JSON_OBJECT,
                            maxAttempts = 1,
                        ),
                ),
            tasks =
                mapOf(
                    LlmTask.MEMORY_MAP to
                        LlmTaskConfig(
                            profile = "map",
                            maxInputTokens = 32_000,
                            maxOutputTokens = 4_000,
                        ),
                ),
        )

    private suspend fun awaitUntil(condition: suspend () -> Boolean) {
        withTimeout(20_000L) {
            while (!condition()) delay(20)
        }
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
