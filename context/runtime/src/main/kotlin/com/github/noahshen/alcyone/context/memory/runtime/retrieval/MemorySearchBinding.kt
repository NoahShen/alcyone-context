package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig

/** Memory Search 的冻结默认上限与允许边界。 */
internal object MemorySearchDefaults {
    const val MAX_LIMIT: Int = 50
    const val MAX_LIMIT_BOUND: Int = 1_000
}

internal data class MemorySearchBinding(
    val task: LlmTaskConfig,
    val profile: LlmProfileConfig,
    val safetyMarginPercent: Int,
)

/** 在 Search 调用时解析 MEMORY_SEARCH Task/Profile；失败不阻塞 start/Get/Bootstrap。 */
internal fun resolveMemorySearchBinding(llm: LlmConfig?): MemorySearchBinding {
    val config = llm ?: throw configuration("context.llm is required for memory search")
    val task =
        config.tasks[LlmTask.MEMORY_SEARCH]
            ?: throw configuration("context.llm.tasks.MEMORY_SEARCH is required for memory search")
    val profile =
        config.profiles[task.profile]
            ?: throw configuration("context.llm.tasks.MEMORY_SEARCH.profile refers to an unknown profile")
    if (profile.jsonMode != LlmResponseFormat.JSON_SCHEMA && profile.jsonMode != LlmResponseFormat.JSON_OBJECT) {
        throw configuration("MEMORY_SEARCH profile jsonMode must be JSON_SCHEMA or JSON_OBJECT")
    }
    return MemorySearchBinding(task = task, profile = profile, safetyMarginPercent = config.safetyMarginPercent)
}

internal fun requireValidMaxLimit(maxLimit: Int): Int {
    if (maxLimit !in 1..MemorySearchDefaults.MAX_LIMIT_BOUND) {
        throw configuration(
            "maxLimit must be in 1..${MemorySearchDefaults.MAX_LIMIT_BOUND}",
        )
    }
    return maxLimit
}

internal fun requireValidSearchLimit(
    limit: Int,
    maxLimit: Int,
) {
    if (limit !in 1..maxLimit) {
        throw ContextRetrievalException(
            ContextRetrievalErrorCode.INVALID_REQUEST,
            "limit must be in 1..$maxLimit",
        )
    }
}

internal fun configuration(detail: String): ContextRetrievalException =
    ContextRetrievalException(ContextRetrievalErrorCode.CONFIGURATION, detail)
