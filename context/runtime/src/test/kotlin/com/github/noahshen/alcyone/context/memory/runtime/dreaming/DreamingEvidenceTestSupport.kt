package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal val EVIDENCE_BASE: Instant = Instant.parse("2026-11-09T09:00:00Z")

internal fun evidenceTopic(
    title: String = "topic",
    description: String = "description",
    sourceTurnIds: List<Long> = listOf(1),
): DiscoveredTopic = DiscoveredTopic(title, description, listOf("keyword"), sourceTurnIds)

internal fun evidenceTurn(
    id: Long,
    content: String = "message-$id",
): StoredConversationTurn =
    StoredConversationTurn(
        id = id,
        runtimeId = "runtime-$id",
        agentId = "agent-$id",
        sessionId = "session-$id",
        sessionTurnId = "turn-$id",
        timestamp = EVIDENCE_BASE.plusSeconds(id),
        createdAt = EVIDENCE_BASE.plusSeconds(id),
        messages = listOf(ChatMessage(ChatRole.USER, content)),
        extractionStatus = com.github.noahshen.alcyone.context.memory.core.ExtractionStatus.PENDING,
    )

internal fun evidenceOrganized(
    id: String,
    rawIds: List<String> = listOf("R-1"),
    content: String = "organized-$id",
    domains: List<String> = listOf("domain"),
): OrganizedMemory = OrganizedMemory(id, content, domains, rawIds, EVIDENCE_BASE, EVIDENCE_BASE.plusSeconds(1))

internal fun evidenceRaw(
    id: String,
    origin: MemoryOrigin = MemoryOrigin.EXTRACTION,
    content: String = "raw-$id",
    sources: List<MemorySourceRef> = emptyList(),
    createdAt: Instant = EVIDENCE_BASE,
): RawMemory = RawMemory(id, content, origin, sources, createdAt)

internal suspend fun insertEvidenceRaw(
    database: ContextDatabase,
    raw: RawMemory,
) {
    database.write { tx -> check(tx.rawMemories.insertBatch(listOf(raw)) == 1) }
}

internal suspend fun insertEvidenceOrganized(
    database: ContextDatabase,
    organized: OrganizedMemory,
    rawIds: List<String> = organized.rawMemoryIds,
) {
    database.write { tx ->
        check(tx.organizedMemories.insertNew(organized) == 1)
        rawIds.distinct().forEach { rawId -> check(tx.organizedMemories.insertSourceLink(organized.id, rawId) == 1) }
    }
}

internal suspend fun insertEvidenceConversationTurn(
    database: ContextDatabase,
    runtimeId: String = "runtime",
    agentId: String = "agent",
    sessionId: String = "session",
    sessionTurnId: String,
    timestamp: Instant,
    content: String = "message-$sessionTurnId",
): StoredConversationTurn {
    insertDreamingTurn(
        database = database,
        runtimeId = runtimeId,
        agentId = agentId,
        sessionId = sessionId,
        sessionTurnId = sessionTurnId,
        timestamp = timestamp,
        createdAt = timestamp,
    )
    return database.read { read ->
        checkNotNull(read.conversations.findByBusinessKey(runtimeId, agentId, sessionId, sessionTurnId)) {
            "inserted conversation turn was not found"
        }
    }
}

internal fun candidateIdsResponse(ids: List<String>): String = """{"candidate_ids":[""" + ids.joinToString(",") { id -> "\"$id\"" } + "]}"

internal fun noveltyResponse(
    decision: String,
    ids: List<String> = emptyList(),
): String = """{"decision":"$decision","coveredOrganizedIds":[""" + ids.joinToString(",") { id -> "\"$id\"" } + "]}"

internal fun conversationNeedResponse(ids: List<String> = emptyList()): String =
    """{"memoryIds":[""" + ids.joinToString(",") { id -> "\"$id\"" } + "]}"

internal fun evidenceResponse(
    content: String,
    finishReason: String? = "stop",
): LlmResponse = LlmResponse(content = content, usage = null, finishReason = finishReason)

internal class FakeEvidenceLlmClient(
    private val behavior: suspend (LlmRequest, Int) -> LlmResponse,
) : LlmClient {
    val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return behavior(request, requests.size)
    }
}

