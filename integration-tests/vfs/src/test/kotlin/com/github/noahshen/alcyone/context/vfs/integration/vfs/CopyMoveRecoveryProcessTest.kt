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
import org.junit.jupiter.api.Assertions.assertFalse
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

private const val COPY_MOVE_SOURCE_URI = "alcyone://resources/source/source.bin"
private const val COPY_MOVE_TARGET_URI = "alcyone://resources/target/moved.bin"
private const val COPY_MOVE_BEFORE_TARGET_WRITE = "before-target-write"
private const val COPY_MOVE_AFTER_TARGET_WRITE = "after-target-write-before-source-delete"
private const val COPY_MOVE_AFTER_SOURCE_DELETE = "after-source-delete"
private val COPY_MOVE_PAYLOAD = ByteArray(64 * 1024) { index -> (index * 37 + 11).toByte() }

class CopyMoveRecoveryProcessTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `halt before target write leaves TARGET_WRITE_REQUESTED with source present and target absent`() {
        assertCrashFacts(COPY_MOVE_BEFORE_TARGET_WRITE)
    }

    @Test
    @Timeout(60)
    fun `halt after target write leaves SOURCE_DELETE_REQUESTED with both files present`() {
        assertCrashFacts(COPY_MOVE_AFTER_TARGET_WRITE)
    }

    @Test
    @Timeout(60)
    fun `halt after source delete leaves SOURCE_DELETE_REQUESTED with only target present`() {
        assertCrashFacts(COPY_MOVE_AFTER_SOURCE_DELETE)
    }

    private fun assertCrashFacts(checkpoint: String) {
        val stateFile = tempDir.resolve("state-$checkpoint.db")
        val sourceRoot = Files.createDirectory(tempDir.resolve("source-$checkpoint"))
        val targetRoot = Files.createDirectory(tempDir.resolve("target-$checkpoint"))

        val output = runCopyMoveChild(stateFile, sourceRoot, targetRoot, checkpoint)
        assertEquals(0, output.exitCode, "child process failed at $checkpoint:\n${output.text}")

        VfsStateDatabase.file(stateFile).use { state ->
            val journal = SqliteRecoveryJournal(state)
            val pending = runBlocking { journal.listPending() }
            assertEquals(1, pending.size, "the crash must leave exactly one pending journal row")
            val operation = pending.single()
            assertEquals(RecoveryOperationKind.COPY_FILE_MOVE, operation.kind)
            assertEquals(expectedPhase(checkpoint), operation.phase)
            assertEquals(expectedGeneration(checkpoint), operation.generation)
            assertEquals(VfsUri.parse(COPY_MOVE_SOURCE_URI), operation.sourceUri)
            assertEquals(VfsUri.parse(COPY_MOVE_TARGET_URI), operation.targetUri)
            assertEquals("source", operation.sourceStorageKey)
            assertEquals("target", operation.targetStorageKey)
            assertNotNull(operation.nodeId, "the interrupted copy move must reference the registered source Node")

            assertEquals(1, rowCount(stateFile, "node"), "the source seed must commit exactly one Node")
            val sourceNode =
                runBlocking {
                    SqliteNodeRepository(state).findByPath(VfsPath.parse("/resources/source/source.bin"))
                }
            val targetNode =
                runBlocking {
                    SqliteNodeRepository(state).findByPath(VfsPath.parse("/resources/target/moved.bin"))
                }
            assertNotNull(sourceNode, "the interrupted copy move must leave the Node at its old path")
            assertNull(targetNode, "the interrupted copy move must not commit a new target path")
            assertEquals(sourceNode!!.id, operation.nodeId)
            assertEquals(listOf("FILE_CREATED"), eventTypes(stateFile), "the interrupted copy move must not commit FILE_MOVED")

            assertPhysicalFacts(sourceRoot, targetRoot, checkpoint)
            val before =
                RecoveryTableDigests(
                    node = tableDigest(stateFile, "node"),
                    event = tableDigest(stateFile, "event"),
                    journal = tableDigest(stateFile, "recovery_operation"),
                )
            val filesBefore = physicalDigests(sourceRoot, targetRoot)

            runBlocking {
                val source = LocalFsStorage.create(sourceRoot)
                val target = LocalFsStorage.create(targetRoot)
                try {
                    val reconciler = buildCopyMoveReconciler(state, journal, source, target)
                    val report = reconciler.reconcile(operation.operationId)
                    assertNotNull(report, "the pending operation must be observable")
                    report!!
                    assertEquals(VfsErrorCode.RECOVERY_REQUIRED, report.verdict)
                    assertEquals(operation, report.operation)

                    val sourceObservation = report.source
                    assertNotNull(sourceObservation, "copy move must report the source observation")
                    sourceObservation!!
                    assertEquals(expectedSourcePresence(checkpoint), sourceObservation.presence)
                    if (sourceObservation.presence == RecoveryPresence.PRESENT) {
                        assertEquals(NodeType.FILE, sourceObservation.type)
                        assertEquals(COPY_MOVE_PAYLOAD.size.toLong(), sourceObservation.sizeBytes)
                    }

                    assertEquals(expectedTargetPresence(checkpoint), report.target.presence)
                    if (report.target.presence == RecoveryPresence.PRESENT) {
                        assertEquals(NodeType.FILE, report.target.type)
                        assertEquals(COPY_MOVE_PAYLOAD.size.toLong(), report.target.sizeBytes)
                    }

                    assertEquals(sourceNode, report.sourceNode?.record, "the committed Node remains at the source path")
                    assertNull(report.targetNode.record, "the interrupted copy move must not expose a target Node mapping")
                } finally {
                    target.close()
                    source.close()
                }
            }

            val after =
                RecoveryTableDigests(
                    node = tableDigest(stateFile, "node"),
                    event = tableDigest(stateFile, "event"),
                    journal = tableDigest(stateFile, "recovery_operation"),
                )
            assertEquals(before, after, "reconciliation must not change Node, Event, or journal rows")
            assertEquals(
                filesBefore,
                physicalDigests(sourceRoot, targetRoot),
                "reconciliation must not change either physical path",
            )
        }
    }

    private fun buildCopyMoveReconciler(
        state: VfsStateDatabase,
        journal: SqliteRecoveryJournal,
        source: LocalFsStorage,
        target: LocalFsStorage,
    ): OperationReconciler {
        val router =
            MountRouter.of(
                setOf("resources"),
                listOf(
                    MountRecord(VfsPath.parse("/resources/source"), "source"),
                    MountRecord(VfsPath.parse("/resources/target"), "target"),
                ),
            )
        val storages = mapOf("source" to source, "target" to target)
        return OperationReconciler(
            journal = journal,
            nodes = SqliteNodeRepository(state),
            router = router,
            storages = { key -> storages[key] },
            boundary = StateBoundary(),
        )
    }

    private fun assertPhysicalFacts(
        sourceRoot: Path,
        targetRoot: Path,
        checkpoint: String,
    ) {
        val source = sourceRoot.resolve("source.bin")
        val target = targetRoot.resolve("moved.bin")
        when (checkpoint) {
            COPY_MOVE_BEFORE_TARGET_WRITE -> {
                assertTrue(Files.isRegularFile(source), "the source must remain before the target write")
                assertArrayEquals(COPY_MOVE_PAYLOAD, Files.readAllBytes(source))
                assertFalse(Files.exists(target), "the target must not exist before the target write")
            }

            COPY_MOVE_AFTER_TARGET_WRITE -> {
                assertTrue(Files.isRegularFile(source), "the source must remain before its delete")
                assertArrayEquals(COPY_MOVE_PAYLOAD, Files.readAllBytes(source))
                assertTrue(Files.isRegularFile(target), "the target must exist after its write")
                assertArrayEquals(COPY_MOVE_PAYLOAD, Files.readAllBytes(target))
            }

            COPY_MOVE_AFTER_SOURCE_DELETE -> {
                assertFalse(Files.exists(source), "the source must be gone after its delete")
                assertTrue(Files.isRegularFile(target), "the target must remain after the source delete")
                assertArrayEquals(COPY_MOVE_PAYLOAD, Files.readAllBytes(target))
            }

            else -> error("unknown checkpoint $checkpoint")
        }
    }

    private fun runCopyMoveChild(
        stateFile: Path,
        sourceRoot: Path,
        targetRoot: Path,
        checkpoint: String,
    ): CopyMoveChildResult {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val mainClass = "com.github.noahshen.alcyone.context.vfs.integration.vfs.CopyMoveRecoveryProcessTestKt"
        val process =
            ProcessBuilder(
                java,
                "-cp",
                copyMoveChildClasspath(),
                mainClass,
                stateFile.toString(),
                sourceRoot.toString(),
                targetRoot.toString(),
                checkpoint,
            ).redirectErrorStream(true).start()

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("child process timed out at $checkpoint")
        }
        val text = process.inputStream.bufferedReader().use { it.readText() }
        return CopyMoveChildResult(process.exitValue(), text)
    }

    private fun copyMoveChildClasspath(): String {
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
            CopyMoveRecoveryProcessTest::class.java,
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

    private data class CopyMoveChildResult(
        val exitCode: Int,
        val text: String,
    )

    private data class RecoveryTableDigests(
        val node: String,
        val event: String,
        val journal: String,
    )

    private data class PhysicalDigests(
        val source: String,
        val target: String,
    )

    private fun physicalDigests(
        sourceRoot: Path,
        targetRoot: Path,
    ): PhysicalDigests =
        PhysicalDigests(
            source = fileDigest(sourceRoot.resolve("source.bin")),
            target = fileDigest(targetRoot.resolve("moved.bin")),
        )

    private fun expectedPhase(checkpoint: String): RecoveryPhase =
        when (checkpoint) {
            COPY_MOVE_BEFORE_TARGET_WRITE -> RecoveryPhase.TARGET_WRITE_REQUESTED
            COPY_MOVE_AFTER_TARGET_WRITE,
            COPY_MOVE_AFTER_SOURCE_DELETE,
            -> RecoveryPhase.SOURCE_DELETE_REQUESTED
            else -> error("unknown checkpoint $checkpoint")
        }

    private fun expectedGeneration(checkpoint: String): Long =
        when (checkpoint) {
            COPY_MOVE_BEFORE_TARGET_WRITE -> 0L
            COPY_MOVE_AFTER_TARGET_WRITE,
            COPY_MOVE_AFTER_SOURCE_DELETE,
            -> 2L
            else -> error("unknown checkpoint $checkpoint")
        }

    private fun expectedSourcePresence(checkpoint: String): RecoveryPresence =
        when (checkpoint) {
            COPY_MOVE_BEFORE_TARGET_WRITE,
            COPY_MOVE_AFTER_TARGET_WRITE,
            -> RecoveryPresence.PRESENT
            COPY_MOVE_AFTER_SOURCE_DELETE -> RecoveryPresence.ABSENT
            else -> error("unknown checkpoint $checkpoint")
        }

    private fun expectedTargetPresence(checkpoint: String): RecoveryPresence =
        when (checkpoint) {
            COPY_MOVE_BEFORE_TARGET_WRITE -> RecoveryPresence.ABSENT
            COPY_MOVE_AFTER_TARGET_WRITE,
            COPY_MOVE_AFTER_SOURCE_DELETE,
            -> RecoveryPresence.PRESENT
            else -> error("unknown checkpoint $checkpoint")
        }
}

