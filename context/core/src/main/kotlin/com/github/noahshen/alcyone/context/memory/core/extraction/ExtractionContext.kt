package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import java.util.Collections

/**
 * CT10 提取输入快照（Core public，非 `context/api` SDK）。
 *
 * 只承载一个会话与**按真实会话顺序**排列的轮次：
 * - [turns] 在构造时**防御性复制**并包装为不可修改列表，调用方之后修改原集合不影响本快照；
 * - 不额外拆出 `targetTurnIds` / `historyTurns`：Target 由调用方按 `extractionStatus == PENDING` 判定
 *   （见 [ExtractionPrompt.messages]），避免出现两份可能不一致的真相；
 * - [equals]/[hashCode] 稳定；[toString] 只输出会话与轮次**数量**，不展开正文（避免日志泄漏）。
 */
class ExtractionContext(
    val sessionKey: SessionKey,
    turns: List<StoredConversationTurn>,
) {
    /** 不可修改的轮次快照（真实会话顺序）。 */
    val turns: List<StoredConversationTurn> = Collections.unmodifiableList(turns.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractionContext) return false
        return sessionKey == other.sessionKey && turns == other.turns
    }

    override fun hashCode(): Int = 31 * sessionKey.hashCode() + turns.hashCode()

    /** 不展开消息正文，只给出会话与数量，避免把对话内容带进日志/异常。 */
    override fun toString(): String = "ExtractionContext(sessionKey=$sessionKey, turns=${turns.size})"
}
