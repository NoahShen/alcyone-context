package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryMapRefreshCoordinatorTest {
    private val generatedAt = Instant.parse("2026-11-08T03:00:00Z")
    private val sourceUpdatedAt = Instant.parse("2026-11-08T02:59:00Z")

    @Test
    fun `CURRENT map skips generation and is confirmed from DB watermark`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-MAP-1", sourceUpdatedAt)
            fixture.vfs.safeReplace(MemoryMapPublisher.MAP_URI, mapText(generatedAt, sourceUpdatedAt).toByteArray())
            val tracker = MemoryMapStatusTracker()
            val generatorCalls = AtomicInteger(0)
            val coordinator =
                MemoryMapRefreshCoordinator(
                    fixture.database,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(
                        behavior = {
                            generatorCalls.incrementAndGet()
                            mapArtifact(generatedAt, sourceUpdatedAt)
                        },
                    ),
                    tracker,
                )
            coordinator.start()
            coordinator.activate()
            try {
                assertTrue(coordinator.requestRefresh())
                awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 1L }

                assertEquals(0, generatorCalls.get())
                assertEquals(MapStatus.CURRENT, tracker.snapshot().status)
                assertEquals(sourceUpdatedAt, tracker.snapshot().view?.sourceUpdatedAt)
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `MISSING map generates once then a second signal confirms CURRENT without another generation`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-MAP-2", sourceUpdatedAt)
            val tracker = MemoryMapStatusTracker()
            val generatorCalls = AtomicInteger(0)
            val coordinator =
                MemoryMapRefreshCoordinator(
                    fixture.database,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(
                        behavior = {
                            generatorCalls.incrementAndGet()
                            mapArtifact(generatedAt, sourceUpdatedAt)
                        },
                        publisher = MemoryMapPublisher(fixture.vfs),
                    ),
                    tracker,
                )
            coordinator.start()
            coordinator.activate()
            try {
                assertTrue(coordinator.requestRefresh())
                awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 1L }
                assertEquals(1, generatorCalls.get())
                assertEquals(MapStatus.STALE, tracker.snapshot().status)

                assertTrue(coordinator.requestRefresh())
                awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 2L }

                assertEquals(1, generatorCalls.get())
                assertEquals(MapStatus.CURRENT, tracker.snapshot().status)
                assertEquals(sourceUpdatedAt, tracker.snapshot().view?.sourceUpdatedAt)
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `broken map is UNKNOWN and a request generates a complete replacement`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-MAP-BROKEN", sourceUpdatedAt)
            fixture.vfs.safeReplace(MemoryMapPublisher.MAP_URI, "broken-map-secret".toByteArray())
            val tracker = MemoryMapStatusTracker()
            val calls = AtomicInteger(0)
            val coordinator =
                MemoryMapRefreshCoordinator(
                    fixture.database,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(
                        behavior = {
                            calls.incrementAndGet()
                            mapArtifact(generatedAt, sourceUpdatedAt)
                        },
                        publisher = MemoryMapPublisher(fixture.vfs),
                    ),
                    tracker,
                )
            coordinator.start()
            coordinator.activate()
            try {
                assertTrue(coordinator.requestRefresh())
                awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 1L }

                assertEquals(1, calls.get())
                assertEquals(MapStatus.STALE, tracker.snapshot().status)
                assertEquals(sourceUpdatedAt, tracker.snapshot().view?.sourceUpdatedAt)
                assertFalse(
                    tracker
                        .snapshot()
                        .view
                        ?.content
                        .orEmpty()
                        .contains("broken-map-secret"),
                )
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `conflated signals keep one worker and cause at most one follow-up generation`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-MAP-3", sourceUpdatedAt)
            val tracker = MemoryMapStatusTracker()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val coordinator =
                MemoryMapRefreshCoordinator(
                    fixture.database,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(
                        behavior = {
                            if (calls.incrementAndGet() == 1) {
                                entered.complete(Unit)
                                release.await()
                            }
                            mapArtifact(generatedAt, sourceUpdatedAt.minusSeconds(1))
                        },
                        publisher = MemoryMapPublisher(fixture.vfs),
                    ),
                    tracker,
                )
            coordinator.start()
            coordinator.activate()
            try {
                assertTrue(coordinator.requestRefresh())
                entered.await()
                repeat(20) { assertTrue(coordinator.requestRefresh()) }
                release.complete(Unit)

                awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 2L }
                delay(150)

                assertEquals(2, calls.get())
                assertEquals(1, coordinator.workerCreationCountForTesting())
                assertEquals(MapStatus.STALE, tracker.snapshot().status)
            } finally {
                release.complete(Unit)
                coordinator.closeAsync()
            }
        }

    @Test
    fun `generation failure restores the previous view and status and logs a sanitized warning`() =
        withMemoryMapFixture { fixture ->
            Slf4jCapture().use { capture ->
                fixture.insertOrganized("O-MAP-4", sourceUpdatedAt)
                val old = MemoryMapView("old map", generatedAt, sourceUpdatedAt.minusSeconds(1))
                val tracker = MemoryMapStatusTracker()
                tracker.update(old, MapStatus.STALE)
                val marker = "ct19-generation-secret-marker"
                val coordinator =
                    MemoryMapRefreshCoordinator(
                        fixture.database,
                        MemoryMapBootstrapReader(fixture.vfs, tracker),
                        service(behavior = { throw IllegalStateException(marker) }),
                        tracker,
                        LoggerFactory.getLogger(MemoryMapRefreshCoordinator::class.java),
                    )
                coordinator.start()
                coordinator.activate()
                try {
                    assertTrue(coordinator.requestRefresh())
                    awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 1L }

                    assertEquals(MemoryMapState(old, MapStatus.STALE), tracker.snapshot())
                    assertEquals(MemoryMapWorkerStatus.FAILED, coordinator.statusForTesting())
                    val log = capture.logs.single { it.formattedMessage.contains("memoryMap.refresh.failed") }
                    assertTrue(log.throwable == null)
                    assertFalse(log.formattedMessage.contains(marker))
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `safeReplace failure never fakes CURRENT and preserves the previous view and status`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-MAP-PUBLISH-FAIL", sourceUpdatedAt)
            val old = MemoryMapView("old map", generatedAt, sourceUpdatedAt.minusSeconds(1))
            val tracker = MemoryMapStatusTracker()
            tracker.update(old, MapStatus.STALE)
            val publisherFailure =
                VfsException(
                    VfsErrorCode.STATE_ERROR,
                    "ct19-safe-replace-failure",
                    effect = VfsEffect.PARTIAL,
                )
            val coordinator =
                MemoryMapRefreshCoordinator(
                    fixture.database,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(
                        behavior = { mapArtifact(generatedAt, sourceUpdatedAt) },
                        publisher = MemoryMapPublisherPort { throw publisherFailure },
                    ),
                    tracker,
                )
            coordinator.start()
            coordinator.activate()
            try {
                assertTrue(coordinator.requestRefresh())
                awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 1L }

                assertEquals(MemoryMapState(old, MapStatus.STALE), tracker.snapshot())
                assertEquals(MemoryMapWorkerStatus.FAILED, coordinator.statusForTesting())
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `database watermark failure marks UNKNOWN retains a valid view and never generates`() =
        withMemoryMapFixture { fixture ->
            fixture.vfs.safeReplace(MemoryMapPublisher.MAP_URI, mapText(generatedAt, sourceUpdatedAt).toByteArray())
            val tracker = MemoryMapStatusTracker()
            val calls = AtomicInteger(0)
            val tracking = TrackingMemoryMapDatabase(fixture.database, readFailures = 1)
            val coordinator =
                MemoryMapRefreshCoordinator(
                    tracking,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(behavior = {
                        calls.incrementAndGet()
                        mapArtifact(generatedAt, sourceUpdatedAt)
                    }),
                    tracker,
                )
            coordinator.start()
            coordinator.activate()
            try {
                assertTrue(coordinator.requestRefresh())
                awaitMemoryMapUntil { coordinator.processedRoundsForTesting() == 1L }

                assertEquals(0, calls.get())
                assertEquals(MapStatus.UNKNOWN, tracker.snapshot().status)
                assertEquals(sourceUpdatedAt, tracker.snapshot().view?.sourceUpdatedAt)
                assertEquals(1, tracking.attempts.get())
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `beginClosing rejects requests and never creates another worker`() =
        withMemoryMapFixture { fixture ->
            val tracker = MemoryMapStatusTracker()
            val coordinator =
                MemoryMapRefreshCoordinator(
                    fixture.database,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(behavior = { mapArtifact(generatedAt, sourceUpdatedAt) }),
                    tracker,
                )
            coordinator.start()
            coordinator.beginClosing()

            assertFalse(coordinator.requestRefresh())
            assertEquals(1, coordinator.workerCreationCountForTesting())
            coordinator.closeAsync()
            assertEquals("CLOSED", coordinator.stateForTesting())
        }

    @Test
    fun `CancellationException from generation propagates unchanged`() =
        withMemoryMapFixture { fixture ->
            val cancellation = CancellationException("ct19-cancellation")
            val tracker = MemoryMapStatusTracker()
            val coordinator =
                MemoryMapRefreshCoordinator(
                    fixture.database,
                    MemoryMapBootstrapReader(fixture.vfs, tracker),
                    service(behavior = { throw cancellation }),
                    tracker,
                )

            val actual = assertFailsWith<CancellationException> { coordinator.processOnceForTesting() }

            assertSame(cancellation, actual)
            coordinator.closeAsync()
        }
}
