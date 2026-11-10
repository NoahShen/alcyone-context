package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class DreamingTestDatabase(
    val database: ContextDatabaseImpl,
    val path: Path,
    private val executor: ContextDatabaseExecutor,
) : AutoCloseable {
    suspend fun deleteTurn(id: Long) {
        check(executor.updateRaw("DELETE FROM conversation_turn WHERE id = $id") == 1)
    }

    override fun close() = executor.close()
}

internal fun withDreamingDatabase(block: suspend (DreamingTestDatabase) -> Unit) {
    val directory = Files.createTempDirectory("ct22-dreaming-")
    var fixture: DreamingTestDatabase? = null
    try {
        val path = directory.resolve("data/context.db")
        ContextDatabaseInitializer.initialize(path)
        val executor = ContextDatabaseFactory.open(path)
        fixture = DreamingTestDatabase(ContextDatabaseImpl(executor), path, executor)
        runBlocking { block(fixture) }
    } finally {
        fixture?.close()
        deleteTree(directory)
    }
}

internal suspend fun insertDreamingTurn(
    database: ContextDatabase,
    runtimeId: String = "runtime",
    agentId: String = "agent",
    sessionId: String = "session",
    sessionTurnId: String,
    timestamp: Instant = BASE,
    createdAt: Instant = timestamp,
    status: ExtractionStatus = ExtractionStatus.PENDING,
) {
    database.write { tx ->
        val result =
            tx.conversations.insertTurn(
                runtimeId = runtimeId,
                agentId = agentId,
                sessionId = sessionId,
                sessionTurnId = sessionTurnId,
                timestamp = timestamp,
                createdAt = createdAt,
                messages = listOf(ChatMessage(ChatRole.USER, "content-$sessionTurnId")),
            )
        check(result.status == IngestStatus.CREATED)
        when (status) {
            ExtractionStatus.PENDING -> Unit
            ExtractionStatus.COMPLETE -> check(tx.conversations.markComplete(result.id) == 1)
            ExtractionStatus.FAILED -> check(tx.conversations.markFailed(result.id) == 1)
        }
    }
}

internal suspend fun setDreamingCheckpoint(
    database: ContextDatabase,
    value: Long,
) {
    database.write { tx ->
        val current = tx.dreaming.readCheckpoint()
        if (current != value) check(tx.dreaming.casUpdateCheckpoint(current, value) == 1)
    }
}

internal class RecordingDreamingProcessor(
    private val behavior: suspend (List<StoredConversationTurn>, Int) -> DreamingBatchOutcome? = { batch, _ ->
        DreamingBatchOutcome(batch.last().id, discoveredTopics = 1, generatedMemories = 1)
    },
) : DreamingBatchProcessor {
    val calls: MutableList<List<Long>> = CopyOnWriteArrayList()
    val contexts: MutableList<DreamingBatchContext> = CopyOnWriteArrayList()
    val invocationCount = AtomicInteger(0)

    override suspend fun process(context: DreamingBatchContext): DreamingBatchOutcome {
        context.requireValidRange()
        contexts += context
        val batch = context.turns
        calls += batch.map { turn -> turn.id }
        val index = invocationCount.incrementAndGet()
        return behavior(batch, index) ?: DreamingBatchOutcome(batch.last().id, 0, 0)
    }
}

internal class CountingDreamingBudget(
    private val base: Int,
    private val perTurn: Int,
    private val max: Int,
) : DreamingBatchBudget {
    override suspend fun requiredTotalFor(batch: List<StoredConversationTurn>): Long? {
        val total = base + perTurn * batch.size
        return if (total <= max) null else total.toLong()
    }
}

