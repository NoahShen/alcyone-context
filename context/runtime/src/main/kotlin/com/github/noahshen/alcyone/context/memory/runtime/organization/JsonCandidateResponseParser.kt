package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * CT13 候选响应严格解析（Runtime internal）。
 *
 * 规则：必须是 JSON **object**；只允许 `candidate_ids` 字段；值为**字符串数组**（可为空）；
 * 未知字段、非数组、非字符串元素（含 null/bool/数字）一律拒绝；**不修复 Markdown 围栏**、
 * 不剥离前后文本、不提取部分 JSON。
 *
 * 任一失败统一抛 `LlmException(INVALID_RESPONSE, "invalid candidate response")`：不附带原始
 * content、不挂 cause（避免把模型正文带进异常链）。
 */
internal class JsonCandidateResponseParser {
    fun parse(content: String): List<String> {
        val root =
            try {
                Json.parseToJsonElement(content)
            } catch (failure: IllegalArgumentException) {
                throw invalidResponse()
            }
        val obj = root as? kotlinx.serialization.json.JsonObject ?: throw invalidResponse()
        if (obj.keys.any { key -> key != CANDIDATE_IDS }) throw invalidResponse()
        val array = obj[CANDIDATE_IDS] as? JsonArray ?: throw invalidResponse()
        return array.map { element ->
            val primitive = element as? JsonPrimitive ?: throw invalidResponse()
            if (!primitive.isString) throw invalidResponse()
            if (primitive.content.isBlank()) throw invalidResponse()
            primitive.content
        }
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid candidate response",
        )

    private companion object {
        const val CANDIDATE_IDS = "candidate_ids"
    }
}
