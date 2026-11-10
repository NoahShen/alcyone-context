package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyAssessment
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Novelty 响应严格解析；结构和字符串类型错误统一 INVALID_RESPONSE。 */
internal class JsonDreamingNoveltyResponseParser {
    fun parse(content: String): DreamingNoveltyAssessment {
        if (NoveltyDuplicateKeyScanner(content).hasDuplicateKey()) throw invalidResponse()
        val root =
            try {
                Json.parseToJsonElement(content)
            } catch (_: IllegalArgumentException) {
                throw invalidResponse()
            }
        val obj = root as? JsonObject ?: throw invalidResponse()
        if (obj.keys != ALLOWED_FIELDS) throw invalidResponse()
        val decisionPrimitive = obj[DECISION] as? JsonPrimitive ?: throw invalidResponse()
        if (!decisionPrimitive.isString) throw invalidResponse()
        val decision =
            DreamingNoveltyDecision.entries.firstOrNull { value -> value.name == decisionPrimitive.content }
                ?: throw invalidResponse()
        val ids = obj[COVERED_IDS] as? JsonArray ?: throw invalidResponse()
        val coveredIds =
            ids.map { element ->
                val primitive = element as? JsonPrimitive ?: throw invalidResponse()
                if (!primitive.isString) throw invalidResponse()
                primitive.content
            }
        return DreamingNoveltyAssessment(decision, coveredIds.distinct())
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid dreaming novelty response",
        )

    private companion object {
        const val DECISION = "decision"
        const val COVERED_IDS = "coveredOrganizedIds"
        val ALLOWED_FIELDS: Set<String> = setOf(DECISION, COVERED_IDS)
    }
}

private class NoveltyDuplicateKeyScanner(
    private val text: String,
) {
    private var index = 0

    fun hasDuplicateKey(): Boolean {
        skipWhitespace()
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
            skipWhitespace()
            skipValue()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume('}') -> return false
                else -> return false
            }
        }
    }

    private fun skipValue() {
        when (peek()) {
            '"' -> readStringToken()
            '{', '[' -> skipComposite()
            else -> skipPrimitive()
        }
    }

    private fun skipComposite() {
        val opening = peek() ?: return
        val closing = if (opening == '{') '}' else ']'
        val stack = ArrayDeque<Char>()
        stack.addLast(closing)
        index++
        while (index < text.length && stack.isNotEmpty()) {
            when (val char = text[index]) {
                '"' -> readStringToken()
                '{' -> {
                    stack.addLast('}')
                    index++
                }

                '[' -> {
                    stack.addLast(']')
                    index++
                }

                '}', ']' -> {
                    if (stack.removeLastOrNull() != char) return
                    index++
                }

                else -> index++
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
        return try {
            (Json.parseToJsonElement(token) as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: IllegalArgumentException) {
            null
        }
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
