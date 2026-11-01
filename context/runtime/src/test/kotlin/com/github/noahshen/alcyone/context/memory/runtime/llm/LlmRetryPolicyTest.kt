package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT08-B3 A05（纯逻辑部分）：可重试判定表、指数退避 + jitter 上限、`Retry-After` 解析。
 */
class LlmRetryPolicyTest {
    // ------------------------------------------------------------------ 可重试判定

    @Test
    fun `retryable classification follows the frozen table`() {
        // 可重试：NETWORK / TIMEOUT / 500-502-503-504 / 429 可安全判定。
        assertTrue(LlmRetryPolicy.isRetryable(LlmErrorCode.NETWORK))
        assertTrue(LlmRetryPolicy.isRetryable(LlmErrorCode.TIMEOUT, statusCode = 408))
        listOf(500, 502, 503, 504).forEach { status ->
            assertTrue(
                LlmRetryPolicy.isRetryable(LlmErrorCode.PROVIDER_UNAVAILABLE, statusCode = status),
                "status=$status 应可重试",
            )
        }
        assertTrue(LlmRetryPolicy.isRetryable(LlmErrorCode.RATE_LIMITED, statusCode = 429, hasValidRetryAfter = true))
        assertTrue(LlmRetryPolicy.isRetryable(LlmErrorCode.RATE_LIMITED, statusCode = 429, rateLimitHint = true))

        // 不可重试：429 无法安全判定、额度耗尽、其他 5xx、鉴权/请求/响应类错误。
        assertFalse(LlmRetryPolicy.isRetryable(LlmErrorCode.RATE_LIMITED, statusCode = 429))
        assertFalse(
            LlmRetryPolicy.isRetryable(
                LlmErrorCode.QUOTA_EXCEEDED,
                statusCode = 429,
                hasValidRetryAfter = true,
                rateLimitHint = true,
            ),
            "额度耗尽必须不可重试，且优先于 Retry-After",
        )
        listOf(501, 505).forEach { status ->
            assertFalse(
                LlmRetryPolicy.isRetryable(LlmErrorCode.PROVIDER_UNAVAILABLE, statusCode = status),
                "status=$status 不应重试",
            )
        }
        listOf(
            LlmErrorCode.AUTHENTICATION,
            LlmErrorCode.INVALID_REQUEST,
            LlmErrorCode.CONTEXT_LIMIT_EXCEEDED,
            LlmErrorCode.INVALID_RESPONSE,
            LlmErrorCode.OUTPUT_TRUNCATED,
            LlmErrorCode.MODEL_REFUSAL,
            LlmErrorCode.LOCAL_BUDGET_EXCEEDED,
            LlmErrorCode.CONFIGURATION,
        ).forEach { code ->
            assertFalse(LlmRetryPolicy.isRetryable(code, statusCode = 400), "$code 不应重试")
        }
    }

    // ------------------------------------------------------------------ 退避与 jitter

