package com.github.noahshen.alcyone.context.vfs.core.storage

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.transaction.FakeStateUnitOfWork
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * B6 故障注入：Storage 失败时不产生任何状态变更与事件，且事务根本没有开启。
 *
 * 这里的「最小编排」只用直线代码表达「先调 Storage、成功后才开事务」这一条顺序，
 * 不代表 Core 的业务编排（读写流程串联属 T15）。
 */
class StorageFailureIsolationTest {
    private val now: Instant = Instant.parse("2026-09-29T00:00:00Z")

    private fun record(path: String): NodeRecord =
        NodeRecord(
            id = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000a1"),
            path = VfsPath.parse(path),
            type = NodeType.FILE,
            physical = true,
            registeredAt = now,
            updatedAt = now,
        )

    private fun eventRecord(path: String): EventRecord =
        EventRecord(
            id = VfsEventId.parse("018f0a5c-1b2c-7def-8abc-0000000000b1"),
            type = VfsEventType.FILE_CREATED,
            nodeId = record(path).id,
            occurredAt = now,
            uri = VfsUri.parse("alcyone://resources$path"),
        )

    private fun injected(): VfsException = VfsException(VfsErrorCode.STORAGE_ERROR, "injected storage failure")

    /** 断言：状态与事件均未变，且事务从未开启。 */
    private fun assertNoStateAndNoTransaction(
        uow: FakeStateUnitOfWork,
        expectedNodes: Int,
    ) {
        val (nodes, _, events) = uow.snapshot()
        assertEquals(expectedNodes, nodes.size, "no state change is expected")
        assertTrue(events.isEmpty(), "no event is expected")
        assertTrue(uow.calls.isEmpty(), "transaction must not even be opened: ${uow.calls}")
    }

    // 场景 1: 读失败 → 无任何状态变更、无事件、事务未开启
    @Test
    fun `read failure leaves state and event untouched and never opens a transaction`() =
        runBlocking {
            val storage = StorageFakeImpl()
            val uow = FakeStateUnitOfWork()
            val path = StoragePath.parse("notes/a.txt")
            storage.failures.onRead = injected()

            // 最小编排：读是纯查询，本来就不该有状态写入；这里同时验证流式读也被拦截
            assertThrowsStorageError { storage.read(path, maxBytes = 1024) }
            assertThrowsStorageError { storage.readStream(path) }

            assertNoStateAndNoTransaction(uow, expectedNodes = 0)
            assertEquals(listOf("read:notes/a.txt", "readStream:notes/a.txt"), storage.calls)
        }

    // 场景 2: 写失败 → 无任何状态变更、无事件、事务未开启
    @Test
    fun `write failure leaves state and event untouched and never opens a transaction`() =
        runBlocking {
            val storage = StorageFakeImpl()
            val uow = FakeStateUnitOfWork()
            val path = StoragePath.parse("notes/a.txt")
            storage.failures.onWrite = injected()

            // 最小编排：先 Storage 写，成功后才开事务注册 Node 并追加事件
            assertThrowsStorageError { storage.write(path, "hello".toByteArray(), StorageWriteMode.UPSERT) }

            assertNoStateAndNoTransaction(uow, expectedNodes = 0)
            assertEquals(listOf("write:notes/a.txt"), storage.calls)
        }

    // 场景 3: 移动失败 → 源与目标的 Node 记录都不变、无事件、事务未开启
    @Test
    fun `move failure keeps both source and target records unchanged and never opens a transaction`() =
        runBlocking {
            val storage = StorageFakeImpl()
            val source = StoragePath.parse("notes/a.txt")
            val target = StoragePath.parse("archive/a.txt")
            storage.write(source, "hello".toByteArray(), StorageWriteMode.CREATE_NEW)

            val uow = FakeStateUnitOfWork(initialNodes = listOf(record("/notes/a.txt")))
            storage.failures.onMove = injected()

            assertThrowsStorageError { storage.move(source, target) }

            val (nodes, _, events) = uow.snapshot()
            assertEquals(1, nodes.size, "source record must stay")
            assertEquals(VfsPath.parse("/notes/a.txt"), nodes[0].path)
            assertTrue(events.isEmpty())
            assertTrue(uow.calls.isEmpty(), "transaction must not even be opened: ${uow.calls}")
            assertTrue(uow.calls.none { it == "commit" })
        }

    // 场景 4: 删除失败 → 逻辑记录仍有效（未被标记删除）、无事件、事务未开启
    @Test
    fun `delete failure keeps the logical record valid and never opens a transaction`() =
        runBlocking {
            val storage = StorageFakeImpl()
            val path = StoragePath.parse("notes/a.txt")
            storage.write(path, "hello".toByteArray(), StorageWriteMode.CREATE_NEW)

            val uow = FakeStateUnitOfWork(initialNodes = listOf(record("/notes/a.txt")))
            storage.failures.onDelete = injected()

            assertThrowsStorageError { storage.delete(path, recursive = false) }

            val (nodes, _, events) = uow.snapshot()
            assertEquals(1, nodes.size, "logical record must remain valid")
            assertEquals(VfsPath.parse("/notes/a.txt"), nodes[0].path)
            assertTrue(events.isEmpty())
            assertTrue(uow.calls.isEmpty(), "transaction must not even be opened: ${uow.calls}")
            assertTrue(uow.calls.none { it.startsWith("nodes.markDeleted") })
        }

    // 对照组：Storage 成功后才开启事务，事务正常提交并产生事件
    @Test
    fun `successful storage operation does open the transaction and commits event`() =
        runBlocking {
            val storage = StorageFakeImpl()
            val uow = FakeStateUnitOfWork()
            val path = StoragePath.parse("notes/a.txt")
            val target = record("/notes/a.txt")

            // 最小编排：Storage 成功 → 开事务 → 注册 Node → 追加事件 → 提交
            storage.write(path, "hello".toByteArray(), StorageWriteMode.UPSERT)
            uow.inTransaction { tx ->
                tx.nodes.register(target)
                tx.events.append(eventRecord("/notes/a.txt"))
            }

            val (nodes, _, events) = uow.snapshot()
            assertEquals(1, nodes.size)
            assertNotNull(nodes.firstOrNull())
            assertEquals(1, events.size)
            assertEquals(listOf("commit"), uow.calls.filter { it == "commit" })
            assertTrue(uow.calls.isNotEmpty(), "transaction must have been opened in the success path")
        }

    private suspend fun assertThrowsStorageError(block: suspend () -> Unit) {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue(thrown is VfsException, "expected VfsException but got $thrown")
        assertEquals(VfsErrorCode.STORAGE_ERROR, (thrown as VfsException).code)
    }
}