private class CopyMoveHaltingStorage(
    private val delegate: Storage,
    private val role: MountRole,
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
    ): StorageAttributes {
        if (role == MountRole.TARGET && checkpoint == COPY_MOVE_BEFORE_TARGET_WRITE) halt()
        return delegate.write(path, content, mode)
    }

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
    ): StorageAttributes = delegate.publishNew(path, input, expectedSizeBytes, maxBytes, tempName)

    override suspend fun createDirectory(path: StoragePath) = delegate.createDirectory(path)

    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes = delegate.move(source, target)

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        if (role == MountRole.SOURCE && checkpoint == COPY_MOVE_AFTER_TARGET_WRITE) halt()
        delegate.delete(path, recursive)
        if (role == MountRole.SOURCE && checkpoint == COPY_MOVE_AFTER_SOURCE_DELETE) halt()
    }

    override fun capabilities() = delegate.capabilities()
}

private enum class MountRole {
    SOURCE,
    TARGET,
}

fun main(args: Array<String>) {
    val (statePath, sourcePath, targetPath, checkpoint) = args
    val state = VfsStateDatabase.file(Path.of(statePath))
    val source = runBlocking { LocalFsStorage.create(Path.of(sourcePath)) }
    val target = runBlocking { LocalFsStorage.create(Path.of(targetPath)) }
    val notifier = AsyncEventNotifier()
    try {
        val halt = {
            Runtime.getRuntime().halt(0)
            error("Runtime.halt(0) returned")
        }
        val storages =
            mapOf(
                "source" to CopyMoveHaltingStorage(source, MountRole.SOURCE, checkpoint, halt),
                "target" to CopyMoveHaltingStorage(target, MountRole.TARGET, checkpoint, halt),
            )
        val router =
            MountRouter.of(
                setOf("resources"),
                listOf(
                    MountRecord(VfsPath.parse("/resources/source"), "source"),
                    MountRecord(VfsPath.parse("/resources/target"), "target"),
                ),
            )
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
            vfs.safeReplace(VfsUri.parse(COPY_MOVE_SOURCE_URI), COPY_MOVE_PAYLOAD)
            vfs.move(VfsUri.parse(COPY_MOVE_SOURCE_URI), VfsUri.parse(COPY_MOVE_TARGET_URI))
        }
        error("copy move returned without reaching checkpoint $checkpoint")
    } finally {
        notifier.close()
        target.close()
        source.close()
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
