package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.PublishOptions
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.DefaultVfs
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.recovery.OperationReconciler
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryOperationKind
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryPhase
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryPresence
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageContent
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageEntry
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageStream
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteRecoveryJournal
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit

private const val TARGET_URI = "alcyone://resources/published.bin"
private const val CHECKPOINT_BEFORE_WRITE = "before-write"
private const val CHECKPOINT_AFTER_WRITE = "after-write"
private val PAYLOAD = ByteArray(64 * 1024) { index -> (index * 31 + 17).toByte() }

class PublishNewRecoveryProcessTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `halt after journal begin and before external publish leaves REQUESTED with no target`() {
        assertCrashFacts(CHECKPOINT_BEFORE_WRITE)
    }

    @Test
    @Timeout(60)
    fun `halt after external publish and before close leaves REQUESTED with the complete file`() {
        assertCrashFacts(CHECKPOINT_AFTER_WRITE)
    }

    private fun assertCrashFacts(checkpoint: String) {
        val stateFile = tempDir.resolve("state-$checkpoint.db")
        val diskRoot = Files.createDirectory(tempDir.resolve("disk-$checkpoint"))

        val output = runChildProcess(stateFile, diskRoot, checkpoint)
        assertEquals(0, output.exitCode, "child process failed at $checkpoint:\n${output.text}")

        VfsStateDatabase.file(stateFile).use { state ->
            val journal = SqliteRecoveryJournal(state)
            val pending = runBlocking { journal.listPending() }
            assertEquals(1, pending.size, "the crash must leave exactly one pending journal row")
            val operation = pending.single()
            assertEquals(RecoveryOperationKind.PUBLISH_NEW, operation.kind)
            assertEquals(RecoveryPhase.PUBLISH_REQUESTED, operation.phase)
            assertEquals(0L, operation.generation)
            assertEquals(VfsUri.parse(TARGET_URI), operation.targetUri)

            assertTargetFacts(diskRoot, checkpoint)
            assertEquals(0, rowCount(stateFile, "node"), "the interrupted publish must not commit a Node")
            assertEquals(0, rowCount(stateFile, "event"), "the interrupted publish must not commit an Event")

            val before =
                DatabaseDigests(
                    node = tableDigest(stateFile, "node"),
                    event = tableDigest(stateFile, "event"),
                    journal = tableDigest(stateFile, "recovery_operation"),
                )
            val fileBefore = fileDigest(diskRoot.resolve("published.bin"))

            runBlocking {
                val storage = LocalFsStorage.create(diskRoot)
                try {
                    val reconciler = buildReconciler(state, journal, storage)
                    val report = reconciler.reconcile(operation.operationId)
                    assertTrue(report != null, "the pending operation must be observable")
                    report!!
                    assertEquals(VfsErrorCode.RECOVERY_REQUIRED, report.verdict)
                    assertEquals(operation, report.operation)
                    when (checkpoint) {
                        CHECKPOINT_BEFORE_WRITE -> assertEquals(RecoveryPresence.ABSENT, report.target.presence)
                        CHECKPOINT_AFTER_WRITE -> {
                            assertEquals(RecoveryPresence.PRESENT, report.target.presence)
                            assertEquals(NodeType.FILE, report.target.type)
                            assertEquals(PAYLOAD.size.toLong(), report.target.sizeBytes)
                        }
                        else -> error("unknown checkpoint $checkpoint")
                    }
                    assertNull(report.targetNode.record, "reconciliation cannot invent a Node")
                } finally {
                    storage.close()
                }
            }

            val after =
                DatabaseDigests(
                    node = tableDigest(stateFile, "node"),
                    event = tableDigest(stateFile, "event"),
                    journal = tableDigest(stateFile, "recovery_operation"),
                )
            assertEquals(before, after, "reconciliation must not change Node, Event, or journal rows")
            assertEquals(fileBefore, fileDigest(diskRoot.resolve("published.bin")), "reconciliation must not change the file")
        }
    }

    private fun buildReconciler(
        state: VfsStateDatabase,
        journal: SqliteRecoveryJournal,
        storage: LocalFsStorage,
    ): OperationReconciler {
        val router = MountRouter.of(setOf("resources"), listOf(MountRecord(VfsPath.parse("/resources"), "local")))
        return OperationReconciler(
            journal = journal,
            nodes = SqliteNodeRepository(state),
            router = router,
            storages = { key -> storage.takeIf { key == "local" } },
            boundary = StateBoundary(),
        )
    }

    private fun assertTargetFacts(
        diskRoot: Path,
        checkpoint: String,
    ) {
        val target = diskRoot.resolve("published.bin")
        when (checkpoint) {
            CHECKPOINT_BEFORE_WRITE -> assertFalse(Files.exists(target), "the write must not have started")
            CHECKPOINT_AFTER_WRITE -> {
                assertTrue(Files.isRegularFile(target), "publishNew must have installed the target")
                assertArrayEquals(PAYLOAD, Files.readAllBytes(target), "the installed target must be complete")
            }
            else -> error("unknown checkpoint $checkpoint")
        }
    }

    private fun runChildProcess(
        stateFile: Path,
        diskRoot: Path,
        checkpoint: String,
    ): ChildResult {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val mainClass = "com.github.noahshen.alcyone.context.vfs.integration.vfs.PublishNewRecoveryProcessTestKt"
        val process =
            ProcessBuilder(
                java,
                "-cp",
                childClasspath(),
                mainClass,
                stateFile.toString(),
                diskRoot.toString(),
                checkpoint,
            ).redirectErrorStream(true).start()

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("child process timed out at $checkpoint")
        }
        val text = process.inputStream.bufferedReader().use { it.readText() }
        return ChildResult(process.exitValue(), text)
    }

    private fun childClasspath(): String {
        val locations = LinkedHashSet<String>()

        fun addPath(path: String) {
            if (path.isNotBlank()) locations += File(path).absolutePath
        }

        System
            .getProperty("java.class.path")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .forEach(::addPath)

        var loader: ClassLoader? = Thread.currentThread().contextClassLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                loader.urLs.forEach { url -> addPath(File(url.toURI()).absolutePath) }
            }
            loader = loader.parent
        }

        listOf(
            PublishNewRecoveryProcessTest::class.java,
            DefaultVfs::class.java,
            VfsStateDatabase::class.java,
            LocalFsStorage::class.java,
        ).forEach { type ->
            type.protectionDomain.codeSource
                ?.location
                ?.let { url -> addPath(File(url.toURI()).absolutePath) }
        }

        check(locations.isNotEmpty()) { "test runtime classpath is unavailable" }
        return locations.joinToString(File.pathSeparator)
    }

    private data class ChildResult(
        val exitCode: Int,
        val text: String,
    )

    private data class DatabaseDigests(
        val node: String,
        val event: String,
        val journal: String,
    )
}

