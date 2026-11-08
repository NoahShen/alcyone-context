package com.github.noahshen.alcyone.context.memory.core.organization

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory

/**
 * Governance 决策非法（Core 纯逻辑；消息只含安全原因与字段名，**不含**正文 / ID / Key / URL）。
 *
 * 这是**内部**异常：Runtime 服务层统一映射为 `LlmException(INVALID_RESPONSE)`（裁定 D3），
 * 不新增公开错误码。
 */
class GovernanceValidationException(
    message: String,
) : IllegalArgumentException(message)

/**
 * CT14 Governance 决策校验（纯逻辑，无 IO；确定性、不访问 DB/LLM）。
 *
 * 冻结契约（Architect 裁定 §2）：
 *
 * | 动作 | 必填 | 禁止 / 可选 |
 * | --- | --- | --- |
 * | [GovernanceAction.NEW] | `content`、`domains` | `targetMemoryId` 必须缺席 |
 * | [GovernanceAction.MERGE] | `targetMemoryId`（属于本次候选） | `content` 可选（提供则非空）；`domains` **必须缺席**（空数组也拒绝，D4） |
 * | [GovernanceAction.UPDATE] | `targetMemoryId`（属于本次候选）、`content` | `domains` 可选（提供则非空） |
 *
 * 通用规则：
 * - `content` 非空字符串（`isBlank` 拒绝），**不 trim**、不改写原文；
 * - `domains` 元素不得空白（D5），重复**稳定去重**（保留首次出现顺序），**不 trim**；
 * - `targetMemoryId` 必须属于**本次实际提供的候选集合**，未知 / 跨批 / 伪造 ID 一律拒绝；
 * - 空候选集合允许 `NEW`（D6），此时任何 `target` 都必然越界；
 * - 任一非法 → 抛 [GovernanceValidationException]：**绝不降级为 NEW、绝不返回部分/默认决策**。
 */
object GovernanceDecisionValidator {
    /**
     * 校验并返回**规范化**决策（仅 `domains` 稳定去重；`targetMemoryId` / `content` 原样保留）。
     *
     * @throws GovernanceValidationException 动作契约或候选权限不满足。
     */
    fun validate(
        decision: GovernanceDecision,
        candidates: List<OrganizedMemory>,
    ): GovernanceDecision {
        val candidateIds = candidates.mapTo(HashSet(candidates.size)) { memory -> memory.id }
        return when (decision.action) {
            GovernanceAction.NEW -> validateNew(decision)
            GovernanceAction.MERGE -> validateMerge(decision, candidateIds)
            GovernanceAction.UPDATE -> validateUpdate(decision, candidateIds)
        }
    }

    private fun validateNew(decision: GovernanceDecision): GovernanceDecision {
        if (decision.targetMemoryId != null) {
            throw GovernanceValidationException("NEW must not carry targetMemoryId")
        }
        return GovernanceDecision(
            action = GovernanceAction.NEW,
            targetMemoryId = null,
            content = requireContent(decision.content, "NEW"),
            domains = requireDomains(decision.domains, "NEW"),
        )
    }

    private fun validateMerge(
        decision: GovernanceDecision,
        candidateIds: Set<String>,
    ): GovernanceDecision {
        val target = requireTarget(decision.targetMemoryId, candidateIds, "MERGE")
        // D4：MERGE 提供 domains 即非法，空数组同样拒绝（null 表示未提供）。
        if (decision.domains != null) {
            throw GovernanceValidationException("MERGE must not carry domains")
        }
        val content = decision.content?.let { provided -> requireContent(provided, "MERGE") }
        return GovernanceDecision(
            action = GovernanceAction.MERGE,
            targetMemoryId = target,
            content = content,
            domains = null,
        )
    }

    private fun validateUpdate(
        decision: GovernanceDecision,
        candidateIds: Set<String>,
    ): GovernanceDecision {
        val target = requireTarget(decision.targetMemoryId, candidateIds, "UPDATE")
        val content = requireContent(decision.content, "UPDATE")
        val domains = decision.domains?.let { provided -> requireDomains(provided, "UPDATE") }
        return GovernanceDecision(
            action = GovernanceAction.UPDATE,
            targetMemoryId = target,
            content = content,
            domains = domains,
        )
    }

    private fun requireTarget(
        targetMemoryId: String?,
        candidateIds: Set<String>,
        action: String,
    ): String {
        if (targetMemoryId == null || targetMemoryId.isBlank()) {
            throw GovernanceValidationException("$action requires targetMemoryId")
        }
        if (targetMemoryId !in candidateIds) {
            throw GovernanceValidationException("$action targetMemoryId is not part of the provided candidates")
        }
        return targetMemoryId
    }

    private fun requireContent(
        content: String?,
        action: String,
    ): String {
        if (content == null || content.isBlank()) {
            throw GovernanceValidationException("$action requires a non-blank content")
        }
        return content
    }

    private fun requireDomains(
        domains: List<String>?,
        action: String,
    ): List<String> {
        if (domains == null || domains.isEmpty()) {
            throw GovernanceValidationException("$action requires a non-empty domains list")
        }
        val deduplicated = LinkedHashSet<String>(domains.size)
        domains.forEach { domain ->
            if (domain.isBlank()) {
                throw GovernanceValidationException("$action domains must not contain blank entries")
            }
            deduplicated += domain
        }
        return deduplicated.toList()
    }
}
