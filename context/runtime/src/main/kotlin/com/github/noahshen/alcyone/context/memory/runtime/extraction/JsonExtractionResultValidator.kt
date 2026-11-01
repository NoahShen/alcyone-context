package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.core.extraction.DefaultExtractionResultValidator
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCandidate
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCandidateMemory
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionResultValidator
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionValidationException
import com.github.noahshen.alcyone.context.memory.core.extraction.ValidatedExtractionMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CT11 Runtime JSON 提取结果校验（internal；D1/D6/D10）。
 *
 * 严格解析 LLM 返回的 `content`：
 * - 必须是 JSON **object**；顶层只允许 `memories`；`memories` 必须是数组（可为空）；
 * - 元素只允许 `content` / `source_turn_ids`；`content` 为非空白 **string**；
 *   `source_turn_ids` 为非空**整数**数组（拒绝 string/bool/浮点/超大整数）；
 * - 未知字段、缺失字段一律整批拒绝；
 * - 结构合法后转 [ExtractionCandidate] 交给 Core [ExtractionResultValidator]（来源/Snapshot/Target 校验）。
 *
 * 任一失败统一抛 `LlmException(INVALID_RESPONSE, "invalid extraction response")`：
 * **不附带原始 content、不挂 cause**（避免把模型正文/围栏文本带进异常链）；不修复围栏、不提取部分 JSON。
 */
internal class JsonExtractionResultValidator(
    private val core: ExtractionResultValidator = DefaultExtractionResultValidator(),
) {
    fun validate(
        content: String,
        context: ExtractionContext,
    ): List<ValidatedExtractionMemory> {
        val candidate =
            try {
                parse(content)
            } catch (failure: IllegalArgumentException) {
                // JSON 解析失败或结构非法：整批拒绝。
                throw invalidResponse()
            }
        return try {
            core.validate(candidate, context)
        } catch (failure: ExtractionValidationException) {
            // 来源/Snapshot/Target 校验失败：同样以 INVALID_RESPONSE 拒绝整批。
            throw invalidResponse()
        }
    }

    private fun parse(content: String): ExtractionCandidate {
        val root = asObject(parseJson(content), "response")
        requireOnlyFields(root, setOf(MEMORIES), "response")
        val memoriesElement =
            root[MEMORIES] ?: throw ExtractionValidationException("memories is required")
        val memoriesArray = memoriesElement as? JsonArray ?: throw ExtractionValidationException("memories must be an array")
        val memories = memoriesArray.map { element -> parseMemory(asObject(element, "memory")) }
        return ExtractionCandidate(memories)
    }

    private fun parseMemory(memory: JsonObject): ExtractionCandidateMemory {
        requireOnlyFields(memory, setOf(CONTENT, SOURCE_TURN_IDS), "memory")
        val contentElement = memory[CONTENT] as? JsonPrimitive ?: throw ExtractionValidationException("content is required")
        if (!contentElement.isString) throw ExtractionValidationException("content must be a string")
        val content = contentElement.content
        if (content.isBlank()) throw ExtractionValidationException("content must not be blank")

        val sourceElement =
            memory[SOURCE_TURN_IDS] ?: throw ExtractionValidationException("source_turn_ids is required")
        val sourceArray = sourceElement as? JsonArray ?: throw ExtractionValidationException("source_turn_ids must be an array")
        if (sourceArray.isEmpty()) throw ExtractionValidationException("source_turn_ids must not be empty")
        val sourceIds = sourceArray.map { element -> parseInt(element) }
        return ExtractionCandidateMemory(content = content, sourceTurnIds = sourceIds)
    }

    /** 严格整数：拒绝 string / bool / 浮点 / null / 超出 Int 范围。 */
    private fun parseInt(element: JsonElement): Int {
        val primitive = element as? JsonPrimitive ?: throw ExtractionValidationException("source turn id must be an integer")
        if (primitive.isString) throw ExtractionValidationException("source turn id must be an integer")
        val value = primitive.content.toLongOrNull() ?: throw ExtractionValidationException("source turn id must be an integer")
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw ExtractionValidationException("source turn id is out of range")
        }
        return value.toInt()
    }

    private fun parseJson(content: String): JsonElement =
        try {
            Json.parseToJsonElement(content)
        } catch (failure: IllegalArgumentException) {
            throw ExtractionValidationException("response is not valid JSON")
        }

    private fun asObject(
        element: JsonElement,
        what: String,
    ): JsonObject = element as? JsonObject ?: throw ExtractionValidationException("$what must be a JSON object")

    private fun requireOnlyFields(
        map: JsonObject,
        allowed: Set<String>,
        what: String,
    ) {
        if (map.keys.any { key -> key !in allowed }) {
            throw ExtractionValidationException("$what has an unknown field")
        }
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid extraction response",
        )

    private companion object {
        const val MEMORIES = "memories"
        const val CONTENT = "content"
        const val SOURCE_TURN_IDS = "source_turn_ids"
    }
}