private class HaltingStorage(
    private val delegate: Storage,
    private val checkpoint: String,
    private val halt: () -> Nothing,
) : Storage {
    override suspend fun read(
        path: StoragePath,
        maxBytes: Long,
    ): StorageContent = delegate.read(path, maxBytes)

    override suspend fun readStream(
        path: StoragePath,
        maxBytes: Long?,
    ): StorageStream = delegate.readStream(path, maxBytes)

    override suspend fun stat(path: StoragePath): StorageAttributes = delegate.stat(path)

    override suspend fun list(path: StoragePath): List<StorageEntry> = delegate.list(path)

    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: StorageWriteMode,
    ): StorageAttributes = delegate.write(path, content, mode)

    override suspend fun safeReplace(
        path: StoragePath,
        content: ByteArray,
    ): StorageAttributes = delegate.safeReplace(path, content)

    override suspend fun publishNew(
        path: StoragePath,
        input: InputStream,
        expectedSizeBytes: Long,
        maxBytes: Long,
        tempName: String,
    ): StorageAttributes {
        if (checkpoint == CHECKPOINT_BEFORE_WRITE) halt()
        val attributes = delegate.publishNew(path, input, expectedSizeBytes, maxBytes, tempName)
        if (checkpoint == CHECKPOINT_AFTER_WRITE) halt()
        return attributes
    }

    override suspend fun createDirectory(path: StoragePath) = delegate.createDirectory(path)

    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes = delegate.move(source, target)

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) = delegate.delete(path, recursive)

    override fun capabilities() = delegate.capabilities()
}

fun main(args: Array<String>) {
    val (statePath, diskPath, checkpoint) = args
    val state = VfsStateDatabase.file(Path.of(statePath))
    val local =
        runBlocking {
            LocalFsStorage.create(Path.of(diskPath))
        }
    val notifier = AsyncEventNotifier()
    try {
        val haltingStorage =
            HaltingStorage(local, checkpoint) {
                Runtime.getRuntime().halt(0)
                error("Runtime.halt(0) returned")
            }
        val storages = mapOf("local" to haltingStorage)
        val router = MountRouter.of(setOf("resources"), listOf(MountRecord(VfsPath.parse("/resources"), "local")))
        val boundary = StateBoundary()
        val nodes = SqliteNodeRepository(state)
        val metadata = SqliteMetadataRepository(state)
        val journal = SqliteRecoveryJournal(state)
        val vfs =
            DefaultVfs(
                router = router,
                capabilities = CapabilitySnapshot.of(storages.mapValues { it.value.capabilities() }),
                registry = NodeRegistry(router, nodes, { key -> storages[key] }, boundary),
                nodes = nodes,
                metadata = metadata,
                storages = { key -> storages[key] },
                boundary = boundary,
                pipeline = EventPipeline(boundary, SqliteUnitOfWork(state) as UnitOfWork, notifier),
                limits = VfsLimits(),
                recoveryJournal = journal,
            )

        runBlocking {
            vfs.publishNew(
                VfsUri.parse(TARGET_URI),
                PAYLOAD.inputStream(),
                PublishOptions(maxBytes = PAYLOAD.size.toLong(), expectedSizeBytes = PAYLOAD.size.toLong()),
            )
        }
        error("publishNew returned without reaching checkpoint $checkpoint")
    } finally {
        notifier.close()
        local.close()
        state.close()
    }
}

private fun tableDigest(
    database: Path,
    table: String,
): String =
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $table ORDER BY rowid").use { rows ->
                val digest = MessageDigest.getInstance("SHA-256")
                val metadata = rows.metaData
                while (rows.next()) {
                    for (column in 1..metadata.columnCount) {
                        digest.update(metadata.getColumnName(column).toByteArray())
                        digest.update(0)
                        val value = rows.getString(column)
                        if (value == null) {
                            digest.update(byteArrayOf(-1))
                        } else {
                            digest.update(value.toByteArray())
                        }
                        digest.update(0)
                    }
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
        }
    }

private fun rowCount(
    database: Path,
    table: String,
): Int =
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

private fun fileDigest(path: Path): String {
    if (!Files.exists(path)) return "absent"
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
