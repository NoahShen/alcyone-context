package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotifier
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionService
import com.github.noahshen.alcyone.context.memory.core.repository.ConversationTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CT07-B1 唯一约束竞争：持久化层把 SQLSTATE 23000 安全映射后，服务必须重读权威记录并返回
 * DUPLICATE / TURN_CONFLICT，而不是把竞争笼统暴露为 500。
 *
 * 用 ContextDatabase 测试替身模拟「插入抛 23000、随后能读到赢家记录」的时序，
 * 不伪造生产 SQL 路径。
 */
class IngestionConstraintRaceTest {
    private val now: Instant = Instant.parse("2026-10-29T00:00:00.000Z")

    @Test
    fun `unique constraint race resolves to duplicate when the winner has the same content`() =
        withRaceService(existing = storedTurn(messages = IngestionTurns.messages("hello"))) { service ->
            val result = runBlocking { service.ingest(IngestionTurns.turn()) }
            assertEquals(IngestStatus.DUPLICATE, result.status)
            assertEquals(7L, result.id)
        }

    @Test
    fun `unique constraint race resolves to conflict when the winner has different content`() =
        withRaceService(existing = storedTurn(messages = IngestionTurns.messages("other"))) { service ->
            val failure = assertFailsWith<ContextIngestionException> { runBlocking { service.ingest(IngestionTurns.turn()) } }
            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, failure.errorCode)
        }

    @Test
    fun `unique constraint race without a readable winner stays conflict`() =
        withRaceService(existing = null) { service ->
            val failure = assertFailsWith<ContextIngestionException> { runBlocking { service.ingest(IngestionTurns.turn()) } }
            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, failure.errorCode)
        }

    @Test
    fun `non unique persistence failures are not swallowed`() =
        withRaceService(
            existing = null,
            insertFailure = persistence("scoped prepared statement execute failed: SQLSTATE HY000"),
        ) { service ->
            val failure = assertFailsWith<ContextPersistenceException> { runBlocking { service.ingest(IngestionTurns.turn()) } }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        }

    @Test
    fun `insert returning a non created result is not reported as created`() =
        withRaceService(existing = null, insertResult = IngestResult(9L, IngestStatus.DUPLICATE)) { service ->
            val failure = assertFailsWith<ContextIngestionException> { runBlocking { service.ingest(IngestionTurns.turn()) } }
            assertEquals(ContextIngestionErrorCode.TURN_CONFLICT, failure.errorCode)
        }

    @Test
    fun `commit failure propagates instead of reporting created`() {
        val tx = FakeConversationOps(existing = null)
        val database =
            object : ContextDatabase {
                override suspend fun <T> read(block: (ReadContext) -> T): T = throw UnsupportedOperationException()

                override suspend fun <T> write(block: (TransactionContext) -> T): T {
                    block(FakeTransactionContext(tx))
                    throw ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, "commit failed")
                }
            }
        val service = IngestionService.create(IngestionLimits.DEFAULTS, MutableClock(now), IngestionNotifier { }) { database }
        val failure = assertFailsWith<ContextPersistenceException> { runBlocking { service.ingest(IngestionTurns.turn()) } }
        assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
    }

    private fun withRaceService(
        existing: StoredConversationTurn?,
        insertFailure: RuntimeException? = persistence("scoped prepared statement execute failed: SQLSTATE 23000"),
        insertResult: IngestResult = IngestResult(7L, IngestStatus.CREATED),
        block: suspend (com.github.noahshen.alcyone.context.memory.core.ingestion.DelegatingIngestionService) -> Unit,
    ) {
        val tx = FakeConversationOps(existing = existing, insertFailure = insertFailure, insertResult = insertResult)
        val database =
            object : ContextDatabase {
                override suspend fun <T> read(block: (ReadContext) -> T): T = throw UnsupportedOperationException()

                override suspend fun <T> write(block: (TransactionContext) -> T): T = block(FakeTransactionContext(tx))
            }
        val service = IngestionService.create(IngestionLimits.DEFAULTS, MutableClock(now), IngestionNotifier { }) { database }
        runBlocking { block(service) }
    }

    private fun storedTurn(messages: List<ChatMessage>): StoredConversationTurn =
        StoredConversationTurn(
            id = 7L,
            runtimeId = "runtime-1",
            agentId = "agent-1",
            sessionId = "session-1",
            sessionTurnId = "turn-1",
            timestamp = IngestionTurns.T0,
            createdAt = IngestionTurns.T0.plusSeconds(5),
            messages = messages,
            extractionStatus = ExtractionStatus.PENDING,
        )

    private fun persistence(message: String): ContextPersistenceException =
        ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, message)
}

private class FakeConversationOps(
    private val existing: StoredConversationTurn?,
    private val insertFailure: RuntimeException? = null,
    private val insertResult: IngestResult = IngestResult(7L, IngestStatus.CREATED),
) : ConversationTxOps {
    private var reads = 0

    override fun findByBusinessKey(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
    ): StoredConversationTurn? {
        reads++
        // 第一次查找用于幂等预检（返回 null），插入冲突后的重读返回赢家记录。
        return if (reads == 1) null else existing
    }

    override fun insertTurn(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        sessionTurnId: String,
        timestamp: Instant,
        createdAt: Instant,
        messages: List<ChatMessage>,
    ): IngestResult {
        insertFailure?.let { throw it }
        return insertResult
    }

    override fun getById(id: Long): StoredConversationTurn? = error("not used")

    override fun sessionWindow(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        beforeTimestamp: Instant?,
        beforeId: Long?,
        limit: Int,
    ): List<StoredConversationTurn> = error("not used")

    override fun latestPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): StoredConversationTurn? = error("not used")

    override fun countPending(
        runtimeId: String,
        agentId: String,
        sessionId: String,
    ): Int = error("not used")

    override fun idleCheck(
        runtimeId: String,
        agentId: String,
        sessionId: String,
        idleBefore: Instant,
    ): Boolean = error("not used")

    override fun findIdleSessions(idleBefore: Instant): List<SessionKey> = error("not used")

    override fun maxTurnId(): Long? = error("not used")

    override fun idRange(
        fromIdExclusive: Long,
        toIdInclusive: Long,
        limit: Int,
    ): List<StoredConversationTurn> = error("not used")

    override fun markComplete(id: Long): Int = error("not used")

    override fun markFailed(id: Long): Int = error("not used")
}

private class FakeTransactionContext(
    override val conversations: ConversationTxOps,
) : TransactionContext {
    override val rawMemories: RawMemoryTxOps get() = error("not used")
    override val organizedMemories: OrganizedMemoryTxOps get() = error("not used")
    override val pending: OrganizationPendingTxOps get() = error("not used")
    override val dreaming: DreamingCheckpointTxOps get() = error("not used")
}
