package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig

/** DREAMING_REFLECTION 的构造期配置快照。 */
internal data class DreamingReflectionBinding(
    val reflectionTask: LlmTaskConfig,
    val reflectionProfile: LlmProfileConfig,
    val safetyMarginPercent: Int,
)

/** 构造期严格校验 Reflection Task、Profile 与结构化输出模式。 */
internal fun resolveDreamingReflectionBinding(llm: LlmConfig?): DreamingReflectionBinding {
    val config = llm ?: throw reflectionConfig("context.llm is required for dreaming reflection")
    val task =
        config.tasks[LlmTask.DREAMING_REFLECTION]
            ?: throw reflectionConfig("context.llm.tasks.DREAMING_REFLECTION is required for dreaming reflection")
    val profile =
        config.profiles[task.profile]
            ?: throw reflectionConfig("context.llm.tasks.DREAMING_REFLECTION.profile refers to an unknown profile")
    if (profile.jsonMode != LlmResponseFormat.JSON_SCHEMA && profile.jsonMode != LlmResponseFormat.JSON_OBJECT) {
        throw reflectionConfig(
            "context.llm.profiles.${profile.name}.jsonMode must be JSON_SCHEMA or JSON_OBJECT for dreaming reflection",
        )
    }
    return DreamingReflectionBinding(
        reflectionTask = task,
        reflectionProfile = profile,
        safetyMarginPercent = config.safetyMarginPercent,
    )
}

private fun reflectionConfig(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
