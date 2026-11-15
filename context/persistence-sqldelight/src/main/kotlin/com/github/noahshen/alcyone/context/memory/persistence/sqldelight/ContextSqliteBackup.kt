package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import org.sqlite.SQLiteConnection
import org.sqlite.core.DB
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Xerial Online Backup on the executor's exclusive writer thread. */
internal object ContextSqliteBackup {
    fun snapshot(
        connection: Connection,
        target: Path,
        expectedUserVersion: Int,
    ): Long {
        var claimed = false
        var succeeded = false
        try {
            val parent = target.parent ?: error("SQLite backup target must have a parent")
            require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "SQLite backup parent is not a directory" }
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "SQLite backup target already exists" }
            val db = connection.unwrap(SQLiteConnection::class.java).getDatabase()
            val sourceTurns = countTurns(connection)
            db.backup(
                "main",
                target.toAbsolutePath().toString(),
                DB.ProgressObserver { _, _ ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("SQLite backup interrupted")
                },
                1,
                0,
                -1,
            )
            claimed = true
            val actual = Files.size(target)
            require(actual > 0) { "SQLite Online Backup produced an empty target" }
            // Schema identity is checked by the runtime's independent read-only connection.
            require(expectedUserVersion > 0) { "source schema version must be positive" }
            java.sql.DriverManager.getConnection(SqliteUrls.openReadUrl(target)).use { copy ->
                copy.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rows ->
                        check(rows.next() && rows.getInt(1) == expectedUserVersion) {
                            "SQLite backup schema version does not match the source"
                        }
                    }
                    val missing = ContextDatabaseFactory.missingObjects(copy)
                    check(missing.isEmpty()) { "SQLite backup is missing required schema objects: $missing" }
                    val targetTurns = countTurns(copy)
                    check(targetTurns == sourceTurns) {
                        "SQLite backup conversation count mismatch: source=$sourceTurns target=$targetTurns"
                    }
                }
            }
            succeeded = true
            return actual
        } catch (failure: Throwable) {
            throw persistenceUnavailable("SQLite Online Backup failed: ${failure.javaClass.simpleName}")
        } finally {
            if (claimed && !succeeded) Files.deleteIfExists(target)
        }
    }

    private fun countTurns(connection: Connection): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM conversation_turn").use { rows ->
                check(rows.next()) { "SQLite backup cannot read conversation count" }
                rows.getLong(1)
            }
        }
}