    @Test
    fun `backoff grows exponentially and never exceeds the cap`() {
        // 基础序列：1s / 2s / 4s / 8s / 10s（触顶）/ 10s。
        val expectedBase = listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L)
        expectedBase.forEachIndexed { index, base ->
            val attempt = index + 1
            val jitter = base * LlmRetryPolicy.JITTER_PERCENT / 100L
            val lower = (base - jitter).coerceAtLeast(0L)
            val upper = minOf(base + jitter, LlmRetryPolicy.MAX_BACKOFF_MILLIS)
            val random = Random(attempt * 31)
            repeat(200) {
                val wait = LlmRetryPolicy.backoffMillis(attempt, random)
                assertTrue(wait in lower..upper, "attempt=$attempt wait=$wait 超出 [$lower, $upper]")
                assertTrue(wait <= LlmRetryPolicy.MAX_BACKOFF_MILLIS, "attempt=$attempt wait=$wait 突破 10s 上限")
            }
        }
    }

    @Test
    fun `backoff jitter is bounded at both extremes`() {
        // 全零随机源 → 下界；全一随机源 → 上界（触顶时被压回 10s）。
        (1..6).forEach { attempt ->
            val low = LlmRetryPolicy.backoffMillis(attempt, MinRandom)
            val high = LlmRetryPolicy.backoffMillis(attempt, MaxRandom)
            assertTrue(low >= 0L, "attempt=$attempt low=$low")
            assertTrue(high <= LlmRetryPolicy.MAX_BACKOFF_MILLIS, "attempt=$attempt high=$high")
            assertTrue(low <= high, "attempt=$attempt low=$low high=$high")
        }
        // 全零随机源稳定命中下界；全一随机源受 `nextLong(bound)` 拒绝采样影响，
        // 只保证落在 ±20% 区间内（不假设精确上界）。
        assertEquals(800L, LlmRetryPolicy.backoffMillis(1, MinRandom))
        assertTrue(LlmRetryPolicy.backoffMillis(1, MaxRandom) in 1_000L..1_200L)
        // attempt 5+ 触顶：jitter 后仍被压回 ≤ 10_000。
        assertEquals(8_000L, LlmRetryPolicy.backoffMillis(5, MinRandom))
        assertTrue(LlmRetryPolicy.backoffMillis(5, MaxRandom) in 8_000L..10_000L)
        assertTrue(LlmRetryPolicy.backoffMillis(99, MaxRandom) in 8_000L..10_000L)
    }

    // ------------------------------------------------------------------ Retry-After

    @Test
    fun `retry after seconds are converted to millis`() {
        assertEquals(1_000L, LlmRetryPolicy.parseRetryAfter("1", dateHeader = null, nowMillis = 0L))
        assertEquals(0L, LlmRetryPolicy.parseRetryAfter("0", dateHeader = null, nowMillis = 0L))
        assertEquals(120_000L, LlmRetryPolicy.parseRetryAfter(" 120 ", dateHeader = null, nowMillis = 0L))
    }

    @Test
    fun `invalid retry after values are treated as absent`() {
        listOf("-1", "1, 2", "abc", "", "  ", "1.5", "99999999999999999999", "Wed, 99 Xyz 2026").forEach { raw ->
            assertNull(LlmRetryPolicy.parseRetryAfter(raw, dateHeader = null, nowMillis = 0L), "raw=$raw")
        }
        assertNull(LlmRetryPolicy.parseRetryAfter(null, dateHeader = null, nowMillis = 0L))
    }

    @Test
    fun `retry after http date is relative to the date header or now`() {
        val dateHeader = "Wed, 21 Oct 2026 07:28:00 GMT"
        val base = LlmRetryPolicy.parseHttpDateMillis(dateHeader)!!
        val later = "Wed, 21 Oct 2026 07:28:05 GMT"
        assertEquals(5_000L, LlmRetryPolicy.parseRetryAfter(later, dateHeader = dateHeader, nowMillis = 0L))

        // Date 头缺失 → 用注入的本地当前时刻。
        val now = base + 3_000L
        assertEquals(2_000L, LlmRetryPolicy.parseRetryAfter(later, dateHeader = null, nowMillis = now))

        // 目标时间已过去（负差）→ 0。
        val earlier = "Wed, 21 Oct 2026 07:27:55 GMT"
        assertEquals(0L, LlmRetryPolicy.parseRetryAfter(earlier, dateHeader = dateHeader, nowMillis = 0L))
        assertEquals(0L, LlmRetryPolicy.parseRetryAfter(earlier, dateHeader = null, nowMillis = base))
    }

    // ------------------------------------------------------------------ 工具

    /** 全零随机源：jitter 取下界。 */
    private object MinRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    /** 全一随机源：jitter 取上界。 */
    private object MaxRandom : Random() {
        override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else -1 ushr (32 - bitCount)
    }
}
