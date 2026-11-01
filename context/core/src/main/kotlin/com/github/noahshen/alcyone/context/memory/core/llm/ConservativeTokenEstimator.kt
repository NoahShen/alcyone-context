package com.github.noahshen.alcyone.context.memory.core.llm

/**
 * 保守 Token 估算（技术实现设计 §3.3，纯 Kotlin，不依赖 Ktor / 序列化实现）。
 *
 * 规则（**保守估算，不是已证明的 Token 上界**，需按 Provider 真实 Usage 校准）：
 * - ASCII 码点：`ceil(ascii / 3)`（约 3 字符/token，偏保守）；
 * - 非 ASCII 码点（CJK / emoji）：**每个码点 3 tokens**（按 Unicode 码点计，不按 UTF-16 char 计，
 *   因此代理对只算一个码点）；
 * - [estimatePrompt] = `Σ(estimateText(role) + estimateText(content)) + 8 * messages.size + 16`：
 *   role 按**实际字符串**估算，不用固定角色开销替代任意 role；
 * - [estimateSchema]：`null` → 0，否则与 [estimateText] 同规则；
 * - 空字符串 → 0；超长输入**饱和到 [Int.MAX_VALUE]**，绝不回绕成负数。
 *
 * 本类只做估算，不决定丢弃/裁剪什么；安全余量由调用方的预算检查单独加**一次**，
 * 不在此处预置（避免重复加成）。
 */
class ConservativeTokenEstimator : TokenEstimator {
    override fun estimateText(text: String): Int {
        var asciiCodePoints = 0L
        var nonAsciiCodePoints = 0L
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint <= ASCII_MAX_CODE_POINT) {
                asciiCodePoints++
            } else {
                nonAsciiCodePoints++
            }
            index += Character.charCount(codePoint)
        }
        val asciiTokens = ceilDiv(asciiCodePoints, ASCII_CHARS_PER_TOKEN)
        val nonAsciiTokens = saturatedMultiply(nonAsciiCodePoints, NON_ASCII_TOKENS_PER_CODE_POINT)
        return saturate(saturatedAdd(asciiTokens, nonAsciiTokens))
    }

    override fun estimatePrompt(messages: List<LlmMessage>): Int {
        var total = 0L
        messages.forEach { message ->
            total = saturatedAdd(total, estimateText(message.role).toLong())
            total = saturatedAdd(total, estimateText(message.content).toLong())
        }
        total = saturatedAdd(total, saturatedMultiply(PER_MESSAGE_OVERHEAD.toLong(), messages.size.toLong()))
        total = saturatedAdd(total, PROTOCOL_OVERHEAD.toLong())
        return saturate(total)
    }

    override fun estimateSchema(schema: String?): Int = schema?.let { estimateText(it) } ?: 0

    private companion object {
        const val ASCII_MAX_CODE_POINT = 0x7F
        const val ASCII_CHARS_PER_TOKEN = 3L
        const val NON_ASCII_TOKENS_PER_CODE_POINT = 3L

        /** 每条消息的角色/分隔等协议开销。 */
        const val PER_MESSAGE_OVERHEAD = 8

        /** 一次请求整体的协议开销（字段名、结束标记等）。 */
        const val PROTOCOL_OVERHEAD = 16

        fun ceilDiv(
            value: Long,
            divisor: Long,
        ): Long = (value / divisor) + if (value % divisor == 0L) 0 else 1

        fun saturatedAdd(
            left: Long,
            right: Long,
        ): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        fun saturatedMultiply(
            left: Long,
            right: Long,
        ): Long {
            if (left == 0L || right == 0L) return 0L
            return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
        }

        fun saturate(value: Long): Int = if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
    }
}
