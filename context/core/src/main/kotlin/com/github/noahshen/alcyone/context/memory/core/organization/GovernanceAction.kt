package com.github.noahshen.alcyone.context.memory.core.organization

/**
 * CT14 Governance 单动作（Core internal DTO 的一部分；**不是** `context/api` 公共 SDK 类型）。
 *
 * 一次 Governance 只判定一条 Raw 的**一个主要动作**：
 * - [NEW]：新独立当前认知（ID / 时间 / 来源由 CT15 生成）；
 * - [MERGE]：重复确认 / 兼容补充 / 旧历史证据，不改变分类；
 * - [UPDATE]：当前认知变化 / 纠正 / 明确不确定，保留原 ID 与旧来源。
 */
enum class GovernanceAction {
    NEW,
    MERGE,
    UPDATE,
}
