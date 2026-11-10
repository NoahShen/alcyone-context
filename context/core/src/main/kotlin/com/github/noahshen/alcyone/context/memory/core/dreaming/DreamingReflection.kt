package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.util.Collections

/** Reflection 模型输出的最小候选；只允许正文与该 Topic 提供的内部 Turn ID。 */
class DreamingInsightCandidate(
    val content: String,
    sourceTurnIds: List<Long>,
) {
    val sourceTurnIds: List<Long> = Collections.unmodifiableList(sourceTurnIds.toList())

    fun copy(
        content: String = this.content,
        sourceTurnIds: List<Long> = this.sourceTurnIds,
    ): DreamingInsightCandidate = DreamingInsightCandidate(content, sourceTurnIds)

    operator fun component1(): String = content

    operator fun component2(): List<Long> = sourceTurnIds

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DreamingInsightCandidate) return false
        return content == other.content && sourceTurnIds == other.sourceTurnIds
    }

    override fun hashCode(): Int = 31 * content.hashCode() + sourceTurnIds.hashCode()

    override fun toString(): String = "DreamingInsightCandidate(contentLength=${content.length}, sourceTurnIdCount=${sourceTurnIds.size})"
}

/** 已校验的 Insight；来源只保存稳定四元业务键，不保存 SQLite 内部 Turn ID。 */
class ValidatedDreamingInsight(
    val content: String,
    sources: List<MemorySourceRef.ConversationTurnRef>,
) {
    val sources: List<MemorySourceRef.ConversationTurnRef> = Collections.unmodifiableList(sources.toList())

    init {
        require(content.isNotBlank()) { "dreaming insight content must not be blank" }
        require(this.sources.isNotEmpty()) { "dreaming insight sources must not be empty" }
    }

    fun copy(
        content: String = this.content,
        sources: List<MemorySourceRef.ConversationTurnRef> = this.sources,
    ): ValidatedDreamingInsight = ValidatedDreamingInsight(content, sources)

    operator fun component1(): String = content

    operator fun component2(): List<MemorySourceRef.ConversationTurnRef> = sources

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ValidatedDreamingInsight) return false
        return content == other.content && sources == other.sources
    }

    override fun hashCode(): Int = 31 * content.hashCode() + sources.hashCode()

    override fun toString(): String = "ValidatedDreamingInsight(contentLength=${content.length}, sourceCount=${sources.size})"
}

/** Reflection 来源或结构校验失败；消息只含安全规则，不携带正文、业务键或内部 ID。 */
class DreamingReflectionValidationException(
    message: String,
) : IllegalArgumentException(message)

/** Reflection 纯逻辑校验；不判断模型 Insight 的语义正确性。 */
object DreamingReflectionValidator {
    /**
     * 校验候选并转换为稳定 `ConversationTurnRef`。
     *
     * 输入 Turn 必须能按内部 ID 与四元业务键建立无歧义映射；任一候选非法时整批拒绝。
     */
    fun validate(
        candidates: List<DreamingInsightCandidate>,
        turns: List<StoredConversationTurn>,
    ): List<ValidatedDreamingInsight> {
        val turnsById = indexTurns(turns)
        val validated =
            candidates.map { candidate ->
                if (candidate.content.isBlank()) {
                    throw DreamingReflectionValidationException("dreaming insight content must not be blank")
                }
                if (candidate.sourceTurnIds.isEmpty()) {
                    throw DreamingReflectionValidationException("dreaming insight sources must not be empty")
                }
                val sourceRefs =
                    candidate.sourceTurnIds.distinct().map { sourceId ->
                        val turn =
                            turnsById[sourceId]
                                ?: throw DreamingReflectionValidationException(
                                    "dreaming insight source is not part of the current topic",
                                )
                        MemorySourceRef.ConversationTurnRef(
                            runtimeId = turn.runtimeId,
                            agentId = turn.agentId,
                            sessionId = turn.sessionId,
                            sessionTurnId = turn.sessionTurnId,
                        )
                    }
                ValidatedDreamingInsight(candidate.content, sourceRefs)
            }
        return Collections.unmodifiableList(validated)
    }

    private fun indexTurns(turns: List<StoredConversationTurn>): Map<Long, StoredConversationTurn> {
        val byId = LinkedHashMap<Long, StoredConversationTurn>()
        val byBusinessKey = LinkedHashMap<TurnBusinessKey, StoredConversationTurn>()
        turns.forEach { turn ->
            val businessKey = turn.businessKey()
            val existingById = byId[turn.id]
            if (existingById != null && existingById != turn) {
                throw DreamingReflectionValidationException("dreaming source turns contain an ambiguous internal id")
            }
            val existingByKey = byBusinessKey[businessKey]
            if (existingByKey != null && existingByKey != turn) {
                throw DreamingReflectionValidationException("dreaming source turns contain an ambiguous business key")
            }
            byId.putIfAbsent(turn.id, turn)
            byBusinessKey.putIfAbsent(businessKey, turn)
        }
        return byId
    }

    private fun StoredConversationTurn.businessKey(): TurnBusinessKey = TurnBusinessKey(runtimeId, agentId, sessionId, sessionTurnId)

    private data class TurnBusinessKey(
        val runtimeId: String,
        val agentId: String,
        val sessionId: String,
        val sessionTurnId: String,
    )
}
