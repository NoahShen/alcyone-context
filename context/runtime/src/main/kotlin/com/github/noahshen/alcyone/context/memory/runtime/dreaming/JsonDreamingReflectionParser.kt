package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingInsightCandidate
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/** Reflection 响应严格解析；顶层与 Insight 字段均不接受缺失、额外或错误类型。 */
internal class JsonDreamingReflectionParser {
    fun parse(content: String): List<DreamingInsightCandidate> {
        if (ReflectionDuplicateKeyScanner(content).hasDuplicateKey()) throw invalidResponse()
        val root =
            try {
                Json.parseToJsonElement(content)
            } catch (_: IllegalArgumentException) {
                throw invalidResponse()
            }
        val response = root as? JsonObject ?: throw invalidResponse()
        if (response.keys != TOP_LEVEL_FIELDS) throw invalidResponse()
        val insights = response[INSIGHTS] as? JsonArray ?: throw invalidResponse()
        return Collections.unmodifiableList(insights.map(::parseInsight))
    }

    private fun parseInsight(element: JsonElement): DreamingInsightCandidate {
        val insight = element as? JsonObject ?: throw invalidResponse()
        if (insight.keys != INSIGHT_FIELDS) throw invalidResponse()
        val content = requiredString(insight, CONTENT)
        if (content.isBlank()) throw invalidResponse()
        val sourceTurnIds = sourceTurnIds(insight[SOURCE_TURN_IDS])
        return DreamingInsightCandidate(content, sourceTurnIds)
    }

    private fun requiredString(
        value: JsonObject,
        field: String,
    ): String {
        val primitive = value[field] as? JsonPrimitive ?: throw invalidResponse()
        if (!primitive.isString) throw invalidResponse()
        return primitive.content
    }

    private fun sourceTurnIds(element: JsonElement?): List<Long> {
        val array = element as? JsonArray ?: throw invalidResponse()
        if (array.isEmpty()) throw invalidResponse()
        return array.map { item ->
            val primitive = item as? JsonPrimitive ?: throw invalidResponse()
            if (primitive.isString || !INTEGER_PATTERN.matches(primitive.content)) throw invalidResponse()
            primitive.content.toLongOrNull() ?: throw invalidResponse()
        }
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = INVALID_RESPONSE_MESSAGE,
            task = LlmTask.DREAMING_REFLECTION,
        )

    private companion object {
        const val INSIGHTS = "insights"
        const val CONTENT = "content"
        const val SOURCE_TURN_IDS = "sourceTurnIds"
        const val INVALID_RESPONSE_MESSAGE = "invalid dreaming reflection response"
        val TOP_LEVEL_FIELDS: Set<String> = setOf(INSIGHTS)
        val INSIGHT_FIELDS: Set<String> = setOf(CONTENT, SOURCE_TURN_IDS)
        val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
    }
}

/** 在 JSON 解析覆盖重复成员前扫描所有对象层级；可识别转义等价 key。 */
private class ReflectionDuplicateKeyScanner(
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
