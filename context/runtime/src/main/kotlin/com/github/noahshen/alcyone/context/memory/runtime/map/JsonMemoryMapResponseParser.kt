package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Memory Map LLM JSON 信封的严格解析；不修复围栏、前后文本或未知字段。 */
internal class JsonMemoryMapResponseParser {
    fun parse(content: String): MemoryMapDraft {
        if (RootObjectKeyScanner(content).hasDuplicateKey()) throw invalidResponse()
        val root =
            try {
                Json.parseToJsonElement(content)
            } catch (failure: IllegalArgumentException) {
                throw invalidResponse()
            }
        val obj = root as? JsonObject ?: throw invalidResponse()
        if (obj.keys != ALLOWED_FIELDS) throw invalidResponse()
        val overview = stringField(obj, OVERVIEW)
        val navigation = stringField(obj, NAVIGATION)
        val domains =
            (obj[DOMAINS] as? JsonArray)?.map { element ->
                val primitive = element as? JsonPrimitive ?: throw invalidResponse()
                if (!primitive.isString) throw invalidResponse()
                primitive.content
            } ?: throw invalidResponse()
        return MemoryMapDraft(overview = overview, navigation = navigation, domains = domains)
    }

    private fun stringField(
        obj: JsonObject,
        name: String,
    ): String {
        val primitive = obj[name] as? JsonPrimitive ?: throw invalidResponse()
        if (!primitive.isString) throw invalidResponse()
        return primitive.content
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid memory map response",
        )

    private companion object {
        const val OVERVIEW = "overview"
        const val NAVIGATION = "navigation"
        const val DOMAINS = "domains"
        val ALLOWED_FIELDS: Set<String> = setOf(OVERVIEW, NAVIGATION, DOMAINS)
    }
}

/** 在 JsonObject 覆盖重复值之前，只扫描根对象成员名并识别重复 key。 */
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
            } catch (failure: IllegalArgumentException) {
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
