package com.github.noahshen.alcyone.context.memory.runtime.evaluation

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapSnapshot
import java.time.Instant

/** Public JVM-friendly test data factories; no Runtime service, database, or network is created. */
object OfflineEvaluationTestFixtures {
    val BASE_TIME: Instant = Instant.parse("2026-11-08T00:00:00Z")

    @JvmStatic
    fun turn(
        id: Long,
        text: String = "turn-$id",
    ): StoredConversationTurn =
        StoredConversationTurn(
            id = id,
            runtimeId = "eval-runtime",
            agentId = "eval-agent",
            sessionId = "eval-session",
            sessionTurnId = "turn-$id",
            timestamp = BASE_TIME.plusSeconds(id),
            createdAt = BASE_TIME.plusSeconds(id),
            messages = listOf(ChatMessage(ChatRole.USER, text)),
            extractionStatus = ExtractionStatus.PENDING,
        )

    @JvmStatic
    fun turns(vararg ids: Long): List<StoredConversationTurn> = ids.map(::turn)

    @JvmStatic
    fun raw(id: String = "raw-1"): RawMemory =
        RawMemory(id, "raw-content", MemoryOrigin.EXTRACTION, listOf(stableSource(turn(1L))), BASE_TIME)

    @JvmStatic
    fun organized(id: String = "memory-1"): OrganizedMemory =
        OrganizedMemory(id, "content-$id", listOf("work"), listOf("raw-1"), BASE_TIME, BASE_TIME)

    @JvmStatic
    fun mapSnapshot(memories: List<OrganizedMemory> = listOf(organized())): MemoryMapSnapshot =
        MemoryMapSnapshot(memories = memories.sortedBy { it.id }, sourceUpdatedAt = memories.maxOfOrNull { it.updatedAt })

    @JvmStatic
    fun context(
        turns: List<StoredConversationTurn> = turns(1L),
    ): com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext =
        com.github.noahshen.alcyone.context.memory.core.extraction
            .ExtractionContext(turns.first().sessionKey, turns)

    @JvmStatic
    fun stableSource(turn: StoredConversationTurn): MemorySourceRef.ConversationTurnRef =
        MemorySourceRef.ConversationTurnRef(turn.runtimeId, turn.agentId, turn.sessionId, turn.sessionTurnId)
}
