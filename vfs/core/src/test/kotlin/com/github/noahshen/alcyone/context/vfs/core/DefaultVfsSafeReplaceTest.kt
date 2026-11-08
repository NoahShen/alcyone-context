package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.makeDirectory
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/** CT17 B1：DefaultVfs safeReplace 的编排、Node/Event 与失败矩阵。 */
class DefaultVfsSafeReplaceTest {
    private val notifiers = TrackedNotifiers()

    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    private fun safeDisk() = StorageFakeImpl(StorageCapabilities(atomicReplace = true))

    private fun harness(
        disk: StorageFakeImpl,
        capabilityOverrides: Map<String, StorageCapabilities> = emptyMap(),
        limits: VfsLimits = VfsLimits(),
        clock: () -> Instant = { VfsHarness.FIXED_CLOCK },
    ) = VfsHarness(
        mounts = listOf(VfsHarness.Mounted("/resources", disk)),
        capabilityOverrides = capabilityOverrides,
        limits = limits,
        notifier = notifiers.create(),
        clock = clock,
    )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    @Test
    fun `A02 first safe replace creates parents and commits FILE_CREATED`() =
        runBlocking {
            val disk = safeDisk()
            val harness = harness(disk)

            val info = harness.vfs.safeReplace(uri("/a/b/c.txt"), "complete".toByteArray())

            assertEquals(
                listOf("stat:a/b/c.txt", "stat:a", "stat:a/b", "createDirectory:a", "createDirectory:a/b", "safeReplace:a/b/c.txt"),
                disk.calls,
            )
            assertTrue(disk.calls.none { it.startsWith("write:") || it.startsWith("move:") || it.startsWith("delete:") })
            assertEquals("complete", disk.readText("a/b/c.txt"))
            assertEquals(listOf(VfsEventType.FILE_CREATED), harness.committedEvents().map { it.type })
            assertEquals(info.id, harness.committedNodes().single().id)
        }

    @Test
    fun `A03 replacing a registered file preserves identity metadata and registeredAt`() =
        runBlocking {
            var now = VfsHarness.FIXED_CLOCK
            val disk = safeDisk().also { it.withFile("a.txt", "old") }
            val harness = harness(disk, clock = { now })
            val original = harness.seedNode("/resources/a.txt")
            val metadata = NodeMetadata(setOf("map"), "stable")
            harness.seedMetadata(original.id, metadata)
            disk.calls.clear()

            now = VfsHarness.FIXED_CLOCK.plusSeconds(60)
            val info = harness.vfs.safeReplace(uri("/a.txt"), "new".toByteArray())

            assertEquals(original.id, info.id)
            assertEquals(original.registeredAt, info.registeredAt)
            assertEquals(now, info.updatedAt)
            assertEquals(metadata, harness.uow.snapshot().second[original.id])
            assertEquals(listOf(VfsEventType.FILE_WRITTEN), harness.committedEvents().map { it.type })
            assertEquals("new", disk.readText("a.txt"))
            assertTrue(disk.calls.none { it.startsWith("write:") || it.startsWith("move:") || it.startsWith("delete:") })
        }

    @Test
    fun `A03 an unregistered physical file is replaced and gets FILE_WRITTEN`() =
        runBlocking {
            val disk = safeDisk().also { it.withFile("a.txt", "outside") }
            val harness = harness(disk)

            val info = harness.vfs.safeReplace(uri("/a.txt"), "inside".toByteArray())

            assertEquals("inside", disk.readText("a.txt"))
            assertEquals(listOf(VfsEventType.FILE_WRITTEN), harness.committedEvents().map { it.type })
            assertEquals(info.id, harness.committedNodes().single().id)
        }

    @Test
    fun `A04 limit is rejected before the storage is touched`() =
        runBlocking {
            val disk = safeDisk()
            val harness = harness(disk, limits = VfsLimits(defaultWriteMaxBytes = 3))

            val failure = assertFailsWith<VfsException> { harness.vfs.safeReplace(uri("/a.txt"), "four".toByteArray()) }

            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertTrue(disk.calls.isEmpty())
        }

