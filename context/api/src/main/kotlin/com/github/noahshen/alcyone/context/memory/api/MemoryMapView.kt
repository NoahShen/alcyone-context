package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant

/** 最近一份经过严格解析的 Memory Map Markdown 快照。 */
data class MemoryMapView(
    val content: String,
    val generatedAt: Instant,
    val sourceUpdatedAt: Instant?,
) {
    init {
        require(content.isNotBlank()) { "content must not be blank" }
    }
}
