package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig

/** Complete Dreaming bindings enable manual runs; Cron additionally enables periodic runs. */
internal fun validateDreamingBinding(
    config: DreamingConfig,
    llm: LlmConfig?,
) {
    val exclusive = setOf(LlmTask.DREAMING_TOPIC, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION)
    val tasks = listOf(LlmTask.DREAMING_TOPIC, LlmTask.MEMORY_SEARCH, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION)
    // MEMORY_SEARCH alone remains an ordinary retrieval binding, including its existing call-time failures.
    if (config.cron == null && llm?.tasks?.keys?.none { it in exclusive } != false) return
    val owned = llm ?: throw invalid("context.llm is required when context.dreaming is configured")
    tasks
        .forEach { task ->
            val taskConfig = owned.tasks[task] ?: throw invalid("context.llm.tasks.$task is required for dreaming")
            val profile =
                owned.profiles[taskConfig.profile]
                    ?: throw invalid("context.llm.tasks.$task.profile refers to an unknown profile")
            if (profile.jsonMode != LlmResponseFormat.JSON_SCHEMA && profile.jsonMode != LlmResponseFormat.JSON_OBJECT) {
                throw invalid("$task profile jsonMode must be JSON_SCHEMA or JSON_OBJECT for dreaming")
            }
        }
}

private fun invalid(message: String) = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, message)
