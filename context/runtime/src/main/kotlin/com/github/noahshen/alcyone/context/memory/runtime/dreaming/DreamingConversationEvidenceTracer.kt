package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext

/** Raw 来源链或相邻 Turn 无法满足最终证据契约时的安全失败。 */
internal class DreamingEvidenceTraceException(
    message: String,
) : IllegalStateException(message)

/** 在会话业务键权限边界内解析 Raw 来源链、反查 Organized 并补齐同 Session 前后邻居。 */
internal class DreamingConversationEvidenceTracer(
    private val database: ContextDatabase,
) {
    suspend fun trace(
        candidate: DreamingTopicEvidenceCandidate,
        selectedRootIds: List<String>,
    ): DreamingConversationTrace =
        database.read { read ->
            val rawById = linkedMapOf<String, RawMemory>()
            candidate.rawMemories.forEach { raw -> rawById[raw.id] = raw }
            val roots =
                selectedRootIds.distinct().map { id ->
                    rawById[id]
                        ?: throw DreamingEvidenceTraceException("conversation need selected a raw outside the candidate snapshot")
                }
            val conversationByKey = linkedMapOf<TurnBusinessKey, StoredConversationTurn>()
            val completedRaw = hashSetOf<String>()
            val activeRaw = linkedSetOf<String>()

            fun visit(raw: RawMemory) {
                if (raw.id in completedRaw) return
                if (!activeRaw.add(raw.id)) throw DreamingEvidenceTraceException("raw memory source cycle detected")
                raw.sources.forEach { source ->
                    when (source) {
                        is MemorySourceRef.ConversationTurnRef -> {
                            val turn =
                                read.conversations.findByBusinessKey(
                                    source.runtimeId,
                                    source.agentId,
                                    source.sessionId,
                                    source.sessionTurnId,
                                ) ?: throw DreamingEvidenceTraceException("conversation turn source is missing")
                            conversationByKey.putIfAbsent(turn.businessKey(), turn)
                        }

                        is MemorySourceRef.RawMemoryRef -> {
                            val linked =
                                read.rawMemories.findById(source.memoryId)
                                    ?: throw DreamingEvidenceTraceException("linked raw memory source is missing")
                            rawById.putIfAbsent(linked.id, linked)
                            visit(linked)
                        }
                    }
                }
                activeRaw.remove(raw.id)
                completedRaw.add(raw.id)
            }

            roots.forEach(::visit)
            val allEvidenceRaw =
                rawById.values.sortedWith(compareBy<RawMemory> { raw -> raw.createdAt }.thenBy { raw -> raw.id })
            val organized = linkedMapOf<String, OrganizedMemory>()
            candidate.organizedMemories.forEach { memory -> organized[memory.id] = memory }
            allEvidenceRaw.forEach { raw -> read.reverseOrganized(read, raw.id, organized) }

            val currentKeys = candidate.newConversationTurns.mapTo(hashSetOf()) { turn -> turn.businessKey() }
            val historical = linkedMapOf<TurnBusinessKey, StoredConversationTurn>()
            conversationByKey.values.forEach { anchor ->
                historical[anchor.businessKey()] = anchor
                val before =
                    read.conversations
                        .sessionWindow(anchor.sessionKey, anchor.timestamp, anchor.id, 2)
                        .lastOrNull { turn -> turn.businessKey() != anchor.businessKey() }
                if (before != null && before.businessKey() !in currentKeys) {
                    historical.putIfAbsent(before.businessKey(), before)
                }
                val after = read.conversations.sessionWindowAfter(anchor.sessionKey, anchor.timestamp, anchor.id, 1).firstOrNull()
                if (after != null && after.businessKey() !in currentKeys) {
                    historical.putIfAbsent(after.businessKey(), after)
                }
            }

            DreamingConversationTrace(
                rawMemories = allEvidenceRaw,
                organizedMemories = organized.values.sortedBy { memory -> memory.id },
                historicalConversationTurns =
                    historical.values
                        .filter { turn -> turn.businessKey() !in currentKeys }
                        .sortedWith(compareBy<StoredConversationTurn> { turn -> turn.timestamp }.thenBy { turn -> turn.id }),
            )
        }

    private fun ReadContext.reverseOrganized(
        read: ReadContext,
        rawId: String,
        destination: MutableMap<String, OrganizedMemory>,
    ) {
        val ids = read.organizedMemories.findOrganizedByRaw(rawId).distinct()
        if (ids.isEmpty()) return
        val rows = read.organizedMemories.findByIds(ids)
        if (rows.map { memory -> memory.id }.toSet() != ids.toSet()) {
            throw DreamingEvidenceTraceException("organized source relation is incomplete")
        }
        rows.forEach { memory -> destination.putIfAbsent(memory.id, memory) }
    }

    private data class TurnBusinessKey(
        val runtimeId: String,
        val agentId: String,
        val sessionId: String,
        val sessionTurnId: String,
    )

    private fun StoredConversationTurn.businessKey(): TurnBusinessKey = TurnBusinessKey(runtimeId, agentId, sessionId, sessionTurnId)
}
