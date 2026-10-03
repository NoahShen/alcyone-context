package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.makeDirectory
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * T15 S1：list 的单层合并（T02 §5.2）。
 *
 * 覆盖：物理子项 + 配置推导入口、同名时配置优先、按完整子路径去重、已登记 ID 批量补齐、
 * 纯虚拟目录不伪造物理访问、子挂载的后端离线不影响父层入口可见、真要访问的后端失败就整体失败。
 */
class DefaultVfsListTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    private fun harness(
        mounts: List<VfsHarness.Mounted>,
        namespaces: Set<String> = setOf("resources"),
    ) = VfsHarness(mounts, namespaces = namespaces, notifier = notifiers.create())

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    /** 把结果压成「路径 -> 类型」方便按集合比较；list 不保证顺序。 */
    private fun com.github.noahshen.alcyone.context.vfs.VfsEntry.asPair(): Pair<String, NodeType> = uri.path.toString() to type

    @Test
    fun `A04 the logical root lists the configured namespaces without touching a backend`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", disk),
                        VfsHarness.Mounted("/memory", StorageFakeImpl(), key = "memory"),
                    ),
                    namespaces = setOf("resources", "memory"),
                )

            val entries = harness.vfs.list(VfsUri.parse("alcyone://"))

            assertEquals(
                listOf("/memory" to NodeType.DIRECTORY, "/resources" to NodeType.DIRECTORY),
                entries.map { it.asPair() }.sortedBy { it.first },
            )
            assertTrue(disk.calls.isEmpty(), "虚拟目录不访问后端：${disk.calls}")
        }

    @Test
    fun `A04 a mount root merges physical children with configured entries and a nested mount wins`() =
        runBlocking {
            val parent = StorageFakeImpl()
            parent.withFile("note.md", "hi")
            parent.makeDirectory("ct")
            parent.withFile("ct/a.dcm", "parent copy") // 子挂载范围内的东西不该从这里露出来
            val child = StorageFakeImpl()
            child.withFile("a.dcm", "child copy")
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", parent, key = "parent"),
                        VfsHarness.Mounted("/resources/ct", child, key = "child"),
                    ),
                )

            val entries = harness.vfs.list(uri(""))

            assertEquals(
                listOf("/resources/ct" to NodeType.DIRECTORY, "/resources/note.md" to NodeType.FILE),
                entries.map { it.asPair() }.sortedBy { it.first },
                "同名时配置入口优先：父盘那个 ct 目录被挂载入口盖掉",
            )
            assertNull(entries.first { it.uri.path.toString() == "/resources/ct" }.storage, "配置入口没有磁盘属性")
            assertEquals(2L, entries.first { it.uri.path.toString() == "/resources/note.md" }.storage?.sizeBytes)
        }

    @Test
    fun `A04 a deep mount contributes only its first segment on this level`() =
        runBlocking {
            val harness = harness(listOf(VfsHarness.Mounted("/resources/medical/ct", StorageFakeImpl(), key = "ct")))

            assertEquals(listOf("/resources/medical" to NodeType.DIRECTORY), harness.vfs.list(uri("")).map { it.asPair() })
            assertEquals(listOf("/resources/medical/ct" to NodeType.DIRECTORY), harness.vfs.list(uri("/medical")).map { it.asPair() })
        }

    @Test
    fun `A04 a pure virtual directory lists config entries only and never fakes physical access`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources/medical/ct", disk, key = "ct")))

            val entries = harness.vfs.list(uri("/medical"))

            assertEquals(listOf("/resources/medical/ct" to NodeType.DIRECTORY), entries.map { it.asPair() })
            assertTrue(disk.calls.none { it.startsWith("list:") }, "缺失的后端位置不去列：${disk.calls}")
            assertNull(entries.single().storage, "纯虚拟目录条目没有磁盘属性")
        }

    @Test
    fun `A04 a physical file behind a configured directory is shadowed, not listed as a file`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.withFile("medical", "parent file")
            disk.withFile("note.md", "hi")
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", disk, key = "parent"),
                        VfsHarness.Mounted("/resources/medical/ct", StorageFakeImpl(), key = "child"),
                    ),
                )

            val entries = harness.vfs.list(uri(""))

            assertEquals(
                listOf("/resources/medical" to NodeType.DIRECTORY, "/resources/note.md" to NodeType.FILE),
                entries.map { it.asPair() }.sortedBy { it.first },
                "被遮蔽的物理文件不参与列表，普通文件照常列",
            )
        }

    @Test
    fun `A04 registered ids are filled in one batch and listing registers nothing`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.withFile("a.txt", "hi")
            disk.withFile("b.txt", "hi")
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            harness.vfs.stat(uri("/a.txt")) // 先给 a.txt 一个身份
            disk.calls.clear()

            val entries = harness.vfs.list(uri(""))

            assertEquals(1, entries.count { it.nodeId != null }, "只补已登记的那个，没登记的是 null")
            assertNull(entries.first { it.uri.path.toString() == "/resources/b.txt" }.nodeId)
            assertEquals(listOf("stat:.", "list:."), disk.calls, "列一次目录：没有逐项 stat、没有注册")
            assertNull(harness.nodes.findByPath(VfsPath.parse("/resources/b.txt")), "列表不登记新 Node")
        }

    @Test
    fun `A04 a child backend that is offline does not hide its entry from the parent`() =
        runBlocking {
            val parent = StorageFakeImpl()
            parent.withFile("note.md", "hi")
            val child = StorageFakeImpl()
            child.failures.onList = VfsException(VfsErrorCode.STORAGE_ERROR, "child backend is down")
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", parent, key = "parent"),
                        VfsHarness.Mounted("/resources/ct", child, key = "child"),
                    ),
                )

            val parentEntries = harness.vfs.list(uri(""))

            assertEquals(
                listOf("/resources/ct" to NodeType.DIRECTORY, "/resources/note.md" to NodeType.FILE),
                parentEntries.map { it.asPair() }.sortedBy { it.first },
                "父列表不连接子后端",
            )
            val intoChild = assertFailsWith<VfsException> { harness.vfs.list(uri("/ct")) }
            assertEquals(VfsErrorCode.STORAGE_ERROR, intoChild.code, "真要进去列就按真实错误报")
        }

    @Test
    fun `A04 the parent backend failing fails the whole listing`() =
        runBlocking {
            val parent = StorageFakeImpl()
            parent.failures.onList = VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "permission denied")
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", parent, key = "parent"),
                        VfsHarness.Mounted("/resources/ct", StorageFakeImpl(), key = "child"),
                    ),
                )

            val failure = assertFailsWith<VfsException> { harness.vfs.list(uri("")) }

            assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code, "不能只返回挂载入口装作完整")
        }

    @Test
    fun `A04 a plain file is not a directory and an unmounted path has no listing`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.withFile("a.txt", "hi")
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))

            assertEquals(VfsErrorCode.TYPE_MISMATCH, assertFailsWith<VfsException> { harness.vfs.list(uri("/a.txt")) }.code)
            assertEquals(
                VfsErrorCode.MOUNT_NOT_FOUND,
                assertFailsWith<VfsException> { harness.vfs.list(VfsUri.parse("alcyone://elsewhere")) }.code,
            )
            assertEquals(VfsErrorCode.NOT_FOUND, assertFailsWith<VfsException> { harness.vfs.list(uri("/gone")) }.code)
        }

    // __LIST_TEST_APPEND__
}
