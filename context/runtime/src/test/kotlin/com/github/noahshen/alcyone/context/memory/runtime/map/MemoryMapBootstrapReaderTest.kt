package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryMapBootstrapReaderTest {
    private val generatedAt = Instant.parse("2026-11-08T03:00:00Z")
    private val sourceUpdatedAt = Instant.parse("2026-11-08T02:59:00Z")

    @Test
    fun `reader loads the fixed LocalFs map through the strict CT18 parser`() =
        withMemoryMapFixture { fixture ->
            val text = mapText(generatedAt, sourceUpdatedAt)
            fixture.vfs.safeReplace(MemoryMapPublisher.MAP_URI, text.toByteArray())
            val tracker = MemoryMapStatusTracker()
            val reader = MemoryMapBootstrapReader(fixture.vfs, tracker)

            val result = reader.refresh()

            assertTrue(result.reliable)
            assertEquals(MapStatus.UNKNOWN, result.state.status)
            assertEquals(text, result.state.view?.content)
            assertEquals(generatedAt, result.state.view?.generatedAt)
            assertEquals(sourceUpdatedAt, result.state.view?.sourceUpdatedAt)
            assertEquals(result.state, reader.snapshot())
        }

    @Test
    fun `NOT_FOUND is missing and clears any previous view`() =
        withMemoryMapFixture { fixture ->
            val tracker = MemoryMapStatusTracker()
            tracker.update(MemoryMapView("old", generatedAt, sourceUpdatedAt), MapStatus.STALE)
            val reader = MemoryMapBootstrapReader(fixture.vfs, tracker)

            val result = reader.refresh()

            assertTrue(result.reliable)
            assertEquals(MapStatus.MISSING, result.state.status)
            assertNull(result.state.view)
        }

    @Test
    fun `broken map is UNKNOWN and never exposes the broken bytes`() =
        withMemoryMapFixture { fixture ->
            fixture.vfs.safeReplace(MemoryMapPublisher.MAP_URI, "ct19-broken-map-marker".toByteArray())
            val old = MemoryMapView("old valid map", generatedAt, sourceUpdatedAt)
            val tracker = MemoryMapStatusTracker()
            tracker.update(old, MapStatus.STALE)
            val reader = MemoryMapBootstrapReader(fixture.vfs, tracker)

            val result = reader.refresh()

            assertFalse(result.reliable)
            assertEquals(MapStatus.UNKNOWN, result.state.status)
            assertEquals(old, result.state.view)
            assertFalse(
                result.state.view
                    ?.content
                    .orEmpty()
                    .contains("ct19-broken-map-marker"),
            )
        }

    @Test
    fun `VFS read failure is UNKNOWN preserves old view and logs without throwable or marker`() =
        withMemoryMapFixture { fixture ->
            Slf4jCapture().use { capture ->
                val marker = "ct19-vfs-secret-marker"
                val failing = ReadFailingVfs(fixture.vfs, VfsException(VfsErrorCode.STORAGE_ERROR, marker))
                val old = MemoryMapView("old valid map", generatedAt, sourceUpdatedAt)
                val tracker = MemoryMapStatusTracker()
                tracker.update(old, MapStatus.CURRENT)
                val reader =
                    MemoryMapBootstrapReader(
                        failing,
                        tracker,
                        LoggerFactory.getLogger(MemoryMapBootstrapReader::class.java),
                    )

                val result = reader.refresh()

                assertFalse(result.reliable)
                assertEquals(MapStatus.UNKNOWN, result.state.status)
                assertEquals(old, result.state.view)
                assertEquals(1, failing.reads.get())
                val log = capture.logs.single { it.formattedMessage.contains("memoryMap.bootstrap.read.failed") }
                assertEquals(Level.WARN, log.level)
                assertNull(log.throwable)
                assertFalse(log.formattedMessage.contains(marker))
            }
        }

    private class ReadFailingVfs(
        delegate: Vfs,
        private val failure: Throwable,
    ) : Vfs by delegate {
        val reads = AtomicInteger(0)

        override suspend fun read(
            uri: VfsUri,
            options: com.github.noahshen.alcyone.context.vfs.ReadOptions,
        ): ByteArray {
            reads.incrementAndGet()
            throw failure
        }
    }
}
