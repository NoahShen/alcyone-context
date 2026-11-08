package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.RetrievalConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.SearchConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** CT20 B2 A08：真实 Factory/SQLite/Ktor client + Fake HTTP Search/Get/GetRaw 闭环。 */
class RetrievalContextEndToEndTest {
    private val now: Instant = Instant.parse("2026-11-09T02:00:00Z")

    @Test
    fun `public search get and get raw use real sqlite and fake http without extra llm calls`() =
        runBlocking {
            val stub =
                ExtractionHttpStub(
                    listOf(
                        ExtractionHttpStub.chatCompletionEnvelope("""{"candidate_ids":["O-E2E-1"]}"""),
                        ExtractionHttpStub.chatCompletionEnvelope("""{"memory_ids":["O-E2E-1"]}"""),
                    ),
                )
            val directory = Files.createTempDirectory("ct20-retrieval-e2e-")
            var context: com.github.noahshen.alcyone.context.memory.api.AlcyoneContext? = null
            try {
                val contextPath = directory.resolve("data/context.db")
                val vfsConfig =
                    VfsRuntimeConfig(
                        stateDatabase = directory.resolve("data/vfs.db"),
                        namespaces = emptySet(),
                        mounts = emptyList(),
                    )
                val config =
                    ContextConfig(
                        databasePath = contextPath,
                        lifecycleShutdownTimeout = 30.seconds,
                        vfsConfig = vfsConfig,
                        llm = retrievalLlm(stub),
                        extraction = ExtractionConfig(idleEnabled = false),
                        retrieval = RetrievalConfig(SearchConfig(maxLimit = 5)),
                    )
                AlcyoneContextFactory.initializeDatabase(config)
                seed(contextPath)
                val created = AlcyoneContextFactory.create(config, vfsConfig)
                context = created
                created.start()

                assertEquals(ContextLifecycleState.RUNNING, created.getStatus().lifecycle)
                assertEquals(MapStatus.MISSING, created.bootstrap().mapStatus)
                val results = created.searchMemory("  e2e query  ", domains = listOf("domain"), limit = 3)
                assertEquals(listOf("O-E2E-1"), results.map { it.id })
                assertEquals("organized e2e content", results.single().content)
                assertEquals(2, stub.requestCount())

                val organized = created.getMemory("O-E2E-1")
                val raw = created.getRawMemory("R-E2E-1")
                assertEquals("organized e2e content", organized?.content)
                assertEquals("raw e2e content", raw?.content)
                repeat(5) {
                    assertEquals(organized, created.getMemory("O-E2E-1"))
                    assertEquals(raw, created.getRawMemory("R-E2E-1"))
                }
                assertNull(created.getMemory("missing"))
                assertEquals(2, stub.requestCount(), "Get/GetRaw 不得新增 LLM 请求")
            } finally {
                runCatching { context?.close() }
                val closed = assertFailsWith<ContextLifecycleException> { context?.searchMemory("query") }
                assertEquals(ContextLifecycleErrorCode.CLOSED, closed.errorCode)
                assertFailsWith<ContextLifecycleException> { context?.getMemory("id") }
                assertFailsWith<ContextLifecycleException> { context?.getRawMemory("id") }
                stub.close()
                deleteTree(directory)
            }
        }

    private suspend fun seed(path: Path) {
        val executor = ContextDatabaseFactory.open(path)
        try {
            val database = ContextDatabaseImpl(executor)
            database.write { tx ->
                assertEquals(
                    1,
                    tx.organizedMemories.insertNew(
                        OrganizedMemory(
                            id = "O-E2E-1",
                            content = "organized e2e content",
                            domains = listOf("domain"),
                            rawMemoryIds = listOf("R-E2E-1"),
                            createdAt = now,
                            updatedAt = now.plusSeconds(1),
                        ),
                    ),
                )
                assertEquals(
                    1,
                    tx.rawMemories.insertBatch(
                        listOf(
                            RawMemory(
                                id = "R-E2E-1",
                                content = "raw e2e content",
                                generatedBy = MemoryOrigin.EXTRACTION,
                                sources = emptyList(),
                                createdAt = now,
                            ),
                        ),
                    ),
                )
            }
        } finally {
            executor.close()
        }
    }

    private fun retrievalLlm(stub: ExtractionHttpStub): LlmConfig {
        val profile =
            LlmProfileConfig(
                name = "search",
                baseUrl = stub.baseUrl,
                apiKey = "ct20-b2-e2e-key-marker",
                model = "fake-search",
                contextWindowTokens = 64_000,
                jsonMode = LlmResponseFormat.JSON_OBJECT,
                maxAttempts = 1,
            )
        return LlmConfig(
            profiles = mapOf(profile.name to profile),
            tasks = mapOf(LlmTask.MEMORY_SEARCH to LlmTaskConfig("search", 32_000, 4_000)),
            safetyMarginPercent = 0,
        )
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
