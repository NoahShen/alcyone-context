package com.github.noahshen.alcyone.context.memory.core.llm

/**
 * Token 估算器（Port）：保守估算与可配置安全余量。
 *
 * 只做估算/检查，不决定丢弃什么；不是准确上限保证，需按真实 API Usage 校准。
 */
interface TokenEstimator {
    /** 估算纯文本的 Token 数（保守上偏）。 */
    fun estimateText(text: String): Int

    /** 估算 Prompt/messages 的 Token 数（含角色与协议开销）。 */
    fun estimatePrompt(messages: List<LlmMessage>): Int

    /** 估算结构化输出 Schema 的 Token 数。 */
    fun estimateSchema(schema: String?): Int
}
