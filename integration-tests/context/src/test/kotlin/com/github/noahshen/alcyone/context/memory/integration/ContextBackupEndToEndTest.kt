package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.ContextBackupStatus
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.backup.BackupConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** CT26-B2: real online backup through the public Context entry point. */
class ContextBackupEndToEndTest {
    @TempDir lateinit var temp: Path

    @Test
    @Timeout(60)
    fun `public vfs and backup publish an intact committed SQLite snapshot`() =
        runBlocking {
            val database = temp.resolve("context.sqlite")
            val root = Files.createDirectory(temp.resolve("backup"))
            val config =
                VfsRuntimeConfig(
                    temp.resolve("vfs.sqlite"),
                    setOf("memory"),
                    listOf(MountConfig(VfsPath.parse("/memory/backups"), "backups", root)),
                )
            val temporary = Files.createDirectory(temp.resolve("temporary"))
            val contextConfig =
                ContextConfig(
                    databasePath = database,
                    lifecycleShutdownTimeout = 30.seconds,
                    vfsConfig = config,
                    backup = BackupConfig(tempDirectory = temporary),
                )
            AlcyoneContextFactory.initializeDatabase(contextConfig)
            ContextDatabaseFactory.open(database).use { executor ->
                executor.updateRaw(
                    "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) VALUES ('raw-backup', 'committed memory', 'EXTRACTION', '[]', '2026-10-28T00:00:00Z')",
                )
            }
            val context = AlcyoneContextFactory.create(contextConfig, config)
            try {
                context.start()
                val result = context.backupNow()
                val path = root.resolve(result.uri.substringAfter("/backups/"))
                assertTrue(Files.isRegularFile(path))
                assertEquals(result.sizeBytes, Files.size(path))
                DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro").use { connection ->
                    connection.createStatement().use { statement ->
                        assertEquals(
                            ContextDatabaseInitializer.SCHEMA_VERSION.toInt(),
                            statement.executeQuery("PRAGMA user_version").use {
                                it.next()
                                it.getInt(1)
                            },
                        )
                        statement.executeQuery("SELECT content FROM raw_memory WHERE id = 'raw-backup'").use {
                            assertTrue(it.next())
                            assertEquals("committed memory", it.getString(1))
                            assertTrue(!it.next())
                        }
                        for (pragma in listOf("quick_check", "integrity_check")) {
                            statement.executeQuery("PRAGMA $pragma").use { assertEquals("ok", it.apply { next() }.getString(1)) }
                        }
                    }
                }
                assertEquals(ContextBackupStatus(WorkerState.IDLE, result.uri, result.completedAt), context.backupStatus())
                assertEquals(result.uri, assertNotNull(context.getStatus().backup).lastUri)
                val leftovers = Files.list(temporary).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
                assertEquals(emptyList<String>(), leftovers)
            } finally {
                context.closeAsync()
            }
        }

    @Test
    @Timeout(60)
    fun `different writers produce independent complete published databases`() =
        runBlocking {
            val database = temp.resolve("context.sqlite")
            val root = Files.createDirectory(temp.resolve("backup"))
            val config =
                VfsRuntimeConfig(
                    temp.resolve("vfs.sqlite"),
                    setOf("memory"),
                    listOf(MountConfig(VfsPath.parse("/memory/backups"), "backups", root)),
                )
            val contextConfig =
                ContextConfig(
                    databasePath = database,
                    lifecycleShutdownTimeout = 30.seconds,
                    vfsConfig = config,
                    backup = BackupConfig(tempDirectory = temp.resolve("temporary")),
                )
            AlcyoneContextFactory.initializeDatabase(contextConfig)
            ContextDatabaseFactory.open(database).use { executor ->
                executor.updateRaw(
                    "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) VALUES ('raw-backup', 'one and two', 'EXTRACTION', '[]', '2026-10-28T00:00:00Z')",
                )
            }
            val context = AlcyoneContextFactory.create(contextConfig, config)
            try {
                context.start()
                val first = context.backupNow()
                val second = context.backupNow()
                assertTrue(first.uri != second.uri)
                assertEquals(2, Files.list(root).use { it.count() })
            } finally {
                context.closeAsync()
            }
        }
}
