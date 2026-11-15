package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextOfflineBackupTest {
    @TempDir lateinit var directory: Path

    @Test fun `read-only backup has exactly one quick_check row and identical committed facts`() {
        val source = directory.resolve("source with spaces.sqlite")
        initializeWithTurn(source)
        val before = sha(source)
        val result = ContextOfflineBackup.snapshot(source, directory.resolve("backup.sqlite"))
        assertEquals(1, result.schemaVersion)
        assertEquals(1, result.turnCount)
        assertEquals(before, sha(source))
        DriverManager.getConnection(SqliteUrls.openReadUrl(directory.resolve("backup.sqlite"))).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ok", rows.getString(1))
                    assertFalse(rows.next())
                }
                statement.executeQuery("SELECT COUNT(*) FROM conversation_turn").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getLong(1))
                }
            }
        }
    }

    @Test fun `missing zero corrupt and wrong versions reject without changing source`() {
        assertFailsWith<ContextPersistenceException> {
            ContextOfflineBackup.snapshot(directory.resolve("missing.sqlite"), directory.resolve("a.sqlite"))
        }.also { assertEquals(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, it.errorCode) }
        val zero = directory.resolve("zero.sqlite")
        Files.createFile(zero)
        assertFailsWith<ContextPersistenceException> { ContextOfflineBackup.snapshot(zero, directory.resolve("b.sqlite")) }
        val corrupt = directory.resolve("corrupt.sqlite")
        Files.writeString(corrupt, "not a sqlite database")
        assertFailsWith<ContextPersistenceException> { ContextOfflineBackup.snapshot(corrupt, directory.resolve("c.sqlite")) }
        assertFalse(Files.exists(directory.resolve("c.sqlite")))
        for (version in listOf(0, 2)) {
            val source = directory.resolve("v$version.sqlite")
            ContextDatabaseInitializer.initialize(source)
            DriverManager.getConnection(SqliteUrls.openReadWriteUrl(source)).use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
            }
            val before = sha(source)
            val error =
                assertFailsWith<ContextPersistenceException> {
                    ContextOfflineBackup.snapshot(
                        source,
                        directory.resolve("v$version-copy.sqlite"),
                    )
                }
            assertEquals(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, error.errorCode)
            assertEquals(before, sha(source))
        }
    }

    @Test fun `target must be claimed exclusively and missing parent is rejected`() {
        val source = directory.resolve("source.sqlite")
        initializeWithTurn(source)
        val target = directory.resolve("backup.sqlite")
        Files.writeString(target, "existing")
        assertFailsWith<ContextPersistenceException> { ContextOfflineBackup.snapshot(source, target) }
        assertEquals("existing", Files.readString(target))
        assertFailsWith<ContextPersistenceException> { ContextOfflineBackup.snapshot(source, directory.resolve("absent/copy.sqlite")) }
    }

    @Test
    fun `preexisting sidecars are rejected byte for byte and missing schema objects are refused`() {
        val source = directory.resolve("source.sqlite")
        initializeWithTurn(source)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val target = directory.resolve("target$suffix".replace("-wal.sqlite", ".sqlite"))
            val sidecar = target.resolveSibling(target.fileName.toString() + suffix)
            val bytes = byteArrayOf(1, 2, 3, 4)
            Files.write(sidecar, bytes)
            assertFailsWith<ContextPersistenceException> { ContextOfflineBackup.snapshot(source, target) }
            assertEquals(bytes.toList(), Files.readAllBytes(sidecar).toList())
            assertFalse(Files.exists(target))
        }
        for (missing in ContextDatabaseFactory.REQUIRED_TABLES + ContextDatabaseFactory.REQUIRED_INDEXES) {
            val invalid = directory.resolve("missing-$missing.sqlite")
            ContextDatabaseInitializer.initialize(invalid)
            DriverManager.getConnection(SqliteUrls.openReadWriteUrl(invalid)).use { connection ->
                connection.createStatement().use { statement ->
                    if (missing in ContextDatabaseFactory.REQUIRED_TABLES) {
                        statement.execute("PRAGMA foreign_keys=OFF")
                        statement.execute("DROP TABLE $missing")
                    } else {
                        statement.execute("DROP INDEX $missing")
                    }
                }
            }
            assertFailsWith<ContextPersistenceException> {
                ContextOfflineBackup.snapshot(invalid, directory.resolve("copy-$missing.sqlite"))
            }
        }
    }

    @Test
    fun `failure after target replacement preserves other owner and own claimed empty file is removed`() {
        val source = directory.resolve("source.sqlite")
        initializeWithTurn(source)
        val replaced = directory.resolve("replaced.sqlite")
        ContextOfflineBackup.failureAfterClaimForTesting = {
            Files.delete(replaced)
            Files.write(replaced, byteArrayOf(9, 8, 7))
            throw IllegalStateException("controlled failure")
        }
        try {
            assertFailsWith<ContextPersistenceException> { ContextOfflineBackup.snapshot(source, replaced) }
        } finally {
            ContextOfflineBackup.failureAfterClaimForTesting = null
        }
        assertEquals(listOf<Byte>(9, 8, 7), Files.readAllBytes(replaced).toList())
        val own = directory.resolve("own.sqlite")
        ContextOfflineBackup.failureAfterClaimForTesting = { throw IllegalStateException("controlled failure") }
        try {
            assertFailsWith<ContextPersistenceException> { ContextOfflineBackup.snapshot(source, own) }
        } finally {
            ContextOfflineBackup.failureAfterClaimForTesting = null
        }
        assertFalse(Files.exists(own))
    }

    @Test
    fun `concurrent claims have one success and all losers preserve the winner`() {
        val source = directory.resolve("source.sqlite")
        initializeWithTurn(source)
        val target = directory.resolve("backup.sqlite")
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        val outcomes =
            (1..8).map {
                pool.submit<Boolean> {
                    gate.await()
                    runCatching { ContextOfflineBackup.snapshot(source, target) }.isSuccess
                }
            }
        gate.countDown()
        assertEquals(1, outcomes.count { it.get(20, TimeUnit.SECONDS) })
        pool.shutdownNow()
        assertTrue(Files.size(target) > 0)
    }

    private fun initializeWithTurn(path: Path) {
        ContextDatabaseInitializer.initialize(path)
        ContextDatabaseFactory.open(path).use { executor ->
            kotlinx.coroutines.runBlocking {
                executor.updateRaw(
                    "INSERT INTO conversation_turn(runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, messages_json) VALUES ('r','a','s','t','1970-01-01T00:00:00Z','1970-01-01T00:00:00Z','[]')",
                )
            }
        }
    }

    private fun sha(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
            "%02x".format(it)
        }
}
