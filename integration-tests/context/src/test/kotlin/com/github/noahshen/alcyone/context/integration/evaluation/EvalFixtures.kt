package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapSnapshot
import com.github.noahshen.alcyone.context.memory.integration.evaluation.EvalDatasetLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime

internal val evalJson = Json { prettyPrint = true }
internal val evalSession = SessionKey("ct31-eval", "ct31-agent", "ct31-session")
internal val evalTime: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal data class EvalSample(
    val task: String,
    val json: JsonObject,
) {
    val id: String get() = json.string("id")
    val input: JsonObject get() = json.obj("input")
    val oracle: JsonObject get() = json.obj("oracle")
    val riskTags: Set<String> get() = oracle.strings("riskTags").toSet()
}

internal fun loadEvalSamples(root: Path): List<EvalSample> =
    EvalDatasetLoader.load(root).samples.map { sample ->
        EvalSample(
            sample.task,
            buildJsonObject {
                put("id", sample.id)
                put("input", sample.input)
                put("oracle", sample.oracle)
            },
        )
    }

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("Expected JSON object")

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).obj()

internal fun JsonObject.array(key: String): JsonArray = getValue(key) as? JsonArray ?: error("Expected JSON array: $key")

internal fun JsonObject.string(key: String): String =
    (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected JSON string: $key")

internal fun JsonObject.strings(key: String): List<String> =
    array(key).map {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Expected string in $key")
    }

internal fun JsonObject.longs(key: String): List<Long> =
    array(key).map {
        (it as? JsonPrimitive)?.long ?: error("Expected integer in $key")
    }

internal fun JsonObject.nullableStrings(key: String): List<String>? =
    get(key)?.takeUnless { it is JsonNull }?.let { value ->
        (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: error("Expected string in $key") }
            ?: error("Expected array in $key")
    }

internal fun JsonObject.bool(key: String): Boolean = (getValue(key) as? JsonPrimitive)?.boolean ?: error("Expected boolean: $key")

internal fun JsonObject.int(key: String): Int = (getValue(key) as? JsonPrimitive)?.content?.toIntOrNull() ?: error("Expected integer: $key")

internal fun JsonObject.nullableString(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.toTurns(windowEnd: Long? = null): List<StoredConversationTurn> =
    array("turns")
        .sortedBy { (it.obj().getValue("id") as JsonPrimitive).long }
        .filter {
            windowEnd == null ||
                (it.obj().getValue("id") as JsonPrimitive).long <= windowEnd
        }.map { value ->
            val turn = value.obj()
            val id = (turn["id"] as JsonPrimitive).long
            val timestamp = turn["timestamp"]?.let { parseTime((it as JsonPrimitive).content) } ?: evalTime
            val role =
                when (turn["role"]?.let { (it as JsonPrimitive).content.lowercase() } ?: "user") {
                    "system", "user" -> ChatRole.USER
                    "assistant" -> ChatRole.ASSISTANT
                    else -> error("Unsupported evaluation turn role")
                }
            StoredConversationTurn(
                id = id,
                runtimeId = evalSession.runtimeId,
                agentId = evalSession.agentId,
                sessionId = evalSession.sessionId,
                sessionTurnId = id.toString(),
                timestamp = timestamp,
                createdAt = timestamp,
                messages = listOf(ChatMessage(role, turn.string("content"))),
                extractionStatus =
                    if (turn["target"]?.let { (it as JsonPrimitive).boolean } ==
                        true
                    ) {
                        ExtractionStatus.PENDING
                    } else {
                        ExtractionStatus.COMPLETE
                    },
            )
        }

internal fun JsonObject.toExtractionContext(): ExtractionContext = ExtractionContext(evalSession, toTurns())

internal fun JsonObject.toDreamingWindow(): List<StoredConversationTurn> =
    toTurns(
        array("turnIds")
            .map {
                (it as JsonPrimitive).content.toLong()
            }.max(),
    )

internal fun JsonObject.toOrganizedMemories(key: String = "organizedMemories"): List<OrganizedMemory> =
    array(key)
        .map { value ->
            val memory = value.obj()
            val updated = (memory["sourceUpdatedAt"] ?: memory["updatedAt"])?.let { parseTime((it as JsonPrimitive).content) } ?: evalTime
            OrganizedMemory(
                memory.string("id"),
                memory.string("content"),
                memory.strings("domains"),
                memory["rawMemoryIds"]?.let { memory.strings("rawMemoryIds") } ?: emptyList(),
                evalTime,
                updated,
            )
        }.sortedBy { it.id }

internal fun JsonObject.toRawMemory(): RawMemory =
    (get("rawMemory")?.obj() ?: obj("raw")).let { raw ->
        val turnIds = raw["sourceTurnIds"]?.let { raw.longs("sourceTurnIds") }?.toSet() ?: emptySet()
        val created = raw["createdAt"]?.let { parseTime((it as JsonPrimitive).content) } ?: evalTime
        RawMemory(raw.string("id"), raw.string("content"), MemoryOrigin.EXTRACTION, inputSources(raw, turnIds), created)
    }

private fun JsonObject.inputSources(
    raw: JsonObject,
    turnIds: Set<Long>,
): List<MemorySourceRef> {
    if (turnIds.isNotEmpty()) {
        return turnIds.sorted().map {
            MemorySourceRef.ConversationTurnRef(evalSession.runtimeId, evalSession.agentId, evalSession.sessionId, it.toString())
        }
    }
    val fallback =
        raw["sources"]
            ?.let {
                raw.strings("sources")
            }?.map { MemorySourceRef.ConversationTurnRef(evalSession.runtimeId, evalSession.agentId, evalSession.sessionId, it) }
    return fallback
        ?: listOf(MemorySourceRef.ConversationTurnRef(evalSession.runtimeId, evalSession.agentId, evalSession.sessionId, "eval-source"))
}

internal fun JsonObject.toRawMemories(): List<RawMemory> =
    array("rawMemories").map { value ->
        val raw = value.obj()
        RawMemory(
            raw.string("id"),
            raw.string("content"),
            MemoryOrigin.EXTRACTION,
            inputSources(raw, raw.longs("sourceTurnIds").toSet()),
            evalTime,
        )
    }

internal fun JsonObject.toMapSnapshot(): MemoryMapSnapshot {
    val memories = toOrganizedMemories("organizedMemories")
    return MemoryMapSnapshot(memories, memories.maxOfOrNull { it.updatedAt })
}

internal fun JsonObject.toQuery(): String = string("query")

internal fun EvalSample.conversationText(): String = input.toString()

internal fun parseTime(value: String): Instant =
    try {
        Instant.parse(value)
    } catch (_: Exception) {
        OffsetDateTime.parse(value).toInstant()
    }

internal fun evalRequest(
    task: LlmTask,
    messages: List<com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage>,
    schema: String,
): LlmRequest = LlmRequest(task, messages, LlmResponseFormat.JSON_SCHEMA, maxOutputTokens = 4096, schema = schema)
