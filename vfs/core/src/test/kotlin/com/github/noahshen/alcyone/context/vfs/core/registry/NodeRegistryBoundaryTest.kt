package com.github.noahshen.alcyone.context.vfs.core.registry

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T13 A04 并发与串行边界、A05 冲突：同路径并发拿到同一个 ID、等锁时取消不遗留锁、类型冲突报 `CONFLICT`。
 *
 * 全部用闩锁（[CompletableDeferred]）控制时序，不赌 `Thread.sleep`；每条都有 [Timeout] 兜底，
 * 万一锁真被遗留也不会把测试挂到天荒地老。
 */
@Timeout(60)
class NodeRegistryBoundaryTest {
    /** 这条测试自己的边界；占住它就能模拟「另一个协程正在改状态」。 */
    private val boundary = StateBoundary()

    private fun registry(
        disk: StorageFakeImpl,
        nodes: NodeRepository = InMemoryNodeRepository(),
    ) = NodeRegistry(
        MountRouter.of(setOf("resources"), listOf(MountRecord(VfsPath.parse("/resources"), "disk"))),
        nodes,
        { _: String -> disk },
        boundary,
    )

    // ------------------------------------------------ 同路径并发

    @Test
    fun `A04 concurrent resolves of the same path end up with one node`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val nodes = InMemoryNodeRepository()
            val registry = registry(disk, nodes)
            disk.withFile("a.txt")
            val path = VfsPath.parse("/resources/a.txt")

            // 8 个协程一起问同一个路径；谁先谁后不确定，但结果必须唯一
            val ids =
                (1..8)
                    .map { async(Dispatchers.Default) { registry.resolveOrRegister(path).id } }
                    .awaitAll()

