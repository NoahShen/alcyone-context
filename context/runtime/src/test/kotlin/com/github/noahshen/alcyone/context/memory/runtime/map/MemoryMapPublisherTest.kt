package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapArtifact
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapDocument
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeInfo
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.StorageStat
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryMapPublisherTest {
    private val generatedAt: Instant = Instant.parse("2026-11-08T03:00:00Z")

    @Test
    fun `LocalFs publisher creates and replaces the fixed map URI through safeReplace only`() =
        ContextFixture().use { fixture ->
            val vfsConfig =
                fixture.vfsConfig(
                    namespaces = setOf("memory"),
                    mounts = listOf(fixture.localMount(vfsPath = "/memory", storageKey = "memory-map")),
                )
            val vfs = runBlocking { AlcyoneVfs.create(vfsConfig) }
            val guarded = GuardedVfs(vfs)
            try {
                val publisher = MemoryMapPublisher(guarded)
                val first = "first map\n".toByteArray()
                val second = "second map\n".toByteArray()

                val created = runBlocking { publisher.publish(first) }
                assertEquals(MemoryMapPublisher.MAP_URI, created.uri)
                assertContentEquals(first, Files.readAllBytes(fixture.mountRoot.resolve("map.md")))
                val replaced = runBlocking { publisher.publish(second) }
                assertEquals(MemoryMapPublisher.MAP_URI, replaced.uri)
                assertContentEquals(second, Files.readAllBytes(fixture.mountRoot.resolve("map.md")))
                assertContentEquals(second, runBlocking { vfs.read(MemoryMapPublisher.MAP_URI) })

                assertEquals(2, guarded.safeReplaceCalls.get())
                assertEquals(0, guarded.writeCalls.get())
                assertEquals(0, guarded.moveCalls.get())
                assertEquals(0, guarded.deleteCalls.get())
            } finally {
                runBlocking { vfs.close() }
            }
        }

    @Test
    fun `precommit failure preserves the previous map and publisher performs no downgrade`() =
        ContextFixture().use { fixture ->
            val vfsConfig =
                fixture.vfsConfig(
                    namespaces = setOf("memory"),
                    mounts = listOf(fixture.localMount(vfsPath = "/memory", storageKey = "memory-map")),
                )
            val vfs = runBlocking { AlcyoneVfs.create(vfsConfig) }
            try {
                val old = "old complete map\n".toByteArray()
                runBlocking { MemoryMapPublisher(vfs).publish(old) }
                val expected = VfsException(VfsErrorCode.STORAGE_ERROR, "precommit failed")
                val failing = GuardedVfs(vfs, safeReplaceFailure = expected)

                val actual = assertFailsWith<VfsException> { runBlocking { MemoryMapPublisher(failing).publish("new".toByteArray()) } }

                assertSame(expected, actual)
                assertContentEquals(old, Files.readAllBytes(fixture.mountRoot.resolve("map.md")))
                assertEquals(1, failing.safeReplaceCalls.get())
                assertEquals(0, failing.writeCalls.get())
                assertEquals(0, failing.moveCalls.get())
                assertEquals(0, failing.deleteCalls.get())
            } finally {
                runBlocking { vfs.close() }
            }
        }

    @Test
    fun `STATE_ERROR PARTIAL is propagated unchanged`() =
        ContextFixture().use { fixture ->
            val vfsConfig =
                fixture.vfsConfig(
                    namespaces = setOf("memory"),
                    mounts = listOf(fixture.localMount(vfsPath = "/memory", storageKey = "memory-map")),
                )
            val vfs = runBlocking { AlcyoneVfs.create(vfsConfig) }
            try {
                val expected =
                    VfsException(
                        VfsErrorCode.STATE_ERROR,
                        "state commit failed",
                        uri = MemoryMapPublisher.MAP_URI,
                        effect = VfsEffect.PARTIAL,
                    )
                val failing = GuardedVfs(vfs, safeReplaceFailure = expected)

                val actual = assertFailsWith<VfsException> { runBlocking { MemoryMapPublisher(failing).publish("complete".toByteArray()) } }

                assertSame(expected, actual)
                assertEquals(VfsErrorCode.STATE_ERROR, actual.code)
                assertEquals(VfsEffect.PARTIAL, actual.effect)
            } finally {
                runBlocking { vfs.close() }
            }
        }

    @Test
    fun `VFS write limit is enforced without truncating or replacing the old map`() =
        ContextFixture().use { fixture ->
            val vfsConfig =
                fixture
                    .vfsConfig(
                        namespaces = setOf("memory"),
                        mounts = listOf(fixture.localMount(vfsPath = "/memory", storageKey = "memory-map")),
                    ).copy(limits = VfsLimits(defaultReadMaxBytes = 64, defaultWriteMaxBytes = 4))
            val vfs = runBlocking { AlcyoneVfs.create(vfsConfig) }
            try {
                val publisher = MemoryMapPublisher(vfs)
                val old = byteArrayOf(1, 2, 3, 4)
                runBlocking { publisher.publish(old) }

                val failure = assertFailsWith<VfsException> { runBlocking { publisher.publish(byteArrayOf(1, 2, 3, 4, 5)) } }

                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)
                assertContentEquals(old, Files.readAllBytes(fixture.mountRoot.resolve("map.md")))
            } finally {
                runBlocking { vfs.close() }
            }
        }

    @Test
    fun `service does not publish when generation fails`() =
        runBlocking {
            val failure = IllegalStateException("generation failed")
            val publisher = RecordingPublisher(generatedAt)
            val service = MemoryMapService(MemoryMapGeneratorPort { throw failure }, publisher)

            val actual = assertFailsWith<IllegalStateException> { service.generateAndPublish() }

            assertSame(failure, actual)
            assertEquals(0, publisher.calls.get())
        }

    @Test
    fun `service publishes the exact rendered bytes only after successful generation`() =
        runBlocking {
            val bytes = "rendered map".toByteArray()
            val artifact = MemoryMapArtifact(document(), bytes)
            val publisher = RecordingPublisher(generatedAt)
            val service = MemoryMapService(MemoryMapGeneratorPort { artifact }, publisher)

            val returned = service.generateAndPublish()

            assertSame(artifact, returned)
            assertEquals(1, publisher.calls.get())
            assertTrue(bytes.contentEquals(publisher.bytes.single()))
        }

    private fun document(): MemoryMapDocument =
        MemoryMapDocument(
            overview = "overview",
            navigation = "navigation",
            domains = emptyList(),
            generatedAt = generatedAt,
            sourceUpdatedAt = null,
        )

    private class GuardedVfs(
        private val delegate: Vfs,
        private val safeReplaceFailure: Throwable? = null,
    ) : Vfs by delegate {
        val safeReplaceCalls = AtomicInteger(0)
        val writeCalls = AtomicInteger(0)
        val moveCalls = AtomicInteger(0)
        val deleteCalls = AtomicInteger(0)

        override suspend fun safeReplace(
            uri: VfsUri,
            content: ByteArray,
        ): NodeInfo {
            safeReplaceCalls.incrementAndGet()
            safeReplaceFailure?.let { throw it }
            return delegate.safeReplace(uri, content)
        }

        override suspend fun write(
            uri: VfsUri,
            content: ByteArray,
            options: WriteOptions,
        ): NodeInfo {
            writeCalls.incrementAndGet()
            return delegate.write(uri, content, options)
        }

        override suspend fun move(
            source: VfsUri,
            target: VfsUri,
        ): NodeInfo {
            moveCalls.incrementAndGet()
            return delegate.move(source, target)
        }

        override suspend fun delete(
            uri: VfsUri,
            options: DeleteOptions,
        ) {
            deleteCalls.incrementAndGet()
            delegate.delete(uri, options)
        }
    }

    private class RecordingPublisher(
        private val timestamp: Instant,
    ) : MemoryMapPublisherPort {
        val calls = AtomicInteger(0)
        val bytes: MutableList<ByteArray> = mutableListOf()

        override suspend fun publish(renderedBytes: ByteArray): NodeInfo {
            calls.incrementAndGet()
            bytes += renderedBytes
            return NodeInfo(
                id = NodeId.parse("018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8092"),
                uri = MemoryMapPublisher.MAP_URI,
                type = NodeType.FILE,
                registeredAt = timestamp,
                updatedAt = timestamp,
                storage = StorageStat(renderedBytes.size.toLong(), timestamp),
            )
        }
    }
}
