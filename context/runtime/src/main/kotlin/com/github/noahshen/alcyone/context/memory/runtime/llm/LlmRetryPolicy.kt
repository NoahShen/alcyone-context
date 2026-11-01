package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random

/**
 * CT08-B3 的纯重试策略：可重试判定、退避 + jitter、`Retry-After` 解析。
 *
 * 全部为纯函数（可注入 [Random] 与「当前时刻」），不做 HTTP、不持有状态，便于精确单测。
 * 契约见技术实现设计 §3.5 与方案 §e：
 * - 只重试**可判定的暂时错误**；429 无法安全判定时**不重试**（不猜）；
 * - 额度耗尽（`QUOTA_EXCEEDED`）不可重试，且优先于 `Retry-After`；
 * - 退避 `min(1000 * 2^(attempt-1), 10_000)`，jitter ±20%，**加 jitter 后仍 ≤ 10_000ms**；
 * - `Retry-After` 只接受「纯非负整数秒」与「RFC 1123 日期」，其余（多值/负数/非法/溢出）视为无效。
 */
internal object LlmRetryPolicy {
    /** 基础退避起点（1s）。 */
    const val BASE_BACKOFF_MILLIS: Long = 1_000L

    /** 退避上限（10s）；jitter 后也不得突破。 */
    const val MAX_BACKOFF_MILLIS: Long = 10_000L

    /** jitter 幅度（±20%）。 */
    const val JITTER_PERCENT: Long = 20L

    /** 指数退避的指数上限：`2^4 = 16` 已足以让 base 触顶 10s，避免溢出。 */
    private const val MAX_BACKOFF_EXPONENT = 4

    /** 可重试的 5xx（500/502/503/504）；其他 5xx 不重试。 */
    private val RETRYABLE_SERVER_STATUSES = setOf(500, 502, 503, 504)

    /**
     * 可重试判定（§e 表）。
     *
     * @param hasValidRetryAfter 429 是否带**有效** `Retry-After`（无效值不算）。
     * @param rateLimitHint 429 的 `error.code`/`error.type` 是否属限流类标记。
     */
    fun isRetryable(
        errorCode: LlmErrorCode,
        statusCode: Int? = null,
        hasValidRetryAfter: Boolean = false,
        rateLimitHint: Boolean = false,
    ): Boolean =
        when (errorCode) {
            LlmErrorCode.NETWORK, LlmErrorCode.TIMEOUT -> true
            // 429 只在「能安全判定为暂时」时重试；否则单次失败。
            LlmErrorCode.RATE_LIMITED -> hasValidRetryAfter || rateLimitHint
            LlmErrorCode.PROVIDER_UNAVAILABLE -> statusCode != null && statusCode in RETRYABLE_SERVER_STATUSES
            // QUOTA_EXCEEDED / AUTHENTICATION / INVALID_REQUEST / CONTEXT_LIMIT_EXCEEDED /
            // INVALID_RESPONSE / OUTPUT_TRUNCATED / MODEL_REFUSAL / LOCAL_BUDGET_EXCEEDED / CONFIGURATION
            else -> false
        }

    /**
     * 第 [attempt] 次尝试失败后的退避时长（毫秒）。
     *
     * @param attempt 刚失败的尝试序号（1 起；`<= 0` 按 1 处理）。
     */
    fun backoffMillis(
        attempt: Int,
        random: Random = Random.Default,
    ): Long {
        val normalizedAttempt = attempt.coerceAtLeast(1)
        val exponent = minOf(normalizedAttempt - 1, MAX_BACKOFF_EXPONENT)
        val base = minOf(BASE_BACKOFF_MILLIS * (1L shl exponent), MAX_BACKOFF_MILLIS)
        val jitter = base * JITTER_PERCENT / 100L
        val offset = if (jitter <= 0L) 0L else random.nextLong(-jitter, jitter + 1L)
        return (base + offset).coerceIn(0L, MAX_BACKOFF_MILLIS)
    }

    /**
     * 解析 `Retry-After`；无效返回 `null`（调用方按退避处理）。
     *
     * @param value `Retry-After` 头原值。
     * @param dateHeader 响应 `Date` 头（HTTP-date 差值基准）；缺失时用 [nowMillis]。
     * @param nowMillis 本地当前时刻（毫秒），仅用于缺失 `Date` 头时。
     */
    fun parseRetryAfter(
        value: String?,
        dateHeader: String?,
        nowMillis: Long,
    ): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val seconds = raw.toLongOrNull()
        if (seconds != null) {
            // 负数无效；溢出（秒 -> 毫秒）同样视为无效，不做饱和猜测。
            if (seconds < 0L) return null
            if (seconds > Long.MAX_VALUE / 1_000L) return null
            return seconds * 1_000L
        }
        val targetMillis = parseHttpDateMillis(raw) ?: return null
        val baseMillis = parseHttpDateMillis(dateHeader) ?: nowMillis
        // 负差（目标时间已过去）按 0。
        return (targetMillis - baseMillis).coerceAtLeast(0L)
    }

    /** 解析 RFC 1123 HTTP-date；非法返回 `null`。 */
    fun parseHttpDateMillis(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
