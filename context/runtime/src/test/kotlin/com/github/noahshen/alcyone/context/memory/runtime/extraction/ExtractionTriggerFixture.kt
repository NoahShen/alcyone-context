package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger

/** 轮次状态（测试用，用于插入 pending/complete/failed 三类行）。 */
internal enum class TurnStatus {
    PENDING,
    COMPLETE,
    FAILED,
}

/**
 * CT09-B2 触发测试夹具：真实临时目录 + 真实 Context SQLite（`ContextDatabaseImpl`），不使用替身库。
 *
 * 每个用例结束关闭执行器并清理目录；`createdAt`/`timestamp` 由调用方显式指定。
 */
internal fun withTriggerDatabase(block: suspend (ContextDatabase) -> Unit) {
    val directory = Files.createTempDirectory("ct09-b2-trigger-")
    try {
        val databasePath = directory.resolve("data/context.db")
        Files.createDirectories(databasePath.parent)
        ContextDatabaseInitializer.initialize(databasePath)
        ContextDatabaseFactory.open(databasePath).use { executor ->
            runBlocking { block(ContextDatabaseImpl(executor)) }
        }
    } finally {
        deleteRecursively(directory)
    }
}

/** 插入一条轮次并可选地标记为 complete/failed；`createdAt` 决定 Idle 判定。 */
internal suspend fun insertTurn(
    database: ContextDatabase,
    session: SessionKey,
    sessionTurnId: String,
    createdAt: Instant,
    status: TurnStatus = TurnStatus.PENDING,
    timestamp: Instant = createdAt,
) {
    database.write { tx ->
        val inserted =
            tx.conversations.insertTurn(
                session.runtimeId,
                session.agentId,
                session.sessionId,
                sessionTurnId,
                timestamp,
                createdAt,
                listOf(ChatMessage(ChatRole.USER, "content-$sessionTurnId")),
            )
        when (status) {
            TurnStatus.PENDING -> Unit
            TurnStatus.COMPLETE -> tx.conversations.markComplete(inserted.id)
            TurnStatus.FAILED -> tx.conversations.markFailed(inserted.id)
        }
    }
}

/**
 * 受控失败包装：前 [failures] 次 `read` 抛 [failure]（默认普通异常），之后委托真实库。
 *
 * 用于验证「查询失败 → 隔离/reporter、不触发、下次成功」而无需破坏真实 SQLite。
 */
internal class FlakyDatabase(
    private val delegate: ContextDatabase,
    failures: Int,
    private val failure: () -> Throwable = { IllegalStateException("ct09-b2-query-failure") },
) : ContextDatabase {
    private val remainingFailures = AtomicInteger(failures)

    override suspend fun <T> read(block: (ReadContext) -> T): T {
        if (remainingFailures.getAndDecrement() > 0) throw failure()
        return delegate.read(block)
    }

    override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)
}

/** 可变时钟：Idle 边界与天文级 Instant 用例共用，不读墙钟。 */
internal class MutableClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}

private fun deleteRecursively(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
