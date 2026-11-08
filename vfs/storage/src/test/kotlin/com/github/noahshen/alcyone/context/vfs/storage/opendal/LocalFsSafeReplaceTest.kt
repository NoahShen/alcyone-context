package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertFailsWith

/** CT17 B1：真实 LocalFs 同目录临时文件与原子 replace 探针。 */
class LocalFsSafeReplaceTest {
    @TempDir
    lateinit var root: Path

    private fun temporaryFiles(): List<Path> =
        Files.walk(root).use { entries ->
            entries
                .filter { it.fileName.toString().startsWith(LocalFsStorage.SAFE_REPLACE_TEMP_PREFIX) }
                .toList()
        }

    @Test
    fun `the current JVM and filesystem atomically replace an existing target`() {
        val source = Files.writeString(root.resolve("source.tmp"), "new")
        val target = Files.writeString(root.resolve("target.txt"), "old")

        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        assertEquals("new", Files.readString(target))
        assertFalse(Files.exists(source))
    }

    @Test
    fun `capability is backed by a real probe and first creation is complete`() =
        runBlocking {
            Files.createDirectory(root.resolve("notes"))
            LocalFsStorage.create(root).use { storage ->
                assertTrue(storage.capabilities().atomicReplace, "current filesystem must pass the real atomic probe")

                val attributes = storage.safeReplace(StoragePath.parse("notes/a.txt"), "created".toByteArray())

                assertEquals("created", Files.readString(root.resolve("notes/a.txt")))
                assertEquals(7L, attributes.sizeBytes)
                assertTrue(temporaryFiles().isEmpty(), "successful safeReplace must not leave a temporary file")
            }
        }

    @Test
    fun `replacing an existing file changes all bytes without exposing the temporary file`() =
        runBlocking {
            Files.createDirectories(root.resolve("notes"))
            val target = Files.write(root.resolve("notes/a.bin"), ByteArray(64 * 1024) { 0x11 })
            val replacement = ByteArray(128 * 1024) { (it % 251).toByte() }

            LocalFsStorage.create(root).use { storage ->
                val attributes = storage.safeReplace(StoragePath.parse("notes/a.bin"), replacement)

                assertTrue(Files.readAllBytes(target).contentEquals(replacement))
                assertEquals(replacement.size.toLong(), attributes.sizeBytes)
                assertEquals(listOf("a.bin"), storage.list(StoragePath.parse("notes")).map { it.name })
                assertTrue(temporaryFiles().isEmpty())
            }
        }

    @Test
    fun `temporary write failure leaves the old file byte for byte unchanged`() =
        runBlocking {
            val target = Files.writeString(root.resolve("a.txt"), "old")
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    tempWriter = { _, _ -> throw IOException("injected temporary write failure") },
                ).use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.safeReplace(StoragePath.parse("a.txt"), "new".toByteArray()) }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.NONE, failure.effect)
                    assertEquals("old", Files.readString(target))
                    assertTrue(temporaryFiles().isEmpty())
                }
        }

    @Test
    fun `temporary confirmation failure leaves the old file unchanged and cleans the temporary file`() =
        runBlocking {
            val target = Files.writeString(root.resolve("a.txt"), "old")
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    tempConfirmer = { _, _ -> throw IOException("injected confirmation failure") },
                ).use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.safeReplace(StoragePath.parse("a.txt"), "new".toByteArray()) }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals("old", Files.readString(target))
                    assertTrue(temporaryFiles().isEmpty())
                }
        }

    @Test
    fun `atomic move failure does not fall back and keeps the old target`() =
        runBlocking {
            val target = Files.writeString(root.resolve("a.txt"), "old")
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    atomicMover = { _, _ -> throw IOException("injected atomic move failure") },
                ).use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.safeReplace(StoragePath.parse("a.txt"), "new".toByteArray()) }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.UNKNOWN, failure.effect)
                    assertEquals("old", Files.readString(target))
                    assertTrue(temporaryFiles().isEmpty())
                }
        }

    @Test
    fun `no atomic capability maps to unsupported and never falls back`() =
        runBlocking {
            val target = Files.writeString(root.resolve("a.txt"), "old")
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    atomicMover = { temp, destination ->
                        throw AtomicMoveNotSupportedException(temp.toString(), destination.toString(), "injected")
                    },
                ).use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.safeReplace(StoragePath.parse("a.txt"), "new".toByteArray()) }

                    assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code)
                    assertEquals(VfsEffect.NONE, failure.effect)
                    assertEquals("old", Files.readString(target))
                    assertTrue(temporaryFiles().isEmpty())
                }
        }

    @Test
    fun `temporary cleanup failure preserves the primary error and reports PARTIAL`() =
        runBlocking {
            val target = Files.writeString(root.resolve("a.txt"), "old")
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    tempWriter = { _, _ -> throw IOException("primary temporary failure") },
                    tempCleaner = { throw IOException("injected cleanup failure") },
                ).use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.safeReplace(StoragePath.parse("a.txt"), "new".toByteArray()) }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("old", Files.readString(target))
                    assertTrue(failure.suppressed.any { it.message == "injected cleanup failure" })
                    assertEquals(1, temporaryFiles().size, "the injected cleaner intentionally leaves the temporary file")
                }
        }

    @Test
    fun `cancellation before atomic move keeps old bytes hides the temporary file and cleans up`() =
        runBlocking {
            val target = Files.writeString(root.resolve("a.txt"), "old")
            val entered = CompletableDeferred<Unit>()
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    beforeAtomicMove = { _, _ ->
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                ).use { storage ->
                    val operation =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            storage.safeReplace(StoragePath.parse("a.txt"), "new".toByteArray())
                        }
                    withTimeout(30_000) { entered.await() }
                    assertEquals(
                        listOf("a.txt"),
                        storage.list(StoragePath.root).map { it.name },
                        "active temporary file is not a VFS entry",
                    )

                    operation.cancel()
                    val failure = runCatching { withTimeout(30_000) { operation.await() } }.exceptionOrNull()
                    assertTrue(failure is CancellationException, "CancellationException must escape unwrapped: $failure")
                    assertEquals("old", Files.readString(target))
                    assertTrue(temporaryFiles().isEmpty())
                }
        }

    @Test
    fun `a directory target is refused before a temporary file is created`() =
        runBlocking {
            Files.createDirectory(root.resolve("dir"))
            LocalFsStorage.create(root).use { storage ->
                val failure = assertFailsWith<VfsException> { storage.safeReplace(StoragePath.parse("dir"), "new".toByteArray()) }

                assertEquals(VfsErrorCode.TYPE_MISMATCH, failure.code)
                assertEquals(VfsEffect.NONE, failure.effect)
                assertTrue(temporaryFiles().isEmpty())
            }
        }

    @Test
    fun `a closed adapter rejects safeReplace with CLOSED`() =
        runBlocking {
            val storage = LocalFsStorage.create(root)
            storage.close()

            val failure = assertFailsWith<VfsException> { storage.safeReplace(StoragePath.parse("a.txt"), "new".toByteArray()) }

            assertEquals(VfsErrorCode.CLOSED, failure.code)
            assertFalse(Files.exists(root.resolve("a.txt")))
        }
}