    @Test
    fun `A04 route readonly structure directory and parent conflicts are rejected without target changes`() =
        runBlocking {
            val disk =
                safeDisk().also {
                    it.makeDirectory("dir")
                    it.withFile("parent", "old-parent")
                }
            val harness = harness(disk)

            val readonlyDisk = safeDisk()
            val readonlyHarness =
                harness(
                    readonlyDisk,
                    capabilityOverrides = mapOf("/resources" to StorageCapabilities(atomicReplace = true, readOnly = true)),
                )
            val readonlyFailure = assertFailsWith<VfsException> { readonlyHarness.vfs.safeReplace(uri("/a.txt"), "new".toByteArray()) }
            assertEquals(VfsErrorCode.READ_ONLY, readonlyFailure.code)
            assertEquals(VfsEffect.NONE, readonlyFailure.effect)
            assertTrue(readonlyDisk.calls.isEmpty())

            val cases =
                listOf(
                    Triple(VfsUri.parse("alcyone://elsewhere/a.txt"), VfsErrorCode.MOUNT_NOT_FOUND, "route"),
                    Triple(uri(""), VfsErrorCode.UNSUPPORTED_OPERATION, "namespace root"),
                    Triple(uri("/dir"), VfsErrorCode.TYPE_MISMATCH, "directory"),
                    Triple(uri("/parent/a.txt"), VfsErrorCode.TYPE_MISMATCH, "parent file"),
                )
            for ((target, code, label) in cases) {
                disk.calls.clear()
                val failure = assertFailsWith<VfsException>(label) { harness.vfs.safeReplace(target, "new".toByteArray()) }
                assertEquals(code, failure.code, label)
                assertEquals(VfsEffect.NONE, failure.effect, label)
                assertTrue(
                    disk.calls.none {
                        it.startsWith("safeReplace:") ||
                            it.startsWith("write:") ||
                            it.startsWith("move:") ||
                            it.startsWith("delete:")
                    },
                    label,
                )
            }
            assertEquals("old-parent", disk.readText("parent"))
        }

    @Test
    fun `A04 a registered non-file node is a CONFLICT before atomic replacement`() =
        runBlocking {
            val disk = safeDisk()
            val harness = harness(disk)
            harness.seedNode("/resources/a.txt", type = NodeType.DIRECTORY)

            val failure = assertFailsWith<VfsException> { harness.vfs.safeReplace(uri("/a.txt"), "new".toByteArray()) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertTrue(disk.calls.none { it.startsWith("safeReplace:") })
        }

    @Test
    fun `A01 atomic capability false is refused before any storage call`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(disk)

            val failure = assertFailsWith<VfsException> { harness.vfs.safeReplace(uri("/a.txt"), "new".toByteArray()) }

            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertTrue(disk.calls.isEmpty())
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `A04 parent creation failure keeps its code and reports PARTIAL`() =
        runBlocking {
            val disk = safeDisk()
            val harness = harness(disk)
            disk.failures.onCreateDirectoryAt = "a/b" to VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "denied")

            val failure = assertFailsWith<VfsException> { harness.vfs.safeReplace(uri("/a/b/c.txt"), "new".toByteArray()) }

            assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect)
            assertEquals(NodeType.DIRECTORY, disk.typeOfOrNull("a"))
            assertNull(disk.typeOfOrNull("a/b/c.txt"))
            assertTrue(disk.calls.none { it.startsWith("safeReplace:") })
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `A04 storage failure before commit leaves the old bytes and reports its effect`() =
        runBlocking {
            val disk = safeDisk().also { it.withFile("a.txt", "old") }
            val harness = harness(disk)
            disk.failures.onSafeReplace =
                VfsException(VfsErrorCode.STORAGE_ERROR, "connection lost before atomic replace", effect = VfsEffect.UNKNOWN)

            val failure = assertFailsWith<VfsException> { harness.vfs.safeReplace(uri("/a.txt"), "new".toByteArray()) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
            assertEquals(VfsEffect.UNKNOWN, failure.effect)
            assertEquals("old", disk.readText("a.txt"))
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `A04 state commit failure keeps the complete new file and reports STATE_ERROR PARTIAL`() =
        runBlocking {
            val disk = safeDisk().also { it.withFile("a.txt", "old") }
            val harness = harness(disk)
            harness.uow.failOnEventAppend = VfsException(VfsErrorCode.STATE_ERROR, "event log rejected the row")

            val failure = assertFailsWith<VfsException> { harness.vfs.safeReplace(uri("/a.txt"), "new".toByteArray()) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect)
            assertEquals("new", disk.readText("a.txt"))
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `A04 cancellation at the atomic boundary propagates and commits no state`() =
        runBlocking {
            val disk = safeDisk().also { it.withFile("a.txt", "old") }
            val harness = harness(disk)
            val entered = CompletableDeferred<Unit>()
            disk.onSafeReplace = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }

            val operation = async(start = CoroutineStart.UNDISPATCHED) { harness.vfs.safeReplace(uri("/a.txt"), "new".toByteArray()) }
            withTimeout(30_000) { entered.await() }
            operation.cancel()

            val failure = runCatching { withTimeout(30_000) { operation.await() } }.exceptionOrNull()
            assertTrue(failure is CancellationException, "CancellationException must escape unwrapped: $failure")
            assertEquals("old", disk.readText("a.txt"))
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }
}
