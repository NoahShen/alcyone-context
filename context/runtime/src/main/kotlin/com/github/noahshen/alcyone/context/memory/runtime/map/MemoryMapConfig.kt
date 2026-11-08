package com.github.noahshen.alcyone.context.memory.runtime.map

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig

/** `context.memoryMap` 的冻结默认值。 */
object MemoryMapDefaults {
    const val PERIODIC_ENABLED: Boolean = true
    const val CRON: String = "*/10 * * * *"
    const val TIME_ZONE_ID: String = "Asia/Shanghai"
}

/** Memory Map 的 Periodic 补偿配置；关闭 Periodic 不关闭启动检查与后续通知入口。 */
data class MemoryMapPeriodicConfig(
    val enabled: Boolean = MemoryMapDefaults.PERIODIC_ENABLED,
    val cron: String = MemoryMapDefaults.CRON,
) {
    init {
        validateMemoryMapCron(cron)
    }
}

/** `context.memoryMap` 配置快照；非 null 表示显式启用 Map pipeline。 */
data class MemoryMapConfig(
    val periodic: MemoryMapPeriodicConfig = MemoryMapPeriodicConfig(),
)

/** B2 Factory 使用的纯 LLM 绑定校验；本函数不访问数据库、网络或 LLM 实例。 */
internal fun validateMemoryMapBinding(
    memoryMap: MemoryMapConfig?,
    llm: LlmConfig?,
) {
    if (memoryMap == null) return
    val config = llm ?: throw invalidBinding("context.llm is required when context.memoryMap is configured")
    val task =
        config.tasks[LlmTask.MEMORY_MAP]
            ?: throw invalidBinding("context.llm.tasks.MEMORY_MAP is required when context.memoryMap is configured")
    val profile =
        config.profiles[task.profile]
            ?: throw invalidBinding("context.llm.tasks.MEMORY_MAP.profile refers to an unknown profile: ${task.profile}")
    if (profile.jsonMode != LlmResponseFormat.JSON_SCHEMA && profile.jsonMode != LlmResponseFormat.JSON_OBJECT) {
        throw invalidBinding("MEMORY_MAP profile jsonMode must be JSON_SCHEMA or JSON_OBJECT for memory map")
    }
}

private fun validateMemoryMapCron(cron: String) {
    if (cron.isBlank()) throw invalidMemoryMap("context.memoryMap.refresh.periodic.cron must be a 5-field UNIX cron expression")
    try {
        CRON_PARSER.parse(cron).validate()
    } catch (_: IllegalArgumentException) {
        // cron-utils includes the original expression in its exception; do not attach it as a cause.
        throw invalidMemoryMap("context.memoryMap.refresh.periodic.cron must be a 5-field UNIX cron expression")
    } catch (_: RuntimeException) {
        throw invalidMemoryMap("context.memoryMap.refresh.periodic.cron must be a 5-field UNIX cron expression")
    }
}

private val CRON_PARSER: CronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

private fun invalidMemoryMap(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)

private fun invalidBinding(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
