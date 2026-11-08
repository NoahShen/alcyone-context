@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapRenderer
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** CT21 A03：第二版真实 Organization 提交期间 Map 只暴露完整旧版，释放后完整新版并收敛 CURRENT。 */
class Cm4MapInterleavingEndToEndTest {
    private val session = SessionKey("cm4-map-interleave", "agent", "session")
    private val base = Instant.parse("2026-11-09T04:00:00Z")

    @Test
    fun `old map stays complete during blocked second publish then new map converges current`() =
        runBlocking {
            withTimeout(60_000L) {
                val directory = Files.createTempDirectory("cm4-map-interleave-")
                val contextPath = directory.resolve("data/context.db")
                val memoryRoot = Files.createDirectories(directory.resolve("memory"))
                val stub = Cm4HttpStub(contextPath, blockSecondMap = true)
                var context: AlcyoneContext? = null
                try {
                    val vfsConfig =
                        VfsRuntimeConfig(
                            stateDatabase = directory.resolve("data/vfs.db"),
                            namespaces = setOf("memory"),
                            mounts = listOf(MountConfig(VfsPath.parse("/memory"), "cm4-memory", memoryRoot)),
                        )
                    val config = mapConfig(contextPath, vfsConfig, stub)
                    AlcyoneContextFactory.initializeDatabase(config)
                    val created = AlcyoneContextFactory.create(config, vfsConfig)
                    context = created
                    created.start()

                    repeat(5) { index -> ingest(created, index) }
                    awaitUntil {
                        sqliteLong(contextPath, "SELECT count(*) FROM organized_memory") == 1L &&
                            created
                                .bootstrap()
                                .memoryMap
                                ?.content
                                ?.contains("CM4 map version one") == true
                    }
                    val oldView = assertNotNull(created.bootstrap().memoryMap)
                    val oldParsed = MemoryMapRenderer.parse(oldView.content)
                    assertEquals("CM4 map version one", oldParsed.overview)

                    repeat(5) { index -> ingest(created, index + 5) }
                    assertTrue(stub.awaitSecondMapEntered(), "second map generation must reach deterministic gate")
                    assertEquals(2L, sqliteLong(contextPath, "SELECT count(*) FROM organized_memory"))

                    repeat(20) {
                        val view = assertNotNull(created.bootstrap().memoryMap)
                        assertEquals(oldView.content, view.content, "blocked publish must expose only the complete old map")
                        val parsed = MemoryMapRenderer.parse(view.content)
                        assertEquals(oldParsed.sourceUpdatedAt, parsed.sourceUpdatedAt)
                    }

                    stub.releaseSecondMapGate()
                    awaitUntil {
                        val view = created.bootstrap().memoryMap
                        view != null && view.content.contains("CM4 map version two")
                    }
                    val newView = assertNotNull(created.bootstrap().memoryMap)
                    val newParsed = MemoryMapRenderer.parse(newView.content)
                    assertEquals("CM4 map version two", newParsed.overview)
                    assertNotEquals(oldView.content, newView.content)
                    assertEquals(
                        sqliteString(contextPath, "SELECT MAX(updated_at) FROM organized_memory"),
                        SHANGHAI_MILLIS.format(newView.sourceUpdatedAt),
                    )
                    assertTrue(created.bootstrap().mapStatus in setOf(MapStatus.STALE, MapStatus.CURRENT))

                    (created as AlcyoneContextImpl).organizationMapWakeupForTesting().onOrganizedCommitted()
                    awaitUntil { created.bootstrap().mapStatus == MapStatus.CURRENT }

                    assertEquals(
                        listOf(
                            Cm4HttpStub.RequestType.EXTRACTION,
                            Cm4HttpStub.RequestType.GOVERNANCE,
                            Cm4HttpStub.RequestType.MEMORY_MAP,
                            Cm4HttpStub.RequestType.EXTRACTION,
                            Cm4HttpStub.RequestType.ORGANIZATION_CANDIDATE,
                            Cm4HttpStub.RequestType.GOVERNANCE,
                            Cm4HttpStub.RequestType.MEMORY_MAP,
                        ),
                        stub.requestTypes(),
                    )
                } finally {
                    stub.releaseSecondMapGate()
                    runCatching { context?.close() }
                    stub.close()
                    deleteTree(directory)
                }
            }
        }

    private suspend fun ingest(
        context: AlcyoneContext,
        index: Int,
    ) {
        val result =
            context.ingestConversation(
                ConversationTurn(
                    runtimeId = session.runtimeId,
                    agentId = session.agentId,
                    sessionId = session.sessionId,
                    sessionTurnId = "interleave-$index",
                    timestamp = base.plusSeconds(index.toLong()),
                    messages = listOf(ChatMessage(ChatRole.USER, "CM4 interleave turn $index")),
                ),
            )
        assertEquals(IngestStatus.CREATED, result.status)
    }

    private fun mapConfig(
        contextPath: Path,
        vfsConfig: VfsRuntimeConfig,
        stub: Cm4HttpStub,
    ): ContextConfig {
        val profile =
            LlmProfileConfig(
                name = "cm4-map",
                baseUrl = stub.baseUrl,
                apiKey = "cm4-map-secret-marker",
                model = "fake-map",
                contextWindowTokens = 64_000,
                jsonMode = LlmResponseFormat.JSON_OBJECT,
                maxAttempts = 1,
            )
        val tasks =
            listOf(LlmTask.EXTRACTION, LlmTask.CANDIDATE_SCANNER, LlmTask.GOVERNANCE, LlmTask.MEMORY_MAP)
                .associateWith { LlmTaskConfig("cm4-map", 32_000, 4_000) }
        return ContextConfig(
            databasePath = contextPath,
            lifecycleShutdownTimeout = 30.seconds,
            vfsConfig = vfsConfig,
            llm = LlmConfig(profiles = mapOf(profile.name to profile), tasks = tasks, safetyMarginPercent = 0),
            extraction = ExtractionConfig(countThreshold = 5, idleEnabled = false),
            organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
            memoryMap = MemoryMapConfig(MemoryMapPeriodicConfig(enabled = false)),
        )
    }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) {
        withTimeout(30_000L) {
            while (!condition()) delay(20)
        }
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        val SHANGHAI_MILLIS =
            java.time.format.DateTimeFormatter
                .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
                .withZone(java.time.ZoneOffset.ofHours(8))
    }
}
