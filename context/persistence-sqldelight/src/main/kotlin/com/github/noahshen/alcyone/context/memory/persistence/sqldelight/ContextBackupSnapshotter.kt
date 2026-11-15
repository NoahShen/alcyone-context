package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import java.nio.file.Path

/** Runtime Backup 唯一可用的 SQLite 快照 Port；不暴露 JDBC、裸事务或备份恢复。 */
fun interface ContextBackupSnapshotter {
    suspend fun backupSnapshot(target: Path): Long
}
