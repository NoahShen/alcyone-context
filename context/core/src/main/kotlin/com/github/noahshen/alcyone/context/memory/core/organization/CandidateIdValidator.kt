package com.github.noahshen.alcyone.context.memory.core.organization

/** 候选 ID 校验失败（Core 纯逻辑；消息只含安全原因，不含正文/Key/URL）。 */
class CandidateIdValidationException(
    message: String,
) : IllegalArgumentException(message)

/**
 * CT13 批内候选 ID 校验（纯逻辑，无 IO）。
 *
 * 规则：
 * - 每个 ID 必须**属于该批实际发送的候选集合**（未知 / 跨批 / 空白一律拒绝）；
 * - 重复 ID **稳定去重**（保留首次出现），不视为失败；
 * - 空列表合法（模型可以认为本批没有相关候选）。
 *
 * 这是**程序级权限边界**：模型正文里出现任何分隔符、指令样文本或伪造 ID 都不会绕过本校验。
 */
object CandidateIdValidator {
    fun validate(
        batchIds: Set<String>,
        returned: List<String>,
    ): List<String> {
        val accepted = LinkedHashSet<String>(returned.size)
        returned.forEach { id ->
            if (id.isBlank()) {
                throw CandidateIdValidationException("candidate id must not be blank")
            }
            if (id !in batchIds) {
                throw CandidateIdValidationException("candidate id is not part of the sent batch")
            }
            accepted += id
        }
        return accepted.toList()
    }
}
