package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef

/**
 * LLM 输出的单条候选记忆（Core public，非 `context/api` SDK；CT11 D1）。
 *
 * [sourceTurnIds] 使用**本次 Snapshot 的内部 SQLite Turn id**，只在本次校验内有效；
 * 通过校验后由 [ExtractionResultValidator] 映射为稳定的
 * [MemorySourceRef.ConversationTurnRef]（四元业务键），不长期保存内部 id。
 */
data class ExtractionCandidateMemory(
    val content: String,
    val sourceTurnIds: List<Int>,
)

/** LLM 输出的整批候选（`memories` 可为空数组，表示本批没有需要保存的记忆）。 */
data class ExtractionCandidate(
    val memories: List<ExtractionCandidateMemory>,
)

/** 通过校验、来源已映射为稳定业务键的记忆（供 [ExtractionCommitter] 原子提交）。 */
data class ValidatedExtractionMemory(
    val content: String,
    val sources: List<MemorySourceRef.ConversationTurnRef>,
)
