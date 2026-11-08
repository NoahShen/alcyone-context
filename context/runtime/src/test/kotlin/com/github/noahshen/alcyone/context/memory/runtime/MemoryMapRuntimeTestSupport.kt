package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapArtifact
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapDocument
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapRenderer
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapGeneratorPort
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicConfig
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPeriodicTickSource
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

internal val MAP_NOW: Instant = Instant.parse("2026-11-08T04:00:00Z")

internal fun mapArtifact(
    sourceUpdatedAt: Instant?,
    generatedAt: Instant = MAP_NOW,
    overview: String = "map overview",
): MemoryMapArtifact {
    val document =
        MemoryMapDocument(
            overview = overview,
            navigation = "map navigation",
            domains = emptyList(),
            generatedAt = generatedAt,
            sourceUpdatedAt = sourceUpdatedAt,
        )
    return MemoryMapArtifact(document, MemoryMapRenderer.render(document))
}

internal fun memoryMapConfig(periodicEnabled: Boolean = false): MemoryMapConfig =
    MemoryMapConfig(MemoryMapPeriodicConfig(enabled = periodicEnabled))

internal fun memoryMapVfsConfig(fixture: ContextFixture): VfsRuntimeConfig =
    fixture.vfsConfig(
        namespaces = setOf("memory"),
        mounts = listOf(fixture.localMount(vfsPath = "/memory", storageKey = "ct19-memory-map", root = fixture.mountRoot)),
    )

internal fun memoryMapContextConfig(
    fixture: ContextFixture,
    llm: LlmConfig?,
    mapConfig: MemoryMapConfig = memoryMapConfig(),
): ContextConfig =
    fixture
        .config(vfsConfig = memoryMapVfsConfig(fixture))
        .copy(
            llm = llm,
            extraction = ExtractionConfig(idleEnabled = false),
            memoryMap = mapConfig,
        )

internal fun memoryMapLlm(
    stub: LlmHttpStub,
    mode: LlmResponseFormat = LlmResponseFormat.JSON_SCHEMA,
    includeTask: Boolean = true,
): LlmConfig {
    val profile =
        LlmProfileConfig(
            name = "map",
            baseUrl = stub.baseUrl,
            apiKey = "ct19-b2-map-key-marker",
            model = "fake-map",
            contextWindowTokens = 64_000,
            jsonMode = mode,
            maxAttempts = 1,
        )
    return LlmConfig(
        profiles = mapOf(profile.name to profile),
        tasks =
            if (includeTask) {
                mapOf(
                    LlmTask.MEMORY_MAP to
                        LlmTaskConfig(
                            profile = profile.name,
                            maxInputTokens = 32_000,
                            maxOutputTokens = 4_000,
                        ),
                )
            } else {
                emptyMap()
            },
    )
}

internal fun multiPipelineLlm(stub: LlmHttpStub): LlmConfig {
    val profile =
        LlmProfileConfig(
            name = "shared",
            baseUrl = stub.baseUrl,
            apiKey = "ct19-b2-shared-key-marker",
            model = "fake-model",
            contextWindowTokens = 64_000,
            jsonMode = LlmResponseFormat.JSON_OBJECT,
            maxAttempts = 1,
        )
    return LlmConfig(
        profiles = mapOf(profile.name to profile),
        tasks =
            mapOf(
                LlmTask.MEMORY_MAP to LlmTaskConfig(profile.name, 32_000, 4_000),
                LlmTask.CANDIDATE_SCANNER to LlmTaskConfig(profile.name, 16_000, 2_000),
                LlmTask.GOVERNANCE to LlmTaskConfig(profile.name, 16_000, 2_000),
                LlmTask.EXTRACTION to LlmTaskConfig(profile.name, 16_000, 2_000),
            ),
    )
}

internal class RecordingMemoryMapGenerator(
    private val behavior: suspend (Int) -> MemoryMapArtifact,
) : MemoryMapGeneratorPort {
    val calls = AtomicInteger(0)

    override suspend fun generate(): MemoryMapArtifact {
        val call = calls.incrementAndGet()
        return behavior(call)
    }
}

internal class ManualMemoryMapTicks : MemoryMapPeriodicTickSource {
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    fun tick() {
        check(ticks.trySend(Unit).isSuccess)
    }

    fun close() {
        ticks.close()
    }

    override suspend fun awaitTick(): Boolean = ticks.receiveCatching().isSuccess
}

internal suspend fun insertOrganized(
    database: ContextDatabase,
    id: String,
    updatedAt: Instant,
    domains: List<String> = listOf("domain"),
) {
    database.write { tx ->
        check(
            tx.organizedMemories.insertNew(
                OrganizedMemory(
                    id = id,
                    content = "content-$id",
                    domains = domains,
                    rawMemoryIds = emptyList(),
                    createdAt = updatedAt.minusSeconds(1),
                    updatedAt = updatedAt,
                ),
            ) == 1,
        )
    }
}

internal suspend fun insertPendingRaw(
    database: ContextDatabase,
    id: String,
    createdAt: Instant,
) {
    database.write { tx ->
        check(tx.rawMemories.insertBatch(listOf(RawMemory(id, "raw-$id", MemoryOrigin.EXTRACTION, emptyList(), createdAt))) == 1)
        check(tx.pending.enqueue(id) == 1)
    }
}

internal suspend fun awaitMemoryMapWiringUntil(condition: suspend () -> Boolean) {
    withTimeout(10_000L) {
        while (!condition()) delay(10)
    }
}
