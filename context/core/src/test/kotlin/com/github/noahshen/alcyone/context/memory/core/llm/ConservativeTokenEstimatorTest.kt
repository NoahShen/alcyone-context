package com.github.noahshen.alcyone.context.memory.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CT08-B1：保守 Token 估算规则（纯逻辑，无 Ktor / 无序列化依赖）。
 *
 * 断言的是**规则本身**（不是 Provider 真实计费值）：ASCII 3 字符/token、非 ASCII 每码点 3、
 * role 按实际字符串估算、每条消息 8 + 协议 16、`null` schema 为 0。
 */
class ConservativeTokenEstimatorTest {
    private val estimator = ConservativeTokenEstimator()

    @Test
    fun `ascii text uses three characters per token rounded up`() {
        assertEquals(0, estimator.estimateText(""))
        assertEquals(1, estimator.estimateText("a"))
        assertEquals(1, estimator.estimateText("abc"))
        assertEquals(2, estimator.estimateText("abcd"))
        assertEquals(2, estimator.estimateText("abcdef"))
        assertEquals(4, estimator.estimateText("a".repeat(10)))
    }

    @Test
    fun `non ascii code points cost three tokens each`() {
        assertEquals(3, estimator.estimateText("你"))
        assertEquals(6, estimator.estimateText("你好"))
        // emoji 是一个 Unicode 码点（UTF-16 代理对），不是两个码点。
        assertEquals(3, estimator.estimateText("\uD83D\uDE00"))
        assertEquals(3, estimator.estimateText("😀"))
    }

    @Test
    fun `mixed text adds ascii rounding and non ascii per code point`() {
        // ascii 2 个 → ceil(2/3) = 1；非 ascii 1 个 → 3；合计 4。
        assertEquals(4, estimator.estimateText("ab你"))
        // ascii 3 个 → 1；emoji 1 个 → 3；合计 4。
        assertEquals(4, estimator.estimateText("abc😀"))
    }

    @Test
    fun `prompt estimation includes roles content message overhead and protocol overhead`() {
        // role "user" = ceil(4/3) = 2；content "abcd" = 2；每条消息 8；协议 16。
        assertEquals(
            28,
            estimator.estimatePrompt(listOf(LlmMessage(role = "user", content = "abcd"))),
        )
        // 空消息列表只有协议开销。
        assertEquals(16, estimator.estimatePrompt(emptyList()))
        // 空 content 不产生正文开销，但仍有 role 与两条消息开销。
        assertEquals(
            37,
            estimator.estimatePrompt(
                listOf(
                    LlmMessage(role = "system", content = "x"),
                    LlmMessage(role = "user", content = ""),
                ),
            ),
        )
    }

    @Test
    fun `prompt estimation uses the actual role string instead of a fixed role cost`() {
        val shortRole = estimator.estimatePrompt(listOf(LlmMessage(role = "u", content = "abcd")))
        val longRole = estimator.estimatePrompt(listOf(LlmMessage(role = "supervisor-role", content = "abcd")))
        // "u" = 1， "supervisor-role" = ceil(15/3) = 5 → 差 4。
        assertEquals(4, longRole - shortRole)
    }

    @Test
    fun `schema estimation is zero for null and text rules otherwise`() {
        assertEquals(0, estimator.estimateSchema(null))
        assertEquals(0, estimator.estimateSchema(""))
        assertEquals(2, estimator.estimateSchema("abcd"))
        assertEquals(3, estimator.estimateSchema("你"))
    }

    @Test
    fun `large inputs stay positive and exact`() {
        val text = "你".repeat(200_000)
        assertEquals(600_000, estimator.estimateText(text))
        val ascii = "a".repeat(300_000)
        assertEquals(100_000, estimator.estimateText(ascii))
        assertTrue(estimator.estimateText(text) > 0)
    }

    @Test
    fun `estimator implements the core port without transport dependencies`() {
        val port: TokenEstimator = estimator
        assertEquals(2, port.estimateText("abcdef"))
    }
}
