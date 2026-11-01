package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn

/**
 * 提取结果校验失败（Core）。
 *
 * 消息只含**安全原因**（例如 “source turn id is not in the snapshot”），
 * **不含** content 正文、来源文本、Key 或 URL；异常不带 cause（避免把模型原文带进异常链）。
 */
class ExtractionValidationException(
    message: String,
) : IllegalArgumentException(message)

/**
 * CT11 提取结果校验 Port（Core public，非 `context/api` SDK）。
 *
 * 只做**结构与来源确定性**校验，不做语义判断、不自动修复、不合并相近记忆。
 */
interface ExtractionResultValidator {
    /**
     * 校验 [candidate] 并返回可提交的记忆（来源已映射为稳定业务键）。
     *
     * @throws ExtractionValidationException 任一条非法（整批拒绝）。
     */
    fun validate(
        candidate: ExtractionCandidate,
        context: ExtractionContext,
    ): List<ValidatedExtractionMemory>
}

/**
 * 默认校验实现（纯逻辑，无 IO/无 LLM；D1/D6）。
 *
 * 规则：
 * - `memories` 可为空 → 返回空列表（零记忆是合法成功）；
 * - 每条 `content` **trim 后**非空（不改写原 content，按原样保留）；
 * - 每条 `sourceTurnIds` 非空；每个 id 必须出现在**本次 Snapshot** 的 [ExtractionContext.turns] 中；
 * - 重复来源按**首次出现**确定性去重；
 * - 整批来源（去重后）至少引用一个 Snapshot 时 `extractionStatus == PENDING` 的 Target；
 *   仅 History / 窗口外 / 其他 Session 的来源一律整批拒绝；
 * - 不修复、不改写 content、不做语义去重或合并。
 */
class DefaultExtractionResultValidator : ExtractionResultValidator {
    override fun validate(
        candidate: ExtractionCandidate,
        context: ExtractionContext,
    ): List<ValidatedExtractionMemory> {
        if (candidate.memories.isEmpty()) return emptyList()

        val turnsById = HashMap<Long, StoredConversationTurn>(context.turns.size)
        context.turns.forEach { turn -> turnsById[turn.id] = turn }

        val validated = ArrayList<ValidatedExtractionMemory>(candidate.memories.size)
        var hasTargetSource = false

        candidate.memories.forEach { memory ->
            if (memory.content.isBlank()) {
                throw ExtractionValidationException("extraction memory content must not be blank")
            }
            if (memory.sourceTurnIds.isEmpty()) {
                throw ExtractionValidationException("extraction memory must reference at least one source turn")
            }
            val sources = ArrayList<MemorySourceRef.ConversationTurnRef>(memory.sourceTurnIds.size)
            val seen = HashSet<Long>(memory.sourceTurnIds.size)
            memory.sourceTurnIds.forEach { sourceId ->
                val turnId = sourceId.toLong()
                if (!seen.add(turnId)) return@forEach
                val turn =
                    turnsById[turnId]
                        ?: throw ExtractionValidationException("extraction source turn id is not in the snapshot")
                if (turn.extractionStatus == ExtractionStatus.PENDING) hasTargetSource = true
                sources +=
                    MemorySourceRef.ConversationTurnRef(
                        runtimeId = turn.runtimeId,
                        agentId = turn.agentId,
                        sessionId = turn.sessionId,
                        sessionTurnId = turn.sessionTurnId,
                    )
            }
            validated += ValidatedExtractionMemory(content = memory.content, sources = sources)
        }

        if (!hasTargetSource) {
            throw ExtractionValidationException("extraction sources must reference at least one pending target turn")
        }
        return validated
    }
}
