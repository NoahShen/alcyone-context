package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.RetrievalConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.SearchConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** CT21 A01/A02/A04/A05/A06：单条真实栈 CM4 SDK 全景闭环。 */
class Cm4SdkJourneyEndToEndTest {
    private val session = SessionKey("cm4-journey", "agent", "session")
    private val base = Instant.parse("2026-11-09T03:00:00Z")

    @Test
    fun `conversation through organization map search get resource and close is one real journey`() =
        runBlocking {
            val directory = Files.createTempDirectory("cm4-sdk-journey-")
            val contextPath = directory.resolve("data/context.db")
            val memoryRoot = Files.createDirectories(directory.resolve("memory"))
            val resourceRoot = Files.createDirectories(directory.resolve("resources"))
            val resourceBytes = ByteArray(140 * 1024 + 7) { index -> (index % 251).toByte() }
            val resourceFile = resourceRoot.resolve("journey").resolve("data.bin")
            Files.createDirectories(resourceFile.parent)
            Files.write(resourceFile, resourceBytes)
            val stub = Cm4HttpStub(contextPath)
            var context: AlcyoneContext? = null
            try {
                val vfsConfig = journeyVfsConfig(directory, memoryRoot, resourceRoot)
                val config = journeyConfig(contextPath, vfsConfig, stub)
                AlcyoneContextFactory.initializeDatabase(config)
                val created = AlcyoneContextFactory.create(config, vfsConfig)
                context = created
                created.start()
                assertEquals(ContextLifecycleState.RUNNING, created.getStatus().lifecycle)

                repeat(5) { index -> ingest(created, index) }
                awaitUntil {
                    sqliteLong(contextPath, "SELECT count(*) FROM organized_memory") == 1L &&
                        sqliteLong(contextPath, "SELECT count(*) FROM organized_memory_source") == 1L &&
                        sqliteLong(contextPath, "SELECT count(*) FROM organization_pending") == 0L &&
                        created
                            .bootstrap()
                            .memoryMap
                            ?.content
                            ?.contains("CM4 map version one") == true
                }
                val organizedId = firstOrganizedId(contextPath)
                val rawId = firstRawId(contextPath)
                assertTrue(organizedId.startsWith("O-MEM-"))
                val bootstrap = created.bootstrap()
                assertTrue(bootstrap.mapStatus in setOf(MapStatus.STALE, MapStatus.CURRENT))
                val mapView = assertNotNull(bootstrap.memoryMap)
                assertTrue(mapView.content.startsWith("---\n"))
                assertTrue(mapView.content.contains("# Memory Overview"))
                assertEquals(
                    sqliteString(contextPath, "SELECT MAX(updated_at) FROM organized_memory"),
                    SHANGHAI_MILLIS.format(mapView.sourceUpdatedAt),
                )

                val searchResults = created.searchMemory("resource notes", domains = listOf("domain-a"), limit = 5)
                assertEquals(listOf(organizedId), searchResults.map { it.id })
                val organized = created.getMemory(organizedId)
                val raw = created.getRawMemory(rawId)
                assertEquals(searchResults.single(), organized)
                assertEquals(rawId, raw?.id)
                assertTrue(organized?.content.orEmpty().contains("alcyone://resources/journey/data.bin"))
                val requestsAfterGet = stub.requestTypes().size
                repeat(3) {
                    created.getMemory(organizedId)
                    created.getRawMemory(rawId)
                }
                assertEquals(requestsAfterGet, stub.requestTypes().size, "Get/GetRaw 不得新增 LLM 请求")

                val uri = RESOURCE_URI.find(organized!!.content)?.value ?: error("resource URI missing from organized memory")
                val metadata = created.loadMeta(uri)
                assertEquals(uri, metadata.uri)
                assertEquals(resourceBytes.size.toLong(), metadata.sizeBytes)
                assertTrue(metadata.modifiedAt != null)
                assertTrue(created.read(uri).contentEquals(resourceBytes))
                val chunks = created.readStream(uri).toList()
                assertTrue(chunks.all { chunk -> chunk.size <= 64 * 1024 })
                assertTrue(chunks.size > 2)
                assertTrue(chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.contentEquals(resourceBytes))

                assertEquals(
                    listOf(
                        Cm4HttpStub.RequestType.EXTRACTION,
                        Cm4HttpStub.RequestType.GOVERNANCE,
                        Cm4HttpStub.RequestType.MEMORY_MAP,
                        Cm4HttpStub.RequestType.SEARCH_SCANNER,
                        Cm4HttpStub.RequestType.SEARCH_RERANK,
                    ),
                    stub.requestTypes(),
                )
                assertTrue(stub.recordedRequests().all { request -> request.authorizationPresent })
            } finally {
                runCatching { context?.close() }
                val countAfterClose = stub.requestTypes().size
                delay(100)
                assertEquals(countAfterClose, stub.requestTypes().size, "close 后不得新增请求")
                val closed = assertFailsWith<ContextLifecycleException> { context?.searchMemory("query") }
                assertEquals(ContextLifecycleErrorCode.CLOSED, closed.errorCode)
                assertFailsWith<ContextLifecycleException> { context?.getMemory("id") }
                assertFailsWith<ContextLifecycleException> { context?.getRawMemory("id") }
                assertFailsWith<ContextLifecycleException> { context?.loadMeta("alcyone://resources/journey/data.bin") }
                stub.close()
                deleteTree(directory)
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
                    sessionTurnId = "cm4-turn-$index",
                    timestamp = base.plusSeconds(index.toLong()),
                    messages = listOf(ChatMessage(ChatRole.USER, "CM4 journey turn $index")),
                ),
            )
        assertEquals(IngestStatus.CREATED, result.status)
    }

    private fun journeyConfig(
        contextPath: Path,
        vfsConfig: VfsRuntimeConfig,
        stub: Cm4HttpStub,
    ): ContextConfig {
        val profile =
            LlmProfileConfig(
                name = "cm4",
                baseUrl = stub.baseUrl,
                apiKey = "cm4-e2e-secret-key-marker",
                model = "fake-cm4",
                contextWindowTokens = 64_000,
                jsonMode = LlmResponseFormat.JSON_OBJECT,
                maxAttempts = 1,
            )
        val tasks =
            listOf(
                LlmTask.EXTRACTION,
                LlmTask.CANDIDATE_SCANNER,
                LlmTask.GOVERNANCE,
                LlmTask.MEMORY_MAP,
                LlmTask.MEMORY_SEARCH,
            ).associateWith { task -> LlmTaskConfig("cm4", 32_000, 4_000) }
        return ContextConfig(
            databasePath = contextPath,
            lifecycleShutdownTimeout = 30.seconds,
            vfsConfig = vfsConfig,
            llm = LlmConfig(profiles = mapOf(profile.name to profile), tasks = tasks, safetyMarginPercent = 0),
            extraction = ExtractionConfig(countThreshold = 5, idleEnabled = false),
            organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
            memoryMap = MemoryMapConfig(MemoryMapPeriodicConfig(enabled = false)),
            retrieval = RetrievalConfig(SearchConfig(maxLimit = 5)),
        )
    }

    private fun journeyVfsConfig(
        directory: Path,
        memoryRoot: Path,
        resourceRoot: Path,
    ): VfsRuntimeConfig =
        VfsRuntimeConfig(
            stateDatabase = directory.resolve("data/vfs.db"),
            namespaces = setOf("memory", "resources"),
            mounts =
                listOf(
                    MountConfig(VfsPath.parse("/memory"), "cm4-memory", memoryRoot),
                    MountConfig(VfsPath.parse("/resources"), "cm4-resources", resourceRoot),
                ),
        )

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
        val RESOURCE_URI = Regex("alcyone://resources/[^\\s]+")
        val SHANGHAI_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.ofHours(8))
    }
}
