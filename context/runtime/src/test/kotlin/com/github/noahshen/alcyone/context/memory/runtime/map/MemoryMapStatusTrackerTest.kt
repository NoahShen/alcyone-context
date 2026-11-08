package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryMapStatusTrackerTest {
    private val generatedAt = Instant.parse("2026-11-08T03:00:00Z")

    @Test
    fun `tracker starts unknown without a map view`() {
        val snapshot = MemoryMapStatusTracker().snapshot()
        assertEquals(MapStatus.UNKNOWN, snapshot.status)
        assertNull(snapshot.view)
    }

    @Test
    fun `tracker stores all four statuses and their view rules`() {
        val tracker = MemoryMapStatusTracker()
        val view = view("current", generatedAt)

        tracker.update(view, MapStatus.CURRENT)
        assertEquals(MemoryMapState(view, MapStatus.CURRENT), tracker.snapshot())

        tracker.update(view, MapStatus.STALE)
        assertEquals(MemoryMapState(view, MapStatus.STALE), tracker.snapshot())

        tracker.markUnknown()
        assertEquals(MemoryMapState(view, MapStatus.UNKNOWN), tracker.snapshot())

        tracker.markMissing()
        assertEquals(MemoryMapState(null, MapStatus.MISSING), tracker.snapshot())
    }

    @Test
    fun `restore replaces the complete view and status atomically`() {
        val tracker = MemoryMapStatusTracker()
        val first = view("first", generatedAt)
        val second = view("second", generatedAt.plusSeconds(1))
        tracker.update(first, MapStatus.STALE)
        val saved = tracker.snapshot()

        tracker.update(second, MapStatus.CURRENT)
        tracker.restore(saved)

        assertEquals(saved, tracker.snapshot())
    }

    @Test
    fun `concurrent snapshots never observe a mixed view and status pair`() =
        runBlocking {
            val tracker = MemoryMapStatusTracker()
            val states =
                listOf(
                    MemoryMapState(view("first", generatedAt), MapStatus.STALE),
                    MemoryMapState(view("second", generatedAt.plusSeconds(1)), MapStatus.CURRENT),
                )
            val writer =
                async(Dispatchers.Default) {
                    repeat(2_000) { index ->
                        val state = states[index % states.size]
                        tracker.update(state.view, state.status)
                    }
                }
            val readers =
                List(8) {
                    async(Dispatchers.Default) {
                        repeat(2_000) {
                            val snapshot = tracker.snapshot()
                            assertTrue(snapshot in states)
                        }
                    }
                }

            (readers + writer).awaitAll()
            Unit
        }

    private fun view(
        content: String,
        generatedAt: Instant,
    ): MemoryMapView = MemoryMapView(content, generatedAt, generatedAt.minusSeconds(10))
}
