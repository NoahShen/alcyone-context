package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Discovery 与 Consolidation 共用的严格 Topic 输出 JSON Parser。 */
internal class JsonDreamingTopicResponseParser {
    fun parse(content: String): List<DiscoveredTopic> {
        if (hasDuplicateKey(content)) throw invalidResponse()
        val root =
            try {
                Json.parseToJsonElement(content)
            } catch (_: IllegalArgumentException) {
                throw invalidResponse()
            }
        val response = root as? JsonObject ?: throw invalidResponse()
        if (response.keys != TOP_LEVEL_FIELDS) throw invalidResponse()
        val topics = response[TOPICS] as? JsonArray ?: throw invalidResponse()
        return topics.map { element -> parseTopic(element) }
    }

    private fun parseTopic(element: JsonElement): DiscoveredTopic {
        val topic = element as? JsonObject ?: throw invalidResponse()
        if (topic.keys != TOPIC_FIELDS) throw invalidResponse()
        val title = requiredString(topic, TITLE)
        val description = requiredString(topic, DESCRIPTION)
        if (title.isBlank() || description.isBlank()) throw invalidResponse()

        val keywords = stringArray(topic[KEYWORDS])
        if (keywords.any { keyword -> keyword.isBlank() }) throw invalidResponse()
        val sourceTurnIds = sourceTurnIds(topic[SOURCE_TURN_IDS])
        return DiscoveredTopic(
            title = title,
            description = description,
            keywords = keywords.distinct(),
            sourceTurnIds = sourceTurnIds.distinct(),
        )
    }

    private fun requiredString(
        topic: JsonObject,
        field: String,
    ): String {
        val primitive = topic[field] as? JsonPrimitive ?: throw invalidResponse()
        if (!primitive.isString) throw invalidResponse()
        return primitive.content
    }

    private fun stringArray(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: throw invalidResponse()
        return array.map { item ->
            val primitive = item as? JsonPrimitive ?: throw invalidResponse()
            if (!primitive.isString || primitive.content.isBlank()) throw invalidResponse()
            primitive.content
        }
    }

    private fun sourceTurnIds(element: JsonElement?): List<Long> {
        val array = element as? JsonArray ?: throw invalidResponse()
        if (array.isEmpty()) throw invalidResponse()
        return array.map { item ->
            val primitive = item as? JsonPrimitive ?: throw invalidResponse()
            if (primitive.isString || !INTEGER_PATTERN.matches(primitive.content)) throw invalidResponse()
            val id = primitive.content.toLongOrNull() ?: throw invalidResponse()
            if (id < 0L) throw invalidResponse()
            id
        }
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = INVALID_RESPONSE_MESSAGE,
        )

    private fun hasDuplicateKey(content: String): Boolean = DuplicateKeyScanner(content).hasDuplicateKey()

    private companion object {
        const val TOPICS = "topics"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val KEYWORDS = "keywords"
        const val SOURCE_TURN_IDS = "sourceTurnIds"
        const val INVALID_RESPONSE_MESSAGE = "invalid dreaming topic response"
        val TOP_LEVEL_FIELDS: Set<String> = setOf(TOPICS)
        val TOPIC_FIELDS: Set<String> = setOf(TITLE, DESCRIPTION, KEYWORDS, SOURCE_TURN_IDS)
        val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
    }
}

/** 在 JsonObject 覆盖重复成员前扫描所有对象层级的 key；同时能识别转义等价 key。 */
private class DuplicateKeyScanner(
    private val text: String,
) {
    private var index: Int = 0

    fun hasDuplicateKey(): Boolean {
        skipWhitespace()
        return scanValue()
    }

    private fun scanValue(): Boolean {
        skipWhitespace()
        return when (peek()) {
            '{' -> scanObject()
            '[' -> scanArray()
            '"' -> readStringToken() != null && false
            null -> false
            else -> {
                skipPrimitive()
                false
            }
        }
    }

    private fun scanObject(): Boolean {
        if (!consume('{')) return false
        skipWhitespace()
        if (consume('}')) return false
        val keys = HashSet<String>()
        while (true) {
            skipWhitespace()
            val key = readDecodedString() ?: return false
            if (!keys.add(key)) return true
            skipWhitespace()
            if (!consume(':')) return false
            if (scanValue()) return true
            skipWhitespace()
            when {
                consume(',') -> continue
                consume('}') -> return false
                else -> return false
            }
        }
    }

    private fun scanArray(): Boolean {
        if (!consume('[')) return false
        skipWhitespace()
        if (consume(']')) return false
        while (true) {
            if (scanValue()) return true
            skipWhitespace()
            when {
                consume(',') -> continue
                consume(']') -> return false
                else -> return false
            }
        }
    }

    private fun skipPrimitive() {
        while (index < text.length) {
            val char = text[index]
            if (char == ',' || char == '}' || char == ']' || char.isWhitespace()) break
            index++
        }
    }

    private fun readDecodedString(): String? {
        val token = readStringToken() ?: return null
        val primitive =
            try {
                Json.parseToJsonElement(token) as? JsonPrimitive
            } catch (_: IllegalArgumentException) {
                null
            }
        return primitive?.takeIf { it.isString }?.content
    }

    private fun readStringToken(): String? {
        if (peek() != '"') return null
        val start = index++
        var escaped = false
        while (index < text.length) {
            val char = text[index++]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return text.substring(start, index)
            }
        }
        return null
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun peek(): Char? = text.getOrNull(index)
}
