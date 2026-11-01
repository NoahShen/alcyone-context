package com.github.noahshen.alcyone.context.memory.api

/** 存储状态快照（按需查询，不可伪造零值）。 */
data class StorageStatus(
    val databasePath: String,
    val databaseInitialized: Boolean = false,
)
