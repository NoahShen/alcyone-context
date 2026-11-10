package com.github.noahshen.alcyone.context.memory.core.dreaming

import java.util.Collections

/** Topic 相对当前 Organized 证据的新颖性决策。 */
enum class DreamingNoveltyDecision {
    NEEDS_EVIDENCE,
    FULLY_COVERED,
}

/**
 * Novelty 模型输出；列表在构造与复制时防御性拷贝并包装为不可修改视图。
 * [toString] 只输出决策与计数，不暴露 Organized ID。
 */
class DreamingNoveltyAssessment(
    val decision: DreamingNoveltyDecision,
    coveredOrganizedIds: List<String>,
) {
    val coveredOrganizedIds: List<String> = Collections.unmodifiableList(coveredOrganizedIds.toList())

    fun copy(
        decision: DreamingNoveltyDecision = this.decision,
        coveredOrganizedIds: List<String> = this.coveredOrganizedIds,
    ): DreamingNoveltyAssessment = DreamingNoveltyAssessment(decision, coveredOrganizedIds)

    operator fun component1(): DreamingNoveltyDecision = decision

    operator fun component2(): List<String> = coveredOrganizedIds

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DreamingNoveltyAssessment) return false
        return decision == other.decision && coveredOrganizedIds == other.coveredOrganizedIds
    }

    override fun hashCode(): Int = 31 * decision.hashCode() + coveredOrganizedIds.hashCode()

    override fun toString(): String = "DreamingNoveltyAssessment(decision=$decision, coveredOrganizedIdCount=${coveredOrganizedIds.size})"
}

/** Novelty 决策/覆盖 ID 校验失败；消息只含安全规则名。 */
class DreamingNoveltyValidationException(
    message: String,
) : IllegalArgumentException(message)

/** 纯逻辑 Novelty 契约校验；不判断模型语义正确性。 */
object DreamingNoveltyValidator {
    fun validate(
        assessment: DreamingNoveltyAssessment,
        relevantOrganizedIds: Set<String>,
    ): DreamingNoveltyAssessment {
        val covered = assessment.coveredOrganizedIds.distinct()
        when (assessment.decision) {
            DreamingNoveltyDecision.NEEDS_EVIDENCE -> {
                if (covered.isNotEmpty()) {
                    throw DreamingNoveltyValidationException("NEEDS_EVIDENCE must not cover organized memories")
                }
            }

            DreamingNoveltyDecision.FULLY_COVERED -> {
                if (covered.isEmpty()) {
                    throw DreamingNoveltyValidationException("FULLY_COVERED requires at least one organized memory")
                }
                if (covered.any { id -> id.isBlank() || id !in relevantOrganizedIds }) {
                    throw DreamingNoveltyValidationException("covered organized id is not part of the relevant candidates")
                }
            }
        }
        return DreamingNoveltyAssessment(assessment.decision, covered)
    }
}
