package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitContext
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitter
import com.github.noahshen.alcyone.context.memory.core.dreaming.ValidatedDreamingInsight
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** 独立 JVM 的真实 Committer/Coordinator 探针；父测试只管理本对象启动的 PID。 */
object DreamingCommitCrashRecoveryProbe {
    private const val PROBE_MARKER = "CT26_COMMIT_RETURNED_BEFORE_NOTIFICATION"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "expected database path and crash-after-first-commit flag" }
        val databasePath = Path.of(args[0]).toAbsolutePath().normalize()
        val crash = args[1].toBooleanStrict()
        if (crash) ContextDatabaseInitializer.initialize(databasePath)
        runBlocking {
            ContextDatabaseFactory.open(databasePath).use { executor ->
                val database = ContextDatabaseImpl(executor)
                if (crash) repeat(2) { insertTurn(database, "turn-${it + 1}") }
                val ids = AtomicInteger(0)
                val committer = DreamingCommitter(database, sequentialIds(ids), fixedClock())
                val committed = AtomicInteger(0)
                val processor =
                    DreamingBatchProcessor { context ->
                        context.turns.forEach { turn ->
                            val checkpoint = database.read { it.dreaming.readCheckpoint() }
                            val commitContext = DreamingCommitContext(checkpoint, context.upperBound, turn.id)
                            val outcome = committer.commit(commitContext, listOf(insight(turn)))
                            check(outcome.lastTurnId == turn.id)
                            val count = committed.incrementAndGet()
                            // 这是真实 commit() 已返回后、Processor 结果发布前的边界。
                            if (crash && count == 1) {
                                println(PROBE_MARKER)
                                System.out.flush()
                                Thread.sleep(120_000)
                            }
                        }
                        DreamingBatchOutcome(context.turns.last().id, 0, context.turns.size)
                    }
                val coordinator = DreamingRunCoordinator(database, processor, { null }, pageSize = 1)
                val result = coordinator.runOnce()
                println(
                    "RECOVERY_RESULT=${result.startCheckpoint}:${result.endCheckpoint}:${result.scannedTurns}:${result.generatedMemories}",
                )
            }
        }
    }

    private fun sequentialIds(counter: AtomicInteger): MemoryIdGenerator =
        object : MemoryIdGenerator {
            override fun nextRawMemoryId(): String = "ct26-crash-${counter.incrementAndGet()}"

            override fun nextOrganizedMemoryId(): String = error("Dreaming does not create organized memories")
        }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)

    private suspend fun insertTurn(
        database: ContextDatabase,
        id: String,
    ) {
        database.write { tx ->
            val result =
                tx.conversations.insertTurn(
                    runtimeId = "ct26-crash",
                    agentId = "agent",
                    sessionId = "session",
                    sessionTurnId = id,
                    timestamp = fixedClock().instant(),
                    createdAt = fixedClock().instant(),
                    messages = listOf(ChatMessage(ChatRole.USER, "sanitized-$id")),
                )
            check(result.status == IngestStatus.CREATED)
        }
    }

    private fun insight(turn: StoredConversationTurn): ValidatedDreamingInsight =
        ValidatedDreamingInsight(
            content = "sanitized-${turn.sessionTurnId}",
            sources =
                listOf(
                    MemorySourceRef.ConversationTurnRef(
                        runtimeId = turn.runtimeId,
                        agentId = turn.agentId,
                        sessionId = turn.sessionId,
                        sessionTurnId = turn.sessionTurnId,
                    ),
                ),
        )
}
