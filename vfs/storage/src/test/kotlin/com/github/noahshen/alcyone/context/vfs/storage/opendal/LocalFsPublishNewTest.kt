package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.PublishTempName
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFsPublishNewTest {
    @TempDir lateinit var temp: Path

    @Test
    @Timeout(60)
    fun `short and long streams remove their exact temporary file and close input`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("root"))
            LocalFsStorage.create(root).use { storage ->
                for (content in listOf("short", "this is too long")) {
                    val input = OwnedInput(content.toByteArray())
                    assertFailsWith<VfsException> {
                        storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 6, 6, PublishTempName.newName())
                    }.also { assertEquals(VfsErrorCode.STORAGE_ERROR, it.code) }
                    assertEquals(1, input.closed)
                    assertFalse(Files.exists(root.resolve("snapshot.sqlite")))
                    assertEquals(emptyList<String>(), Files.list(root).use { stream -> stream.map { p -> p.fileName.toString() }.toList() })
                }
            }
        }

    @Test
    @Timeout(60)
    fun `a cancelled stream cleans temporary storage and propagates cancellation`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("cancel"))
            LocalFsStorage.create(root).use { storage ->
                val input =
                    object : InputStream() {
                        override fun read(): Int = throw CancellationException("cancel the upload")
                    }
                assertFailsWith<CancellationException> {
                    storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 1, 1, PublishTempName.newName())
                }
                assertFalse(Files.exists(root.resolve("snapshot.sqlite")))
                assertEquals(emptyList<String>(), Files.list(root).use { stream -> stream.map { p -> p.fileName.toString() }.toList() })
            }
        }

    @Test
    @Timeout(60)
    fun `publication synchronizes after link before cleanup and removes staging on success`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("durable-order"))
            val events = mutableListOf<String>()
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    directorySyncer = { directory ->
                        val targetVisible = Files.exists(root.resolve("snapshot.sqlite"))
                        val stagingVisible = entries(root).any { it != "snapshot.sqlite" }
                        val directoryLabel = if (Files.isSameFile(directory, root)) "." else "unexpected:$directory"
                        events += "sync:$directoryLabel:target=$targetVisible:staging=$stagingVisible"
                    },
                ).use { storage ->
                    assertTrue(storage.capabilities().durableDirectorySync)
                    assertEquals(listOf("sync:.:target=false:staging=false"), events)

                    events.clear()
                    storage.publishNew(
                        StoragePath.parse("snapshot.sqlite"),
                        OwnedInput("snapshot".toByteArray()),
                        8,
                        8,
                        PublishTempName.newName(),
                    )

                    assertEquals("snapshot", Files.readString(root.resolve("snapshot.sqlite")))
                    assertEquals(
                        listOf("sync:.:target=true:staging=true", "sync:.:target=true:staging=false"),
                        events,
                        "the target must be visible at the publication sync and staging must survive until after it",
                    )
                    assertEquals(listOf("snapshot.sqlite"), entries(root))
                }
        }

    @Test
    @Timeout(60)
    fun `post-link directory sync failure keeps the new target and reports PARTIAL`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("durable-publish-failure"))
            var syncCalls = 0
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    directorySyncer = {
                        syncCalls++
                        if (syncCalls == 2) throw IOException("injected publish directory sync failure")
                    },
                ).use { storage ->
                    assertTrue(storage.capabilities().durableDirectorySync)
                    val input = OwnedInput("snapshot".toByteArray())
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 8, 8, PublishTempName.newName())
                        }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("injected publish directory sync failure", failure.cause?.message)
                    assertEquals("snapshot", Files.readString(root.resolve("snapshot.sqlite")), "published target must not be rolled back")
                    assertEquals(3, syncCalls)
                    assertEquals(1, input.closed)
                    assertEquals(listOf("snapshot.sqlite"), entries(root))
                    assertFalse(failure.message.orEmpty().contains(root.toString()), "public failure must not expose the physical path")
                }
        }

    @Test
    @Timeout(60)
    fun `cleanup unlink sync failure keeps the new target and reports PARTIAL`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("durable-cleanup-failure"))
            var syncCalls = 0
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    directorySyncer = {
                        syncCalls++
                        if (syncCalls == 3) throw IOException("injected cleanup directory sync failure")
                    },
                ).use { storage ->
                    assertTrue(storage.capabilities().durableDirectorySync)
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.publishNew(
                                StoragePath.parse("snapshot.sqlite"),
                                OwnedInput("snapshot".toByteArray()),
                                8,
                                8,
                                PublishTempName.newName(),
                            )
                        }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("injected cleanup directory sync failure", failure.cause?.message)
                    assertEquals("snapshot", Files.readString(root.resolve("snapshot.sqlite")))
                    assertEquals(3, syncCalls)
                    assertEquals(listOf("snapshot.sqlite"), entries(root))
                    assertFalse(failure.message.orEmpty().contains(root.toString()), "public failure must not expose the physical path")
                }
        }

    @Test
    @Timeout(60)
    fun `cleanup sync failure cannot replace a prior publication failure`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("durable-primary-failure"))
            var syncCalls = 0
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    directorySyncer = {
                        syncCalls++
                        when (syncCalls) {
                            2 -> throw IOException("injected publish failure")
                            3 -> throw IOException("injected cleanup failure")
                        }
                    },
                ).use { storage ->
                    assertTrue(storage.capabilities().durableDirectorySync)
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.publishNew(
                                StoragePath.parse("snapshot.sqlite"),
                                OwnedInput("snapshot".toByteArray()),
                                8,
                                8,
                                PublishTempName.newName(),
                            )
                        }

                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("injected publish failure", failure.cause?.message)
                    assertTrue(
                        failure.suppressed.any { it.cause?.message == "injected cleanup failure" },
                        "cleanup failure must remain suppressed: ${failure.suppressed}",
                    )
                    assertEquals("snapshot", Files.readString(root.resolve("snapshot.sqlite")))
                    assertEquals(3, syncCalls)
                    assertEquals(listOf("snapshot.sqlite"), entries(root))
                }
        }

    @Test
    @Timeout(60)
    fun `input close and cleanup sync failures retain both diagnostics after publication`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("durable-close-failure"))
            var syncCalls = 0
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    directorySyncer = {
                        syncCalls++
                        if (syncCalls == 3) throw IOException("injected cleanup sync failure")
                    },
                ).use { storage ->
                    assertTrue(storage.capabilities().durableDirectorySync)
                    val input = CloseFailingInput("snapshot".toByteArray())
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 8, 8, PublishTempName.newName())
                        }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("injected input close failure", failure.cause?.message)
                    assertTrue(
                        failure.cause?.suppressed?.any { it.cause?.message == "injected cleanup sync failure" } == true,
                        "cleanup sync failure must stay attached to the cleanup aggregate",
                    )
                    assertEquals("snapshot", Files.readString(root.resolve("snapshot.sqlite")))
                    assertEquals(1, input.closed)
                    assertEquals(3, syncCalls)
                    assertEquals(listOf("snapshot.sqlite"), entries(root))
                }
        }

    @Test
    @Timeout(60)
    fun `existing target is refused without overwrite and a foreign staging path is never removed`() =
        runBlocking {
            val targetRoot = Files.createDirectory(temp.resolve("existing-target"))
            val target = Files.writeString(targetRoot.resolve("snapshot.sqlite"), "old")
            LocalFsStorage.create(targetRoot).use { storage ->
                val input = OwnedInput("snapshot".toByteArray())
                val failure =
                    assertFailsWith<VfsException> {
                        storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 8, 8, PublishTempName.newName())
                    }

                assertEquals(VfsErrorCode.ALREADY_EXISTS, failure.code)
                assertEquals("old", Files.readString(target))
                assertEquals(1, input.closed)
                assertEquals(listOf("snapshot.sqlite"), entries(targetRoot))
            }

            val orphanRoot = Files.createDirectory(temp.resolve("foreign-orphan"))
            val orphan = Files.writeString(orphanRoot.resolve("foreign.tmp"), "owned elsewhere")
            LocalFsStorage.create(orphanRoot).use { storage ->
                val input = OwnedInput("snapshot".toByteArray())
                val failure =
                    assertFailsWith<VfsException> {
                        storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 8, 8, "foreign.tmp")
                    }

                assertEquals(VfsErrorCode.ALREADY_EXISTS, failure.code)
                assertEquals("owned elsewhere", Files.readString(orphan))
                assertFalse(Files.exists(orphanRoot.resolve("snapshot.sqlite")))
                assertEquals(1, input.closed)
                assertEquals(listOf("foreign.tmp"), entries(orphanRoot))
            }
        }

    @Test
    @Timeout(60)
    fun `null initial fileKey never publishes and preserves unknown staging`() =
        runBlocking {
            assertUnknownInitialIdentityPreserved("null-key", { null }, null)
        }

    @Test
    @Timeout(60)
    fun `fileKey read failure never publishes and preserves unknown staging`() =
        runBlocking {
            assertUnknownInitialIdentityPreserved(
                "throwing-key",
                { throw IOException("injected fileKey read failure") },
                "injected fileKey read failure",
            )
        }

    @Test
    @Timeout(60)
    fun `null actual fileKey during cleanup preserves a known staging identity`() =
        runBlocking {
            val root = Files.createDirectory(temp.resolve("null-actual-key"))
            val target = root.resolve("snapshot.sqlite")
            val staging = root.resolve("controlled.tmp")
            var keyReads = 0
            try {
                LocalFsStorage
                    .open(
                        root,
                        LocalFsOptions(),
                        fileKeyReader = {
                            keyReads++
                            if (keyReads == 1) "known-identity" else null
                        },
                    ).use { storage ->
                        val input = ThrowingInput(IOException("stop after recording identity"))
                        val failure =
                            assertFailsWith<VfsException> {
                                storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 8, 8, staging.fileName.toString())
                            }
                        assertAll(
                            { assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code) },
                            { assertEquals(VfsEffect.UNKNOWN, failure.effect, "existing copy-phase UNKNOWN must not be downgraded") },
                            { assertEquals(2, keyReads) },
                            { assertEquals(1, input.closed) },
                            { assertFalse(Files.exists(target)) },
                            { assertTrue(Files.exists(staging), "unknown current identity must be retained") },
                            { assertFalse(failure.message.orEmpty().contains(root.toString())) },
                        )
                    }
            } finally {
                Files.deleteIfExists(target)
                Files.deleteIfExists(staging)
            }
        }

    @Test
    @Timeout(60)
    fun `replacement staging path survives IOException without publishing`() =
        runBlocking {
            assertReplacementStagingSurvives(IOException("stop after replacing staging"))
        }

    @Test
    @Timeout(60)
    fun `replacement staging path survives cancellation without publishing`() =
        runBlocking {
            assertReplacementStagingSurvives(CancellationException("cancel after replacing staging"))
        }

    private suspend fun assertReplacementStagingSurvives(failureToThrow: Throwable) {
        val root = Files.createDirectory(temp.resolve("replace-${failureToThrow::class.simpleName}"))
        val target = root.resolve("snapshot.sqlite")
        val staging = root.resolve(".alcyone-publish-controlled.tmp")
        val original = root.resolve("original-staging.tmp")
        LocalFsStorage.create(root).use { storage ->
            val input = RearrangingInput(staging, original, "foreign".toByteArray(), failureToThrow)
            try {
                val thrown =
                    assertFailsWith<Throwable> {
                        storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 8, 8, staging.fileName.toString())
                    }
                assertAll(
                    { assertEquals(1, input.closed) },
                    { assertFalse(Files.exists(target)) },
                    { assertEquals("foreign", Files.readString(staging)) },
                    {
                        if (failureToThrow is CancellationException) {
                            assertTrue(thrown is CancellationException, "CancellationException must escape unwrapped: $thrown")
                            assertEquals(failureToThrow.message, thrown.message)
                        } else {
                            val vfsFailure = thrown as VfsException
                            assertEquals(VfsErrorCode.STORAGE_ERROR, vfsFailure.code)
                            assertTrue(vfsFailure.cause === failureToThrow)
                            assertFalse(vfsFailure.message.orEmpty().contains(root.toString()))
                        }
                    },
                )
            } finally {
                Files.deleteIfExists(target)
                Files.deleteIfExists(staging)
                Files.deleteIfExists(original)
            }
        }
    }

    private suspend fun assertUnknownInitialIdentityPreserved(
        label: String,
        fileKeyReader: (Path) -> Any?,
        expectedCauseMessage: String?,
    ) {
        val root = Files.createDirectory(temp.resolve(label))
        val target = root.resolve("snapshot.sqlite")
        val staging = root.resolve("controlled.tmp")
        try {
            LocalFsStorage
                .open(root, LocalFsOptions(), fileKeyReader = fileKeyReader)
                .use { storage ->
                    val input = ReadForbiddenInput()
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.publishNew(StoragePath.parse("snapshot.sqlite"), input, 8, 8, staging.fileName.toString())
                        }
                    assertAll(
                        { assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code) },
                        { assertEquals(VfsEffect.PARTIAL, failure.effect) },
                        { assertEquals(expectedCauseMessage, failure.cause?.message) },
                        { assertFalse(failure.message.orEmpty().contains(root.toString())) },
                        { assertEquals(1, input.closed) },
                        { assertFalse(Files.exists(target)) },
                        { assertTrue(Files.exists(staging), "unknown staging owner must be retained for manual review") },
                        { assertEquals(emptyList(), Files.readAllBytes(staging).toList(), "input must not be read") },
                    )
                }
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(staging)
        }
    }

    private fun entries(root: Path): List<String> =
        Files.list(root).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

    private class OwnedInput(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = 0

        override fun close() {
            closed++
            super.close()
        }
    }

    private class CloseFailingInput(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = 0

        override fun close() {
            closed++
            throw IOException("injected input close failure")
        }
    }

    private class RearrangingInput(
        private val staging: Path,
        private val original: Path,
        private val replacement: ByteArray,
        private val failure: Throwable,
    ) : InputStream() {
        var closed = 0

        override fun read(): Int {
            Files.move(staging, original)
            Files.write(staging, replacement)
            throw failure
        }

        override fun close() {
            closed++
        }
    }

    private class ThrowingInput(
        private val failure: Throwable,
    ) : InputStream() {
        var closed = 0

        override fun read(): Int = throw failure

        override fun close() {
            closed++
        }
    }

    private class ReadForbiddenInput : InputStream() {
        var closed = 0

        override fun read(): Int = error("input must not be read when file identity is unavailable")

        override fun close() {
            closed++
        }
    }
}
