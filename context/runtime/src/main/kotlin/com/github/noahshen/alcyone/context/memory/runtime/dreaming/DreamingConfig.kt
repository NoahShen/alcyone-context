package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import java.time.ZoneId
import java.time.ZoneOffset

/** `context.dreaming` 配置；cron=null 表示 manual-only。 */
data class DreamingConfig(
    val cron: String? = null,
    val timezone: String = DEFAULT_TIMEZONE,
) {
    init {
        validateTimezone(timezone)
        if (cron == null) {
            if (timezone != DEFAULT_TIMEZONE) {
                throw invalid("context.dreaming.timezone is not allowed without context.dreaming.cron")
            }
        } else {
            validateCron(cron)
        }
    }

    companion object {
        const val DEFAULT_TIMEZONE: String = "Asia/Shanghai"
    }
}

private fun validateCron(cron: String) {
    if (cron.isBlank()) throw invalid("context.dreaming.cron must be a 5-field UNIX cron expression")
    try {
        CRON_PARSER.parse(cron).validate()
    } catch (_: IllegalArgumentException) {
        throw invalid("context.dreaming.cron must be a 5-field UNIX cron expression")
    } catch (_: RuntimeException) {
        throw invalid("context.dreaming.cron must be a 5-field UNIX cron expression")
    }
}

private fun validateTimezone(timezone: String) {
    if (timezone.isBlank()) throw invalid("context.dreaming.timezone must be a valid IANA timezone")
    val zone =
        try {
            ZoneId.of(timezone)
        } catch (_: RuntimeException) {
            throw invalid("context.dreaming.timezone must be a valid IANA timezone")
        }
    if (zone is ZoneOffset || zone.id != timezone) {
        throw invalid("context.dreaming.timezone must be a valid IANA timezone")
    }
}

private val CRON_PARSER: CronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

private fun invalid(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
