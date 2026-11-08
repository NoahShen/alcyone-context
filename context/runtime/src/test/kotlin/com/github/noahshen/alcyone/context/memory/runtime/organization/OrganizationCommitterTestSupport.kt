package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryTxOps
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

/** 真实临时 SQLite 数据库及其文件路径；路径仅供测试 trigger/原文断言使用。 */
internal class OrganizationTestDatabase internal constructor(
    val database: ContextDatabase,
    val path: Path,
    private val executor: ContextDatabaseExecutor,
) : AutoCloseable {
    override fun close() {
        executor.close()
    }
}

/** 每个用例使用独立临时目录、真实初始化库与真实 Persistence/Runtime Ops。 */
internal fun withOrganizationDatabase(block: suspend (OrganizationTestDatabase) -> Unit) {
    val directory = Files.createTempDirectory("ct15-organization-")
    try {
        val path = directory.resolve("data/context.db")
        ContextDatabaseInitializer.initialize(path)
        val executor = ContextDatabaseFactory.open(path)
        val testDatabase = OrganizationTestDatabase(ContextDatabaseImpl(executor), path, executor)
        try {
            runBlocking { block(testDatabase) }
        } finally {
            testDatabase.close()
        }
    } finally {
        deleteRecursively(directory)
    }
}

/** 按顺序返回 Organized ID；Raw ID 不属于 CT15 路径。 */
internal class ScriptedOrganizedIdGenerator(
    ids: List<String>,
) : MemoryIdGenerator {
    private val remaining = ArrayDeque(ids)

    override fun nextRawMemoryId(): String = throw UnsupportedOperationException("CT15 does not generate Raw IDs")

    override fun nextOrganizedMemoryId(): String = remaining.removeFirstOrNull() ?: throw AssertionError("no more scripted Organized IDs")
}

/** 固定或可推进的测试时钟，不读取墙钟。 */
internal class OrganizationClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}

/** 在提交器读取系统时间时抛出原始取消对象。 */
internal class CancellingOrganizationClock(
    private val failure: CancellationException,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = throw failure
}

/** 可注入精确影响行数错误的真实事务包装点。 */
internal enum class RowCountFaultPoint {
    ORGANIZED_INSERT,
    ORGANIZED_UPDATE,
    ORGANIZED_ADVANCE,
    SOURCE_INSERT,
    PENDING_DEQUEUE,
}

/**
 * 先执行真实事务操作，再向上报告受控的错误行数。
 *
 * 提交器必须因此抛 `ROW_COUNT_MISMATCH`，并由真实 `database.write` 回滚本次全部写入。
 */
internal class RowCountFaultDatabase(
    private val delegate: ContextDatabase,
    private val point: RowCountFaultPoint,
    private val reportedCount: Int,
) : ContextDatabase {
    override suspend fun <T> read(block: (ReadContext) -> T): T = delegate.read(block)

    override suspend fun <T> write(block: (TransactionContext) -> T): T =
        delegate.write { tx -> block(RowCountFaultTransaction(tx, point, reportedCount)) }
}

private class RowCountFaultTransaction(
    delegate: TransactionContext,
    point: RowCountFaultPoint,
    reportedCount: Int,
) : TransactionContext by delegate {
    override val organizedMemories: OrganizedMemoryTxOps =
        RowCountFaultOrganizedOps(delegate.organizedMemories, point, reportedCount)
    override val pending: OrganizationPendingTxOps =
        RowCountFaultPendingOps(delegate.pending, point, reportedCount)
}

private class RowCountFaultOrganizedOps(
    private val delegate: OrganizedMemoryTxOps,
    private val point: RowCountFaultPoint,
    private val reportedCount: Int,
) : OrganizedMemoryTxOps by delegate {
    override fun insertNew(memory: OrganizedMemory): Int = report(delegate.insertNew(memory), RowCountFaultPoint.ORGANIZED_INSERT)

    override fun update(memory: OrganizedMemory): Int = report(delegate.update(memory), RowCountFaultPoint.ORGANIZED_UPDATE)

    override fun advanceUpdatedAt(
        organizedId: String,
        next: Instant,
    ): Int = report(delegate.advanceUpdatedAt(organizedId, next), RowCountFaultPoint.ORGANIZED_ADVANCE)

    override fun insertSourceLink(
        organizedId: String,
        rawId: String,
    ): Int = report(delegate.insertSourceLink(organizedId, rawId), RowCountFaultPoint.SOURCE_INSERT)

    private fun report(
        actual: Int,
        current: RowCountFaultPoint,
    ): Int = if (point == current) reportedCount else actual
}

