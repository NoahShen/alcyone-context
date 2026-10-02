package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MetadataRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个 SQLite 事务（T11 §2.3、T03 §4）。
 *
 * **为什么能写得这么直白**：状态库整库只有一条连接（见 [SingleConnectionJdbcDriver]），
 * 而 SQLite 事务属于连接、不属于线程，所以事务体里的每次挂起都可以去任意线程执行语句，
 * 不需要把回调钉在某个线程上，也就不会出现「固定线程 + 阻塞驱动挂起回调」的死锁。
 *
 * **不用 [kotlinx.coroutines.runBlocking]**：那会在外层被取消的协程里另起一个阻塞事件循环，
 * 外层等它结束、内层等同一个协程，已实际造成挂死（测试跑了 48 分钟）。这里只用普通挂起协程
 * 加显式 SQL 事务控制。
 *
 * 回滚包在 [NonCancellable] 里：协程已被取消时也必须执行，否则事务与写锁会留在库里。
 *
 * **BEGIN 也在事务生命周期内**：`BEGIN` 是在 IO 线程上真正执行的，切回调用协程时如果它已被取消，
 * [withContext] 会抛 CancellationException。所以 BEGIN 必须在 `try` 之内，并且只有本次调用**确实取得**了事务
 * （标志在同一个 IO 执行块里置位）才回滚：BEGIN 自己失败时事务属于别人，回滚它等于破坏别人的数据。
 *
 * **并发边界**（首版不支持）：整库只有一条连接，多个协程并发调用 [inTransaction] 会在同一连接上交错，
 * 第二个 `BEGIN` 直接被 SQLite 拒绝，以 STATE_ERROR 失败（不会静默写坏数据）。
 * 调用方需要串行使用状态变更，串行化由上层编排负责（T13 / T15）。
 *
 * 事务只覆盖状态库内部，不承诺回滚外部 Storage 操作（T03 §3）；首版不支持嵌套事务。
 */
class SqliteUnitOfWork(
    private val state: VfsStateDatabase,
) : UnitOfWork {
    override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T {
        val guard = ScopeGuard()
        val scope =
            object : TransactionScope {
                override val nodes: NodeRepository = TransactionNodeRepository(state.database.nodeQueries, guard)
                override val metadata: MetadataRepository = TransactionMetadataRepository(state.database.metadataQueries, guard)
                override val events: EventRepository = TransactionEventRepository(state.database.eventQueries, guard)
            }
        val driver = state.driver
        // BEGIN 真的在 IO 线程上执行成功了吗？只有那里知道，所以标志在同一个执行块里置位。
        // 用 AtomicBoolean 是因为 IO 线程与调用协程不在同一个线程，局部捕获变量没有可见性保证。
        val transactionStarted = AtomicBoolean(false)
        return try {
            // IMMEDIATE 立即取写锁：延迟升级锁在并发写入时会中途失败，不如一开始就失败得明确。
            stateCall {
                driver.execute(null, BEGIN_SQL, 0)
                transactionStarted.set(true)
            }
            val result = block(scope)
            stateCall { driver.execute(null, COMMIT_SQL, 0) }
            result
        } catch (failure: Throwable) {
            // 只有本次调用真的开过事务才回滚：BEGIN 失败时连接上的事务不是本次的。
            if (transactionStarted.get()) {
                // 回滚是非挂起的 JDBC 调用，NonCancellable 保证取消路径也能执行；它失败不掩盖原始异常。
                runCatching { withContext(NonCancellable) { stateCall { driver.execute(null, ROLLBACK_SQL, 0) } } }
                    .exceptionOrNull()
                    ?.let { failure.addSuppressed(it) }
            }
            throw failure
        } finally {
            // 无论提交还是回滚，作用域都失效：逃逸到回调外只会拿到 IllegalStateException，
            // 不会退化成自动提交写入。
            guard.close()
        }
    }
}

/** 事务作用域的有效期闸门。 */
private class ScopeGuard {
    @Volatile
    private var open = true

    fun ensureOpen() {
        check(open) { "TransactionScope used outside of inTransaction block" }
    }

    fun close() {
        open = false
    }
}

private class TransactionNodeRepository(
    private val queries: NodeQueries,
    private val guard: ScopeGuard,
) : NodeRepository {
    override suspend fun findByPath(path: VfsPath): NodeRecord? = guarded { queries.findByPath(path) }

    override suspend fun findById(id: NodeId): NodeRecord? = guarded { queries.findById(id) }

    override suspend fun register(record: NodeRecord): NodeRecord = guarded { queries.register(record) }

    override suspend fun updatePath(
        id: NodeId,
        newPath: VfsPath,
        updatedAt: Instant,
    ) {
        guarded { queries.updatePath(id, newPath, updatedAt) }
    }

    override suspend fun touch(
        id: NodeId,
        updatedAt: Instant,
    ) {
        guarded { queries.touch(id, updatedAt) }
    }

    override suspend fun markDeleted(
        ids: Collection<NodeId>,
        deletedAt: Instant,
    ) {
        guarded { queries.markDeleted(ids, deletedAt) }
    }

    override suspend fun findSubtree(path: VfsPath): List<NodeRecord> = guarded { queries.findSubtree(path) }

    override suspend fun findByPaths(paths: List<VfsPath>): List<NodeRecord> = guarded { queries.findByPaths(paths) }

    /** 作用域有效期内才执行；阻塞 JDBC 走 IO 调度器，与自动提交 Repository 一致。 */
    private suspend fun <T> guarded(block: () -> T): T {
        guard.ensureOpen()
        return stateCall(block)
    }
}

private class TransactionMetadataRepository(
    private val queries: MetadataQueries,
    private val guard: ScopeGuard,
) : MetadataRepository {
    override suspend fun get(id: NodeId): NodeMetadata? = guarded { queries.readMetadata(id) }

    override suspend fun put(
        id: NodeId,
        metadata: NodeMetadata,
    ) {
        guarded { queries.writeMetadata(id, metadata) }
    }

    override suspend fun delete(id: NodeId) {
        guarded { queries.removeMetadata(id) }
    }

    private suspend fun <T> guarded(block: () -> T): T {
        guard.ensureOpen()
        return stateCall(block)
    }
}

private class TransactionEventRepository(
    private val queries: EventQueries,
    private val guard: ScopeGuard,
) : EventRepository {
    override suspend fun append(event: EventRecord) {
        guard.ensureOpen()
        stateCall { queries.append(event) }
    }
}
