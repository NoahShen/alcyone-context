package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceAction
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CT14 Governance 响应严格解析（Runtime internal）。
 *
 * 规则：
 * - 必须是 JSON **object**，只允许 `action` / `targetMemoryId` / `content` / `domains` 四个字段；
 * - `action` 必须存在、是字符串、非空白，且只能是 `NEW` / `MERGE` / `UPDATE`；
 * - `targetMemoryId` / `content`：缺席或 `null` → `null`，否则必须是字符串（空白交由 Validator 判定）；
 * - `domains`：缺席或 `null` → `null`，否则必须是**字符串数组**（元素类型错误一律拒绝；空白元素由 Validator 判定）；
 * - 未知字段、类型错误、Markdown 围栏、前后解释文本、空 action → 全部拒绝，**不做任何修复**。
 *
 * 任一失败统一抛 `LlmException(INVALID_RESPONSE, "invalid governance response")`：不附带原始 content、
 * 不挂 cause（避免把模型正文带进异常链）。字段组合语义（如 `MERGE` 带 `domains`）不在此判定，
 * 由 Core `GovernanceDecisionValidator` 负责。
 */
internal class JsonGovernanceResponseParser {
    fun parse(content: String): GovernanceDecision {
        val root =
            try {
                Json.parseToJsonElement(content)
            } catch (failure: IllegalArgumentException) {
                throw invalidResponse()
            }
        val obj = root as? JsonObject ?: throw invalidResponse()
        if (obj.keys.any { key -> key !in ALLOWED_FIELDS }) throw invalidResponse()

        val actionElement = obj[ACTION] ?: throw invalidResponse()
        val action = parseAction(actionElement)
        val target = optionalString(obj[TARGET_MEMORY_ID], TARGET_MEMORY_ID)
        val decisionContent = optionalString(obj[CONTENT], CONTENT)
        val domains = optionalStringArray(obj[DOMAINS], DOMAINS)
        return GovernanceDecision(
            action = action,
            targetMemoryId = target,
            content = decisionContent,
            domains = domains,
        )
    }

    private fun parseAction(element: kotlinx.serialization.json.JsonElement): GovernanceAction {
        val primitive = element as? JsonPrimitive ?: throw invalidResponse()
        if (!primitive.isString) throw invalidResponse()
        val raw = primitive.content
        if (raw.isBlank()) throw invalidResponse()
        return ACTION_BY_NAME[raw] ?: throw invalidResponse()
    }

    /** 缺席或 JSON `null` → `null`；否则必须是字符串（保留原文，不 trim）。 */
    private fun optionalString(
        element: kotlinx.serialization.json.JsonElement?,
        field: String,
    ): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw invalidResponse()
        if (!primitive.isString) throw invalidResponse()
        return primitive.content
    }

    /** 缺席或 JSON `null` → `null`；否则必须是字符串数组（保留元素原文与顺序，不 trim、不去重）。 */
    private fun optionalStringArray(
        element: kotlinx.serialization.json.JsonElement?,
        field: String,
    ): List<String>? {
        if (element == null || element is JsonNull) return null
        val array = element as? JsonArray ?: throw invalidResponse()
        return array.map { item ->
            val primitive = item as? JsonPrimitive ?: throw invalidResponse()
            if (!primitive.isString) throw invalidResponse()
            primitive.content
        }
    }

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid governance response",
        )

    private companion object {
        const val ACTION = "action"
        const val TARGET_MEMORY_ID = "targetMemoryId"
        const val CONTENT = "content"
        const val DOMAINS = "domains"

        val ALLOWED_FIELDS: Set<String> = setOf(ACTION, TARGET_MEMORY_ID, CONTENT, DOMAINS)

        val ACTION_BY_NAME: Map<String, GovernanceAction> =
            GovernanceAction.entries.associateBy { action -> action.name }
    }
}
