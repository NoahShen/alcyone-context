package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CM1 全面复查 P2：所有分页入口在**执行 SQL 之前**拒绝负 limit / 负 offset；
 * `limit = 0`、`offset = 0` 合法（limit=0 返回空页）。
 */
class PaginationArgumentValidationTest {
    private val session = SessionKey("runtime-p2", "agent-p2", "session-p2")
    private val t0: Instant = Instant.parse("2026-10-29T00:00:00.000Z")

    @Test
    fun `sessionWindow rejects a negative limit and returns an empty page for zero`() =
        withExecutor { executor ->
            insertTurn(executor, "turn-1", t0)
            insertTurn(executor, "turn-2", t0.plusSeconds(1))

            val invalid =
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { executor.read { ConversationReadOpsImpl(it).sessionWindow(session, null, null, -1) } }
                }
            assertInvalid(invalid, "limit", "-1")

            assertEquals(
                emptyList(),
                runBlocking { executor.read { ConversationReadOpsImpl(it).sessionWindow(session, null, null, 0) } },
            )
            assertEquals(2, runBlocking { executor.read { ConversationReadOpsImpl(it).sessionWindow(session, null, null, 10) }.size })
            assertHealthy(executor)
        }

    @Test
    fun `sessionWindowAfter rejects a negative limit and returns an empty page for zero`() =
        withExecutor { executor ->
            val anchor = insertTurn(executor, "turn-1", t0)
            insertTurn(executor, "turn-2", t0.plusSeconds(1))

            val invalid =
                assertFailsWith<IllegalArgumentException> {
                    runBlocking {
                        executor.read { read ->
                            ConversationReadOpsImpl(read).sessionWindowAfter(session, t0, anchor, -1)
                        }
                    }
                }
            assertInvalid(invalid, "limit", "-1")
            assertEquals(
                emptyList(),
                runBlocking { executor.read { read -> ConversationReadOpsImpl(read).sessionWindowAfter(session, t0, anchor, 0) } },
            )
            assertEquals(
                1,
                runBlocking { executor.read { read -> ConversationReadOpsImpl(read).sessionWindowAfter(session, t0, anchor, 10) } }.size,
            )
            assertHealthy(executor)
        }

    @Test
    fun `idRange rejects a negative limit and returns an empty page for zero`() =
        withExecutor { executor ->
            val first = insertTurn(executor, "turn-1", t0)
            insertTurn(executor, "turn-2", t0.plusSeconds(1))

            val invalid =
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { executor.read { ConversationReadOpsImpl(it).idRange(0, Long.MAX_VALUE, -1) } }
                }
            assertInvalid(invalid, "limit", "-1")

            assertEquals(emptyList(), runBlocking { executor.read { ConversationReadOpsImpl(it).idRange(0, Long.MAX_VALUE, 0) } })
            assertEquals(
                listOf(first),
                runBlocking { executor.read { ConversationReadOpsImpl(it).idRange(0, Long.MAX_VALUE, 1) }.map { it.id } },
            )
            assertHealthy(executor)
        }

    @Test
    fun `raw scanPage rejects negative offset and limit but allows zero`() =
        withExecutor { executor ->
            executor.write { tx -> RawMemoryTxOpsImpl(tx).insertBatch(listOf(raw("raw-1"), raw("raw-2"))) }

            val negativeLimit =
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { executor.read { RawMemoryReadOpsImpl(it).scanPage(0, -1) } }
                }
            assertInvalid(negativeLimit, "limit", "-1")

            val negativeOffset =
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { executor.read { RawMemoryReadOpsImpl(it).scanPage(-1, 10) } }
                }
            assertInvalid(negativeOffset, "offset", "-1")

            assertEquals(emptyList(), runBlocking { executor.read { RawMemoryReadOpsImpl(it).scanPage(0, 0) } })
            assertEquals(2, runBlocking { executor.read { RawMemoryReadOpsImpl(it).scanPage(0, 10) }.size })
            assertHealthy(executor)
        }

    @Test
    fun `organized scanPage rejects negative offset and limit but allows zero`() =
        withExecutor { executor ->
            executor.write { tx ->
                OrganizedMemoryTxOpsImpl(tx).insertNew(organized("org-1"))
                OrganizedMemoryTxOpsImpl(tx).insertNew(organized("org-2"))
            }

            val negativeLimit =
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { executor.read { OrganizedMemoryReadOpsImpl(it).scanPage(0, -1) } }
                }
            assertInvalid(negativeLimit, "limit", "-1")

            val negativeOffset =
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { executor.read { OrganizedMemoryReadOpsImpl(it).scanPage(-5, 10) } }
                }
            assertInvalid(negativeOffset, "offset", "-5")

            assertEquals(emptyList(), runBlocking { executor.read { OrganizedMemoryReadOpsImpl(it).scanPage(0, 0) } })
            assertEquals(2, runBlocking { executor.read { OrganizedMemoryReadOpsImpl(it).scanPage(0, 10) }.size })
            assertHealthy(executor)
        }

    // ------------------------------------------------------------------ 夹具

    private fun withExecutor(block: suspend (ContextDatabaseExecutor) -> Unit) {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor -> runBlocking { block(executor) } }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    private fun insertTurn(
        executor: ContextDatabaseExecutor,
        sessionTurnId: String,
        timestamp: Instant,
    ): Long =
        runBlocking {
            executor.write { tx ->
                ConversationTxOpsImpl(tx)
                    .insertTurn(
                        session.runtimeId,
                        session.agentId,
                        session.sessionId,
                        sessionTurnId,
                        timestamp,
                        timestamp,
                        listOf(ChatMessage(ChatRole.USER, "hello")),
                    ).id
            }
        }

    private fun raw(id: String): RawMemory = RawMemory(id, "content-$id", MemoryOrigin.EXTRACTION, emptyList(), t0)

    private fun organized(id: String): OrganizedMemory = OrganizedMemory(id, "content-$id", listOf("domain-a"), emptyList(), t0, t0)

    private fun assertInvalid(
        failure: IllegalArgumentException,
        field: String,
        value: String,
    ) {
        assertTrue(failure.message.orEmpty().contains(field), "消息应含字段名 $field，实际: ${failure.message}")
        assertTrue(failure.message.orEmpty().contains(value), "消息应含非法值 $value，实际: ${failure.message}")
        assertTrue(!failure.message.orEmpty().contains("LIMIT"), "消息不得回显 SQL 原文: ${failure.message}")
        assertTrue(!failure.message.orEmpty().contains("OFFSET"), "消息不得回显 SQL 原文: ${failure.message}")
    }

    /** 失败后执行器仍然健康：autoCommit 已恢复，写事务可继续。 */
    private fun assertHealthy(executor: ContextDatabaseExecutor) {
        assertTrue(executor.writeSlotAutoCommit(), "分页参数失败不得留下未结束的事务")
        runBlocking {
            executor.write { tx ->
                RawMemoryTxOpsImpl(tx).insertBatch(listOf(raw("raw-after-failure")))
            }
        }
        assertEquals(
            1L,
            runBlocking {
                executor.queryRaw("SELECT count(*) FROM raw_memory WHERE id = 'raw-after-failure'") { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            },
        )
    }
}
