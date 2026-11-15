package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager

data class OfflineBackupResult(
    val schemaVersion: Long,
    val bytes: Long,
    val turnCount: Long,
)

/** Offline, no-overwrite snapshot using the same Xerial backup primitive as Runtime Backup. */
object ContextOfflineBackup {
    internal var failureAfterClaimForTesting: (() -> Unit)? = null

    fun snapshot(
        source: Path,
        target: Path,
    ): OfflineBackupResult {
        val normalizedSource = source.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalizedSource, NOFOLLOW_LINKS) || safeSize(normalizedSource) == 0L) {
            throw failure(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, "DATABASE_NOT_INITIALIZED")
        }
        val sourceFacts = readFacts(normalizedSource)
        val parent = normalizedTarget.parent ?: throw persistenceUnavailable("BACKUP_TARGET_UNAVAILABLE")
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || hasTargetOrSidecar(normalizedTarget)) {
            throw persistenceUnavailable("BACKUP_TARGET_UNAVAILABLE")
        }
        val claimKey = claim(normalizedTarget)
        var succeeded = false
        try {
            failureAfterClaimForTesting?.invoke()
            DriverManager.getConnection(SqliteUrls.openReadUrl(normalizedSource)).use { sourceConnection ->
                // Read-only source: no journal/checkpoint or schema PRAGMA writes.
                val db = sourceConnection.unwrap(org.sqlite.SQLiteConnection::class.java).getDatabase()
                db.backup("main", normalizedTarget.toString(), null, 1, 0, -1)
            }
            val targetFacts = readFacts(normalizedTarget)
            if (targetFacts != sourceFacts) throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
            checkpointTarget(normalizedTarget)
            removeTargetSidecars(normalizedTarget)
            val finalFacts = readFacts(normalizedTarget)
            if (finalFacts != sourceFacts) throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
            requireQuickCheck(normalizedTarget)
            removeTargetSidecars(normalizedTarget)
            succeeded = true
            return OfflineBackupResult(sourceFacts.version, Files.size(normalizedTarget), sourceFacts.turnCount)
        } catch (failure: Throwable) {
            throw when (failure) {
                is ContextPersistenceException -> failure
                else -> persistenceUnavailable("BACKUP_FAILED")
            }
        } finally {
            if (!succeeded) removeOwnProduct(normalizedTarget, claimKey)
        }
    }

    private data class Facts(
        val version: Long,
        val turnCount: Long,
    )

    private fun readFacts(path: Path): Facts =
        try {
            DriverManager.getConnection(SqliteUrls.openReadUrl(path)).use { connection ->
                connection.createStatement().use { statement ->
                    val version =
                        statement.executeQuery("PRAGMA user_version").use { rows ->
                            if (!rows.next()) throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
                            rows.getLong(1)
                        }
                    if (version != ContextDatabaseInitializer.SCHEMA_VERSION) {
                        throw ContextPersistenceException(
                            ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH,
                            "SCHEMA_VERSION_MISMATCH",
                        )
                    }
                    val missing = ContextDatabaseFactory.missingObjects(connection)
                    if (missing.isNotEmpty()) throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
                    val turns =
                        statement.executeQuery("SELECT COUNT(*) FROM conversation_turn").use { rows ->
                            if (!rows.next()) throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
                            rows.getLong(1)
                        }
                    Facts(version, turns)
                }
            }
        } catch (failure: Throwable) {
            if (failure is ContextPersistenceException) throw failure
            throw persistenceUnavailable("BACKUP_DATABASE_INVALID")
        }

    private fun requireQuickCheck(path: Path) {
        try {
            DriverManager.getConnection(SqliteUrls.openReadUrl(path)).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { rows ->
                        if (!rows.next() || rows.getString(1) != "ok" || rows.next()) {
                            throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            if (failure is ContextPersistenceException) throw failure
            throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
        }
    }

    private fun checkpointTarget(path: Path) {
        try {
            DriverManager.getConnection(SqliteUrls.openReadWriteUrl(path)).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
                        if (!rows.next() || rows.getInt(1) != 0) throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
                    }
                }
            }
        } catch (failure: Throwable) {
            if (failure is ContextPersistenceException) throw failure
            throw persistenceUnavailable("BACKUP_VERIFICATION_FAILED")
        }
    }

    private fun removeTargetSidecars(target: Path) {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            Files.deleteIfExists(target.resolveSibling(target.fileName.toString() + suffix))
        }
    }

    private fun hasTargetOrSidecar(target: Path): Boolean =
        listOf("", "-wal", "-shm", "-journal").any { suffix ->
            Files.exists(target.resolveSibling(target.fileName.toString() + suffix), NOFOLLOW_LINKS)
        }

    private fun claim(target: Path): Any? =
        try {
            FileChannel.open(target, CREATE_NEW, WRITE).use {}
            Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        } catch (failure: Throwable) {
            throw persistenceUnavailable("BACKUP_TARGET_UNAVAILABLE")
        }

    private fun removeOwnProduct(
        target: Path,
        claimKey: Any?,
    ) {
        if (claimKey == null) return
        try {
            val current = Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            if (current == claimKey) Files.deleteIfExists(target)
        } catch (_: NoSuchFileException) {
        } catch (_: Throwable) {
            // Identity cannot be proven: preserve the file rather than delete another owner's product.
        }
    }

    private fun safeSize(path: Path): Long =
        try {
            Files.size(path)
        } catch (_: Throwable) {
            0L
        }

    private fun failure(
        code: ContextPersistenceErrorCode,
        message: String,
    ) = ContextPersistenceException(code, message)
}