internal class TrackingDreamingDatabase(
    private val delegate: ContextDatabase,
    private val failReadNumber: Int? = null,
    private val failure: Throwable = IllegalStateException("ct22-read-failure"),
    private val checkpointOverride: Long? = null,
) : ContextDatabase {
    val reads = AtomicInteger(0)
    val rangeCalls = CopyOnWriteArrayList<Pair<Long, Long>>()
    val checkpointReads = AtomicInteger(0)
    val maxTurnReads = AtomicInteger(0)
    val readsWithBothRangeFields = AtomicInteger(0)

    override suspend fun <T> read(block: (ReadContext) -> T): T {
        val number = reads.incrementAndGet()
        if (number == failReadNumber) throw failure
        return delegate.read { read -> block(TrackingRead(read)) }
    }

    override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)

    private inner class TrackingRead(
        delegate: ReadContext,
    ) : ReadContext by delegate {
        private var checkpointRead = false
        private var maxTurnRead = false
        private var rangeFieldsObserved = false

        override val dreaming: com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointReadOps =
            object : com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointReadOps by delegate.dreaming {
                override fun readCheckpoint(): Long {
                    checkpointReads.incrementAndGet()
                    checkpointRead = true
                    observeRangeFields()
                    return checkpointOverride ?: delegate.dreaming.readCheckpoint()
                }
            }

        override val conversations: com.github.noahshen.alcyone.context.memory.core.repository.ConversationReadOps =
            object : com.github.noahshen.alcyone.context.memory.core.repository.ConversationReadOps by delegate.conversations {
                override fun maxTurnId(): Long? {
                    maxTurnReads.incrementAndGet()
                    maxTurnRead = true
                    observeRangeFields()
                    return delegate.conversations.maxTurnId()
                }

                override fun idRange(
                    fromIdExclusive: Long,
                    toIdInclusive: Long,
                    limit: Int,
                ): List<StoredConversationTurn> {
                    rangeCalls += fromIdExclusive to toIdInclusive
                    return delegate.conversations.idRange(fromIdExclusive, toIdInclusive, limit)
                }
            }

        private fun observeRangeFields() {
            if (checkpointRead && maxTurnRead && !rangeFieldsObserved) {
                rangeFieldsObserved = true
                readsWithBothRangeFields.incrementAndGet()
            }
        }
    }
}

internal class DreamingCommitFaultDatabase(
    private val delegate: ContextDatabase,
    private val failOnCas: (expected: Long, next: Long) -> Boolean,
) : ContextDatabase {
    val casAttempts = CopyOnWriteArrayList<Pair<Long, Long>>()

    override suspend fun <T> read(block: (ReadContext) -> T): T = delegate.read(block)

    override suspend fun <T> write(block: (TransactionContext) -> T): T =
        delegate.write { transaction -> block(FaultingTransaction(transaction)) }

    private inner class FaultingTransaction(
        private val delegate: TransactionContext,
    ) : TransactionContext by delegate {
        override val dreaming: DreamingCheckpointTxOps =
            object : DreamingCheckpointTxOps by delegate.dreaming {
                override fun casUpdateCheckpoint(
                    expected: Long,
                    next: Long,
                ): Int {
                    casAttempts += expected to next
                    check(!failOnCas(expected, next)) { "ct25-real-sqlite-cas-failure" }
                    return delegate.dreaming.casUpdateCheckpoint(expected, next)
                }
            }
    }
}

internal class ManualDreamingTicks : DreamingPeriodicTickSource {
    private sealed interface Event {
        data object Tick : Event

        data class Failure(
            val failure: Throwable,
        ) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    fun tick() {
        check(events.trySend(Event.Tick).isSuccess)
    }

    fun fail(failure: Throwable = IllegalStateException("ct22-tick-failure")) {
        check(events.trySend(Event.Failure(failure)).isSuccess)
    }

    fun close() {
        events.close()
    }

    override suspend fun awaitTick(): Boolean =
        when (val event = events.receiveCatching().getOrNull()) {
            null -> false
            is Event.Tick -> true
            is Event.Failure -> throw event.failure
        }
}

internal fun StoredConversationTurn.status(status: ExtractionStatus): StoredConversationTurn = copy(extractionStatus = status)

private fun deleteTree(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private val BASE: Instant = Instant.parse("2026-11-09T06:00:00Z")
