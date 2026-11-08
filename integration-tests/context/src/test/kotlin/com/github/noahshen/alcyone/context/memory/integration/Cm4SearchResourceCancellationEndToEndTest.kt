@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.RetrievalConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.SearchConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** CT21 A06/A07：Search 返回的 Resource URI 驱动真实 LocalFs 读取与 stream 取消/关闭释放。 */
class Cm4SearchResourceCancellationEndToEndTest {
    private val now: Instant = Instant.parse("2026-11-09T05:00:00Z")

    @Test
    fun `search resource URI supports read and stream and both cancellation paths release vfs streams`() =
        runBlocking {
            val directory = Files.createTempDirectory("cm4-search-resource-")
            val contextPath = directory.resolve("data/context.db")
            val resourceRoot = Files.createDirectories(directory.resolve("resources"))
            val resourceBytes = ByteArray(140 * 1024 + 11) { index -> (index % 241).toByte() }
            val resourceFile = resourceRoot.resolve("cm4").resolve("large.bin")
            Files.createDirectories(resourceFile.parent)
            Files.write(resourceFile, resourceBytes)
            val stub = Cm4HttpStub(contextPath)
            var context: com.github.noahshen.alcyone.context.memory.api.AlcyoneContext? = null
            try {
                val vfsConfig =
                    VfsRuntimeConfig(
                        stateDatabase = directory.resolve("data/vfs.db"),
                        namespaces = setOf("resources"),
                        mounts = listOf(MountConfig(VfsPath.parse("/resources"), "cm4-resources", resourceRoot)),
                    )
                val config = resourceSearchConfig(contextPath, vfsConfig, stub)
                AlcyoneContextFactory.initializeDatabase(config)
                seedOrganized(contextPath)
                val created = AlcyoneContextFactory.create(config, vfsConfig)
                context = created
                created.start()

                val search = created.searchMemory("large resource", limit = 3)
                val memory = search.single()
                val uri = RESOURCE_URI.find(memory.content)?.value ?: error("resource URI missing from search result")
                assertEquals("alcyone://resources/cm4/large.bin", uri)

                val metadata = created.loadMeta(uri)
                assertEquals(uri, metadata.uri)
                assertEquals(resourceBytes.size.toLong(), metadata.sizeBytes)
                assertTrue(metadata.modifiedAt != null)
                assertTrue(created.read(uri).contentEquals(resourceBytes))
                val normalChunks = created.readStream(uri).toList()
                assertTrue(normalChunks.all { chunk -> chunk.size <= 64 * 1024 })
                assertTrue(normalChunks.size > 2)
                assertTrue(normalChunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.contentEquals(resourceBytes))

                val impl = created as AlcyoneContextImpl
                val vfs = checkNotNull(impl.vfsForTesting())
                awaitUntil { vfs.trackedStreamCount == 0 }

                val firstChunk = CompletableDeferred<Unit>()
                val cancelledCollector =
                    launch(Dispatchers.Default) {
                        created.readStream(uri).collect {
                            firstChunk.complete(Unit)
                            awaitCancellation()
                        }
                    }
                firstChunk.await()
                awaitUntil { vfs.trackedStreamCount == 1 }
                cancelledCollector.cancelAndJoin()
                awaitUntil { vfs.trackedStreamCount == 0 }

                val secondFirstChunk = CompletableDeferred<Unit>()
                val closeCollector =
                    launch(Dispatchers.Default) {
                        created.readStream(uri).collect {
                            secondFirstChunk.complete(Unit)
                            awaitCancellation()
                        }
                    }
                secondFirstChunk.await()
                awaitUntil { vfs.trackedStreamCount == 1 }
                withTimeout(10_000L) { created.closeAsync() }
                awaitUntil { vfs.trackedStreamCount == 0 }
                closeCollector.cancelAndJoin()

                assertEquals(
                    listOf(Cm4HttpStub.RequestType.SEARCH_SCANNER, Cm4HttpStub.RequestType.SEARCH_RERANK),
                    stub.requestTypes(),
                )
            } finally {
                runCatching { context?.close() }
                val closed =
                    assertFailsWith<ContextLifecycleException> {
                        context?.readStream("alcyone://resources/cm4/large.bin")?.toList()
                    }
                assertEquals(ContextLifecycleErrorCode.CLOSED, closed.errorCode)
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
                            id = "O-RESOURCE-1",
                            content = "Large resource is alcyone://resources/cm4/large.bin for CM4 stream verification.",
                            domains = listOf("domain-a"),
                            rawMemoryIds = emptyList(),
                            createdAt = now,
                            updatedAt = now.plusSeconds(1),
                        ),
                    ),
                )
            }
        } finally {
            executor.close()
        }
    }

    private fun resourceSearchConfig(
        contextPath: Path,
        vfsConfig: VfsRuntimeConfig,
        stub: Cm4HttpStub,
    ): ContextConfig {
        val profile =
            LlmProfileConfig(
                name = "cm4-search",
                baseUrl = stub.baseUrl,
                apiKey = "cm4-search-secret-marker",
                model = "fake-search",
                contextWindowTokens = 64_000,
                jsonMode = LlmResponseFormat.JSON_OBJECT,
                maxAttempts = 1,
            )
        return ContextConfig(
            databasePath = contextPath,
            lifecycleShutdownTimeout = 30.seconds,
            vfsConfig = vfsConfig,
            llm =
                LlmConfig(
                    profiles = mapOf(profile.name to profile),
                    tasks = mapOf(LlmTask.MEMORY_SEARCH to LlmTaskConfig("cm4-search", 32_000, 4_000)),
                    safetyMarginPercent = 0,
                ),
            extraction = ExtractionConfig(idleEnabled = false),
            retrieval = RetrievalConfig(SearchConfig(maxLimit = 5)),
        )
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
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

    private companion object {
        val RESOURCE_URI = Regex("alcyone://resources/[^\\s]+")
    }
}
