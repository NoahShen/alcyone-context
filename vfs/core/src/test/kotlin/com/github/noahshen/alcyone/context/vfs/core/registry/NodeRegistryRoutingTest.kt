package com.github.noahshen.alcyone.context.vfs.core.registry

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.registry.RegistryHarness.MountedStorage
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * T13 A03 路由与虚拟目录：最长挂载命中、没有挂载的普通路径、装配漏盘、纯虚拟目录、祖先被文件遮蔽、挂载根必须真的能访问。
 */
class NodeRegistryRoutingTest {
    private fun harness(
        mounts: List<MountedStorage>,
        namespaces: Set<String> = setOf("resources"),
        storages: (String) -> Storage? = { key -> mounts.firstOrNull { it.key == key }?.storage },
    ) = RegistryHarness(mounts, namespaces = namespaces, storages = storages)

    // ------------------------------------------------ 路由

    @Test
    fun `A03 the longest mount wins and only that mount is queried`() =
        runBlocking {
            val parent = MountedStorage("/resources", StorageFakeImpl())
            val child = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val harness = harness(listOf(parent, child))
            child.storage.withFile("a.dcm")

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical/ct/a.dcm"))

            assertEquals(listOf("a.dcm"), harness.statCalls(child.storage), "只问最深的挂载")
            assertTrue(harness.statCalls(parent.storage).isEmpty(), "父挂载不该被问")
            assertEquals(NodeType.FILE, info.type)
        }

    @Test
    fun `A03 a child mount failure does not fall back to the parent backend`() =
        runBlocking {
            val parent = MountedStorage("/resources", StorageFakeImpl())
            val child = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val harness = harness(listOf(parent, child))
            parent.storage.withFile("medical/ct/a.dcm") // 父盘上确实有同名文件，也不许拿它顶替
            child.storage.failures.onStat = VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "denied")

            val failure = assertFailsWith<VfsException> { harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical/ct/a.dcm")) }

            assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
            assertTrue(harness.statCalls(parent.storage).isEmpty(), "路由已经定了，换一块盘问就是错的")
        }

