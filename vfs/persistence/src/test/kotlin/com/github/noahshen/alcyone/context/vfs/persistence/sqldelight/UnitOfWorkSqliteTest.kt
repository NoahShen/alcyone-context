package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A03：状态与事件在同一 SQLite 事务内全有或全无。
 *
 * 涉及挂起与取消，历史上出现过挂死；[Timeout] 让它变成一次失败而不是 48 分钟的空等。
 */
@Timeout(60)
class UnitOfWorkSqliteTest {
    private val path = VfsPath.parse("/notes/a.txt")
    private val metadata = NodeMetadata(setOf("tag"), "描述", JsonObject(emptyMap()))

    // A03-1：回调正常返回即提交，三类变更一起生效
    @Test
    fun `commit persists node, metadata and event together`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)
                val event = testEvent(null, "alcyone://notes/a.txt")

                val registered =
                    uow.inTransaction { scope ->
                        val node = scope.nodes.register(testRecord("/notes/a.txt"))
                        scope.metadata.put(node.id, metadata)
                        scope.events.append(event)
                        node
                    }

                assertEquals(registered.id, SqliteNodeRepository(state).findByPath(path)?.id)
                assertEquals(metadata, SqliteMetadataRepository(state).get(registered.id))
                assertEquals(listOf(event.id.value), state.eventRows().map { it.eventId })
            }
        }

    // A03-2：回调抛异常整体回滚，重开文件库确认无残留
    @Test
    fun `throwing inside the transaction leaves no residue after reopening`() {
        val dir = Files.createTempDirectory("alcyone-vfs-uow")
        val file: Path = dir.resolve("state.db")
        try {
            var nodeId: NodeId? = null
            runBlocking {
                VfsStateDatabase.file(file).use { state ->
                    val uow = SqliteUnitOfWork(state)
                    val failure =
                        runCatching {
                            uow.inTransaction { scope ->
                                val node = scope.nodes.register(testRecord("/notes/a.txt"))
                                nodeId = node.id
                                scope.metadata.put(node.id, metadata)
                                scope.events.append(testEvent(node.id, "alcyone://notes/a.txt"))
                                scope.nodes.markDeleted(listOf(node.id), TEST_NOW)
                                error("boom")
                            }
                        }.exceptionOrNull()
                    assertEquals("boom", failure?.message)
                }
            }
            val rolledBack = requireNotNull(nodeId)

            runBlocking {
                VfsStateDatabase.file(file).use { state ->
                    assertNull(SqliteNodeRepository(state).findByPath(path))
                    assertNull(SqliteNodeRepository(state).findById(rolledBack))
                    assertNull(SqliteMetadataRepository(state).get(rolledBack))
                    assertEquals(0L, state.eventCount())
                    assertEquals(0L, state.metadataCount())
                    assertEquals(0L, state.activeNodeCount())
                }
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
    }

    // A03-2：同一个事务内多次写入一起提交
    @Test
    fun `several writes in one transaction share a single commit`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)
                val nodes = SqliteNodeRepository(state)

                uow.inTransaction { scope ->
                    scope.nodes.register(testRecord("/notes/a.txt"))
                    scope.nodes.register(testRecord("/notes/b.txt"))
                }

                assertEquals(2L, state.activeNodeCount())
                assertEquals(2, nodes.findByPaths(listOf(path, VfsPath.parse("/notes/b.txt"))).size)
            }
        }

    // A03-1：回调返回值原样返回
    @Test
    fun `the block result is returned`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)

                val result =
                    uow.inTransaction { scope ->
                        scope.nodes.register(testRecord("/notes/a.txt"))
                        "committed"
                    }

                assertEquals("committed", result)
            }
        }

    // A03-3：事务视图逃逸到回调外抛 IllegalStateException，不退化成自动提交
    @Test
    fun `scope used after the block is rejected`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)
                var escaped: TransactionScope? = null
                val node = testRecord("/notes/a.txt")

                uow.inTransaction { scope ->
                    escaped = scope
                    scope.nodes.register(node)
                }

                val leaked = requireNotNull(escaped)
                assertThrows(IllegalStateException::class.java) { runBlocking { leaked.nodes.findByPath(path) } }
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { leaked.metadata.put(node.id, metadata) }
                }
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { leaked.events.append(testEvent(node.id, "alcyone://notes/a.txt")) }
                }
                assertEquals(0L, state.eventCount())
            }
        }

    // A03-3：回调抛出时作用域同样失效
    @Test
    fun `scope used after a failed block is rejected`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)
                var escaped: TransactionScope? = null

                runCatching {
                    uow.inTransaction<Unit> { scope ->
                        escaped = scope
                        error("boom")
                    }
                }

                val leaked = requireNotNull(escaped)
                assertThrows(IllegalStateException::class.java) { runBlocking { leaked.nodes.findByPath(path) } }
            }
        }

    // A03-3：CancellationException 原样传播，不被包装成 STATE_ERROR
    @Test
    fun `cancellation thrown in the block rolls back and is not mapped to STATE_ERROR`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)
                val cancellation = CancellationException("cancelled")
                val node = testRecord("/notes/a.txt")

                val thrown =
                    runCatching {
                        uow.inTransaction<Unit> { scope ->
                            scope.nodes.register(node)
                            scope.events.append(testEvent(node.id, "alcyone://notes/a.txt"))
                            throw cancellation
                        }
                    }.exceptionOrNull()

                // kotlinx 在取消 withContext 作用域时会另建 CancellationException 实例，类型与消息保持不变；
                // 这里要求的「原样传播」指它仍是取消而不是被改写成 VfsException(STATE_ERROR)。
                assertEquals(CancellationException::class.java, thrown?.javaClass)
                assertEquals("cancelled", thrown?.message)
                assertNull(SqliteNodeRepository(state).findByPath(path))
                assertEquals(0L, state.eventCount())
            }
        }

    // A03-3：调用方协程被取消时同样回滚并向上传播，调用方其余部分不受影响
    @Test
    fun `cancelling the coroutine that runs a transaction rolls back`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)
                val written = CompletableDeferred<Unit>()

                val job =
                    launch {
                        uow.inTransaction<Unit> { scope ->
                            scope.nodes.register(testRecord("/notes/a.txt"))
                            written.complete(Unit)
                            awaitCancellation()
                        }
                    }
                written.await()
                job.cancelAndJoin()

                assertTrue(job.isCancelled)
                assertNull(SqliteNodeRepository(state).findByPath(path))
                // 取消不影响同一个状态库的后续使用。
                val afterCancel = SqliteNodeRepository(state).register(testRecord("/notes/a.txt"))
                assertEquals(afterCancel, SqliteNodeRepository(state).findByPath(path))
            }
        }

    // 事务内的事务视图同样走数据库约束：占用中的路径仍然复用既有记录
    @Test
    fun `registration inside a transaction keeps the R3 competition semantics`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val uow = SqliteUnitOfWork(state)

                val (first, second) =
                    uow.inTransaction { scope ->
                        val a = scope.nodes.register(testRecord("/notes/a.txt"))
                        val b = scope.nodes.register(testRecord("/notes/a.txt"))
                        a to b
                    }

                assertEquals(first, second)
                assertEquals(1L, state.activeNodeCount())
            }
        }

    /**
     * R1 回归：BEGIN 已在 IO 线程上执行成功后取消调用协程，事务必须被回滚掉。
     *
     * 控制顺序：BEGIN 真的执行 → `job.cancel()` → 放行 IO 执行块。于是 `withContext` 切回已取消的协程时
     * 抛 CancellationException，修复前它发生在回滚与 `finally` 之前，连接上就留下一个开着的事务，
     * 下一个 `inTransaction` 直接报 "cannot start a transaction within a transaction"。
     */
    @Test
    fun `cancelling the coroutine after BEGIN succeeded leaves no open transaction`() =
        runBlocking {
            val beginExecuted = CountDownLatch(1)
            val releaseBegin = CountDownLatch(1)
            val gated = AtomicBoolean(false)
            hookedState { sql, prepare ->
                if (sql == BEGIN_SQL && gated.compareAndSet(false, true)) {
                    val statement = prepare() // BEGIN 在这条连接上确实执行了
                    beginExecuted.countDown()
                    check(releaseBegin.await(30, TimeUnit.SECONDS)) { "测试没有放行 BEGIN 的 IO 执行块" }
                    statement
                } else {
                    prepare()
                }
            }.use { state ->
                val uow = SqliteUnitOfWork(state)
                var blockRan = false

                val job = launch { uow.inTransaction { blockRan = true } }
                // 等 BEGIN 与取消必须在 IO 线程上做：直接 await 会把 runBlocking 的事件循环线程卡住，
                // launch 的协程根本没机会开始跑。
                withContext(Dispatchers.IO) {
                    assertTrue(beginExecuted.await(30, TimeUnit.SECONDS), "BEGIN 没有在 IO 线程上执行")
                    job.cancel()
                    releaseBegin.countDown()
                }
                job.join()

                assertTrue(job.isCancelled)
                // 取消落在 BEGIN 之后、业务回调之前：回调没跑，事务里也没留下任何写入。
                assertFalse(blockRan)
                assertEquals(0L, state.activeNodeCount())

                // 关键断言：连接上不再挂着事务，下一次事务能正常提交。
                val committed = uow.inTransaction { scope -> scope.nodes.register(testRecord("/notes/a.txt")) }
                assertEquals(committed, SqliteNodeRepository(state).findByPath(path))
                assertEquals(1L, state.activeNodeCount())
            }
        }

    /**
     * R1 回归：本次调用没拿到事务（BEGIN 失败）时不得回滚——那个事务是别人的。
     *
     * 用真实的 SQLite 拒绝制造这个场景：先手动开一个事务并写入，再让 `inTransaction` 撞上去。
     */
    @Test
    fun `a failed BEGIN does not roll back the transaction that is already open`() {
        val statements = CopyOnWriteArrayList<String>()
        hookedState { sql, prepare ->
            statements += sql
            prepare()
        }.use { state ->
            val record = testRecord("/notes/held.txt")
            // 别人的事务：直接在驱动上 BEGIN，绕开 UnitOfWork。
            state.driver.execute(null, BEGIN_SQL, 0)
            runBlocking { SqliteNodeRepository(state).register(record) }

            val failure =
                assertThrows(VfsException::class.java) {
                    runBlocking { SqliteUnitOfWork(state).inTransaction<Unit> { error("不该进回调") } }
                }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            // 两个 BEGIN 都发生过（手动一个 + 被拒一个），但没有一次 ROLLBACK。
            assertEquals(2, statements.count { it == BEGIN_SQL })
            assertFalse(statements.contains(ROLLBACK_SQL), "失败的 BEGIN 不该发出 ROLLBACK")

            // 别人的事务还活着：提交后数据在，自动提交写入也照常可用。
            state.driver.execute(null, COMMIT_SQL, 0)
            runBlocking { assertEquals(record, SqliteNodeRepository(state).findByPath(record.path)) }
            assertEquals(1L, state.activeNodeCount())
        }
    }
}
