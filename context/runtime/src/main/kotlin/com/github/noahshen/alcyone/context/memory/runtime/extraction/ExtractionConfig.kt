package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** `context.extraction.*` 的冻结默认值（CT09 D1/D2）。 */
object ExtractionDefaults {
    const val COUNT_THRESHOLD: Int = 5
    const val IDLE_ENABLED: Boolean = true
    val IDLE_TIMEOUT: Duration = 10.minutes
    const val CRON: String = "*/5 * * * *"

    /** Cron 的固定时区（不提供 `timezone` 配置键，出现即未知键）。 */
    const val TIME_ZONE_ID: String = "Asia/Shanghai"

    /** CT10 预提取上下文的初始窗口轮次数（D4）。 */
    const val INITIAL_WINDOW_TURNS: Int = 32

    /** `initialWindowTurns` 的允许范围。 */
    const val MIN_INITIAL_WINDOW_TURNS: Int = 1
    const val MAX_INITIAL_WINDOW_TURNS: Int = 1024
}

/**
 * `context.extraction` 的不可变配置快照（CT09-B1）。
 *
 * 层级对应 `context.extraction.triggers.count.threshold`、
 * `context.extraction.triggers.idle.enabled` / `.idleTimeout` / `.cron`；全部可省略。
 *
 * **构造即校验**：[validateExtraction] 在 `init` 中执行，因此 YAML 加载与直接 Kotlin 构造
 * 使用同一套规则（Loader 不重复实现范围/语法判定）。
 *
 * 校验失败抛 [ContextConfigException]（`INVALID_CONFIG`）：消息只含字段路径与原因，
 * 不回显原始值，也不附带可能泄漏原值的解析 cause（cron 解析异常不挂 cause）。
 *
 * @property countThreshold Count Trigger 的 pending 阈值（`>= 1`）。
 * @property idleEnabled 是否启用 Idle Trigger；关闭它**不**豁免其余字段的合法性校验。
 * @property idleTimeout Idle 判定窗口：**有限正** `Duration`（0/负/无限都拒绝）。
 * @property cron UNIX 五字段 cron（分/时/日/月/周），时区固定 `Asia/Shanghai`。
 * @property initialWindowTurns CT10 预提取上下文的**初始**窗口轮次数（`1..1024`，默认 32）；
 *   预算超限时由 Builder 按实际读取条数折半，本值只是初始 LIMIT。
 */
data class ExtractionConfig(
    val countThreshold: Int = ExtractionDefaults.COUNT_THRESHOLD,
    val idleEnabled: Boolean = ExtractionDefaults.IDLE_ENABLED,
    val idleTimeout: Duration = ExtractionDefaults.IDLE_TIMEOUT,
    val cron: String = ExtractionDefaults.CRON,
    val initialWindowTurns: Int = ExtractionDefaults.INITIAL_WINDOW_TURNS,
) {
    init {
        validateExtraction(this)
    }
}

/**
 * `context.extraction` 的唯一校验入口（YAML 与直接构造共用）。
 *
 * 抛 [ContextConfigException]（`INVALID_CONFIG`）；消息只含字段路径与规则，不回显非法原值。
 */
internal fun validateExtraction(config: ExtractionConfig) {
    if (config.countThreshold < 1) {
        throw invalidExtraction("context.extraction.triggers.count.threshold must be a positive integer")
    }
    if (!config.idleTimeout.isFinite() || config.idleTimeout.isNegative() || config.idleTimeout == Duration.ZERO) {
        throw invalidExtraction("context.extraction.triggers.idle.idleTimeout must be a finite positive duration")
    }
    if (config.initialWindowTurns !in
        ExtractionDefaults.MIN_INITIAL_WINDOW_TURNS..ExtractionDefaults.MAX_INITIAL_WINDOW_TURNS
    ) {
        throw invalidExtraction(
            "context.extraction.initialWindowTurns must be in " +
                "${ExtractionDefaults.MIN_INITIAL_WINDOW_TURNS}..${ExtractionDefaults.MAX_INITIAL_WINDOW_TURNS}",
        )
    }
    if (config.cron.isBlank()) {
        throw invalidExtraction("context.extraction.triggers.idle.cron must be a 5-field UNIX cron expression")
    }
    try {
        CRON_PARSER.parse(config.cron).validate()
    } catch (failure: IllegalArgumentException) {
        // 不挂 cause：cron-utils 的异常消息包含原始表达式。
        throw invalidExtraction("context.extraction.triggers.idle.cron must be a 5-field UNIX cron expression")
    } catch (failure: RuntimeException) {
        throw invalidExtraction("context.extraction.triggers.idle.cron must be a 5-field UNIX cron expression")
    }
}

/** UNIX 五字段定义（分/时/日/月/周）；固定时区由 [ExtractionDefaults.TIME_ZONE_ID] 表达。 */
private val CRON_PARSER = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

private fun invalidExtraction(detail: String): ContextConfigException =
    ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
