package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig

/** CT24 两个 Task 的配置快照。 */
internal data class DreamingEvidenceBinding(
    val memorySearchTask: LlmTaskConfig,
    val memorySearchProfile: LlmProfileConfig,
    val evidenceTask: LlmTaskConfig,
    val evidenceProfile: LlmProfileConfig,
    val safetyMarginPercent: Int,
)

/** 构造期严格解析 Organized MEMORY_SEARCH 与 Novelty/Raw DREAMING_EVIDENCE 绑定。 */
internal fun resolveDreamingEvidenceBinding(llm: LlmConfig?): DreamingEvidenceBinding {
    val config = llm ?: throw evidenceConfig("context.llm is required for dreaming evidence")
    return DreamingEvidenceBinding(
        memorySearchTask = taskProfile(config, LlmTask.MEMORY_SEARCH, "MEMORY_SEARCH"),
        memorySearchProfile = profile(config, LlmTask.MEMORY_SEARCH, "MEMORY_SEARCH"),
        evidenceTask = taskProfile(config, LlmTask.DREAMING_EVIDENCE, "DREAMING_EVIDENCE"),
        evidenceProfile = profile(config, LlmTask.DREAMING_EVIDENCE, "DREAMING_EVIDENCE"),
        safetyMarginPercent = config.safetyMarginPercent,
    )
}

private fun taskProfile(
    config: LlmConfig,
    task: LlmTask,
    path: String,
): LlmTaskConfig {
    val taskConfig =
        config.tasks[task]
            ?: throw evidenceConfig("context.llm.tasks.$path is required for dreaming evidence")
    val profileConfig =
        config.profiles[taskConfig.profile]
            ?: throw evidenceConfig("context.llm.tasks.$path.profile refers to an unknown profile")
    if (profileConfig.jsonMode != LlmResponseFormat.JSON_SCHEMA &&
        profileConfig.jsonMode != LlmResponseFormat.JSON_OBJECT
    ) {
        throw evidenceConfig("context.llm.profiles.${profileConfig.name}.jsonMode must be JSON_SCHEMA or JSON_OBJECT for dreaming evidence")
    }
    return taskConfig
}

private fun profile(
    config: LlmConfig,
    task: LlmTask,
    path: String,
): LlmProfileConfig {
    val taskConfig = config.tasks[task] ?: throw evidenceConfig("context.llm.tasks.$path is required for dreaming evidence")
    return config.profiles[taskConfig.profile]
        ?: throw evidenceConfig("context.llm.tasks.$path.profile refers to an unknown profile")
}

private fun evidenceConfig(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
