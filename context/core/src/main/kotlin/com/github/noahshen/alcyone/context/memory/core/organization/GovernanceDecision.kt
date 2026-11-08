package com.github.noahshen.alcyone.context.memory.core.organization

import java.util.Collections

/**
 * CT14 Governance 决策 DTO（Core internal；LLM 输出解析后交给 [GovernanceDecisionValidator] 校验）。
 *
 * 字段的**缺席**与“显式提供但为空”必须可区分，因此 [domains] 用 `null` 表示“未提供”，
 * 空列表表示“提供了空 domains”（`MERGE` 即使空数组也非法，见裁定 D4）。
 *
 * 本类只承载字段，不做动作契约校验（那是 [GovernanceDecisionValidator] 的职责）；
 * 也不 trim / 改写任何原文。
 */
class GovernanceDecision(
    val action: GovernanceAction,
    val targetMemoryId: String? = null,
    val content: String? = null,
    domains: List<String>? = null,
) {
    val domains: List<String>? = domains?.let { values -> Collections.unmodifiableList(values.toList()) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GovernanceDecision) return false
        return action == other.action &&
            targetMemoryId == other.targetMemoryId &&
            content == other.content &&
            domains == other.domains
    }

    override fun hashCode(): Int {
        var result = action.hashCode()
        result = 31 * result + (targetMemoryId?.hashCode() ?: 0)
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (domains?.hashCode() ?: 0)
        return result
    }

    /** 不打印 content / domains 原文，避免把记忆正文带进日志或异常。 */
    override fun toString(): String =
        "GovernanceDecision(action=$action, hasTarget=${targetMemoryId != null}, " +
            "hasContent=${content != null}, domains=${domains?.size})"
}
