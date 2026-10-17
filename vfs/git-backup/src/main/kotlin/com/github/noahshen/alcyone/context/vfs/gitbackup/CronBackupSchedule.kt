package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Clock
import java.time.ZonedDateTime
import java.util.Optional

/**
 * 内部 Cron 计算调度组件。
 *
 * - 方言：标准 UNIX 5 字段（分 时 日 月 周），周天取值 0-6（0 为周日，6 为周六），DOM/DOW 任一匹配原则（OR 语义）。
 * - 严格拒绝 6 字段（如含秒）、Quartz 专有特殊字符（?、L、W、# 等）、星期基数 7、英文字母缩写。
 * - 星期字段允许正整数步长（如 `*​/7` 或 `1-5/2`），步长范围 1..7。
 * - 构造期执行有界未来可达性校验，拒绝永远无未来匹配的表达式（如 2月31日）。
 */
internal class CronBackupSchedule(
    val expression: String,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val executionTime: ExecutionTime

    init {
        val trimmed = expression.trim()
        val parts = trimmed.split(WHITESPACE_REGEX)
        require(parts.size == 5) {
            "Cron expression must have exactly 5 fields, got ${parts.size}: '$expression'"
        }

        // 禁止任何英文字母（包括月份与星期缩写如 JAN, MON, 以及 Quartz 字符 L, W）
        require(!trimmed.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            "English letters and abbreviations are not allowed in cron: '$expression'"
        }

        // 禁止 Quartz 特殊字符
        require(!parts.any { field -> field.any { it in FORBIDDEN_QUARTZ_CHARS } }) {
            "Quartz specific characters (?, L, W, #) are not allowed in UNIX cron: '$expression'"
        }

        // 星期契约严格限定为 0-6（0=周日，6=周六），明确拒绝基数 7（包括 7, 1-7, 0,7 等）；步长允许 1..7
        validateDayOfWeekField(parts[4], expression)

        try {
            val cron = CRON_PARSER.parse(trimmed)
            cron.validate()
            executionTime = ExecutionTime.forCron(cron)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cron expression: '$expression'", e)
        }

        // 构造期有界未来可达性校验：拒绝如 2月31日 "0 3 31 2 *" 等无未来执行时间的表达式
        val next = executionTime.nextExecution(ZonedDateTime.now(clock))
        require(next.isPresent) {
            "Cron expression has no future execution: '$expression'"
        }
    }

    /**
     * 计算下一个执行时刻；若无未来匹配则返回 null。
     */
    fun nextExecution(from: ZonedDateTime = ZonedDateTime.now(clock)): ZonedDateTime? {
        val nextOpt: Optional<ZonedDateTime> = executionTime.nextExecution(from)
        return if (nextOpt.isPresent) nextOpt.get() else null
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NUMBER_REGEX = Regex("\\d+")
        private val FORBIDDEN_QUARTZ_CHARS = setOf('?', 'L', 'l', 'W', 'w', '#')
        private val CRON_DEFINITION = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
        private val CRON_PARSER = CronParser(CRON_DEFINITION)

        private fun validateDayOfWeekField(
            field: String,
            expression: String,
        ) {
            val items = field.split(',')
            for (item in items) {
                val parts = item.split('/')
                require(parts.size <= 2) {
                    "Invalid day of week step format in: '$expression'"
                }

                val base = parts[0]
                if (parts.size == 2) {
                    val stepStr = parts[1]
                    val step = stepStr.toIntOrNull()
                    require(step != null && step in 1..7) {
                        "Day of week step must be between 1 and 7, got: '$stepStr' in '$expression'"
                    }
                }

                // 检查 base 中的星期基数
                if (base != "*") {
                    val numbers = NUMBER_REGEX.findAll(base).map { it.value.toInt() }.toList()
                    require(numbers.none { it !in 0..6 }) {
                        "Day of week must be between 0 and 6, got 7: '$expression'"
                    }
                }
            }
        }
    }
}
