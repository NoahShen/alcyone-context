package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * CT05-B1 测试夹具：真实文件 SQLite + 真实执行器，不使用内存库或测试替身。
 *
 * 时间固定为可预测的 UTC 时刻，便于断言库内 `+08:00` 文本。
 */
internal object ConversationFixtures {
    val SESSION: SessionKey = SessionKey("runtime-1", "agent-1", "session-1")
    val OTHER_SESSION: SessionKey = SessionKey("runtime-1", "agent-1", "session-2")

    /** 2026-10-28T00:30:00.123Z → 库内 `2026-10-28T08:30:00.123+08:00`。 */
    val TIMESTAMP: Instant = Instant.parse("2026-10-28T00:30:00.123Z")

    /** 2026-10-28T00:31:00.456Z → 库内 `2026-10-28T08:31:00.456+08:00`。 */
    val CREATED_AT: Instant = Instant.parse("2026-10-28T00:31:00.456Z")

    fun messages(vararg contents: String): List<ChatMessage> =
        contents.mapIndexed { index, content ->
            ChatMessage(if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT, content)
        }

    fun userMessage(content: String): List<ChatMessage> = listOf(ChatMessage(ChatRole.USER, content))

    /** 新建临时库 + 打开真实执行器，回调结束后关闭并清理目录。 */
    fun withDatabase(block: suspend (ContextDatabaseExecutor) -> Unit) {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor -> runBlocking { block(executor) } }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    suspend fun insert(
        executor: ContextDatabaseExecutor,
        sessionTurnId: String,
        timestamp: Instant = TIMESTAMP,
        createdAt: Instant = CREATED_AT,
        messages: List<ChatMessage> = userMessage("hello"),
        session: SessionKey = SESSION,
    ): IngestResult =
        executor.write { tx ->
            ConversationTxOpsImpl(tx).insertTurn(session, sessionTurnId, timestamp, createdAt, messages)
        }

    suspend fun markComplete(
        executor: ContextDatabaseExecutor,
        id: Long,
    ): Int = executor.write { tx -> ConversationTxOpsImpl(tx).markComplete(id) }

    suspend fun markFailed(
        executor: ContextDatabaseExecutor,
        id: Long,
    ): Int = executor.write { tx -> ConversationTxOpsImpl(tx).markFailed(id) }

    suspend fun countTurns(executor: ContextDatabaseExecutor): Long =
        executor.queryRaw("SELECT count(*) FROM conversation_turn") { rs ->
            rs.next()
            rs.getLong(1)
        }
}
