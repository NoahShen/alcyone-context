package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Scanner 响应严格解析；只接受 candidate_ids 字符串数组对象。 */
internal class JsonMemorySearchResponseParser {
    fun parse(content: String): List<String> =
        MemorySearchJsonObjectReader.readStringArray(
            content = content,
            field = CANDIDATE_IDS,
            errorMessage = INVALID_RESPONSE_MESSAGE,
        )

    private companion object {
        const val CANDIDATE_IDS = "candidate_ids"
        const val INVALID_RESPONSE_MESSAGE = "invalid memory search response"
    }
}

internal object MemorySearchJsonObjectReader {
    fun readStringArray(
        content: String,
        field: String,
        errorMessage: String,
    ): List<String> {
        if (RootObjectKeyScanner(content).hasDuplicateKey()) throw invalidResponse(errorMessage)
        val root =
            try {
                Json.parseToJsonElement(content)
            } catch (_: IllegalArgumentException) {
                throw invalidResponse(errorMessage)
            }
        val obj = root as? JsonObject ?: throw invalidResponse(errorMessage)
        if (obj.keys != setOf(field)) throw invalidResponse(errorMessage)
        val array = obj[field] as? JsonArray ?: throw invalidResponse(errorMessage)
        return array.map { element ->
            val primitive = element as? JsonPrimitive ?: throw invalidResponse(errorMessage)
            if (!primitive.isString || primitive.content.isBlank()) throw invalidResponse(errorMessage)
            primitive.content
        }
    }

    fun invalidResponse(message: String): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = message,
        )
}

/** 在 JsonObject 覆盖重复值前扫描根对象成员名。 */
private class RootObjectKeyScanner(
    private val text: String,
) {
    private var index: Int = 0

    fun hasDuplicateKey(): Boolean {
        skipWhitespace()
        if (!consume('{')) return false
        skipWhitespace()
        if (peek() == '}') return false

        val keys = HashSet<String>()
        while (true) {
            skipWhitespace()
            val key = readDecodedString() ?: return false
            if (!keys.add(key)) return true
            skipWhitespace()
            if (!consume(':')) return false
            skipWhitespace()
            if (!skipValue()) return false
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume('}') -> return false
                else -> return false
            }
        }
    }

    private fun skipValue(): Boolean =
        when (peek()) {
            '"' -> readStringToken() != null
            '{', '[' -> skipComposite()
            null -> false
            else -> skipPrimitive()
        }

    private fun skipComposite(): Boolean {
        val stack = ArrayDeque<Char>()
        while (index < text.length) {
            when (val char = text[index]) {
                '"' -> if (readStringToken() == null) return false
                '{' -> {
                    stack.addLast('}')
                    index++
                }
                '[' -> {
                    stack.addLast(']')
                    index++
                }
                '}', ']' -> {
                    if (stack.removeLastOrNull() != char) return false
                    index++
                    if (stack.isEmpty()) return true
                }
                else -> index++
            }
        }
        return false
    }

    private fun skipPrimitive(): Boolean {
        val start = index
        while (index < text.length) {
            val char = text[index]
            if (char == ',' || char == '}' || char.isWhitespace()) break
            index++
        }
        return index > start
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
