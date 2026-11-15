package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.NodeType
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
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteRecoveryJournal
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

private const val SAFE_REPLACE_TARGET_URI = "alcyone://resources/published.bin"
private const val SAFE_REPLACE_BEFORE_MOVE = "before-move"
private const val SAFE_REPLACE_AFTER_MOVE = "after-move"
private val SAFE_REPLACE_OLD_PAYLOAD = "previous-complete-version".toByteArray()
private val SAFE_REPLACE_NEW_PAYLOAD = ByteArray(64 * 1024) { index -> (index * 17 + 29).toByte() }

class SafeReplaceRecoveryProcessTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `halt before atomic replacement leaves REQUESTED with the registered old file`() {
        assertCrashFacts(SAFE_REPLACE_BEFORE_MOVE)
    }

    @Test
    @Timeout(60)
    fun `halt after atomic replacement leaves REQUESTED with the complete new file`() {
        assertCrashFacts(SAFE_REPLACE_AFTER_MOVE)
    }

    private fun assertCrashFacts(checkpoint: String) {
        val stateFile = tempDir.resolve("state-$checkpoint.db")
        val diskRoot = Files.createDirectory(tempDir.resolve("disk-$checkpoint"))

        val output = runSafeReplaceChild(stateFile, diskRoot, checkpoint)
        assertEquals(0, output.exitCode, "child process failed at $checkpoint:\n${output.text}")

        VfsStateDatabase.file(stateFile).use { state ->
            val journal = SqliteRecoveryJournal(state)
            val pending = runBlocking { journal.listPending() }
            assertEquals(1, pending.size, "the crash must leave exactly one pending journal row")
            val operation = pending.single()
            assertEquals(RecoveryOperationKind.SAFE_REPLACE, operation.kind)
            assertEquals(RecoveryPhase.STORAGE_REPLACE_REQUESTED, operation.phase)
            assertEquals(0L, operation.generation)
            assertEquals(VfsUri.parse(SAFE_REPLACE_TARGET_URI), operation.targetUri)
            assertNull(operation.sourceUri)
            assertNull(operation.sourceStorageKey)
            assertEquals("local", operation.targetStorageKey)
            assertNotNull(operation.nodeId, "the interrupted replace must reference the Node committed by the first replace")

            assertEquals(1, rowCount(stateFile, "node"), "the first safeReplace must have committed one Node")
            val recordedNode = runBlocking { SqliteNodeRepository(state).findByPath(VfsPath.parse("/resources/published.bin")) }
            assertNotNull(recordedNode, "the first safeReplace must register the target Node")
            recordedNode!!
            assertEquals(recordedNode.id, operation.nodeId)
            assertEquals(listOf("FILE_CREATED"), eventTypes(stateFile), "the interrupted replace must not commit FILE_WRITTEN")

            assertTargetFacts(diskRoot, checkpoint)
            val before =
                RecoveryTableDigests(
                    node = tableDigest(stateFile, "node"),
                    event = tableDigest(stateFile, "event"),
                    journal = tableDigest(stateFile, "recovery_operation"),
                )
            val fileBefore = fileDigest(diskRoot.resolve("published.bin"))

            runBlocking {
                val storage = LocalFsStorage.create(diskRoot)
                try {
                    val reconciler = buildSafeReplaceReconciler(state, journal, storage)
                    val report = reconciler.reconcile(operation.operationId)
                    assertNotNull(report, "the pending operation must be observable")
                    report!!
                    assertEquals(VfsErrorCode.RECOVERY_REQUIRED, report.verdict)
                    assertEquals(operation, report.operation)
                    assertEquals(RecoveryPresence.PRESENT, report.target.presence)
                    assertEquals(NodeType.FILE, report.target.type)
                    assertEquals(
                        if (checkpoint ==
                            SAFE_REPLACE_BEFORE_MOVE
                        ) {
                            SAFE_REPLACE_OLD_PAYLOAD.size.toLong()
                        } else {
                            SAFE_REPLACE_NEW_PAYLOAD.size.toLong()
                        },
                        report.target.sizeBytes,
                    )
                    assertEquals(recordedNode, report.targetNode.record, "reconciliation must report the unchanged committed Node")
                } finally {
                    storage.close()
                }
            }

            val after =
                RecoveryTableDigests(
                    node = tableDigest(stateFile, "node"),
                    event = tableDigest(stateFile, "event"),
                    journal = tableDigest(stateFile, "recovery_operation"),
                )
            assertEquals(before, after, "reconciliation must not change Node, Event, or journal rows")
            assertEquals(fileBefore, fileDigest(diskRoot.resolve("published.bin")), "reconciliation must not change the file")
        }
    }

    private fun buildSafeReplaceReconciler(
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
        assertTrue(Files.isRegularFile(target), "safeReplace target must exist at $checkpoint")
        when (checkpoint) {
            SAFE_REPLACE_BEFORE_MOVE -> assertArrayEquals(SAFE_REPLACE_OLD_PAYLOAD, Files.readAllBytes(target))
            SAFE_REPLACE_AFTER_MOVE -> assertArrayEquals(SAFE_REPLACE_NEW_PAYLOAD, Files.readAllBytes(target))
            else -> error("unknown checkpoint $checkpoint")
        }
    }

    private fun runSafeReplaceChild(
        stateFile: Path,
        diskRoot: Path,
        checkpoint: String,
    ): SafeReplaceChildResult {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val mainClass = "com.github.noahshen.alcyone.context.vfs.integration.vfs.SafeReplaceRecoveryProcessTestKt"
        val process =
            ProcessBuilder(
                java,
                "-cp",
                safeReplaceChildClasspath(),
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
        return SafeReplaceChildResult(process.exitValue(), text)
    }

    private fun safeReplaceChildClasspath(): String {
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
            SafeReplaceRecoveryProcessTest::class.java,
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

    private data class SafeReplaceChildResult(
        val exitCode: Int,
        val text: String,
    )

    private data class RecoveryTableDigests(
        val node: String,
        val event: String,
        val journal: String,
    )
}

private class SafeReplaceHaltingStorage(
    private val delegate: Storage,
    private val checkpoint: String,
    private val halt: () -> Nothing,
) : Storage {
    private var safeReplaceCalls = 0

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
    ): StorageAttributes {
        safeReplaceCalls += 1
        val crashCall = safeReplaceCalls == 2
        if (crashCall && checkpoint == SAFE_REPLACE_BEFORE_MOVE) halt()
        val attributes = delegate.safeReplace(path, content)
        if (crashCall && checkpoint == SAFE_REPLACE_AFTER_MOVE) halt()
        return attributes
    }

    override suspend fun publishNew(
        path: StoragePath,
        input: InputStream,
        expectedSizeBytes: Long,
        maxBytes: Long,
        tempName: String,
    ): StorageAttributes = delegate.publishNew(path, input, expectedSizeBytes, maxBytes, tempName)

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
            SafeReplaceHaltingStorage(local, checkpoint) {
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
                pipeline = EventPipeline(boundary, SqliteUnitOfWork(state, journal), notifier),
                limits = VfsLimits(),
                recoveryJournal = journal,
            )

        runBlocking {
            vfs.safeReplace(VfsUri.parse(SAFE_REPLACE_TARGET_URI), SAFE_REPLACE_OLD_PAYLOAD)
            vfs.safeReplace(VfsUri.parse(SAFE_REPLACE_TARGET_URI), SAFE_REPLACE_NEW_PAYLOAD)
        }
        error("safeReplace returned without reaching checkpoint $checkpoint")
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

private fun eventTypes(database: Path): List<String> =
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT event_type FROM event ORDER BY rowid").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
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
