package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventCursorStart
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.ReliableEventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.SequencedEventRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
class ReliableEventConsumerTest {
    @Test
    fun `failed batch is retried from the original cursor and committed only after every event succeeds`() =
        runBlocking {
            val repository =
                FakeReliableEventRepository(
                    listOf(
                        reliableRecord(1, "00000000-0000-7000-8000-000000000001"),
                        reliableRecord(2, "00000000-0000-7000-8000-000000000002"),
                    ),
                )
            val attempts = mutableListOf<Long>()
            val committed = CompletableDeferred<Unit>()
            val consumer = consumer(repository)
            val scope = CoroutineScope(Dispatchers.Default)
            consumer.start(scope) { event ->
                attempts += event.sequence
                if (event.sequence == 2L && attempts.count { it == 2L } == 1) error("retry this batch")
                if (event.sequence == 2L) committed.complete(Unit)
            }
            withTimeout(2_000) { committed.await() }
            consumer.close()
            scope.cancel()
            assertEquals(listOf(1L, 2L, 1L, 2L), attempts)
            assertEquals(3L, repository.next)
            assertEquals(listOf(3L), repository.commits)
        }

    @Test
    fun `close waits for the claimed batch and does not commit a failed batch`() =
        runBlocking {
            val repository = FakeReliableEventRepository(listOf(reliableRecord(1, "00000000-0000-7000-8000-000000000001")))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val consumer = consumer(repository)
            val scope = CoroutineScope(Dispatchers.Default)
            val job =
                consumer.start(scope) {
                    entered.complete(Unit)
                    release.await()
                }
            withTimeout(2_000) { entered.await() }
            val closing = AtomicReference(Thread { consumer.close() })
            closing.get().start()
            kotlinx.coroutines.delay(100)
            assertTrue(closing.get().isAlive)
            assertTrue(repository.commits.isEmpty())
            release.complete(Unit)
            closing.get().join(2_000)
            assertTrue(!closing.get().isAlive)
            assertTrue(job.isCompleted)
            assertEquals(listOf(2L), repository.commits)
            scope.cancel()
        }

    @Test
    fun `cursor failure is propagated without reexecuting the successful handler`() =
        runBlocking {
            val repository = FakeReliableEventRepository(listOf(reliableRecord(1, "00000000-0000-7000-8000-000000000001")))
            repository.failAdvance = true
            val consumer = consumer(repository)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var calls = 0
            val called = CompletableDeferred<Unit>()
            val job =
                consumer.start(scope) {
                    calls++
                    called.complete(Unit)
                }
            withTimeout(2_000) { called.await() }
            withTimeout(2_000) {
                while (!job.isCompleted) kotlinx.coroutines.delay(10)
            }
            assertEquals(1, calls)
            assertTrue(job.isCompleted)
            assertTrue(repository.commits.isEmpty())
            scope.cancel()
        }

    @Test
    fun `cancellation is propagated and latest starts after existing events`() =
        runBlocking {
            val repository = FakeReliableEventRepository(listOf(reliableRecord(1, "00000000-0000-7000-8000-000000000001")))
            val cancellation = CancellationException("handler cancelled")
            val consumer = consumer(repository, start = EventCursorStart.LATEST)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val observed = CompletableDeferred<CancellationException>()
            val initialized = CompletableDeferred<Unit>()
            val job =
                consumer.start(scope) {
                    try {
                        throw cancellation
                    } catch (failure: CancellationException) {
                        observed.complete(failure)
                        throw failure
                    }
                }
            kotlinx.coroutines.delay(100)
            repository.events += reliableRecord(2, "00000000-0000-7000-8000-000000000002")
            assertSame(cancellation, withTimeout(2_000) { observed.await() })
            assertTrue(job.isCancelled)
            assertTrue(repository.commits.isEmpty())
            scope.cancel()
        }

    private fun consumer(
        repository: ReliableEventRepository,
        start: EventCursorStart = EventCursorStart.EARLIEST,
    ) = ReliableEventConsumer(
        repository,
        "test-consumer",
        start = start,
        pollInterval = 10.milliseconds,
        retryDelays = listOf(10.milliseconds),
    )

    private class FakeReliableEventRepository(
        initial: List<SequencedEventRecord>,
    ) : ReliableEventRepository {
        val events = initial.toMutableList()
        val commits = mutableListOf<Long>()
        var next = 0L
        var initialNext: Long? = null
        var failAdvance = false

        override suspend fun initializeCursor(
            consumerId: String,
            start: EventCursorStart,
        ): Long {
            next = initialNext ?: if (start == EventCursorStart.EARLIEST) 1L else (events.maxOfOrNull { it.sequence } ?: 0L) + 1
            return next
        }

        override suspend fun readAfter(
            afterExclusive: Long,
            limit: Int,
        ): List<SequencedEventRecord> = events.filter { it.sequence > afterExclusive }.take(limit)

        override suspend fun advanceCursor(
            consumerId: String,
            expectedNextSequence: Long,
            nextSequence: Long,
        ) {
            check(!failAdvance) { "cursor write failed" }
            check(next == expectedNextSequence) { "cursor conflict" }
            next = nextSequence
            commits += nextSequence
        }
    }
}

internal fun reliableRecord(
    sequence: Long,
    eventId: String,
    occurredAt: Instant = Instant.ofEpochMilli(sequence),
): SequencedEventRecord =
    SequencedEventRecord(
        sequence,
        EventRecord(
            VfsEventId.parse(eventId),
            VfsEventType.FILE_CREATED,
            null,
            occurredAt,
            VfsUri.parse("alcyone://resources/$eventId.txt"),
        ),
    )