    @Test
    fun `A03 a plain path without a mount is MOUNT_NOT_FOUND`() =
        runBlocking {
            val disk = MountedStorage("/resources", StorageFakeImpl())
            val harness = harness(listOf(disk))

            val failure = assertFailsWith<VfsException> { harness.registry.resolveOrRegister(VfsPath.parse("/other/a.txt")) }

            assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, failure.code)
            assertTrue(disk.storage.calls.isEmpty(), "连后端都不该问")
        }

    @Test
    fun `A03 a mount without a storage instance is a STATE_ERROR naming the key`() =
        runBlocking {
            val disk = MountedStorage("/resources", StorageFakeImpl())
            val harness = harness(listOf(disk), storages = { null })

            val failure = assertFailsWith<VfsException> { harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt")) }

            // 装配写错了，绝不能装作「文件不存在」把问题藏起来
            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertTrue(failure.message!!.contains("/resources"), "消息要说明缺的是哪块盘：${failure.message}")
        }

    // ------------------------------------------------ 配置目录与虚拟目录

    @Test
    fun `A03 a pure virtual directory is registered without touching the backend`() =
        runBlocking {
            // 只挂了 /resources/medical/ct，/resources/medical 在磁盘上什么都没有
            val child = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val nodes = InMemoryNodeRepository()
            val harness = RegistryHarness(listOf(child), nodes = nodes)

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical"))

            assertEquals(NodeType.DIRECTORY, info.type)
            assertNull(info.storage, "虚拟目录没有磁盘属性")
            assertEquals(false, nodes.findByPath(VfsPath.parse("/resources/medical"))?.physical, "虚拟目录不是物理映射")
            assertTrue(child.storage.calls.isEmpty(), "虚拟目录一次后端都不用问，更不许顺手建一个物理目录")
        }

    @Test
    fun `A03 a configured ancestor that really exists reports the real attributes`() =
        runBlocking {
            val parent = MountedStorage("/resources", StorageFakeImpl())
            val child = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val nodes = InMemoryNodeRepository()
            val harness = RegistryHarness(listOf(parent, child), nodes = nodes)
            parent.storage.makeDirectory("medical")

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical"))

            assertEquals(NodeType.DIRECTORY, info.type)
            assertTrue(nodes.findByPath(VfsPath.parse("/resources/medical"))!!.physical, "磁盘上真有，就是物理目录")
            assertEquals(listOf("medical"), harness.statCalls(parent.storage))
        }

    @Test
    fun `A03 a file sitting where a configured directory is expected is shadowed`() =
        runBlocking {
            val parent = MountedStorage("/resources", StorageFakeImpl())
            val child = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val harness = RegistryHarness(listOf(parent, child))
            parent.storage.withFile("medical", "not a directory") // 磁盘上是个文件

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical"))

            assertEquals(NodeType.DIRECTORY, info.type, "按逻辑目录处理，磁盘上的文件被遮蔽")
            assertNull(info.storage)
        }

    @Test
    fun `A03 a mount root is confirmed against the backend`() =
        runBlocking {
            val disk = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val nodes = InMemoryNodeRepository()
            val harness = RegistryHarness(listOf(disk), nodes = nodes)

            val info = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical/ct"))

            assertEquals(NodeType.DIRECTORY, info.type)
            assertTrue(nodes.findByPath(VfsPath.parse("/resources/medical/ct"))!!.physical, "挂载根是真的挂上了")
            assertEquals(listOf("."), harness.statCalls(disk.storage), "挂载根必须确认后端确实在")
        }

    @Test
    fun `A03 a mount root is not degraded to a virtual directory when the backend is gone`() =
        runBlocking {
            val disk = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val nodes = InMemoryNodeRepository()
            val harness = RegistryHarness(listOf(disk), nodes = nodes)
            disk.storage.failures.onStat = VfsException(VfsErrorCode.NOT_FOUND, "backend root is gone")

            val failure = assertFailsWith<VfsException> { harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical/ct")) }

            assertEquals(VfsErrorCode.NOT_FOUND, failure.code, "挂不上就是挂不上，不能降级成「虚拟目录，正常」")
            assertNull(nodes.findByPath(VfsPath.parse("/resources/medical/ct")))
        }

    @Test
    fun `A03 a general IO failure on a configured directory is not swallowed`() =
        runBlocking {
            val parent = MountedStorage("/resources", StorageFakeImpl())
            val child = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val harness = harness(listOf(parent, child))
            parent.storage.failures.onStat = VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "no permission")

            val failure = assertFailsWith<VfsException> { harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical")) }

            assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code, "权限问题是真故障，不能当成「目录不存在」")
        }

    @Test
    fun `A03 a virtual directory is answered from the registry alone afterwards`() =
        runBlocking {
            val child = MountedStorage("/resources/medical/ct", StorageFakeImpl())
            val harness = RegistryHarness(listOf(child))
            val first = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical"))

            val again = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical"))
            val logical = harness.registry.resolveOrRegister(VfsPath.parse("/resources/medical"), includeStorage = false)

            assertEquals(first.id, again.id)
            assertEquals(first.id, logical.id)
            assertTrue(harness.statCalls(child.storage).isEmpty(), "纯虚拟目录压根没有后端可问")
        }

    @Test
    fun `A03 the logical root is a configured directory`() =
        runBlocking {
            val harness = RegistryHarness(listOf(MountedStorage("/resources", StorageFakeImpl())))

            val info = harness.registry.resolveOrRegister(VfsPath.root)

            assertEquals(NodeType.DIRECTORY, info.type)
            assertEquals("/", info.uri.path.toString())
        }

    @Test
    fun `A05 a stat failure on a mount root leaves nothing registered`() =
        runBlocking {
            val disk = MountedStorage("/resources", StorageFakeImpl())
            val nodes = InMemoryNodeRepository()
            disk.storage.failures.onStat = VfsException(VfsErrorCode.STORAGE_ERROR, "backend is broken")
            val harness = RegistryHarness(listOf(disk), nodes = nodes)

            assertFailsWith<VfsException> { harness.registry.resolveOrRegister(VfsPath.parse("/resources/a.txt")) }

            assertNull(nodes.findByPath(VfsPath.parse("/resources/a.txt")))
            assertEquals(emptyList<NodeType>(), nodes.findSubtree(VfsPath.root).map { it.type }, "确认失败就不许有 Node")
        }
}