private class RowCountFaultPendingOps(
    private val delegate: OrganizationPendingTxOps,
    private val point: RowCountFaultPoint,
    private val reportedCount: Int,
) : OrganizationPendingTxOps by delegate {
    override fun dequeue(rawId: String): Int {
        val actual = delegate.dequeue(rawId)
        return if (point == RowCountFaultPoint.PENDING_DEQUEUE) reportedCount else actual
    }
}

/** 真实 COMMIT 成功后抛一次的“结果未知”包装；下一次调用完全委托真实库。 */
internal class UnknownCommitResultDatabase(
    private val delegate: ContextDatabase,
    private val failure: ContextPersistenceException,
) : ContextDatabase {
    private val shouldFail = AtomicBoolean(true)

    override suspend fun <T> read(block: (ReadContext) -> T): T = delegate.read(block)

    override suspend fun <T> write(block: (TransactionContext) -> T): T {
        val result = delegate.write(block)
        if (shouldFail.compareAndSet(true, false)) throw failure
        return result
    }
}

/** 在任何真实事务开始前抛持久化异常。 */
internal class FailingWriteDatabase(
    private val delegate: ContextDatabase,
    private val failure: ContextPersistenceException,
) : ContextDatabase {
    override suspend fun <T> read(block: (ReadContext) -> T): T = delegate.read(block)

    override suspend fun <T> write(block: (TransactionContext) -> T): T = throw failure
}

/**
 * Test-only 水位 seam：底层仍是真实 SQLite，只让 `maxUpdatedAt()` 返回 [Instant.MAX]。
 *
 * `TimeCodec` 的固定四位上海时间格式无法表示 `Instant.MAX` 的年份，而 D9 仍要求显式覆盖
 * 该溢出分支；因此在此包装点注入 MAX，不新增生产接口，也不伪造其余事务行为。
 */
internal class MaxWatermarkDatabase(
    private val delegate: ContextDatabase,
) : ContextDatabase {
    override suspend fun <T> read(block: (ReadContext) -> T): T = delegate.read(block)

    override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write { tx -> block(MaxWatermarkTransaction(tx)) }
}

private class MaxWatermarkTransaction(
    delegate: TransactionContext,
) : TransactionContext by delegate {
    override val organizedMemories: OrganizedMemoryTxOps = MaxWatermarkOrganizedOps(delegate.organizedMemories)
}

private class MaxWatermarkOrganizedOps(
    private val delegate: OrganizedMemoryTxOps,
) : OrganizedMemoryTxOps by delegate {
    override fun maxUpdatedAt(): Instant = Instant.MAX
}

/** 准备一条未关联 Raw（可选同时入 Pending）。 */
internal suspend fun prepareRaw(
    database: ContextDatabase,
    rawId: String,
    createdAt: Instant,
    pending: Boolean,
) {
    database.write { tx ->
        check(tx.rawMemories.insertBatch(listOf(RawMemory(rawId, "raw-$rawId", MemoryOrigin.EXTRACTION, emptyList(), createdAt))) == 1)
        if (pending) check(tx.pending.enqueue(rawId) == 1)
    }
}

/** 写入一条 Organized Memory 及其已有来源；来源 Raw 必须已存在。 */
internal suspend fun insertOrganized(
    database: ContextDatabase,
    memory: OrganizedMemory,
    sourceRawIds: List<String> = emptyList(),
) {
    database.write { tx ->
        check(tx.organizedMemories.insertNew(memory) == 1)
        sourceRawIds.forEach { rawId -> check(tx.organizedMemories.insertSourceLink(memory.id, rawId) == 1) }
    }
}

internal fun executeSqlite(
    path: Path,
    sql: String,
) {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { statement -> statement.execute(sql) }
    }
}

internal fun sqliteString(
    path: Path,
    sql: String,
): String? =
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null }
        }
    }

internal fun sqliteLong(
    path: Path,
    sql: String,
): Long =
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "query returned no row: $sql" }
                result.getLong(1)
            }
        }
    }

private fun deleteRecursively(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
