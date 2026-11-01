package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.DateTimeException
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** 单次 Idle 扫描的结果（internal，仅用于测试/诊断）。 */
internal enum class IdleScanOutcome {
    /** 命中至少一个空闲会话并逐个提交了提取请求。 */
    TRIGGERED,

    /** 扫描成功但没有空闲会话。 */
    NO_CANDIDATES,

    /** 已有扫描在运行：本次 single-flight 跳过（不排队）。 */
    SKIPPED,

    /** 截止时间溢出或查询失败：本次扫描受控失败，未触发任何会话。 */
    FAILED,
}

/**
 * CT09-B2 Idle Trigger（Runtime internal）。
 *
 * 语义（D6 + §2.4）：
 * - [scanOnce] **single-flight**：同一时刻只允许一次扫描，重叠/并发调用直接
 *   [IdleScanOutcome.SKIPPED]（不排队），并累计 `skippedForTesting()`、写 `extraction.idle.skipped` DEBUG；
 * - 截止时间 `idleBefore = clock.instant() - idleTimeout` **直接用 `Instant` 运算**（不先转毫秒/秒，
 *   不做饱和）：极大有限 [Duration] 或 `Instant.MIN/MAX` 导致 `DateTimeException`/`ArithmeticException`
 *   时，本次扫描受控失败（`extraction.idle.failed` WARN）且**不触发任何会话**，不会变成错误的短窗口
 *   或绕回未来；下一次自然扫描仍然可用；
 * - 成功路径复用既有 `findIdleSessions(idleBefore)`（所有状态 `created_at` + 必须 pending），
 *   对每个会话只提交 `SessionKey`（不选 Turn）；
 * - 查询/扫描的普通失败：`extraction.idle.failed` WARN 后隔离返回；`CancellationException` 原样传播
 *   且不记普通失败；`finally` 一定释放 single-flight 标记。
 *
 * 日志只含固定事件名（DEBUG/WARN），**不含 SessionKey、消息、SQL、异常文本，也不传 Throwable**。
 */
internal class IdleTrigger(
    private val databaseProvider: () -> ContextDatabase,
    private val idleTimeout: Duration,
    private val clock: Clock,
    private val request: (SessionKey) -> Boolean,
    private val logger: Logger = LoggerFactory.getLogger(IdleTrigger::class.java),
) {
    private val lock = Any()

    private var scanning: Boolean = false

    private var skipped: Long = 0

    /** 测试接缝：扫描准入后、查询前的可控屏障（生产为 null）。 */
    internal var scanBarrierForTesting: (suspend () -> Unit)? = null

    internal fun skippedForTesting(): Long = synchronized(lock) { skipped }

    internal fun isScanningForTesting(): Boolean = synchronized(lock) { scanning }

    suspend fun scanOnce(): IdleScanOutcome {
        val admitted =
            synchronized(lock) {
                if (scanning) {
                    skipped++
                    false
                } else {
                    scanning = true
                    true
                }
            }
        if (!admitted) {
            logger.debug("extraction.idle.skipped")
            return IdleScanOutcome.SKIPPED
        }
        try {
            scanBarrierForTesting?.invoke()

            val idleBefore =
                try {
                    // 直接做 Instant 运算（Duration -> java.time 精确换算，不截断/不饱和）。
                    clock.instant().minus(idleTimeout.toJavaDuration())
                } catch (overflow: DateTimeException) {
                    return failed()
                } catch (overflow: ArithmeticException) {
                    return failed()
                }

            val sessions =
                try {
                    databaseProvider().read { read -> read.conversations.findIdleSessions(idleBefore) }
                } catch (cancellation: CancellationException) {
                    // 取消原样传播，不记普通失败。
                    throw cancellation
                } catch (failure: Throwable) {
                    return failed()
                }

            if (sessions.isEmpty()) return IdleScanOutcome.NO_CANDIDATES
            sessions.forEach { sessionKey -> request(sessionKey) }
            return IdleScanOutcome.TRIGGERED
        } finally {
            synchronized(lock) { scanning = false }
        }
    }

    private fun failed(): IdleScanOutcome {
        logger.warn("extraction.idle.failed")
        return IdleScanOutcome.FAILED
    }
}
