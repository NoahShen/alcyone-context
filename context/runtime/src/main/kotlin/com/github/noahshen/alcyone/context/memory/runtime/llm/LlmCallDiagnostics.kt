package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import java.util.concurrent.ConcurrentHashMap

/**
 * CT08-B5 单次调用的**脱敏诊断**（§g 字段白名单）。
 *
 * 只承载白名单字段：`task` / `profile` / `model` / `attempts` / `statusCode` / `elapsedMillis` /
 * `estimatedInputTokens` / `providerUsagePresent` / `retryable`。
 * **不持有** Key、Authorization、Prompt/messages、schema、请求/响应正文、baseUrl、Provider 原始
 * `code`/`requestId`/`finishReason`，也不持有异常对象；未知值输出 `null`（不用 0 冒充）。
 */
internal data class LlmCallDiagnostics(
    val task: String,
    val profile: String,
    val model: String?,
    val attempts: Int,
    val statusCode: Int?,
    val elapsedMillis: Long,
    val estimatedInputTokens: Long?,
    val providerUsagePresent: Boolean,
    /** 仅失败诊断携带；成功时为 `null`（可省略）。 */
    val retryable: Boolean? = null,
) {
    /** 固定顺序的 `key=value` 白名单文本；未知值写 `null`。 */
    fun fields(): String =
        buildString {
            append("task=").append(task)
            append(" profile=").append(profile)
            append(" model=").append(model ?: NULL)
            append(" attempts=").append(attempts)
            append(" statusCode=").append(statusCode ?: NULL)
            append(" elapsedMillis=").append(elapsedMillis)
            append(" estimatedInputTokens=").append(estimatedInputTokens ?: NULL)
            append(" providerUsagePresent=").append(providerUsagePresent)
            retryable?.let { append(" retryable=").append(it) }
        }

    override fun toString(): String = "LlmCallDiagnostics(${fields()})"

    private companion object {
        const val NULL = "null"
    }
}

/**
 * 按 `(task, profile)` 分组的**进程内最小统计**（§m 5；重启清零、不落库、不宣称计费）。
 *
 * `calls = successes + failures`；`attempts` 为实际执行尝试数之和；token 仅对 `usage != null`
 * 的调用求和，**无样本为 `null`**（不是 0）。
 */
internal data class LlmCallStats(
    val calls: Long,
    val successes: Long,
    val failures: Long,
    val attempts: Long,
    val usagePresent: Long,
    val usageMissing: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)

/** 线程安全的统计容器：并发终态计数不得丢失。 */
internal class LlmCallStatistics {
    private class Key(
        val task: LlmTask,
        val profile: String,
    ) {
        override fun equals(other: Any?): Boolean = other is Key && other.task == task && other.profile == profile

        override fun hashCode(): Int = 31 * task.hashCode() + profile.hashCode()
    }

    private class Counters {
        var calls: Long = 0
        var successes: Long = 0
        var failures: Long = 0
        var attempts: Long = 0
        var usagePresent: Long = 0
        var usageMissing: Long = 0
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var totalTokens: Long? = null

        fun recordSuccess(
            attempts: Int,
            usage: TokenUsage?,
        ) {
            calls++
            successes++
            this.attempts += attempts
            if (usage == null) {
                usageMissing++
            } else {
                usagePresent++
                inputTokens = add(inputTokens, usage.inputTokens)
                outputTokens = add(outputTokens, usage.outputTokens)
                totalTokens = add(totalTokens, usage.totalTokens)
            }
        }

        fun recordFailure(attempts: Int) {
            calls++
            failures++
            this.attempts += attempts
        }

        fun snapshot(): LlmCallStats =
            LlmCallStats(
                calls = calls,
                successes = successes,
                failures = failures,
                attempts = attempts,
                usagePresent = usagePresent,
                usageMissing = usageMissing,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = totalTokens,
            )

        /** 只对已知样本求和；没有样本时保持 `null`。 */
        private fun add(
            current: Long?,
            next: Long?,
        ): Long? =
            when {
                next == null -> current
                current == null -> next
                else -> current + next
            }
    }

    private val entries = ConcurrentHashMap<Key, Counters>()

    fun recordSuccess(
        task: LlmTask,
        profile: String,
        attempts: Int,
        usage: TokenUsage?,
    ) {
        val counters = entries.computeIfAbsent(Key(task, profile)) { Counters() }
        synchronized(counters) { counters.recordSuccess(attempts, usage) }
    }

    fun recordFailure(
        task: LlmTask,
        profile: String,
        attempts: Int,
    ) {
        val counters = entries.computeIfAbsent(Key(task, profile)) { Counters() }
        synchronized(counters) { counters.recordFailure(attempts) }
    }

    /** 无记录返回 `null`（不创建空记录）。 */
    fun stats(
        task: LlmTask,
        profile: String,
    ): LlmCallStats? {
        val counters = entries[Key(task, profile)] ?: return null
        return synchronized(counters) { counters.snapshot() }
    }
}
