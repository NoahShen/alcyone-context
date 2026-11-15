package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextBackupSnapshotTest {
    @TempDir lateinit var directory: Path

    @Test fun `native backup writes real schema data and checkpoints WAL`() {
        val source = directory.resolve("source.sqlite")
        ContextDatabaseInitializer.initialize(source)
        ContextDatabaseFactory.open(source).use { executor ->
            runBlocking {
                executor.updateRaw(
                    "INSERT INTO conversation_turn(runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, messages_json) VALUES ('runtime', 'agent', 'session', 'turn', '1970-01-01T00:00:00Z', '1970-01-01T00:00:00Z', '[]')",
                )
            }
            val target = directory.resolve("backup.sqlite")
            val size = runBlocking { executor.backupSnapshot(target) }
            assertEquals(Files.size(target), size)
            ContextDatabaseFactory.verifyBackupSchema(target)
            assertTrue(Files.size(target) > 0)
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                assertFalse(Files.exists(target.resolveSibling(target.fileName.toString() + suffix)))
            }
            assertTrue(size > 0)
            assertEquals("SQLite format 3\u0000", Files.readAllBytes(target).copyOf(16).toString(Charsets.ISO_8859_1))
            DriverManager.getConnection(SqliteUrls.openReadUrl(target)).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(ContextDatabaseInitializer.SCHEMA_VERSION.toInt(), rows.getInt(1))
                    }
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { rows ->
                        val names = generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                        assertTrue(
                            "conversation_turn" in names,
                            "snapshot tables: $names; url=${connection.unwrap(org.sqlite.SQLiteConnection::class.java).url}",
                        )
                    }
                    statement.executeQuery("PRAGMA quick_check").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("ok", rows.getString(1))
                        assertFalse(rows.next())
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM conversation_turn").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
            DriverManager.getConnection(SqliteUrls.openReadWriteUrl(source)).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(2), "WAL must be checkpointed before a portable online backup")
                        assertEquals(0, rows.getInt(3))
                    }
                }
            }
        }
    }
}
