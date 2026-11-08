package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BootstrapContextTest {
    private val generatedAt = Instant.parse("2026-11-08T03:00:00Z")
    private val sourceUpdatedAt = Instant.parse("2026-11-08T02:59:00Z")

    @Test
    fun `memory map view and bootstrap context preserve all public fields`() {
        val view = MemoryMapView("map content", generatedAt, sourceUpdatedAt)
        val bootstrap = BootstrapContext(view, MapStatus.CURRENT, BootstrapContext.DEFAULT_CAPABILITIES)

        assertEquals("map content", bootstrap.memoryMap?.content)
        assertEquals(generatedAt, bootstrap.memoryMap?.generatedAt)
        assertEquals(sourceUpdatedAt, bootstrap.memoryMap?.sourceUpdatedAt)
        assertEquals(MapStatus.CURRENT, bootstrap.mapStatus)
        assertEquals(BootstrapContext.DEFAULT_CAPABILITIES, bootstrap.capabilities)
    }

    @Test
    fun `map status exposes exactly the four frozen states`() {
        assertEquals(
            listOf("CURRENT", "STALE", "MISSING", "UNKNOWN"),
            MapStatus.entries.map { it.name },
        )
    }

    @Test
    fun `bootstrap supports missing map and null source watermark`() {
        val empty = MemoryMapView("empty map", generatedAt, null)
        assertNull(empty.sourceUpdatedAt)
        assertNull(BootstrapContext(null, MapStatus.MISSING, "capabilities").memoryMap)
    }

    @Test
    fun `public values are immutable and blank map content is rejected`() {
        val view = MemoryMapView("map", generatedAt, sourceUpdatedAt)
        val copied = view.copy(content = "copy")
        assertEquals("map", view.content)
        assertEquals("copy", copied.content)
        assertFailsWith<IllegalArgumentException> { MemoryMapView("   ", generatedAt, null) }
    }
}
