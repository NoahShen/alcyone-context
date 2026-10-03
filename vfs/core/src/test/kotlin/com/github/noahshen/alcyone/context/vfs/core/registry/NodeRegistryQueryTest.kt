package com.github.noahshen.alcyone.context.vfs.core.registry

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.registry.RegistryHarness.MountedStorage
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T13 A01 / A02 查询矩阵：默认查后端、`getNode` 与 `includeStorage=false` 不查后端、未注册资源必须先确认。
 *
 * 「有没有问过后端」是**实测**的：内存盘 [StorageFakeImpl] 每次被调都记一条 `stat:相对路径`，
 * 这里直接数它记了几条、记的是哪条路径。
 */
class NodeRegistryQueryTest {
    /** 逻辑登记时间。测试里可以改 [now]，模拟「过了很久才再查一次」。 */
    private var now = Instant.parse("2026-01-01T00:00:00Z")

    // ------------------------------------------------ 首次确认与身份

    @Test
    fun `A02 the first resolve confirms the resource and registers a node`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            assertEquals(NodeType.FILE, info.type)
            assertEquals(listOf("a.txt"), harness.statCalls(disk.storage), "确认存在必须问一次后端")
            assertEquals(5L, info.storage?.sizeBytes)
            assertEquals(info.id, harness.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "ID 真的存下来了")
            assertEquals(info.id, harness.registry.getNode(info.id).id, "再查拿到的是同一个 ID")
        }

    @Test
    fun `A01 re-resolving the same file returns the same id`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")

            val first = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
            val second = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            assertEquals(first.id, second.id)
            assertEquals(listOf("a.txt", "a.txt"), harness.statCalls(disk.storage), "默认每次都要问后端")
        }

    @Test
    fun `A01 a deleted path is registered again with a new id`() =
        runBlocking {
            val nodes = InMemoryNodeRepository()
            val (harness, disk) = newHarness(nodes = nodes)
            disk.storage.withFile("a.txt")
            val first = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            nodes.markDeleted(listOf(first.id), now)
            val second = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            assertNotEquals(first.id, second.id, "删除后同路径重新登记是新身份")
            assertEquals(second.id, nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id)
        }

    // ------------------------------------------------ 后端确认失败

    @Test
    fun `A02 a file deleted outside VFS is NOT_FOUND on the default path`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")
            val first = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
            disk.storage.remove("a.txt") // 有人绕过 VFS 直接删了文件

            val failure = assertFailsWith<VfsException> { harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt")) }

            assertEquals(VfsErrorCode.NOT_FOUND, failure.code)
            assertEquals(first.id, harness.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "查询不改数据")
        }

    @Test
    fun `A02 includeStorage false does not invent a node for a missing resource`() =
        runBlocking {
            val (harness, disk) = newHarness()

            val failure =
                assertFailsWith<VfsException> {
                    harness.registry.resolveOrRegister(VfsPath.parse("/resources/ghost.txt"), includeStorage = false)
                }

            assertEquals(VfsErrorCode.NOT_FOUND, failure.code)
            assertEquals(listOf("ghost.txt"), harness.statCalls(disk.storage))
            assertNull(harness.nodes.findByPath(VfsPath.parse("/resources/ghost.txt")))
        }

    // ------------------------------------------------ 不查后端的三条路

    @Test
    fun `A02 getNode never touches the backend`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")
            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
            val after = harness.statCalls(disk.storage).size

            val found = harness.registry.getNode(info.id)

            assertEquals(info.id, found.id)
            assertNull(found.storage, "getNode 是纯逻辑查询，不问磁盘")
            assertEquals(after, harness.statCalls(disk.storage).size, "一次后端都不许碰")
        }

    @Test
    fun `A02 includeStorage false does not touch the backend for a registered node`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")
            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
            val after = harness.statCalls(disk.storage).size
            disk.storage.remove("a.txt") // 磁盘上已经没了，逻辑查询照样给答案

            val logical = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"), includeStorage = false)

            assertEquals(info.id, logical.id)
            assertNull(logical.storage)
            assertEquals(after, harness.statCalls(disk.storage).size, "已登记就走逻辑查询，一次后端都不碰")
        }

    @Test
    fun `A02 includeStorage false still confirms an unregistered resource`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"), includeStorage = false)

            assertEquals(listOf("a.txt"), harness.statCalls(disk.storage), "没登记过就得先问后端，不能让调用方伪造一个不存在的文件")
            assertNull(info.storage, "调用方不要磁盘属性，就不给")
            assertEquals(info.id, harness.nodes.findByPath(VfsPath.parse("/resources/a.txt"))?.id, "ID 是能用的真 ID")
        }

    // ------------------------------------------------ 逻辑信息不被物理信息覆盖

    @Test
    fun `A02 physical attributes do not overwrite the logical timestamps`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), info.registeredAt)
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), info.updatedAt)
            // 盘上的修改时间是真实的写文件时刻，和逻辑登记时间不是一回事
            assertNotNull(info.storage?.modifiedAt)
            assertNotEquals(info.registeredAt, info.storage?.modifiedAt)
        }

    @Test
    fun `A06 an existing node keeps its identity and timestamps when re-resolved`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")
            val first = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            now = Instant.parse("2026-06-06T00:00:00Z")
            val second = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            assertEquals(first.id, second.id)
            assertEquals(first.registeredAt, second.registeredAt, "查询不改登记时间")
            assertEquals(first.updatedAt, second.updatedAt, "查询不改更新时间")
            assertEquals(first.registeredAt, harness.nodes.findById(second.id)?.registeredAt)
        }

    // ------------------------------------------------ 未知 / 已删除的 ID

    @Test
    fun `A02 an unknown or deleted id is NOT_FOUND`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.withFile("a.txt")
            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))
            val stranger = NodeId.parse("018f0000-0000-7000-8000-0000000000ff")
            harness.nodes.markDeleted(listOf(info.id), now)

            assertEquals(VfsErrorCode.NOT_FOUND, assertFailsWith<VfsException> { harness.registry.getNode(info.id) }.code)
            assertEquals(VfsErrorCode.NOT_FOUND, assertFailsWith<VfsException> { harness.registry.getNode(stranger) }.code)
            assertTrue(harness.statCalls(disk.storage).size == 1, "getNode 连已删除的记录也不去问后端")
        }

    // ------------------------------------------------ 目录

    @Test
    fun `A02 a real directory reports real attributes`() =
        runBlocking {
            val (harness, disk) = newHarness()
            disk.storage.makeDirectory("sub")

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/sub"))

            assertEquals(NodeType.DIRECTORY, info.type)
            assertNull(info.storage?.sizeBytes, "目录大小不伪装成递归总量")
            assertEquals(listOf("sub"), harness.statCalls(disk.storage))
        }

    // ---------------------------------------- 生效的 ID 是 Repository 给的那一个

    @Test
    fun `A02 the returned id is the one the repository made effective`() =
        runBlocking {
            val backing = InMemoryNodeRepository()
            val winnerId = NodeId.parse("018f0000-0000-7000-8000-0000000000ab")
            val disk = MountedStorage("/resources", StorageFakeImpl())
            // register 返回别的记录，模拟数据库唯一索引把本次 INSERT 忽略、复用先到者的情况
            val harness =
                RegistryHarness(
                    mounts = listOf(disk),
                    nodes = RivalNodeRepository(backing, winnerId),
                    clock = { now },
                )
            disk.storage.withFile("a.txt")

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt"))

            assertEquals(winnerId, info.id, "必须用 register 返回的记录，不能返回本地候选 ID")
        }

    /** 一套新脚手架：状态库、时间都是这条用例自己的，互不干扰。 */
    private fun newHarness(nodes: NodeRepository = InMemoryNodeRepository()): Pair<RegistryHarness, MountedStorage> {
        val disk = MountedStorage("/resources", StorageFakeImpl())
        return RegistryHarness(listOf(disk), nodes = nodes, clock = { now }) to disk
    }
}

/** 只改 `register` 的返回值：数据库唯一索引会让本次 INSERT 被忽略，返回先登记的那条记录。 */
private class RivalNodeRepository(
    private val delegate: NodeRepository,
    private val winnerId: NodeId,
) : NodeRepository by delegate {
    override suspend fun register(record: NodeRecord): NodeRecord {
        delegate.register(record)
        return delegate.findByPath(record.path)!!.copy(id = winnerId)
    }
}