internal class TrackingEvidenceDatabase(
    private val delegate: ContextDatabase,
    private val reverseIdsOverride: ((String) -> List<String>)? = null,
) : ContextDatabase {
    val reads = AtomicInteger(0)
    val writes = AtomicInteger(0)
    val readActive = AtomicBoolean(false)

    override suspend fun <T> read(block: (com.github.noahshen.alcyone.context.memory.core.repository.ReadContext) -> T): T {
        reads.incrementAndGet()
        readActive.set(true)
        return try {
            delegate.read { read -> block(TrackingEvidenceRead(read)) }
        } finally {
            readActive.set(false)
        }
    }

    override suspend fun <T> write(block: (com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext) -> T): T {
        writes.incrementAndGet()
        return delegate.write(block)
    }

    private inner class TrackingEvidenceRead(
        delegate: com.github.noahshen.alcyone.context.memory.core.repository.ReadContext,
    ) : com.github.noahshen.alcyone.context.memory.core.repository.ReadContext by delegate {
        override val organizedMemories: com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryReadOps =
            object : com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryReadOps by delegate.organizedMemories {
                override fun findOrganizedByRaw(rawId: String): List<String> =
                    reverseIdsOverride?.invoke(rawId) ?: delegate.organizedMemories.findOrganizedByRaw(rawId)
            }
    }
}

internal class EvidenceCountingEstimator(
    private val organizedBase: Int = 0,
    private val organizedPerItem: Int = 1,
    private val noveltyBase: Int = 0,
    private val noveltyPerItem: Int = 1,
    private val rawBase: Int = 0,
    private val rawPerItem: Int = 1,
    private val schemaTokens: Int = 0,
) : TokenEstimator {
    override fun estimateText(text: String): Int = text.length

    override fun estimatePrompt(messages: List<LlmMessage>): Int {
        val user = messages.lastOrNull()?.content.orEmpty()
        return when {
            user.contains("<organized count=") -> noveltyBase + count(user, "<organized_memory id=") * noveltyPerItem
            user.contains("<raw_memories count=") -> rawBase + count(user, "<raw_memory id=") * rawPerItem
            else -> organizedBase + count(user, "<candidate id=") * organizedPerItem
        }
    }

    override fun estimateSchema(schema: String?): Int = if (schema == null) 0 else schemaTokens

    private fun count(
        text: String,
        marker: String,
    ): Int = Regex(Regex.escape(marker)).findAll(text).count()
}

internal class ConversationNeedCountingEstimator(
    private val base: Int = 0,
    private val perRawCandidate: Int = 1,
    private val schemaTokens: Int = 0,
) : TokenEstimator {
    override fun estimateText(text: String): Int = text.length

    override fun estimatePrompt(messages: List<LlmMessage>): Int {
        val user = messages.lastOrNull()?.content.orEmpty()
        return base + Regex("<raw_candidate id=\\\"").findAll(user).count() * perRawCandidate
    }

    override fun estimateSchema(schema: String?): Int = if (schema == null) 0 else schemaTokens
}

internal fun evidenceLlmConfig(
    mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
    includeMemorySearch: Boolean = true,
    includeEvidence: Boolean = true,
    includeTopic: Boolean = false,
    maxInputTokens: Int = 32_000,
    maxOutputTokens: Int = 1_000,
    contextWindowTokens: Int = 64_000,
    safetyMarginPercent: Int = 0,
): LlmConfig {
    val profile =
        LlmProfileConfig(
            name = "evidence",
            baseUrl = "http://127.0.0.1:1",
            apiKey = "ct24-safe-key-marker",
            model = "fake-evidence",
            contextWindowTokens = contextWindowTokens,
            jsonMode = mode,
            maxAttempts = 1,
        )
    val tasks = mutableMapOf<LlmTask, LlmTaskConfig>()
    if (includeMemorySearch) tasks[LlmTask.MEMORY_SEARCH] = LlmTaskConfig(profile.name, maxInputTokens, maxOutputTokens)
    if (includeEvidence) tasks[LlmTask.DREAMING_EVIDENCE] = LlmTaskConfig(profile.name, maxInputTokens, maxOutputTokens)
    if (includeTopic) tasks[LlmTask.DREAMING_TOPIC] = LlmTaskConfig(profile.name, maxInputTokens, maxOutputTokens)
    return LlmConfig(profiles = mapOf(profile.name to profile), tasks = tasks, safetyMarginPercent = safetyMarginPercent)
}
