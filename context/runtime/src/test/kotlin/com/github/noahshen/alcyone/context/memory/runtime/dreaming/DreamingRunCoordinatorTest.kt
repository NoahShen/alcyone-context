package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextDreamingErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextDreamingException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.DreamingResult
import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import com.github.noahshen.alcyone.context.memory.api.DreamingRunStage
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DreamingRunCoordinatorTest {
    private val base = Instant.parse("2026-11-09T07:00:00Z")

    @Test
    fun `empty table and checkpoint equal U return completed zero work`() =
        withDreamingDatabase { fixture ->
            val processor = RecordingDreamingProcessor()
            val coordinator = coordinator(fixture.database, processor, pageSize = 2)
            try {
                assertEquals(DreamingResult(0, 0, 0, 0, 0, true), coordinator.runOnce())
                insertDreamingTurn(fixture.database, sessionTurnId = "one")
                setDreamingCheckpoint(fixture.database, 1)
                assertEquals(DreamingResult(1, 1, 0, 0, 0, true), coordinator.runOnce())
                assertTrue(processor.calls.isEmpty())
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `fixed range reads all statuses in id ascending and excludes turns above U`() =
        withDreamingDatabase { fixture ->
            listOf(
                Triple("one", ExtractionStatus.PENDING, 1),
                Triple("two", ExtractionStatus.COMPLETE, 2),
                Triple("three", ExtractionStatus.FAILED, 3),
            ).forEach { (turn, status, second) ->
                insertDreamingTurn(fixture.database, sessionTurnId = turn, timestamp = base.plusSeconds(second.toLong()), status = status)
            }
            val processor = RecordingDreamingProcessor()
            val tracking = TrackingDreamingDatabase(fixture.database)
            val coordinator = coordinator(tracking, processor, pageSize = 1, budget = CountingDreamingBudget(0, 1, 1))
            try {
                val result = coordinator.runOnce()
                assertEquals(3, result.scannedTurns)
                assertEquals(3L, result.endCheckpoint)
                assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), processor.calls)
                assertTrue(tracking.rangeCalls.all { (_, upper) -> upper == 3L })
                assertEquals(1, tracking.checkpointReads.get())
                assertEquals(1, tracking.maxTurnReads.get())
                assertEquals(1, tracking.readsWithBothRangeFields.get())
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `new turn after fixed U is excluded and next run processes it`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "first")
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val processor =
                    RecordingDreamingProcessor { batch, _ ->
                        entered.complete(Unit)
                        release.await()
                        DreamingBatchOutcome(batch.last().id, 1, 1)
                    }
                val coordinator = coordinator(fixture.database, processor)
                try {
                    val firstRun = async(Dispatchers.Default) { coordinator.runOnce() }
                    entered.await()
                    insertDreamingTurn(fixture.database, sessionTurnId = "second", timestamp = base.plusSeconds(1))
                    release.complete(Unit)
                    val first = firstRun.await()
                    assertEquals(1L, first.endCheckpoint)
                    assertEquals(listOf(listOf(1L)), processor.calls)

                    // CT22 不写 checkpoint；模拟 CT25 已提交第一轮水位后验证下一轮固定增量。
                    setDreamingCheckpoint(fixture.database, 1)
                    val second = coordinator.runOnce()
                    assertEquals(1L, second.startCheckpoint)
                    assertEquals(2L, second.endCheckpoint)
                    assertEquals(listOf(listOf(1L), listOf(2L)), processor.calls)
                } finally {
                    release.complete(Unit)
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `checkpoint greater than U and negative checkpoint are fixed range failures`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "first")
            setDreamingCheckpoint(fixture.database, 1)
            val processor = RecordingDreamingProcessor()
            setDreamingCheckpoint(fixture.database, 2)
            val greaterThanU = coordinator(fixture.database, processor)
            try {
                val failure = assertFailsWith<DreamingRunException> { greaterThanU.runOnce() }
                assertEquals(DreamingRunStage.FIXED_RANGE, failure.stage)
                assertFalse(failure.partialResult.completed)
                assertTrue(processor.calls.isEmpty())
            } finally {
                greaterThanU.closeAsync()
            }

            val negativeCheckpoint = TrackingDreamingDatabase(fixture.database, checkpointOverride = -1L)
            val negativeCoordinator = coordinator(negativeCheckpoint, processor)
            try {
                val failure = assertFailsWith<DreamingRunException> { negativeCoordinator.runOnce() }
                assertEquals(DreamingRunStage.FIXED_RANGE, failure.stage)
                assertFalse(failure.partialResult.completed)
                assertTrue(processor.calls.isEmpty())
            } finally {
                negativeCoordinator.closeAsync()
            }
        }

    @Test
    fun `cross page streaming assembler exhausts range without losing rows`() =
        withDreamingDatabase { fixture ->
            repeat(
                5,
            ) { index -> insertDreamingTurn(fixture.database, sessionTurnId = "turn-$index", timestamp = base.plusSeconds(index.toLong())) }
            val processor = RecordingDreamingProcessor()
            val coordinator = coordinator(fixture.database, processor, pageSize = 2, budget = CountingDreamingBudget(0, 1, 100))
            try {
                val result = coordinator.runOnce()
                assertEquals(5, result.scannedTurns)
                assertEquals(listOf(1L, 2L, 3L, 4L, 5L), processor.calls.single())
                assertEquals(5L, result.endCheckpoint)
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `cross page assembler uses prior page end as next lower bound for all page sizes`() =
        withDreamingDatabase { fixture ->
            repeat(
                7,
            ) { index -> insertDreamingTurn(fixture.database, sessionTurnId = "turn-$index", timestamp = base.plusSeconds(index.toLong())) }
            listOf(1, 2, 3).forEach { pageSize ->
                val tracking = TrackingDreamingDatabase(fixture.database)
                val processor = RecordingDreamingProcessor()
                val coordinator = coordinator(tracking, processor, pageSize = pageSize, budget = CountingDreamingBudget(0, 0, 0))
                try {
                    val result = coordinator.runOnce()
                    assertEquals(7, result.scannedTurns)
                    assertEquals((1L..7L).toList(), processor.calls.single())
                    assertEquals(0L, tracking.rangeCalls.first().first)
                    assertEquals(7L, tracking.rangeCalls.first().second)
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `id gaps preserve range and processor batches aggregate counters`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "one")
            insertDreamingTurn(fixture.database, sessionTurnId = "two")
            insertDreamingTurn(fixture.database, sessionTurnId = "three")
            fixture.deleteTurn(2)
            val processor = RecordingDreamingProcessor()
            val coordinator = coordinator(fixture.database, processor, pageSize = 2, budget = CountingDreamingBudget(0, 1, 1))
            try {
                val result = coordinator.runOnce()
                assertEquals(2, result.scannedTurns)
                assertEquals(2, result.discoveredTopics)
                assertEquals(2, result.generatedMemories)
                assertEquals(listOf(listOf(1L), listOf(3L)), processor.calls)
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `fixed overhead and single turn over budget fail before processor`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "one")
            listOf(CountingDreamingBudget(101, 0, 100), CountingDreamingBudget(10, 100, 100)).forEach { budget ->
                val processor = RecordingDreamingProcessor()
                val coordinator = coordinator(fixture.database, processor, budget = budget)
                try {
                    val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }
                    assertEquals(DreamingRunStage.BUDGET, failure.stage)
                    assertTrue(processor.calls.isEmpty())
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `budget equality is accepted`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "one")
            val processor = RecordingDreamingProcessor()
            val coordinator = coordinator(fixture.database, processor, budget = CountingDreamingBudget(99, 1, 100))
            try {
                assertEquals(1L, coordinator.runOnce().endCheckpoint)
                assertEquals(1, processor.calls.size)
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `processor serial order and invalid last id fail without later calls`() =
        withDreamingDatabase { fixture ->
            repeat(
                3,
            ) { index -> insertDreamingTurn(fixture.database, sessionTurnId = "turn-$index", timestamp = base.plusSeconds(index.toLong())) }
            val processor =
                RecordingDreamingProcessor { batch, call ->
                    if (call == 2) DreamingBatchOutcome(batch.last().id + 1, 1, 1) else DreamingBatchOutcome(batch.last().id, 1, 1)
                }
            val coordinator = coordinator(fixture.database, processor, pageSize = 1, budget = CountingDreamingBudget(0, 1, 1))
            try {
                val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }
                assertEquals(DreamingRunStage.BATCH_PROCESSING, failure.stage)
                assertEquals(2, processor.calls.size)
                assertEquals(1L, failure.partialResult.endCheckpoint)
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `processor ordinary failure preserves cause and partial result`() =
        withDreamingDatabase { fixture ->
            repeat(
                2,
            ) { index -> insertDreamingTurn(fixture.database, sessionTurnId = "turn-$index", timestamp = base.plusSeconds(index.toLong())) }
            val cause = IllegalStateException("ct22-processor-failure")
            val processor =
                RecordingDreamingProcessor { batch, call ->
                    if (call == 2) throw cause else DreamingBatchOutcome(batch.last().id, 1, 1)
                }
            val coordinator = coordinator(fixture.database, processor, pageSize = 1, budget = CountingDreamingBudget(0, 1, 1))
            try {
                val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }
                assertSame(cause, failure.cause)
                assertEquals(1L, failure.partialResult.endCheckpoint)
            } finally {
                coordinator.closeAsync()
            }
        }

    @Test
    fun `DB failure remains cause and CE propagates from caller and processor`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "one")
            val dbCause = IllegalStateException("ct22-db-failure")
            val dbTracking = TrackingDreamingDatabase(fixture.database, failReadNumber = 2, failure = dbCause)
            val dbCoordinator = coordinator(dbTracking, RecordingDreamingProcessor())
            try {
                val failure = assertFailsWith<DreamingRunException> { dbCoordinator.runOnce() }
                assertEquals(DreamingRunStage.PAGE_READ, failure.stage)
                assertSame(dbCause, failure.cause)
            } finally {
                dbCoordinator.closeAsync()
            }

            val callerCancellation = CancellationException("ct22-caller-cancel")
            val callerCoordinator = coordinator(fixture.database, RecordingDreamingProcessor { _, _ -> throw callerCancellation })
            try {
                assertSame(callerCancellation, assertFailsWith<CancellationException> { callerCoordinator.runOnce() })
            } finally {
                callerCoordinator.closeAsync()
            }
        }

    @Test
    fun `manual overlap returns already running and background overlap skips without new owned run`() =
        withDreamingDatabase { fixture ->
            coroutineScope {
                insertDreamingTurn(fixture.database, sessionTurnId = "one")
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val processor =
                    RecordingDreamingProcessor { batch, _ ->
                        entered.complete(Unit)
                        release.await()
                        DreamingBatchOutcome(batch.last().id, 1, 1)
                    }
                val coordinator = coordinator(fixture.database, processor)
                try {
                    val run = async(Dispatchers.Default) { coordinator.runOnce() }
                    entered.await()
                    val overlap = assertFailsWith<ContextDreamingException> { coordinator.runOnce() }
                    assertEquals(ContextDreamingErrorCode.ALREADY_RUNNING, overlap.errorCode)
                    assertFalse(coordinator.requestBackground())
                    assertEquals(0, coordinator.ownedRunCountForTesting())
                    release.complete(Unit)
                    run.await()
                } finally {
                    release.complete(Unit)
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `close cancels owned run and rejects new work`() =
        withDreamingDatabase { fixture ->
            insertDreamingTurn(fixture.database, sessionTurnId = "one")
            val entered = CompletableDeferred<Unit>()
            val coordinator = coordinator(fixture.database, RecordingDreamingProcessor())
            coordinator.runHookForTesting = {
                entered.complete(Unit)
                awaitCancellation()
            }
            assertTrue(coordinator.requestBackground())
            entered.await()
            coordinator.beginClosing()
            assertFalse(coordinator.requestBackground(), "closing must reject new background work")
            withTimeout(10_000L) { coordinator.closeAsync() }
            assertEquals("CLOSED", coordinator.stateForTesting())
            assertFalse(coordinator.requestBackground())
        }

    @Test
    fun `close waits for external manual run but never cancels its caller`() =
        withDreamingDatabase { fixture ->
            coroutineScope {
                insertDreamingTurn(fixture.database, sessionTurnId = "one")
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val coordinator = coordinator(fixture.database, RecordingDreamingProcessor())
                coordinator.runHookForTesting = {
                    entered.complete(Unit)
                    release.await()
                }
                val run = async(Dispatchers.Default) { coordinator.runOnce() }
                entered.await()
                val close = async(Dispatchers.Default) { coordinator.closeAsync() }
                awaitUntil { coordinator.stateForTesting() == "CLOSING" }
                assertTrue(coordinator.activeForTesting())
                assertFalse(close.isCompleted)
                assertFalse(run.isCompleted)
                val failure = assertFailsWith<ContextLifecycleException> { coordinator.runOnce() }
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                release.complete(Unit)
                withTimeout(10_000L) { close.await() }
                run.await()
                assertFalse(run.isCancelled)
                assertFalse(coordinator.activeForTesting())
                assertEquals("CLOSED", coordinator.stateForTesting())
            }
        }

    @Test
    fun `external manual cancellation remains caller owned and lets close finish`() =
        withDreamingDatabase { fixture ->
            coroutineScope {
                insertDreamingTurn(fixture.database, sessionTurnId = "one")
                val entered = CompletableDeferred<Unit>()
                val coordinator = coordinator(fixture.database, RecordingDreamingProcessor())
                coordinator.runHookForTesting = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val run = async(Dispatchers.Default) { coordinator.runOnce() }
                entered.await()
                val close = async(Dispatchers.Default) { coordinator.closeAsync() }
                assertFalse(close.isCompleted)
                run.cancel(CancellationException("ct26-caller-cancel"))
                assertFailsWith<CancellationException> { run.await() }
                withTimeout(10_000L) { close.await() }
                assertFalse(coordinator.activeForTesting())
            }
        }

    @Test
    fun `concurrent close shares the same failure without duplicate cleanup`() =
        withDreamingDatabase { fixture ->
            coroutineScope {
                val failure = IllegalStateException("ct22-close-failure")
                val cleanupEntered = CompletableDeferred<Unit>()
                val releaseCleanup = CompletableDeferred<Unit>()
                val coordinator = coordinator(fixture.database, RecordingDreamingProcessor())
                coordinator.closeHookForTesting = {
                    cleanupEntered.complete(Unit)
                    runBlocking { releaseCleanup.await() }
                }
                coordinator.closeFailureForTesting = failure

                val first = async(Dispatchers.Default) { runCatching { coordinator.closeAsync() } }
                cleanupEntered.await()
                val second = async(Dispatchers.Default) { runCatching { coordinator.closeAsync() } }
                releaseCleanup.complete(Unit)

                assertSame(failure, first.await().exceptionOrNull())
                assertSame(failure, second.await().exceptionOrNull())
                assertEquals("CLOSED", coordinator.stateForTesting())
            }
        }

    private fun coordinator(
        database: com.github.noahshen.alcyone.context.memory.core.ContextDatabase,
        processor: RecordingDreamingProcessor,
        pageSize: Int = 256,
        budget: DreamingBatchBudget = CountingDreamingBudget(0, 0, 0),
    ): DreamingRunCoordinator = DreamingRunCoordinator(database, processor, budget, pageSize)

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(10_000L) {
            while (!condition()) kotlinx.coroutines.delay(10)
        }
    }
}
