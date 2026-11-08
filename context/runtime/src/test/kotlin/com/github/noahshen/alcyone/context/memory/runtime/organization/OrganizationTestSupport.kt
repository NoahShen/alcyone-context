package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecision
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidateRetriever
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitResult
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 可注入 Periodic/Failure tick 来源；测试不等待真实 cron。 */
internal class ManualOrganizationTicks : OrganizationPeriodicTickSource {
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

    fun fail(failure: Throwable = IllegalStateException("ct16-tick-failure-marker")) {
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

/** 真实库包装：可让前 N 次 read 失败并暴露每次成功读取的完成信号。 */
internal class TrackingOrganizationDatabase(
    private val delegate: ContextDatabase,
    readFailures: Int = 0,
    private val failureFactory: () -> Throwable = {
        ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, "ct16-read-failure")
    },
) : ContextDatabase {
    private val remainingReadFailures = AtomicInteger(readFailures)
    val readCount = AtomicInteger(0)
    val completedReads = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun <T> read(block: (ReadContext) -> T): T {
        if (remainingReadFailures.getAndUpdate { current -> if (current > 0) current - 1 else 0 } > 0) {
            throw failureFactory()
        }
        return delegate.read(block).also {
            readCount.incrementAndGet()
            completedReads.trySend(Unit)
        }
    }

    override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)
}

internal class RecordingCandidateRetriever(
    private val behavior: suspend (RawMemory) -> List<OrganizedMemory> = { emptyList() },
) : OrganizationCandidateRetriever {
    val raws: MutableList<RawMemory> = CopyOnWriteArrayList()

    override suspend fun search(rawMemory: RawMemory): List<OrganizedMemory> {
        raws += rawMemory
        return behavior(rawMemory)
    }
}

internal class RecordingGovernanceDecider(
    private val behavior: suspend (RawMemory, List<OrganizedMemory>) -> GovernanceDecision,
) : OrganizationGovernanceDecider {
    val calls: MutableList<Pair<RawMemory, List<OrganizedMemory>>> = CopyOnWriteArrayList()

    override suspend fun decide(
        rawMemory: RawMemory,
        candidates: List<OrganizedMemory>,
    ): GovernanceDecision {
        calls += rawMemory to candidates
        return behavior(rawMemory, candidates)
    }
}

internal class RecordingCommitOperation(
    private val behavior: suspend (
        String,
        List<OrganizedMemory>,
        GovernanceDecision,
    ) -> OrganizationCommitResult = {
        rawId,
        _,
        _,
        ->
        OrganizationCommitResult.Committed(com.github.noahshen.alcyone.context.memory.core.organization.GovernanceAction.NEW, rawId)
    },
) : OrganizationCommitOperation {
    data class Call(
        val rawId: String,
        val candidates: List<OrganizedMemory>,
        val decision: GovernanceDecision,
    )

    val calls: MutableList<Call> = CopyOnWriteArrayList()

    override suspend fun commit(
        rawId: String,
        candidates: List<OrganizedMemory>,
        decision: GovernanceDecision,
    ): OrganizationCommitResult {
        calls += Call(rawId, candidates, decision)
        return behavior(rawId, candidates, decision)
    }
}

internal class RecordingMapWakeup(
    private val behavior: suspend () -> Unit = { },
) : OrganizationMapWakeup {
    val calls = AtomicInteger(0)

    override suspend fun onOrganizedCommitted() {
        calls.incrementAndGet()
        behavior()
    }
}

internal suspend fun awaitOrganizationUntil(condition: () -> Boolean) {
    withTimeout(10_000L) {
        while (!condition()) delay(10)
    }
}
