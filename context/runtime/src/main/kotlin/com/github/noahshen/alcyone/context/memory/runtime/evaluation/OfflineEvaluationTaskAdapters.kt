package com.github.noahshen.alcyone.context.memory.runtime.evaluation

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingInsightCandidate
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyAssessment
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyValidator
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapSnapshot
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapValidator
import com.github.noahshen.alcyone.context.memory.core.organization.CandidateIdValidator
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecisionValidator
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchIdValidator
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.JsonDreamingConversationNeedParser
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.JsonDreamingEvidenceCandidateParser
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.JsonDreamingNoveltyResponseParser
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.JsonDreamingReflectionParser
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.JsonDreamingTopicResponseParser
import com.github.noahshen.alcyone.context.memory.runtime.extraction.JsonExtractionResultValidator
import com.github.noahshen.alcyone.context.memory.runtime.map.JsonMemoryMapResponseParser
import com.github.noahshen.alcyone.context.memory.runtime.organization.JsonCandidateResponseParser
import com.github.noahshen.alcyone.context.memory.runtime.organization.JsonGovernanceResponseParser
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.JsonMemorySearchRerankResponseParser
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.JsonMemorySearchResponseParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Minimal, synchronous offline-evaluation adapters over the production strict parsers.
 * No database, HTTP service, clock, scheduler, prompt rendering, or retry logic is used.
 */
object OfflineEvaluationTaskAdapters {
    fun parseExtraction(
        content: String,
        context: ExtractionContext,
    ): JsonObject =
        withInvalidResponse("extraction") {
            val memories =
                JsonExtractionResultValidator().validate(content, context).map { memory ->
                    buildJsonObject {
                        put("content", memory.content)
                        putJsonArray("sources") {
                            memory.sources.forEach { source ->
                                add(
                                    buildJsonObject {
                                        put("runtimeId", source.runtimeId)
                                        put("agentId", source.agentId)
                                        put("sessionId", source.sessionId)
                                        put("sessionTurnId", source.sessionTurnId)
                                    },
                                )
                            }
                        }
                    }
                }
            buildJsonObject { put("memories", JsonArray(memories)) }
        }

    fun parseCandidates(
        content: String,
        candidateIds: Set<String>,
    ): JsonObject =
        withInvalidResponse("candidate") {
            val ids = CandidateIdValidator.validate(candidateIds, JsonCandidateResponseParser().parse(content))
            idObject("candidate_ids", ids)
        }

    fun parseScanner(
        content: String,
        allowedIds: Set<String>,
    ): JsonObject =
        withInvalidResponse("memory search") {
            val ids = MemorySearchIdValidator.validateScannerBatch(allowedIds, JsonMemorySearchResponseParser().parse(content))
            idObject("candidate_ids", ids)
        }

    /** Rerank uses stable de-duplication here; the evaluation runner applies the requested limit. */
    fun parseRerank(
        content: String,
        allowedIds: Set<String>,
    ): JsonObject =
        withInvalidResponse("memory search rerank") {
            val ids = MemorySearchIdValidator.validateScannerBatch(allowedIds, JsonMemorySearchRerankResponseParser().parse(content))
            idObject("memory_ids", ids)
        }

    fun parseGovernance(
        content: String,
        raw: RawMemory,
        candidates: List<OrganizedMemory>,
    ): JsonObject =
        withInvalidResponse("governance") {
            require(raw.id.isNotBlank()) { "raw memory id is required" }
            val decision = GovernanceDecisionValidator.validate(JsonGovernanceResponseParser().parse(content), candidates)
            buildJsonObject {
                put(
                    "decision",
                    buildJsonObject {
                        put("action", decision.action.name)
                        decision.targetMemoryId?.let { put("targetMemoryId", it) }
                        decision.content?.let { put("content", it) }
                        decision.domains?.let { put("domains", JsonArray(it.map(::JsonPrimitive))) }
                    },
                )
            }
        }

    fun parseMap(
        content: String,
        snapshot: MemoryMapSnapshot,
    ): JsonObject =
        withInvalidResponse("memory map") {
            require(snapshot.memories.all { it.id.isNotBlank() }) { "memory map snapshot is invalid" }
            val map = MemoryMapValidator.validate(JsonMemoryMapResponseParser().parse(content), snapshot)
            buildJsonObject {
                put("overview", map.overview)
                put("navigation", map.navigation)
                put("domains", JsonArray(map.domains.map(::JsonPrimitive)))
            }
        }

    /** previousTopics is accepted for a consolidation-compatible call shape; no model IDs are persisted. */
    fun parseTopic(
        content: String,
        allowedTurnIds: Set<Long>,
        previousTopics: List<DiscoveredTopic>? = null,
    ): List<DiscoveredTopic> =
        withInvalidResponse("dreaming topic") {
            val topics = JsonDreamingTopicResponseParser().parse(content)
            topics.forEach { topic ->
                require(topic.sourceTurnIds.isNotEmpty() && topic.sourceTurnIds.all { it in allowedTurnIds }) {
                    "topic source turn is not part of the supplied turns"
                }
            }
            topics.map { it.copy(sourceTurnIds = it.sourceTurnIds.distinct()) }
        }

    fun parseNovelty(
        content: String,
        organizedIds: Set<String>,
    ): DreamingNoveltyAssessment =
        withInvalidResponse("dreaming novelty") {
            DreamingNoveltyValidator.validate(JsonDreamingNoveltyResponseParser().parse(content), organizedIds)
        }

    fun parseDreamingCandidates(
        content: String,
        allowedIds: Set<String>,
    ): List<String> =
        withInvalidResponse("dreaming evidence") {
            CandidateIdValidator.validate(allowedIds, JsonDreamingEvidenceCandidateParser().parse(content))
        }

    fun parseConversationNeed(
        content: String,
        allowedIds: Set<String>,
    ): List<String> =
        withInvalidResponse("dreaming conversation need") {
            CandidateIdValidator.validate(allowedIds, JsonDreamingConversationNeedParser().parse(content).memoryIds)
        }

    fun parseReflection(
        content: String,
        turns: List<StoredConversationTurn>,
    ): List<DreamingInsightCandidate> =
        withInvalidResponse("dreaming reflection") {
            val allowedTurnIds = turns.mapTo(HashSet()) { it.id }
            require(allowedTurnIds.size == turns.size) { "turn ids must be unique" }
            JsonDreamingReflectionParser().parse(content).map { insight ->
                require(insight.content.isNotBlank() && insight.sourceTurnIds.isNotEmpty()) { "invalid reflection insight" }
                require(insight.sourceTurnIds.all { it in allowedTurnIds }) { "reflection source is not a supplied turn" }
                insight.copy(sourceTurnIds = insight.sourceTurnIds.distinct())
            }
        }

    private fun idObject(
        field: String,
        ids: List<String>,
    ): JsonObject = buildJsonObject { put(field, JsonArray(ids.map(::JsonPrimitive))) }

    private fun <T> withInvalidResponse(
        category: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: LlmException) {
            throw failure
        } catch (_: IllegalArgumentException) {
            throw LlmException(LlmErrorCode.INVALID_RESPONSE, "invalid evaluation $category response")
        }
}
