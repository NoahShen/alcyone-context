package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitException
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitter
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.extraction.ValidatedExtractionMemory
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CT11-B1 A01/A02（真实 SQLite）：原子提交的 Raw/Pending/Target 一致性、零记忆、
 * Target 状态变化整笔回滚、Raw ID 碰撞重试与耗尽、取消传播、无 Organization 通知。
 */
class ExtractionCommitterTest {
    private val session = SessionKey("rt", "agent", "s1")
    private val base: Instant = Instant.parse("2026-10-31T02:00:00Z")
    private val committedAt: Instant = base.plusSeconds(1_000)
    private val clock: Clock = Clock.fixed(committedAt, ZoneOffset.UTC)

    @Test
    fun `a non empty batch writes raw pending rows and completes targets atomically`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.COMPLETE)
            insertTurn(database, session, "t3", base.plusSeconds(2), TurnStatus.PENDING)
            val context = contextOf(database)
            val memories =
                listOf(
                    ValidatedExtractionMemory("memory-1", listOf(source(context, 1), source(context, 2))),
                    ValidatedExtractionMemory("memory-2", listOf(source(context, 3))),
                )

            val committer =
                ExtractionCommitter(database, ScriptedIdGenerator(listOf("raw-1", "raw-2")), clock)
            val result = committer.commit(context, memories)

            assertEquals(2, result.rawCount)
            val raws = database.read { read -> read.rawMemories.scanPage(0, 10) }
            assertEquals(listOf("raw-1", "raw-2"), raws.map { it.id })
            assertEquals(listOf("memory-1", "memory-2"), raws.map { it.content })
            assertTrue(raws.all { memory -> memory.generatedBy == MemoryOrigin.EXTRACTION })
            assertEquals(memories[0].sources, raws[0].sources)
            assertTrue(raws.all { memory -> memory.createdAt == committedAt }, "同批 createdAt 必须一致")
            database.read { read ->
                raws.forEach { memory -> assertTrue(read.pending.exists(memory.id), "每个 Raw 必须入队") }
            }
            assertEquals(
                listOf(
                    "t1" to ExtractionStatus.COMPLETE,
                    "t2" to ExtractionStatus.COMPLETE,
                    "t3" to ExtractionStatus.COMPLETE,
                ),
                statuses(database),
                "Target 完成、History 不变",
            )
        }

    @Test
    fun `an empty memory batch only completes the targets`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.PENDING)
            val context = contextOf(database)

            val committer = ExtractionCommitter(database, ScriptedIdGenerator(emptyList()), clock)
            val result = committer.commit(context, emptyList())

            assertEquals(0, result.rawCount)
            assertTrue(database.read { read -> read.rawMemories.scanPage(0, 10) }.isEmpty())
            assertTrue(!database.read { read -> read.pending.hasAnyPending() })
            assertEquals(
                listOf("t1" to ExtractionStatus.COMPLETE, "t2" to ExtractionStatus.COMPLETE),
                statuses(database),
            )
        }

    @Test
    fun `a target state change rolls the whole batch back`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.PENDING)
            val context = contextOf(database)
            // 提交前把其中一个 Target 改成 complete：条件更新汇总必然不匹配。
            database.write { tx -> tx.conversations.markComplete(context.turns.first().id) }

            val committer = ExtractionCommitter(database, ScriptedIdGenerator(listOf("raw-1")), clock)
            val failure =
                assertFailsWith<ExtractionCommitException> {
                    committer.commit(context, listOf(ValidatedExtractionMemory("memory", listOf(source(context, 1)))))
                }
            assertTrue(failure.message.orEmpty().contains("completion count mismatch"), failure.message.orEmpty())
            assertTrue(database.read { read -> read.rawMemories.scanPage(0, 10) }.isEmpty(), "整笔回滚，不得部分写")
            assertTrue(!database.read { read -> read.pending.hasAnyPending() })
        }

    @Test
    fun `a raw id collision retries with a freshly generated batch without overwriting`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val context = contextOf(database)
            val existing = RawMemory("existing", "existing-content", MemoryOrigin.EXTRACTION, listOf(source(context, 1)), base)
            database.write { tx -> tx.rawMemories.insertBatch(listOf(existing)) }

            val committer =
                ExtractionCommitter(
                    database,
                    // 首次尝试命中已有 ID；重试使用新 ID。
                    ScriptedIdGenerator(listOf("existing", "raw-new")),
                    clock,
                )
            val result =
                committer.commit(context, listOf(ValidatedExtractionMemory("memory", listOf(source(context, 1)))))

            assertEquals(1, result.rawCount)
            val raws = database.read { read -> read.rawMemories.scanPage(0, 10) }
            assertEquals(listOf("existing", "raw-new"), raws.map { it.id })
            assertEquals("existing-content", raws.first { memory -> memory.id == "existing" }.content, "已有 Raw 不得被覆盖")
            assertEquals(ExtractionStatus.COMPLETE, statuses(database).single().second)
        }

    @Test
    fun `an id collision that persists across all attempts fails without new raw rows`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val context = contextOf(database)
            val existing = RawMemory("existing", "existing-content", MemoryOrigin.EXTRACTION, listOf(source(context, 1)), base)
            database.write { tx -> tx.rawMemories.insertBatch(listOf(existing)) }

            val committer =
                ExtractionCommitter(database, ScriptedIdGenerator(List(10) { "existing" }), clock, maxIdAttempts = 3)
            val failure =
                assertFailsWith<ExtractionCommitException> {
                    committer.commit(context, listOf(ValidatedExtractionMemory("memory", listOf(source(context, 1)))))
                }
            assertTrue(failure.message.orEmpty().contains("after 3 attempts"), failure.message.orEmpty())
            assertEquals(1, database.read { read -> read.rawMemories.scanPage(0, 10) }.size, "不得产生新 Raw")
            assertEquals(ExtractionStatus.PENDING, statuses(database).single().second, "Target 仍 pending")
        }

    @Test
    fun `a context without pending targets is rejected`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.COMPLETE)
            val context = contextOf(database)
            val committer = ExtractionCommitter(database, ScriptedIdGenerator(listOf("raw-1")), clock)

            val failure =
                assertFailsWith<ExtractionCommitException> {
                    committer.commit(context, emptyList())
                }
            assertTrue(failure.message.orEmpty().contains("no pending target"), failure.message.orEmpty())
        }

    @Test
    fun `cancellation propagates unchanged`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val context = contextOf(database)
            val cancellingClock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = throw CancellationException("ct11-b1-commit-cancel")
                }
            val committer = ExtractionCommitter(database, ScriptedIdGenerator(listOf("raw-1")), cancellingClock)

            assertFailsWith<CancellationException> {
                committer.commit(context, listOf(ValidatedExtractionMemory("memory", listOf(source(context, 1)))))
            }
            assertTrue(database.read { read -> read.rawMemories.scanPage(0, 10) }.isEmpty())
            assertEquals(ExtractionStatus.PENDING, statuses(database).single().second)
        }

    @Test
    fun `the committer exposes no organization notifier in this batch`() {
        val parameterTypes = ExtractionCommitter::class.java.constructors.flatMap { it.parameterTypes.toList() }
        assertTrue(
            parameterTypes.none { type -> type.name.contains("Notifier") },
            "B1 提交器不得持有 Organization 通知依赖: ${parameterTypes.map { it.name }}",
        )
    }

    // ------------------------------------------------------------------ 工具

    private suspend fun contextOf(database: ContextDatabase): ExtractionContext =
        ExtractionContext(
            session,
            database.read { read ->
                read.conversations.sessionWindow(sessionKey = session, beforeTimestamp = null, beforeId = null, limit = 100)
            },
        )

    private fun source(
        context: ExtractionContext,
        turnId: Long,
    ): MemorySourceRef.ConversationTurnRef {
        val turn = context.turns.first { it.id == turnId }
        return MemorySourceRef.ConversationTurnRef(turn.runtimeId, turn.agentId, turn.sessionId, turn.sessionTurnId)
    }

    private suspend fun statuses(database: ContextDatabase): List<Pair<String, ExtractionStatus>> =
        database.read { read ->
            read.conversations
                .sessionWindow(sessionKey = session, beforeTimestamp = null, beforeId = null, limit = 100)
                .map { turn -> turn.sessionTurnId to turn.extractionStatus }
        }

    /** 脚本化 ID 生成器：按顺序返回；用尽即失败（暴露多余的重试）。 */
    private class ScriptedIdGenerator(
        private val ids: List<String>,
    ) : MemoryIdGenerator {
        private var index = 0

        override fun nextRawMemoryId(): String {
            check(index < ids.size) { "no more scripted raw ids" }
            return ids[index++]
        }

        override fun nextOrganizedMemoryId(): String = throw UnsupportedOperationException("not used in B1")
    }
}
