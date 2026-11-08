package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal val SEARCH_BASE: Instant = Instant.parse("2026-11-08T06:00:00Z")

internal class SearchDatabaseFixture internal constructor(
    val database: ContextDatabaseImpl,
    val path: Path,
    private val executor: ContextDatabaseExecutor,
) : AutoCloseable {
    override fun close() {
        executor.close()
    }
}

internal fun withSearchDatabase(block: suspend (SearchDatabaseFixture) -> Unit) {
    val directory = Files.createTempDirectory("ct20-search-")
    var fixture: SearchDatabaseFixture? = null
    try {
        val path = directory.resolve("data/context.db")
        ContextDatabaseInitializer.initialize(path)
        val executor = ContextDatabaseFactory.open(path)
        fixture = SearchDatabaseFixture(ContextDatabaseImpl(executor), path, executor)
        runBlocking { block(fixture) }
    } finally {
        fixture?.close()
        deleteTree(directory)
    }
}

internal suspend fun insertSearchMemory(
    database: ContextDatabase,
    id: String,
    domains: List<String> = listOf("domain"),
    content: String = "content-$id",
    createdAt: Instant = SEARCH_BASE,
    updatedAt: Instant = createdAt.plusSeconds(1),
) {
    database.write { tx ->
        check(
            tx.organizedMemories.insertNew(
                OrganizedMemory(
                    id = id,
                    content = content,
                    domains = domains,
                    rawMemoryIds = emptyList(),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
            ) == 1,
        )
    }
}

internal class FakeSearchLlmClient(
    private val behavior: suspend (LlmRequest, Int) -> LlmResponse = { request, _ -> defaultSearchResponse(request) },
) : LlmClient {
    val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return behavior(request, requests.size)
    }
}

internal fun defaultSearchResponse(request: LlmRequest): LlmResponse {
    val ids = requestCandidateIds(request)
    return if (request.messages
            .last()
            .content
            .contains("<limit>")
    ) {
        LlmResponse(rerankResponse(ids.reversed()), usage = null, finishReason = "stop")
    } else {
        LlmResponse(scannerResponse(ids), usage = null, finishReason = "stop")
    }
}

internal fun scannerResponse(ids: List<String>): String = "{\"candidate_ids\":[" + ids.joinToString(",") { id -> "\"$id\"" } + "]}"

internal fun rerankResponse(ids: List<String>): String = "{\"memory_ids\":[" + ids.joinToString(",") { id -> "\"$id\"" } + "]}"

internal fun requestCandidateIds(request: LlmRequest): List<String> =
    CANDIDATE_ID.findAll(request.messages.last().content).map { match -> match.groupValues[1] }.toList()

internal fun searchLlmConfig(
    includeTask: Boolean = true,
    mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
    maxInputTokens: Int = 32_000,
    contextWindowTokens: Int = 64_000,
    safetyMarginPercent: Int = 0,
): LlmConfig {
    val profile =
        LlmProfileConfig(
            name = "search",
            baseUrl = "http://127.0.0.1:1",
            apiKey = "ct20-safe-key-marker",
            model = "fake-search",
            contextWindowTokens = contextWindowTokens,
            jsonMode = mode,
            maxAttempts = 1,
        )
    return LlmConfig(
        profiles = mapOf(profile.name to profile),
        tasks =
            if (includeTask) {
                mapOf(LlmTask.MEMORY_SEARCH to LlmTaskConfig(profile.name, maxInputTokens, 100))
            } else {
                emptyMap()
            },
        safetyMarginPercent = safetyMarginPercent,
    )
}

internal class CountingSearchEstimator(
    private val baseTokens: Int,
    private val itemTokens: Int,
    private val schemaTokens: Int = 0,
    private val rerankBaseTokens: Int = baseTokens,
    private val rerankItemTokens: Int = itemTokens,
) : TokenEstimator {
    override fun estimateText(text: String): Int = text.length

    override fun estimatePrompt(messages: List<LlmMessage>): Int {
        val candidateCount = messages.sumOf { message -> CANDIDATE_ID.findAll(message.content).count() }
        val rerank = messages.any { message -> message.content.contains("<limit>") }
        return if (rerank) {
            rerankBaseTokens + candidateCount * rerankItemTokens
        } else {
            baseTokens + candidateCount * itemTokens
        }
    }

    override fun estimateSchema(schema: String?): Int = if (schema == null) 0 else schemaTokens
}

internal class TrackingSearchDatabase(
    private val delegate: ContextDatabase,
    private val readFailure: Throwable? = null,
    private val pageCancellationAt: Int? = null,
    private val pageCancellation: CancellationException = CancellationException("ct20-page-cancel"),
    private val snapshotOverride: List<OrganizedMemory>? = null,
) : ContextDatabase {
    val readCalls = AtomicInteger(0)
    val scanOffsets: MutableList<Int> = CopyOnWriteArrayList()
    val findByIdsCalls: MutableList<List<String>> = CopyOnWriteArrayList()

    override suspend fun <T> read(block: (ReadContext) -> T): T {
        readCalls.incrementAndGet()
        readFailure?.let { throw it }
        return delegate.read { read -> block(TrackingReadContext(read)) }
    }

    override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)

    private inner class TrackingReadContext(
        delegate: ReadContext,
    ) : ReadContext by delegate {
        override val organizedMemories: OrganizedMemoryReadOps = TrackingOrganizedOps(delegate.organizedMemories)
    }

    private inner class TrackingOrganizedOps(
        private val delegate: OrganizedMemoryReadOps,
    ) : OrganizedMemoryReadOps by delegate {
        override fun scanPage(
            offset: Int,
            limit: Int,
        ): List<OrganizedMemory> {
            if (pageCancellationAt != null && scanOffsets.size == pageCancellationAt) throw pageCancellation
            scanOffsets += offset
            return snapshotOverride?.drop(offset)?.take(limit) ?: delegate.scanPage(offset, limit)
        }

        override fun findByIds(ids: Collection<String>): List<OrganizedMemory> {
            findByIdsCalls += ids.toList()
            return snapshotOverride?.filter { memory -> memory.id in ids } ?: delegate.findByIds(ids)
        }
    }
}

internal fun persistenceFailure(): ContextPersistenceException =
    ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, "ct20-database-failure")

internal fun executeSearchSql(
    path: Path,
    sql: String,
) {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { statement -> statement.execute(sql) }
    }
}

private fun deleteTree(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private val CANDIDATE_ID = Regex("<candidate id=\"([^\"]+)\"")
