package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException

/** `context.retrieval` 的不可变配置快照。 */
data class RetrievalConfig(
    val search: SearchConfig = SearchConfig(),
)

/** Search 上限配置；API 默认 limit=10 不由此值改变。 */
data class SearchConfig(
    val maxLimit: Int = MemorySearchDefaults.MAX_LIMIT,
) {
    init {
        if (maxLimit !in 1..MemorySearchDefaults.MAX_LIMIT_BOUND) {
            throw ContextConfigException(
                ContextConfigErrorCode.INVALID_CONFIG,
                "context.retrieval.search.maxLimit must be in 1..${MemorySearchDefaults.MAX_LIMIT_BOUND}",
            )
        }
    }
}
