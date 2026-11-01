package com.github.noahshen.alcyone.context.memory.core.repository

/**
 * Dreaming Checkpoint 普通 suspend 读取 Repository Port。
 * 读取单行水位（固定 id=1）。
 */
interface DreamingCheckpointRepository {
    suspend fun readCheckpoint(): Long
}
