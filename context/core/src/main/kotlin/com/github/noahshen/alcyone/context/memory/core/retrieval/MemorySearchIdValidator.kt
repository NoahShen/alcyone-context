package com.github.noahshen.alcyone.context.memory.core.retrieval

import com.github.noahshen.alcyone.context.memory.core.organization.CandidateIdValidationException
import com.github.noahshen.alcyone.context.memory.core.organization.CandidateIdValidator

/** Memory Search ID 校验失败；消息只含安全原因。 */
class MemorySearchIdValidationException(
    message: String,
) : IllegalArgumentException(message)

/** Scanner 批内与 Rerank 全局 ID 规则。 */
object MemorySearchIdValidator {
    /** 复用 CT13 批内 ID 规则：未知/空白拒绝，重复稳定去重，空数组合法。 */
    fun validateScannerBatch(
        batchIds: Set<String>,
        returned: List<String>,
    ): List<String> =
        try {
            CandidateIdValidator.validate(batchIds, returned)
        } catch (failure: CandidateIdValidationException) {
            throw MemorySearchIdValidationException(failure.message ?: "candidate id validation failed")
        }

    /** Rerank 输出必须有序、唯一且属于输入候选；空数组合法。 */
    fun validateRerank(
        candidateIds: Set<String>,
        returned: List<String>,
    ): List<String> {
        val accepted = LinkedHashSet<String>(returned.size)
        returned.forEach { id ->
            if (id.isBlank()) throw MemorySearchIdValidationException("rerank id must not be blank")
            if (id !in candidateIds) {
                throw MemorySearchIdValidationException("rerank id is not part of the candidate set")
            }
            if (!accepted.add(id)) {
                throw MemorySearchIdValidationException("rerank ids must be unique")
            }
        }
        return accepted.toList()
    }
}
