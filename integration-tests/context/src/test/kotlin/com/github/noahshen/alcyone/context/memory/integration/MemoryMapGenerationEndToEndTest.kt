@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapRenderer
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapGenerator
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPublisher
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapService
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryMapGenerationEndToEndTest {
    private val base: Instant = Instant.parse("2026-11-08T01:00:00Z")
    private val generatedAt: Instant = Instant.parse("2026-11-08T03:00:00Z")

    @Test
    fun `real sqlite and LocalFs publish the full snapshot map while a concurrent write stays out of T1`() =
        TestDatabase().use { database ->
            database.open().use { executor ->
                runBlocking {
                    val contextDatabase = ContextDatabaseImpl(executor)
                    insert(contextDatabase, "O-MEM-0002", "finance memory", listOf("finance"), base.plusSeconds(2))
                    insert(contextDatabase, "O-MEM-0001", "work memory", listOf("work"), base.plusSeconds(1))
                    val directory = database.path.parent
                    val mountRoot = Files.createDirectories(directory.resolve("memory-map-storage"))
                    val vfsConfig =
                        VfsRuntimeConfig(
                            stateDatabase = directory.resolve("map-vfs.db"),
                            namespaces = setOf("memory"),
                            mounts =
                                listOf(
                                    MountConfig(
                                        vfsPath = VfsPath.parse("/memory"),
                                        storageKey = "memory-map-e2e",
                                        root = mountRoot,
                                    ),
                                ),
                        )
                    val vfs = AlcyoneVfs.create(vfsConfig)
                    try {
                        val llm =
                            FakeLlmClient { _ ->
                                insert(contextDatabase, "O-MEM-0003", "new finance memory", listOf("finance"), base.plusSeconds(3))
                                LlmResponse(
                                    """{"overview":"Snapshot overview","navigation":"Use the finance and work domains.","domains":["finance","work"]}""",
                                    usage = null,
                                    finishReason = "stop",
                                )
                            }
                        val generator =
                            MemoryMapGenerator(
                                database = contextDatabase,
                                llmClient = llm,
                                llmConfig = llmConfig(),
                                clock = Clock.fixed(generatedAt, ZoneOffset.UTC),
                                pageSize = 1,
                            )
                        val service = MemoryMapService(generator, MemoryMapPublisher(vfs))

                        val artifact = service.generateAndPublish()
                        val mapUri = VfsUri.parse("alcyone://memory/map.md")
                        val published = vfs.read(mapUri)
                        val parsed = MemoryMapRenderer.parse(published)
                        val text = published.toString(Charsets.UTF_8)

                        assertEquals(1, llm.requests.size)
                        assertEquals(LlmTask.MEMORY_MAP, llm.requests.single().task)
                        assertEquals(LlmResponseFormat.JSON_SCHEMA, llm.requests.single().responseFormat)
                        assertTrue(
                            llm.requests
                                .single()
                                .schema
                                .orEmpty()
                                .contains("\"additionalProperties\": false"),
                        )
                        assertTrue(
                            llm.requests
                                .single()
                                .messages
                                .last()
                                .content
                                .indexOf("O-MEM-0001") <
                                llm.requests
                                    .single()
                                    .messages
                                    .last()
                                    .content
                                    .indexOf("O-MEM-0002"),
                        )
                        assertTrue(
                            !llm.requests
                                .single()
                                .messages
                                .last()
                                .content
                                .contains("O-MEM-0003"),
                        )
                        assertEquals(base.plusSeconds(2), artifact.document.sourceUpdatedAt)
                        assertEquals(base.plusSeconds(2), parsed.sourceUpdatedAt)
                        assertEquals(generatedAt, parsed.generatedAt)
                        assertEquals(listOf("finance", "work"), parsed.domains)
                        assertEquals("Snapshot overview", parsed.overview)
                        assertTrue(text.contains("# Memory Overview"))
                        assertTrue(text.contains("# Memory Navigation"))
                        assertTrue(text.contains("## Domains"))
                        assertEquals(3L, countOrganized(contextDatabase))
                        assertEquals(base.plusSeconds(3), contextDatabase.read { read -> read.organizedMemories.maxUpdatedAt() })
                    } finally {
                        vfs.close()
                    }
                }
            }
        }

    private suspend fun insert(
        database: ContextDatabaseImpl,
        id: String,
        content: String,
        domains: List<String>,
        updatedAt: Instant,
    ) {
        database.write { tx ->
            tx.organizedMemories.insertNew(
                OrganizedMemory(
                    id = id,
                    content = content,
                    domains = domains,
                    rawMemoryIds = emptyList(),
                    createdAt = base,
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    private suspend fun countOrganized(database: ContextDatabaseImpl): Long =
        database.read { read ->
            var count = 0L
            var offset = 0
            while (true) {
                val page = read.organizedMemories.scanPage(offset, 256)
                if (page.isEmpty()) break
                count += page.size
                if (page.size < 256) break
                offset += page.size
            }
            count
        }

    private fun llmConfig(): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "map" to
                        LlmProfileConfig(
                            name = "map",
                            baseUrl = "http://127.0.0.1:1",
                            apiKey = "fake-key",
                            model = "fake-map",
                            contextWindowTokens = 200_000,
                            jsonMode = LlmResponseFormat.JSON_SCHEMA,
                            maxAttempts = 1,
                        ),
                ),
            tasks =
                mapOf(
                    LlmTask.MEMORY_MAP to
                        LlmTaskConfig(
                            profile = "map",
                            maxInputTokens = 100_000,
                            maxOutputTokens = 10_000,
                        ),
                ),
        )

    private class FakeLlmClient(
        private val respond: suspend (LlmRequest) -> LlmResponse,
    ) : LlmClient {
        val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()

        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return respond(request)
        }
    }
}
