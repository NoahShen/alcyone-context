package com.github.noahshen.alcyone.context.memory.runtime.backup

import com.github.noahshen.alcyone.context.memory.api.*
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextBackupSnapshotter
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.vfs.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class BackupRuntimeTest {
    @Test
    fun `stage mapping exposes only fixed enum values`() {
        assertEquals(
            ContextBackupFailureStage.SNAPSHOT,
            failureStage(BackupStageFailure(ContextBackupFailureStage.SNAPSHOT, IllegalStateException())),
        )
        assertEquals(ContextBackupFailureStage.PRE_CHECK, failureStage(IllegalStateException("unpublished")))
        assertEquals(
            ContextBackupFailureStage.POST_CHECK,
            failureStage(BackupStageFailure(ContextBackupFailureStage.POST_CHECK, IllegalStateException())),
        )
        assertEquals(
            ContextBackupFailureStage.PUBLISH,
            failureStage(ContextBackupException(ContextBackupErrorCode.PUBLISH_FAILED, "unpublished")),
        )
        assertNull(failureStage(CancellationException("unpublished")))
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)

    @Test
    @Timeout(15)
    fun `concurrent manual backups are single flight and complete cleanup before returning`() =
        runBlocking {
            val released = CompletableDeferred<Unit>()
            val enteredPublish = CompletableDeferred<Unit>()
            val snapshot =
                ContextBackupSnapshotter {
                    ContextDatabaseInitializer.initialize(it)
                    Files.size(it)
                }
            val vfs = RecordingVfs(gate = released, enteredPublish = enteredPublish)
            val cleaned = CompletableDeferred<Unit>()
            val runtime =
                BackupRuntime(
                    BackupConfig(),
                    executor = null,
                    vfs = vfs,
                    clock = clock,
                    snapshotter = snapshot,
                    cleanup = { _, _ -> cleaned.complete(Unit) },
                )
            runtime.start()
            val first = async { runtime.runNow() }
            enteredPublish.await()
            assertFailsWith<ContextBackupException> { runtime.runNow() }.also {
                assertEquals(ContextBackupErrorCode.ALREADY_RUNNING, it.code)
            }
            released.complete(Unit)
            val result = first.await()
            assertTrue(cleaned.isCompleted)
            assertEquals(listOf(result.uri), vfs.published)
            assertEquals(WorkerState.IDLE, runtime.status().state)
            runtime.closeAsync()
        }

    @Test
    @Timeout(15)
    fun `publish failure is visible and next backup can run`() =
        runBlocking {
            val vfs = RecordingVfs(fail = true)
            val runtime =
                BackupRuntime(
                    BackupConfig(),
                    executor = null,
                    vfs = vfs,
                    clock = clock,
                    snapshotter = {
                        ContextDatabaseInitializer.initialize(it)
                        Files.size(it)
                    },
                    cleanup = { _, _ -> },
                )
            runtime.start()
            assertFailsWith<Throwable> { runtime.runNow() }
            assertEquals(ContextBackupFailureStage.PUBLISH, runtime.status().lastFailureStage)
            vfs.fail = false
            runtime.runNow()
            assertNull(runtime.status().lastFailureStage)
            assertEquals(1, vfs.published.size)
            runtime.closeAsync()
        }

    @Test
    @Timeout(15)
    fun `successful snapshot leaves its temporary workspace removed`() =
        runBlocking {
            val root = Files.createTempDirectory("backup-runtime-cleanup-")
            val runtime =
                BackupRuntime(
                    BackupConfig(tempDirectory = root),
                    executor = null,
                    vfs = RecordingVfs(),
                    clock = clock,
                    snapshotter = {
                        ContextDatabaseInitializer.initialize(it)
                        Files.size(it)
                    },
                )
            try {
                runtime.start()
                runtime.runNow()
                assertEquals(emptyList(), Files.list(root).use { it.toList() })
            } finally {
                runtime.closeAsync()
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

    @Test
    @Timeout(15)
    fun `close waits for an accepted publication to finish and preserves the published object`() =
        runBlocking {
            val enteredPublish = CompletableDeferred<Unit>()
            val releasePublish = CompletableDeferred<Unit>()
            val vfs = RecordingVfs(gate = releasePublish, enteredPublish = enteredPublish)
            val runtime =
                BackupRuntime(
                    BackupConfig(),
                    executor = null,
                    vfs = vfs,
                    clock = clock,
                    snapshotter = {
                        ContextDatabaseInitializer.initialize(it)
                        Files.size(it)
                    },
                    cleanup = { _, _ -> },
                )
            runtime.start()
            val run = async { runtime.runNow() }
            enteredPublish.await()
            val closing = async { runtime.closeAsync() }
            yield()
            assertFalse(closing.isCompleted, "close must not return while publication is in flight")
            releasePublish.complete(Unit)
            val result = run.await()
            closing.await()
            assertEquals(listOf(result.uri), vfs.published)
            assertEquals(WorkerState.STOPPED, runtime.status().state)
            assertEquals(ContextBackupErrorCode.CLOSED, assertFailsWith<ContextBackupException> { runtime.runNow() }.code)
        }

    @Test
    @Timeout(15)
    fun `snapshot and pre-check failures report distinct stages`() =
        runBlocking {
            val snapshotFailure =
                BackupRuntime(
                    BackupConfig(),
                    executor = null,
                    vfs = RecordingVfs(),
                    clock = clock,
                    snapshotter = { throw java.io.IOException("controlled snapshot failure") },
                    cleanup = { _, _ -> },
                )
            snapshotFailure.start()
            assertFailsWith<Throwable> { snapshotFailure.runNow() }
            assertEquals(ContextBackupFailureStage.SNAPSHOT, snapshotFailure.status().lastFailureStage)
            snapshotFailure.closeAsync()

            val preCheckFailure =
                BackupRuntime(
                    BackupConfig(),
                    executor = null,
                    vfs = RecordingVfs(),
                    clock = clock,
                    snapshotter = {
                        ContextDatabaseInitializer.initialize(it)
                        Files.newByteChannel(it, java.nio.file.StandardOpenOption.WRITE).use { channel -> channel.truncate(0) }
                        Files.size(it)
                    },
                    cleanup = { _, _ -> },
                )
            preCheckFailure.start()
            assertFailsWith<Throwable> { preCheckFailure.runNow() }
            assertEquals(ContextBackupFailureStage.PRE_CHECK, preCheckFailure.status().lastFailureStage)
            preCheckFailure.closeAsync()
        }

    @Test
    fun `real post publish read failure is post check and leaves the published object`() =
        runBlocking {
            val temp = Files.createTempDirectory("backup-post-publish-")
            val database = Files.createTempDirectory("backup-post-publish-db-").resolve("context.db")
            ContextDatabaseInitializer.initialize(database)
            val executor =
                com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
                    .open(database)
            val vfs = RecordingVfs(postPublishFailure = IllegalStateException("controlled post-publish read"))
            val runtime = BackupRuntime(BackupConfig(tempDirectory = temp), executor, vfs, clock)
            runtime.start()
            try {
                val stageFailure = assertFailsWith<BackupStageFailure> { runtime.runNow() }
                assertEquals(ContextBackupFailureStage.POST_CHECK, stageFailure.stage)
                val failure = assertIs<ContextBackupException>(stageFailure.cause)
                assertEquals(ContextBackupErrorCode.UNAVAILABLE, failure.code)
                assertEquals(ContextBackupFailureStage.POST_CHECK, runtime.status().lastFailureStage)
                assertEquals(1, vfs.published.size)
                assertTrue(vfs.objects.isNotEmpty())
                assertTrue(Files.list(temp).use { paths -> paths.findFirst().isEmpty })
            } finally {
                runtime.closeAsync()
                executor.closeAsync()
                temp.toFile().deleteRecursively()
                database.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun `external cancellation during post publish propagates without recording a backup failure`() =
        runBlocking {
            val cancellation = CancellationException("caller cancelled post-publish")
            val vfs = RecordingVfs(postPublishFailure = cancellation)
            val runtime =
                BackupRuntime(
                    BackupConfig(),
                    executor = null,
                    vfs = vfs,
                    clock = clock,
                    snapshotter = { path ->
                        ContextDatabaseInitializer.initialize(path)
                        Files.size(path)
                    },
                    cleanup = { _, _ -> },
                )
            runtime.start()
            val failure = assertFailsWith<CancellationException> { runtime.runNow() }
            assertEquals(cancellation.message, failure.message)
            assertNull(runtime.status().lastFailureStage)
            assertEquals(1, vfs.published.size)
            runtime.closeAsync()
        }

    private class RecordingVfs(
        var fail: Boolean = false,
        val gate: CompletableDeferred<Unit>? = null,
        val enteredPublish: CompletableDeferred<Unit>? = null,
        val postPublishFailure: Throwable? = null,
    ) : Vfs {
        val published = mutableListOf<String>()
        val objects = mutableMapOf<VfsUri, ByteArray>()

        override suspend fun publishNew(
            uri: VfsUri,
            input: java.io.InputStream,
            options: com.github.noahshen.alcyone.context.vfs.PublishOptions,
        ): NodeInfo {
            input.use {
                enteredPublish?.complete(Unit)
                gate?.await()
                if (fail) {
                    throw com.github.noahshen.alcyone.context.vfs.VfsException(
                        com.github.noahshen.alcyone.context.vfs.VfsErrorCode.STORAGE_ERROR,
                        "controlled",
                    )
                }
                published += uri.toString()
                objects[uri] = it.readBytes()
            }
            return NodeInfo(NodeId.parse("01900000-0000-7000-8000-000000000000"), uri, NodeType.FILE, Instant.EPOCH, Instant.EPOCH, null)
        }

        override suspend fun openStream(
            uri: VfsUri,
            options: com.github.noahshen.alcyone.context.vfs.VfsStreamOptions,
        ): com.github.noahshen.alcyone.context.vfs.VfsStreamResult {
            postPublishFailure?.let { throw it }
            return com.github.noahshen.alcyone.context.vfs.VfsStreamResult(
                uri,
                objects.getValue(uri).size.toLong(),
                java.io.ByteArrayInputStream(objects.getValue(uri)),
            )
        }

        private fun unsupported(): Nothing = throw NotImplementedError()

        override suspend fun read(
            uri: VfsUri,
            options: ReadOptions,
        ): ByteArray = unsupported()

        override suspend fun write(
            uri: VfsUri,
            content: ByteArray,
            options: WriteOptions,
        ): NodeInfo = unsupported()

        override suspend fun stat(
            uri: VfsUri,
            options: StatOptions,
        ): NodeInfo = unsupported()

        override suspend fun getNode(id: NodeId): NodeInfo = unsupported()

        override suspend fun list(uri: VfsUri): List<VfsEntry> = unsupported()

        override suspend fun move(
            source: VfsUri,
            target: VfsUri,
        ): NodeInfo = unsupported()

        override suspend fun delete(
            uri: VfsUri,
            options: DeleteOptions,
        ) = unsupported()

        override suspend fun getMetadata(id: NodeId): NodeMetadata = unsupported()

        override suspend fun setMetadata(
            id: NodeId,
            metadata: NodeMetadata,
        ) = unsupported()
    }
}
