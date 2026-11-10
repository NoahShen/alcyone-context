package com.github.noahshen.alcyone.context.memory.api

/** 一次成功 Dreaming run 的公开聚合结果；partial failure 只通过具名异常携带。 */
data class DreamingResult(
    val startCheckpoint: Long,
    val endCheckpoint: Long,
    val scannedTurns: Int,
    val discoveredTopics: Int,
    val generatedMemories: Int,
    val completed: Boolean,
) {
    init {
        require(startCheckpoint >= 0L) { "startCheckpoint must not be negative" }
        require(endCheckpoint >= 0L) { "endCheckpoint must not be negative" }
        require(startCheckpoint <= endCheckpoint) { "startCheckpoint must not exceed endCheckpoint" }
        require(scannedTurns >= 0) { "scannedTurns must not be negative" }
        require(discoveredTopics >= 0) { "discoveredTopics must not be negative" }
        require(generatedMemories >= 0) { "generatedMemories must not be negative" }
    }
}
