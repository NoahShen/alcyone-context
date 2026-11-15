package com.github.noahshen.alcyone.context.memory.runtime.backup

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.nio.file.Path
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

data class BackupConfig(
    val cron: String? = null,
    val timezone: String = DreamingConfig.DEFAULT_TIMEZONE,
    val uri: VfsUri = VfsUri.parse(DEFAULT_URI),
    val tempDirectory: Path? = null,
    val maxBytes: Long = 1024L * 1024 * 1024,
    /** Runtime-owned effective path. A null YAML value is resolved by ContextConfigLoader. */
    val effectiveTempDirectory: Path =
        tempDirectory
            ?: Path.of(System.getProperty("java.io.tmpdir")).resolve("alcyone-backup-${java.lang.ProcessHandle.current().pid()}"),
) {
    init {
        if (maxBytes <= 0) throw invalidBackup("context.backup.maxBytes must be positive")
        try {
            DreamingConfig(cron, timezone)
        } catch (
            failure: IllegalArgumentException,
        ) {
            throw invalidBackup(failure.message ?: "invalid backup cron")
        }
    }

    fun nextUri(clock: Clock): VfsUri {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(clock.instant().atZone(java.time.ZoneId.of(timezone)))
        val fileName = "alcyone-$stamp-${UUID.randomUUID().toString().take(8)}.sqlite"
        return VfsUri.of(VfsPath.of(uri.path.segments + fileName))
    }

    companion object {
        const val DEFAULT_URI = "alcyone://memory/backups/"
    }
}

internal fun invalidBackup(message: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, message)
