package com.github.noahshen.alcyone.context.memory.runtime.retrieval

/** Rerank Parser 的独立类型入口；严格规则与 Scanner Parser 共用同一读取器。 */
internal class JsonMemorySearchRerankResponseParser {
    fun parse(content: String): List<String> =
        MemorySearchJsonObjectReader.readStringArray(
            content = content,
            field = MEMORY_IDS,
            errorMessage = INVALID_RESPONSE_MESSAGE,
        )

    private companion object {
        const val MEMORY_IDS = "memory_ids"
        const val INVALID_RESPONSE_MESSAGE = "invalid memory search rerank response"
    }
}
