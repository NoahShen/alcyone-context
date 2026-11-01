package com.github.noahshen.alcyone.context.common

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 上海业务日历（日 + 当天毫秒 + 两位序列）的通用 ID 生成器（公共工具，不含业务前缀语义）。
 *
 * 维护 `lastLogicalMillis + sequence`：本地当前毫秒未超过逻辑毫秒时在同一逻辑毫秒
 * 递增两位序号（00～99）；序号超过 99 则逻辑毫秒推进 1 ms 并归零；时钟回拨不得导致
 * 进程内重复（逻辑时刻单调递增）。
 *
 * 日期与当天毫秒按 `Asia/Shanghai` 业务日历编码（上海日历日/当天毫秒），仅承担标识功能，
 * 不代表事实发生时间或真实保存时间。格式化补零使用 [Locale.ROOT]，不受进程当前
 * Locale 影响，避免产出本地数字（如阿拉伯语数字）。
 *
 * 业务前缀由调用方通过 [nextId] 的 [prefix] 参数传入，本类不绑定任何具体业务常量。
 * 生产默认 [Clock.systemUTC]，测试可注入固定/可控 [Clock]。
 *
 * 线程安全：核心状态通过 [synchronized] 保护。
 */
class SequenceIdGenerator(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val shanghaiZone = ZoneId.of("Asia/Shanghai")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT).withZone(shanghaiZone)

    /** 上一逻辑毫秒（epoch-millis），初始为极小值，首次生成会被当前毫秒覆盖。 */
    private var lastLogicalMillis = Long.MIN_VALUE

    /** 同一逻辑毫秒内的两位序号，范围 00～99。 */
    private var sequence = 0

    /**
     * 生成下一个线程安全的序列 ID：`{prefix}-{yyyyMMdd}-{8位当天毫秒}-{2位序列}`。
     *
     * @param prefix 业务前缀（由调用方传入，不得为空串）。
     */
    fun nextId(prefix: String): String =
        synchronized(this) {
            require(prefix.isNotEmpty()) { "prefix must not be empty" }
            val nowMs = clock.millis()
            if (nowMs > lastLogicalMillis) {
                lastLogicalMillis = nowMs
                sequence = 0
            } else {
                // 同毫秒或时钟回拨：逻辑毫秒不退缩，仅推进序号；溢出则逻辑毫秒 +1 ms。
                sequence++
                if (sequence > 99) {
                    lastLogicalMillis++
                    sequence = 0
                }
            }

            val zoned = Instant.ofEpochMilli(lastLogicalMillis).atZone(shanghaiZone)
            val dayMillis = (zoned.toLocalTime().toNanoOfDay() / 1_000_000).toInt()
            "$prefix-${dateFormat.format(zoned)}-${"%08d".format(Locale.ROOT, dayMillis)}-${"%02d".format(Locale.ROOT, sequence)}"
        }
}