            assertEquals(1, ids.distinct().size, "同一个文件只能有一个 Node ID，实际拿到：$ids")
            assertEquals(1, nodes.findSubtree(VfsPath.root).size, "状态库里只该有一条有效记录")
            assertEquals(ids.first(), nodes.findByPath(path)?.id)
        }

    @Test
    fun `A04 concurrent resolves of different paths do not fail each other`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val registry = registry(disk)
            repeat(8) { disk.withFile("f$it.txt") }

            val infos =
                (0 until 8)
                    .map { index -> async(Dispatchers.Default) { registry.resolveOrRegister(VfsPath.parse("/resources/f$index.txt")) } }
                    .awaitAll()

            assertEquals(8, infos.map { it.id }.distinct().size, "不同路径各拿各的 ID")
        }

    // ------------------------------------------------ 等锁时取消

    @Test
    fun `A04 cancelling a waiter does not leave the lock held`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val nodes = InMemoryNodeRepository()
            val registry = registry(disk, nodes)
            disk.withFile("a.txt")

            // 第一个协程占着锁不放
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                launch(Dispatchers.Default) {
                    boundary.withLock {
                        locked.complete(Unit)
                        release.await()
                    }
                }
            locked.await()

            // 第二个协程排队等锁，这时把它取消
            val started = CompletableDeferred<Unit>()
            val waiter =
                launch(Dispatchers.Default) {
                    started.complete(Unit)
                    registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
                }
            started.await()
            waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled, "被取消的等待者确实结束了")

            // 放掉第一个：锁必须还能被拿到，否则就是遗留锁
            release.complete(Unit)
            holder.join()
            withTimeout(10_000) { boundary.withLock { } }

            // 而且下一次正常调用照样能登记
            val info = registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
            assertEquals(info.id, nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id)
        }

    @Test
    fun `A04 a waiter that gets cancelled sees a CancellationException`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val registry = registry(disk)
            disk.withFile("a.txt")
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val holder =
                launch(Dispatchers.Default) {
                    boundary.withLock {
                        locked.complete(Unit)
                        release.await()
                    }
                }
            locked.await()
            val waiter = async(Dispatchers.Default) { registry.resolveOrRegister(VfsPath.parse("/resources/a.txt")) }

            waiter.cancel()

            assertFailsWith<CancellationException> { waiter.await() } // 原样传播，不被包装成 VfsException
            release.complete(Unit)
            holder.join()
        }

    @Test
    fun `A04 cancelling the holder releases the boundary`() =
        runBlocking {
            val boundary = StateBoundary()
            val locked = CompletableDeferred<Unit>()

            val holder =
                launch(Dispatchers.Default) {
                    boundary.withLock {
                        locked.complete(Unit)
                        awaitCancellation() // 只能靠取消退出
                    }
                }
            locked.await()
            holder.cancelAndJoin()

            // 锁要是没还回来，这里会超时失败
            withTimeout(10_000) { boundary.withLock { } }
        }

    @Test
    fun `A05 a cancellation after the commit leaves the node in place and it is reused later`() =
        runBlocking {
            // 取消时机：register 已经提交成功，但调用方还没拿到返回值。这里用一个门把取消卡在确定的位置，
            // 不赌线程调度。结论按实际 effect 写：Node 留在库里，下次查询复用它，不做补偿删除。
            val disk = StorageFakeImpl()
            val nodes = InMemoryNodeRepository()
            val committed = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val registry = registry(disk, GatedCommitRepository(nodes, committed, gate))
            disk.withFile("a.txt")
            val path = VfsPath.parse("/resources/a.txt")

            val job = launch(Dispatchers.Default) { registry.resolveOrRegister(path) }
            committed.await() // 写入已完成，现在卡在门上
            job.cancel()
            job.join()
            assertTrue(job.isCancelled, "取消原样传播")

            val stored = nodes.findByPath(path)
            assertNotNull(stored, "提交已经发生，Node 留在库里；「收到取消」不等于「一定没提交」")

            // 后续查询直接复用同一个 ID，不新建、不删除
            val again = registry.resolveOrRegister(path)
            assertEquals(stored!!.id, again.id)
            assertEquals(1, nodes.findSubtree(VfsPath.root).size, "只有一条有效记录")
        }

    // ------------------------------------------------ 类型冲突

    @Test
    fun `A05 a type change on disk is CONFLICT and changes nothing`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val nodes = InMemoryNodeRepository()
            val registry = registry(disk, nodes)
            val path = VfsPath.parse("/resources/a.txt")
            disk.withFile("a.txt")
            val registered = registry.resolveOrRegister(path)
            // 外部把同一个位置换成了目录
            disk.remove("a.txt")
            disk.makeDirectory("a.txt")

            val failure = assertFailsWith<VfsException> { registry.resolveOrRegister(path) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code)
            val stored = nodes.findByPath(path)!!
            assertEquals(registered.id, stored.id, "不能换 ID")
            assertEquals(NodeType.FILE, stored.type, "不能顺手把记录改对")
            assertEquals(registered.registeredAt, stored.registeredAt, "不改登记时间")
        }

    @Test
    fun `A02 the pure logical path does not notice a conflict at all`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val nodes = InMemoryNodeRepository()
            val registry = registry(disk, nodes)
            val path = VfsPath.parse("/resources/a.txt")
            disk.withFile("a.txt")
            registry.resolveOrRegister(path)

            // includeStorage=false 是纯逻辑查询：磁盘上换了什么它都不知道，也就没什么可冲突的
            val logical = registry.resolveOrRegister(path, includeStorage = false)

            assertEquals(NodeType.FILE, logical.type)
            assertNull(logical.storage)
            assertEquals(1, nodes.findSubtree(VfsPath.root).size)
        }

    @Test
    fun `A05 a rival register of a different type is CONFLICT`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val nodes = InMemoryNodeRepository()
            val path = VfsPath.parse("/resources/a.txt")
            // 另一个写者抢先在同一个路径登记了一个目录
            val rival =
                nodes.register(
                    NodeRecord(
                        id = NodeId.parse("018f0000-0000-7000-8000-0000000000cd"),
                        path = path,
                        type = NodeType.DIRECTORY,
                        physical = true,
                        registeredAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                )
            val registry = registry(disk, nodes)
            disk.withFile("a.txt")

            val failure = assertFailsWith<VfsException> { registry.resolveOrRegister(path) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "先登记的是目录，磁盘上是文件")
            assertEquals(rival.id, nodes.findByPath(path)?.id)
        }
}

/** 把 register 的写入做完之后再挂起一段，方便把取消卡在「已提交、未返回」这个确定的位置。 */
private class GatedCommitRepository(
    private val delegate: NodeRepository,
    private val committed: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : NodeRepository by delegate {
    override suspend fun register(record: NodeRecord): NodeRecord {
        val registered = delegate.register(record)
        committed.complete(Unit)
        gate.await() // 挂在这里等取消；写入早已提交
        return registered
    }
}
