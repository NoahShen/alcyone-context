package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

internal val DREAMING_TOPIC_BASE: Instant = Instant.parse("2026-11-09T08:00:00Z")

internal fun dreamingTopicTurn(
    id: Long,
    message: String = "message-$id",
    timestamp: Instant = DREAMING_TOPIC_BASE.plusSeconds(id),
    status: ExtractionStatus = ExtractionStatus.PENDING,
): StoredConversationTurn =
    StoredConversationTurn(
        id = id,
        runtimeId = "runtime-$id",
        agentId = "agent-$id",
        sessionId = "session-$id",
        sessionTurnId = "turn-$id",
        timestamp = timestamp,
        createdAt = timestamp,
        messages =
            listOf(
                ChatMessage(ChatRole.USER, message),
                ChatMessage(ChatRole.ASSISTANT, "assistant-$id"),
            ),
        extractionStatus = status,
    )

internal fun discoveredTopic(
    title: String = "topic",
    description: String = "description",
    keywords: List<String> = listOf("keyword"),
    sourceTurnIds: List<Long> = listOf(1),
): DiscoveredTopic = DiscoveredTopic(title, description, keywords, sourceTurnIds)

internal fun dreamingTopicResponse(vararg topics: DiscoveredTopic): String =
    JsonObject(
        mapOf(
            "topics" to
                JsonArray(
                    topics.map { topic ->
                        JsonObject(
                            mapOf(
                                "title" to JsonPrimitive(topic.title),
                                "description" to JsonPrimitive(topic.description),
                                "keywords" to JsonArray(topic.keywords.map(::JsonPrimitive)),
                                "sourceTurnIds" to JsonArray(topic.sourceTurnIds.map(::JsonPrimitive)),
                            ),
                        )
                    },
                ),
        ),
    ).toString()

internal fun dreamingTopicLlmResponse(
    content: String,
    finishReason: String? = "stop",
): LlmResponse = LlmResponse(content = content, usage = null, finishReason = finishReason)

internal class FakeDreamingTopicLlmClient(
    private val behavior: suspend (com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest, Int) -> LlmResponse,
) : LlmClient {
    val requests: MutableList<com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest> =
        CopyOnWriteArrayList()

    override suspend fun complete(request: com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest): LlmResponse {
        requests += request
        return behavior(request, requests.size)
    }
}

internal class DreamingTopicCountingEstimator(
    private val discoveryBase: Int = 0,
    private val discoveryPerTurn: Int = 1,
    private val consolidationBase: Int = 0,
    private val consolidationPerTopic: Int = 1,
    private val schemaTokens: Int = 0,
) : TokenEstimator {
    override fun estimateText(text: String): Int = text.length

    override fun estimatePrompt(messages: List<LlmMessage>): Int {
        val user = messages.lastOrNull()?.content.orEmpty()
        return if (user.contains("<topics count=")) {
            consolidationBase + count(user, "<topic index=") * consolidationPerTopic
        } else {
            discoveryBase + count(user, "<turn id=") * discoveryPerTurn
        }
    }

    override fun estimateSchema(schema: String?): Int = if (schema == null) 0 else schemaTokens

    private fun count(
        text: String,
        marker: String,
    ): Int = Regex(Regex.escape(marker)).findAll(text).count()
}

internal fun dreamingTopicLlmConfig(
    includeTask: Boolean = true,
    mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
    maxInputTokens: Int = 32_000,
    maxOutputTokens: Int = 1_000,
    contextWindowTokens: Int = 64_000,
    safetyMarginPercent: Int = 0,
): LlmConfig {
    val profile =
        LlmProfileConfig(
            name = "topic",
            baseUrl = "http://127.0.0.1:1",
            apiKey = "ct23-safe-key-marker",
            model = "fake-topic",
            contextWindowTokens = contextWindowTokens,
            jsonMode = mode,
            maxAttempts = 1,
        )
    return LlmConfig(
        profiles = mapOf(profile.name to profile),
        tasks =
            if (includeTask) {
                mapOf(
                    LlmTask.DREAMING_TOPIC to
                        LlmTaskConfig(
                            profile = profile.name,
                            maxInputTokens = maxInputTokens,
                            maxOutputTokens = maxOutputTokens,
                        ),
                )
            } else {
                emptyMap()
            },
        safetyMarginPercent = safetyMarginPercent,
    )
}
