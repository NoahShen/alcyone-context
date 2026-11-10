package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitContext
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitException
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitter
import com.github.noahshen.alcyone.context.memory.core.dreaming.ValidatedDreamingInsight
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DreamingCommitterRealDbTest {
    @Test
    fun `multi insight commit writes raw pending and advances checkpoint atomically`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val first = insertTurn(fixture.database, "one")
                val second = insertTurn(fixture.database, "two")
                setDreamingCheckpoint(fixture.database, 1)
                val ids = QueueIdGenerator("raw-1", "raw-2")
                val now = Instant.parse("2026-11-09T12:34:56Z")
                val insights = listOf(insight("first", first), insight("second", second, first))

                val result =
                    committer(fixture.database, ids, Clock.fixed(now, ZoneOffset.UTC))
                        .commit(DreamingCommitContext(1, second.id, second.id), insights)

                assertEquals(2, result.rawCount)
                assertEquals(second.id, result.lastTurnId)
                val state = readState(fixture.database, listOf("raw-1", "raw-2"), first, second)
                assertEquals(2, state.raws.size)
                assertEquals(setOf("raw-1", "raw-2"), state.pendingIds.toSet())
                assertEquals(second.id, state.checkpoint)
                assertEquals(ExtractionStatus.PENDING, state.firstStatus)
                assertEquals(ExtractionStatus.PENDING, state.secondStatus)
                assertEquals(setOf(MemoryOrigin.DREAMING), state.raws.map { it.generatedBy }.toSet())
                assertEquals(setOf(now), state.raws.map { it.createdAt }.toSet())
                assertEquals(listOf("first", "second"), state.raws.map { it.content })
                assertEquals(insights.flatMap { it.sources }, state.raws.flatMap { it.sources })
                assertEquals(insights[0].sources, state.raws[0].sources)
                assertEquals(insights[1].sources, state.raws[1].sources)
                assertEquals(2, ids.calls.get())
            }
        }

    @Test
    fun `zero insights still runs one write and advances checkpoint without raw or pending`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val turn = insertTurn(fixture.database, "only")
                val tracking = WriteTrackingDatabase(fixture.database)
                val ids = QueueIdGenerator()

                val result =
                    committer(tracking, ids, fixedClock())
                        .commit(DreamingCommitContext(0, turn.id, turn.id), emptyList())

                assertEquals(0, result.rawCount)
                assertEquals(turn.id, result.lastTurnId)
                assertEquals(1, tracking.writes.get())
                assertEquals(0, ids.calls.get())
                val state = readState(fixture.database, emptyList(), turn)
                assertEquals(turn.id, state.checkpoint)
                assertEquals(emptyList(), state.raws)
                assertEquals(emptyList(), state.pendingIds)
                assertEquals(ExtractionStatus.PENDING, state.firstStatus)
            }
        }

    @Test
    fun `id generation failure is safe and performs no write`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val first = insertTurn(fixture.database, "one")
                val second = insertTurn(fixture.database, "two")
                val tracking = WriteTrackingDatabase(fixture.database)
                val ids = QueueIdGenerator("raw-only-one")

                val failure =
                    assertFailsWith<DreamingCommitException> {
                        committer(tracking, ids, fixedClock())
                            .commit(
                                DreamingCommitContext(0, second.id, second.id),
                                listOf(insight("first", first), insight("second", second)),
                            )
                    }

                assertSafe(failure, "raw-only-one")
                assertEquals(0, tracking.writes.get())
                val state = readState(fixture.database, listOf("raw-only-one"), first, second)
                assertEquals(emptyList(), state.raws)
                assertEquals(emptyList(), state.pendingIds)
                assertEquals(0, state.checkpoint)
                assertEquals(ExtractionStatus.PENDING, state.firstStatus)
                assertEquals(ExtractionStatus.PENDING, state.secondStatus)
            }
        }

    @Test
    fun `duplicate ids within one attempt fail safely before any database write`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val first = insertTurn(fixture.database, "one")
                val second = insertTurn(fixture.database, "two")
                val tracking = WriteTrackingDatabase(fixture.database)
                val ids = QueueIdGenerator("raw-duplicate", "raw-duplicate")

                val failure =
                    assertFailsWith<DreamingCommitException> {
                        committer(tracking, ids, fixedClock())
                            .commit(
                                DreamingCommitContext(0, second.id, second.id),
                                listOf(insight("first", first), insight("second", second)),
                            )
                    }

                assertSafe(failure, "raw-duplicate")
                assertEquals(0, tracking.writes.get())
                assertEquals(2, ids.calls.get())
                val state = readState(fixture.database, listOf("raw-duplicate"), first, second)
                assertEquals(emptyList(), state.raws)
                assertEquals(emptyList(), state.pendingIds)
                assertEquals(0, state.checkpoint)
                assertEquals(ExtractionStatus.PENDING, state.firstStatus)
                assertEquals(ExtractionStatus.PENDING, state.secondStatus)
            }
        }

    @Test
    fun `checkpoint and id holes use actual final id and preserve upper bound`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val first = insertTurn(fixture.database, "first")
                val gapTurn = insertTurn(fixture.database, "gap")
                val last = insertTurn(fixture.database, "last")
                fixture.deleteTurn(gapTurn.id)
                setDreamingCheckpoint(fixture.database, first.id)

                val result =
                    committer(fixture.database, QueueIdGenerator("raw-final"), fixedClock())
                        .commit(DreamingCommitContext(first.id, last.id, last.id), listOf(insight("last", last)))

                assertEquals(last.id, result.lastTurnId)
                assertEquals(last.id, fixture.database.read { it.dreaming.readCheckpoint() })
                assertEquals(listOf("raw-final"), fixture.database.read { it.pending.listAllByRawCreatedAt() })
            }
        }

    @Test
    fun `invalid ranges fail before any database write`() =
        runBlocking {
            val tracking = WriteTrackingDatabase(NoopDatabase)
            val contexts =
                listOf(
                    { DreamingCommitContext(-1, 2, 1) },
                    { DreamingCommitContext(0, -1, 0) },
                    { DreamingCommitContext(1, 2, 1) },
                    { DreamingCommitContext(1, 2, 3) },
                    { DreamingCommitContext(2, 3, 1) },
                )

            contexts.forEach { createContext ->
                assertFailsWith<DreamingCommitException> {
                    committer(tracking, QueueIdGenerator("raw-1"), fixedClock()).commit(createContext(), listOf())
                }
            }
            assertEquals(0, tracking.writes.get())
        }

    @Test
    fun `checkpoint conflict rolls back raw and pending and keeps expected authority`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val first = insertTurn(fixture.database, "one")
                val second = insertTurn(fixture.database, "two")
                setDreamingCheckpoint(fixture.database, first.id)

                val failure =
                    assertFailsWith<DreamingCommitException> {
                        committer(fixture.database, QueueIdGenerator("raw-conflict"), fixedClock())
                            .commit(DreamingCommitContext(0, second.id, second.id), listOf(insight("new", second)))
                    }

                assertSafe(failure, "raw-conflict")
                assertRolledBack(fixture.database, "raw-conflict", first.id, first, second)
            }
        }

    @Test
    fun `insert pending and cas failures roll back real sqlite state`() =
        runBlocking {
            data class Stage(
                val name: String,
                val fault: Fault,
            )

            listOf(
                Stage("insert", Fault.THROW_INSERT),
                Stage("pending", Fault.THROW_ENQUEUE),
                Stage("cas-throw", Fault.THROW_CAS),
                Stage("cas-count", Fault.CAS_COUNT_ZERO),
                Stage("insert-count", Fault.INSERT_COUNT_ZERO),
                Stage("enqueue-count", Fault.ENQUEUE_COUNT_ZERO),
            ).forEach { stage ->
                withDreamingDatabase { fixture ->
                    val first = insertTurn(fixture.database, "one")
                    val second = insertTurn(fixture.database, "two")
                    setDreamingCheckpoint(fixture.database, first.id)
                    val rawId = "raw-${stage.name}"
                    val failing = FaultInjectingDatabase(fixture.database, stage.fault)

                    val failure =
                        assertFailsWith<DreamingCommitException>(stage.name) {
                            committer(failing, QueueIdGenerator(rawId), fixedClock())
                                .commit(DreamingCommitContext(first.id, second.id, second.id), listOf(insight("new", second)))
                        }

                    assertSafe(failure, rawId)
                    assertEquals(1, failing.writes.get())
                    assertRolledBack(fixture.database, rawId, first.id, first, second)
                }
            }
        }

    @Test
    fun `cancellation is propagated unchanged and the transaction leaves no partial writes`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val turn = insertTurn(fixture.database, "one")
                val cancellation = CancellationException("ct25-committer-cancel")
                val failing = FaultInjectingDatabase(fixture.database, Fault.cancel(cancellation))

                val propagated =
                    assertFailsWith<CancellationException> {
                        committer(failing, QueueIdGenerator("raw-cancel"), fixedClock())
                            .commit(DreamingCommitContext(0, turn.id, turn.id), listOf(insight("new", turn)))
                    }
                assertEquals(cancellation.message, propagated.message)
                assertRolledBack(fixture.database, "raw-cancel", 0, turn)
            }
        }

    @Test
    fun `known raw id collision retries the complete batch with new ids and one final write`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val first = insertTurn(fixture.database, "one")
                val second = insertTurn(fixture.database, "two")
                insertRaw(fixture.database, raw("raw-collision"))
                val ids = QueueIdGenerator("raw-fresh-1", "raw-collision", "raw-fresh-1-retry", "raw-fresh-2-retry")
                val tracking = WriteTrackingDatabase(fixture.database)

                val result =
                    committer(tracking, ids, fixedClock())
                        .commit(
                            DreamingCommitContext(0, second.id, second.id),
                            listOf(insight("first", first), insight("second", second)),
                        )

                assertEquals(2, result.rawCount)
                assertEquals(2, tracking.writes.get())
                assertEquals(4, ids.calls.get())
                assertNull(fixture.database.read { it.rawMemories.findById("raw-fresh-1") })
                assertNotNull(fixture.database.read { it.rawMemories.findById("raw-fresh-1-retry") })
                assertNotNull(fixture.database.read { it.rawMemories.findById("raw-fresh-2-retry") })
                assertEquals(
                    setOf("raw-fresh-1-retry", "raw-fresh-2-retry"),
                    fixture.database.read { it.pending.listAllByRawCreatedAt() }.toSet(),
                )
                assertEquals(second.id, fixture.database.read { it.dreaming.readCheckpoint() })
                assertNull(fixture.database.read { it.rawMemories.findById("raw-collision")?.takeIf { it.content == "new" } })
            }
        }

    @Test
    fun `persistent collision exhausts three attempts with no new write`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val turn = insertTurn(fixture.database, "one")
                insertRaw(fixture.database, raw("same"))
                val ids = QueueIdGenerator("same", "same", "same", "unused")
                val tracking = WriteTrackingDatabase(fixture.database)

                val failure =
                    assertFailsWith<DreamingCommitException> {
                        committer(tracking, ids, fixedClock())
                            .commit(DreamingCommitContext(0, turn.id, turn.id), listOf(insight("new", turn)))
                    }

                assertEquals(3, tracking.writes.get())
                assertEquals(3, ids.calls.get())
                assertSafe(failure, "same")
                assertEquals(0, fixture.database.read { it.dreaming.readCheckpoint() })
                assertEquals(emptyList(), fixture.database.read { it.pending.listAllByRawCreatedAt() })
            }
        }

    @Test
    fun `non collision database failure is never retried`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val turn = insertTurn(fixture.database, "one")
                val tracking = WriteTrackingDatabase(FaultInjectingDatabase(fixture.database, Fault.THROW_INSERT))
                val ids = QueueIdGenerator("raw-a", "raw-b")

                val failure =
                    assertFailsWith<DreamingCommitException> {
                        committer(tracking, ids, fixedClock())
                            .commit(DreamingCommitContext(0, turn.id, turn.id), listOf(insight("new", turn)))
                    }

                assertSafe(failure, "raw-a")
                assertEquals(1, tracking.writes.get())
                assertEquals(1, ids.calls.get())
                assertRolledBack(fixture.database, "raw-a", 0, turn)
            }
        }

    @Test
    fun `committer rejects invalid retry policy`() {
        assertFailsWith<IllegalArgumentException> { DreamingCommitter(NoopDatabase, QueueIdGenerator(), fixedClock(), 0) }
    }

    private suspend fun insertTurn(
        database: ContextDatabase,
        key: String,
    ): StoredConversationTurn {
        insertDreamingTurn(database = database, sessionTurnId = key)
        return checkNotNull(
            database.read {
                it.conversations.findByBusinessKey("runtime", "agent", "session", key)
            },
        )
    }

    private fun insight(
        content: String,
        vararg turns: StoredConversationTurn,
    ): ValidatedDreamingInsight =
        ValidatedDreamingInsight(
            content = content,
            sources =
                turns.map {
                    MemorySourceRef.ConversationTurnRef(
                        runtimeId = it.runtimeId,
                        agentId = it.agentId,
                        sessionId = it.sessionId,
                        sessionTurnId = it.sessionTurnId,
                    )
                },
        )

    private fun committer(
        database: ContextDatabase,
        ids: MemoryIdGenerator,
        clock: Clock,
        attempts: Int = 3,
    ) = DreamingCommitter(database, ids, clock, attempts)

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)

    private suspend fun insertRaw(
        database: ContextDatabase,
        raw: RawMemory,
    ) {
        database.write { check(it.rawMemories.insertBatch(listOf(raw)) == 1) }
    }

    private fun raw(id: String): RawMemory = RawMemory(id, "seed", MemoryOrigin.EXTRACTION, emptyList(), fixedClock().instant())

    private suspend fun assertRolledBack(
        database: ContextDatabase,
        rawId: String,
        expectedCheckpoint: Long,
        vararg turns: StoredConversationTurn,
    ) {
        val state = readState(database, listOf(rawId), *turns)
        assertNull(state.raws.firstOrNull())
        assertFalse(rawId in state.pendingIds)
        assertEquals(expectedCheckpoint, state.checkpoint)
        state.statuses.forEach { assertEquals(ExtractionStatus.PENDING, it) }
    }

    private suspend fun readState(
        database: ContextDatabase,
        rawIds: List<String>,
        vararg turns: StoredConversationTurn,
    ): State =
        database.read { tx ->
            State(
                raws = rawIds.mapNotNull(tx.rawMemories::findById),
                pendingIds = tx.pending.listAllByRawCreatedAt(),
                checkpoint = tx.dreaming.readCheckpoint(),
                statuses =
                    turns.map { turn ->
                        checkNotNull(
                            tx.conversations.findByBusinessKey(
                                turn.runtimeId,
                                turn.agentId,
                                turn.sessionId,
                                turn.sessionTurnId,
                            ),
                        ).extractionStatus
                    },
            )
        }

    private fun assertSafe(
        failure: DreamingCommitException,
        marker: String,
    ) {
        var current: Throwable? = failure
        while (current != null) {
            assertFalse(current.message.orEmpty().contains(marker), current.message.orEmpty())
            current = current.cause
        }
    }

    private data class State(
        val raws: List<RawMemory>,
        val pendingIds: List<String>,
        val checkpoint: Long,
        val statuses: List<ExtractionStatus>,
    ) {
        val firstStatus: ExtractionStatus get() = statuses[0]
        val secondStatus: ExtractionStatus get() = statuses[1]
    }

    private class QueueIdGenerator(
        vararg ids: String,
    ) : MemoryIdGenerator {
        private val ids = ids.toList()
        private var index = 0
        val calls = AtomicInteger(0)

        override fun nextRawMemoryId(): String {
            calls.incrementAndGet()
            check(index < ids.size) { "id generator exhausted" }
            return ids[index++]
        }

        override fun nextOrganizedMemoryId(): String = throw UnsupportedOperationException("Dreaming does not generate Organized IDs")
    }

    private class WriteTrackingDatabase(
        private val delegate: ContextDatabase,
    ) : ContextDatabase {
        val writes = AtomicInteger(0)

        override suspend fun <T> read(block: (com.github.noahshen.alcyone.context.memory.core.repository.ReadContext) -> T): T =
            delegate.read(block)

        override suspend fun <T> write(block: (TransactionContext) -> T): T {
            writes.incrementAndGet()
            return delegate.write(block)
        }
    }

    private class FaultInjectingDatabase(
        private val delegate: ContextDatabase,
        private val fault: Fault,
    ) : ContextDatabase {
        val writes = AtomicInteger(0)

        override suspend fun <T> read(block: (com.github.noahshen.alcyone.context.memory.core.repository.ReadContext) -> T): T =
            delegate.read(block)

        override suspend fun <T> write(block: (TransactionContext) -> T): T {
            writes.incrementAndGet()
            return delegate.write { tx -> block(FaultingTransaction(tx, fault)) }
        }
    }

    private class FaultingTransaction(
        private val delegate: TransactionContext,
        private val fault: Fault,
    ) : TransactionContext by delegate {
        override val rawMemories: RawMemoryTxOps =
            object : RawMemoryTxOps by delegate.rawMemories {
                override fun insertBatch(memories: List<RawMemory>): Int =
                    when (fault.kind) {
                        FaultKind.THROW_INSERT -> throw IllegalStateException("ct25-insert-failure")
                        FaultKind.INSERT_COUNT_ZERO -> 0
                        else -> delegate.rawMemories.insertBatch(memories)
                    }
            }

        override val pending: OrganizationPendingTxOps =
            object : OrganizationPendingTxOps by delegate.pending {
                override fun enqueue(rawId: String): Int =
                    when (fault.kind) {
                        FaultKind.THROW_ENQUEUE -> throw IllegalStateException("ct25-pending-failure")
                        FaultKind.ENQUEUE_COUNT_ZERO -> 0
                        else -> delegate.pending.enqueue(rawId)
                    }
            }

        override val dreaming: DreamingCheckpointTxOps =
            object : DreamingCheckpointTxOps by delegate.dreaming {
                override fun casUpdateCheckpoint(
                    expected: Long,
                    next: Long,
                ): Int =
                    when (fault.kind) {
                        FaultKind.THROW_CAS -> throw IllegalStateException("ct25-cas-failure")
                        FaultKind.CAS_COUNT_ZERO -> 0
                        FaultKind.CANCEL -> throw fault.cancellation!!
                        else -> delegate.dreaming.casUpdateCheckpoint(expected, next)
                    }
            }
    }

    private enum class FaultKind {
        NONE,
        THROW_INSERT,
        INSERT_COUNT_ZERO,
        THROW_ENQUEUE,
        ENQUEUE_COUNT_ZERO,
        THROW_CAS,
        CAS_COUNT_ZERO,
        CANCEL,
    }

    private data class Fault(
        val kind: FaultKind,
        val cancellation: CancellationException? = null,
    ) {
        companion object {
            val THROW_INSERT = Fault(FaultKind.THROW_INSERT)
            val INSERT_COUNT_ZERO = Fault(FaultKind.INSERT_COUNT_ZERO)
            val THROW_ENQUEUE = Fault(FaultKind.THROW_ENQUEUE)
            val ENQUEUE_COUNT_ZERO = Fault(FaultKind.ENQUEUE_COUNT_ZERO)
            val THROW_CAS = Fault(FaultKind.THROW_CAS)
            val CAS_COUNT_ZERO = Fault(FaultKind.CAS_COUNT_ZERO)

            fun cancel(cancellation: CancellationException) = Fault(FaultKind.CANCEL, cancellation)
        }
    }

    private companion object {
        val NoopDatabase: ContextDatabase =
            object : ContextDatabase {
                override suspend fun <T> read(block: (com.github.noahshen.alcyone.context.memory.core.repository.ReadContext) -> T): T =
                    error("unexpected read")

                override suspend fun <T> write(block: (TransactionContext) -> T): T = error("unexpected write")
            }
    }
}
