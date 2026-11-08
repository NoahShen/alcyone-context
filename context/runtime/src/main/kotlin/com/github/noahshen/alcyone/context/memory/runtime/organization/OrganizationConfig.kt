package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig

/** `context.organization` 的冻结默认值。时区固定为 Asia/Shanghai，不提供 timezone 字段。 */
object OrganizationDefaults {
    const val IMMEDIATE_ENABLED: Boolean = true
    const val PERIODIC_ENABLED: Boolean = true
    const val CRON: String = "*/10 * * * *"
    const val TIME_ZONE_ID: String = "Asia/Shanghai"
}

/**
 * Organization 的不可变 Trigger 配置快照。
 *
 * `ContextConfig.organization == null` 表示完全禁用；非 null 即显式启用。构造即校验 UNIX 五字段
 * cron，并要求至少一个 Trigger 开启。`periodicEnabled=false` 仍校验 cron，避免保存无效配置。
 */
data class OrganizationConfig(
    val immediateEnabled: Boolean = OrganizationDefaults.IMMEDIATE_ENABLED,
    val periodicEnabled: Boolean = OrganizationDefaults.PERIODIC_ENABLED,
    val cron: String = OrganizationDefaults.CRON,
) {
    init {
        validateOrganizationConfig(this)
    }
}

/**
 * 纯配置绑定门：显式 Organization 必须同时具备 CANDIDATE_SCANNER 与 GOVERNANCE Task/Profile，
 * 且两个 Profile 都使用 JSON_SCHEMA 或 JSON_OBJECT。
 *
 * 本函数不访问数据库、网络或 LLM 实例；B2 Factory 会在资源创建前调用。
 */
internal fun validateOrganizationBinding(
    organization: OrganizationConfig?,
    llm: LlmConfig?,
) {
    if (organization == null) return
    val config = llm ?: throw invalidBinding("context.llm is required when context.organization is configured")
    val scanner = requireTask(config, LlmTask.CANDIDATE_SCANNER)
    val governance = requireTask(config, LlmTask.GOVERNANCE)
    val scannerProfile = requireProfile(config, scanner.profile, "CANDIDATE_SCANNER")
    val governanceProfile = requireProfile(config, governance.profile, "GOVERNANCE")
    requireStructured(scannerProfile.jsonMode, "CANDIDATE_SCANNER")
    requireStructured(governanceProfile.jsonMode, "GOVERNANCE")
}

internal fun validateOrganizationConfig(config: OrganizationConfig) {
    if (!config.immediateEnabled && !config.periodicEnabled) {
        throw invalidOrganization("context.organization requires at least one enabled trigger")
    }
    if (config.cron.isBlank()) {
        throw invalidOrganization(
            "context.organization.triggers.periodic.cron must be a 5-field UNIX cron expression",
        )
    }
    try {
        CRON_PARSER.parse(config.cron).validate()
    } catch (_: IllegalArgumentException) {
        // cron-utils 的异常文本回显原始表达式，不挂 cause。
        throw invalidOrganization("context.organization.triggers.periodic.cron must be a 5-field UNIX cron expression")
    } catch (_: RuntimeException) {
        throw invalidOrganization("context.organization.triggers.periodic.cron must be a 5-field UNIX cron expression")
    }
}

private fun requireTask(
    config: LlmConfig,
    task: LlmTask,
) = config.tasks[task]
    ?: throw invalidBinding("context.llm.tasks.${task.name} is required when context.organization is configured")

private fun requireProfile(
    config: LlmConfig,
    profileName: String,
    task: String,
) = config.profiles[profileName]
    ?: throw invalidBinding("context.llm.tasks.$task.profile refers to an unknown profile: $profileName")

private fun requireStructured(
    format: LlmResponseFormat,
    task: String,
) {
    if (format != LlmResponseFormat.JSON_SCHEMA && format != LlmResponseFormat.JSON_OBJECT) {
        throw invalidBinding("$task profile jsonMode must be JSON_SCHEMA or JSON_OBJECT for organization")
    }
}

private val CRON_PARSER: CronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

private fun invalidOrganization(detail: String): ContextConfigException =
    ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)

private fun invalidBinding(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
