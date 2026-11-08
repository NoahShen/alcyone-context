package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryMapPipelineTest {
    private val generatedAt = Instant.parse("2026-11-08T03:00:00Z")
    private val sourceUpdatedAt = Instant.parse("2026-11-08T02:59:00Z")

    @Test
    fun `start check waits at activation and performs no generation before RUNNING`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-PIPE-1", sourceUpdatedAt)
            val calls = AtomicInteger(0)
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = false),
                    fixture.database,
                    fixture.vfs,
                    service(
                        behavior = {
                            calls.incrementAndGet()
                            mapArtifact(generatedAt, sourceUpdatedAt)
                        },
                        publisher = MemoryMapPublisher(fixture.vfs),
                    ),
                )
            pipeline.start()
            try {
                delay(150)
                assertEquals(0, calls.get())
                assertFalse(pipeline.isClosingForTesting())

                pipeline.activate()
                awaitMemoryMapUntil { calls.get() == 1 }
            } finally {
                pipeline.closeAsync()
            }
        }

    @Test
    fun `beginClosing before activation rejects queued refresh and performs no generation`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-PIPE-2", sourceUpdatedAt)
            val calls = AtomicInteger(0)
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = false),
                    fixture.database,
                    fixture.vfs,
                    service(behavior = {
                        calls.incrementAndGet()
                        mapArtifact(generatedAt, sourceUpdatedAt)
                    }),
                )
            pipeline.start()
            pipeline.beginClosing()
            pipeline.activate()
            delay(200)

            assertEquals(0, calls.get())
            assertFalse(pipeline.coordinatorForTesting().requestRefresh())
            assertTrue(pipeline.isClosingForTesting())
            pipeline.closeAsync()
        }

    @Test
    fun `close stops cron before coordinator and both close hooks run`() =
        withMemoryMapFixture { fixture ->
            val ticks = ManualMemoryMapTicks()
            val order = CopyOnWriteArrayList<String>()
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = true),
                    fixture.database,
                    fixture.vfs,
                    service(behavior = { mapArtifact(generatedAt, sourceUpdatedAt) }),
                    tickSource = ticks,
                )
            pipeline.start()
            val cron = requireNotNull(pipeline.periodicCronForTesting())
            cron.closeActionForTesting = { order += "cron" }
            pipeline.coordinatorForTesting().closeActionForTesting = { order += "coordinator" }

            pipeline.closeAsync()

            assertEquals(listOf("cron", "coordinator"), order)
            assertEquals("CLOSED", cron.stateForTesting())
            assertEquals("CLOSED", pipeline.coordinatorForTesting().stateForTesting())
            ticks.close()
        }

    @Test
    fun `close cancels and joins an in flight map generation`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-PIPE-3", sourceUpdatedAt)
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = false),
                    fixture.database,
                    fixture.vfs,
                    service(
                        behavior = {
                            try {
                                entered.countDown()
                                awaitCancellation()
                            } finally {
                                finished.countDown()
                            }
                        },
                    ),
                )
            pipeline.start()
            pipeline.activate()
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            withTimeout(10_000L) { pipeline.closeAsync() }

            assertEquals(0L, finished.count)
            assertEquals(MemoryMapWorkerStatus.STOPPED, pipeline.coordinatorForTesting().statusForTesting())
        }

    @Test
    fun `close failures are shared across concurrent waiters in cron then coordinator order`() =
        withMemoryMapFixture { fixture ->
            val ticks = ManualMemoryMapTicks()
            val cronFailure = IllegalStateException("ct19-cron-close-failure")
            val coordinatorFailure = IllegalStateException("ct19-coordinator-close-failure")
            val pipelineFailure = IllegalStateException("ct19-pipeline-close-failure")
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = true),
                    fixture.database,
                    fixture.vfs,
                    service(behavior = { mapArtifact(generatedAt, sourceUpdatedAt) }),
                    tickSource = ticks,
                )
            pipeline.start()
            requireNotNull(pipeline.periodicCronForTesting()).closeActionForTesting = { throw cronFailure }
            pipeline.coordinatorForTesting().closeActionForTesting = { throw coordinatorFailure }
            pipeline.closeFailureForTesting = pipelineFailure

            val first = assertFailsWith<IllegalStateException> { pipeline.closeAsync() }
            val second = assertFailsWith<IllegalStateException> { pipeline.closeAsync() }

            assertSame(cronFailure, first)
            assertSame(cronFailure, second)
            assertTrue(first.suppressed.any { it === coordinatorFailure })
            assertTrue(first.suppressed.any { it === pipelineFailure })
            ticks.close()
        }

    @Test
    fun `pipeline snapshot is a read-only tracker snapshot`() =
        withMemoryMapFixture { fixture ->
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = false),
                    fixture.database,
                    fixture.vfs,
                    service(behavior = { mapArtifact(generatedAt, sourceUpdatedAt) }),
                )
            val view = MemoryMapView("cached map", generatedAt, sourceUpdatedAt)
            pipeline.trackerForTesting().update(view, MapStatus.STALE)

            assertEquals(MemoryMapState(view, MapStatus.STALE), pipeline.snapshot())
            assertEquals(MemoryMapWorkerStatus.IDLE, pipeline.coordinatorForTesting().statusForTesting())
        }

    @Test
    fun `snapshot returns the cached value immediately while generation is in flight`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-PIPE-SNAPSHOT", sourceUpdatedAt)
            val publishedSource = sourceUpdatedAt.minusSeconds(1)
            val publishedText = mapText(generatedAt, publishedSource)
            fixture.vfs.safeReplace(MemoryMapPublisher.MAP_URI, publishedText.toByteArray())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = false),
                    fixture.database,
                    fixture.vfs,
                    service(
                        behavior = {
                            entered.complete(Unit)
                            release.await()
                            mapArtifact(generatedAt, sourceUpdatedAt)
                        },
                    ),
                )
            val cached = MemoryMapView("cached map", generatedAt, sourceUpdatedAt.minusSeconds(1))
            pipeline.trackerForTesting().update(cached, MapStatus.STALE)
            pipeline.start()
            pipeline.activate()
            try {
                entered.await()
                withTimeout(500L) {
                    assertEquals(
                        MemoryMapState(
                            MemoryMapView(publishedText, generatedAt, publishedSource),
                            MapStatus.UNKNOWN,
                        ),
                        pipeline.snapshot(),
                    )
                }
            } finally {
                release.complete(Unit)
                pipeline.closeAsync()
            }
        }

    @Test
    fun `close before start is safe and start after close is rejected`() =
        withMemoryMapFixture { fixture ->
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = false),
                    fixture.database,
                    fixture.vfs,
                    service(behavior = { mapArtifact(generatedAt, sourceUpdatedAt) }),
                )

            withTimeout(5_000L) { pipeline.closeAsync() }
            val failure = assertFailsWith<ContextLifecycleException> { pipeline.start() }

            assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
            assertEquals(MemoryMapWorkerStatus.STOPPED, pipeline.coordinatorForTesting().statusForTesting())
        }

    @Test
    fun `periodic disabled keeps startup refresh available`() =
        withMemoryMapFixture { fixture ->
            fixture.insertOrganized("O-PIPE-4", sourceUpdatedAt)
            val calls = AtomicInteger(0)
            val pipeline =
                MemoryMapPipeline(
                    config(periodic = false),
                    fixture.database,
                    fixture.vfs,
                    service(
                        behavior = {
                            calls.incrementAndGet()
                            mapArtifact(generatedAt, sourceUpdatedAt)
                        },
                        publisher = MemoryMapPublisher(fixture.vfs),
                    ),
                )
            assertEquals(null, pipeline.periodicCronForTesting())
            pipeline.start()
            try {
                pipeline.activate()
                awaitMemoryMapUntil { calls.get() == 1 }
            } finally {
                pipeline.closeAsync()
            }
        }

    private fun config(periodic: Boolean): MemoryMapConfig = MemoryMapConfig(periodic = MemoryMapPeriodicConfig(enabled = periodic))
}
