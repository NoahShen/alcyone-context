package com.github.noahshen.alcyone.context.memory.api

/** Bootstrap 返回的 Memory Map 水位状态。 */
enum class MapStatus {
    CURRENT,
    STALE,
    MISSING,
    UNKNOWN,
}
