package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.makeDirectory
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T16 S1/S2：删除一条链的预检顺序、文件与目录模式矩阵、逻辑清理范围和失败 effect。
 *
 * 这些用例证明的是「顺序和分支」：替身盘记下每次调用，所以「拒绝时一次都没动手」「物理删除前逻辑记录已经读好」是可断言的事实。
 * 真实事务回滚（SQLite 真的 roll back）和真盘删除放 `DefaultVfsDeleteRealStackTest`。
 */
class DefaultVfsDeleteTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行，不留分发协程。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    private fun harness(
        mounts: List<VfsHarness.Mounted> = listOf(VfsHarness.Mounted("/resources")),
        capabilityOverrides: Map<String, StorageCapabilities> = emptyMap(),
        clock: () -> Instant = { VfsHarness.FIXED_CLOCK },
    ) = VfsHarness(mounts, capabilityOverrides = capabilityOverrides, notifier = notifiers.create(), clock = clock)

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    /** 往事务那份状态库里放一条 Metadata；setMetadata 属 T17，测试自己直接用仓库。 */
    private suspend fun VfsHarness.seedMetadata(
        id: com.github.noahshen.alcyone.context.vfs.NodeId,
        metadata: NodeMetadata,
    ) {
        uow.inTransaction { scope -> scope.metadata.put(id, metadata) }
    }

    @Test
    fun `A01 a registered file disappears with one FILE_DELETED and no new identity`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(setOf("ct"), "胸部 CT"))
            disk.calls.clear()

            harness.vfs.delete(uri("/a.txt"))

            assertEquals(listOf("stat:a.txt", "delete:a.txt"), disk.calls, "只 stat 确认一次，然后直接交给 Storage 删")
            assertNull(disk.typeOfOrNull("a.txt"), "物理文件真的没了")
            assertTrue(harness.committedNodes().isEmpty(), "目标 Node 被标记删除")
            assertEquals(
                null,
                harness.uow.snapshot().second[original.id],
                "Metadata 跟着清掉",
            )
            val events = harness.committedEvents()
            assertEquals(listOf(VfsEventType.FILE_DELETED), events.map { it.type }, "一次删除一条目标级事件")
            assertEquals(original.id, events.single().nodeId, "已登记目标的事件带 Node ID")
            assertEquals(uri("/a.txt"), events.single().uri)
        }

    @Test
    fun `A01 an unregistered file is deleted without inventing an identity`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("outside.txt", "hi") // 盘上有，状态库没有

            harness.vfs.delete(uri("/outside.txt"), DeleteOptions(recursive = true))

            assertNull(disk.typeOfOrNull("outside.txt"))
            assertTrue(harness.committedNodes().isEmpty(), "删除不会顺手登记一个 Node")
            val events = harness.committedEvents()
            assertEquals(listOf(VfsEventType.FILE_DELETED), events.map { it.type })
            assertNull(events.single().nodeId, "没登记过的目标，事件里没有 Node ID")
        }

    @Test
    fun `A01 an empty directory goes without recursive and a missing target is NOT_FOUND`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.makeDirectory("empty")

            harness.vfs.delete(uri("/empty"))

            assertNull(disk.typeOfOrNull("empty"), "空目录非递归可删")
            assertEquals(listOf(VfsEventType.DIRECTORY_DELETED), harness.committedEvents().map { it.type })

            val missing = assertFailsWith<VfsException> { harness.vfs.delete(uri("/gone.txt")) }
            assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "缺失目标不当作幂等成功")
            assertEquals(VfsEffect.NONE, missing.effect, "没删到任何东西")
            assertEquals(listOf(VfsEventType.DIRECTORY_DELETED), harness.committedEvents().map { it.type }, "缺失目标不发事件")
        }

    @Test
    fun `A01 a non-empty directory needs recursive and the refusal changes nothing`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.makeDirectory("a")
            disk.withFile("a/b.txt", "deep")
            disk.withFile("a/c.txt", "deep")
            disk.calls.clear()

            val refused = assertFailsWith<VfsException> { harness.vfs.delete(uri("/a")) }

            assertEquals(VfsErrorCode.DIRECTORY_NOT_EMPTY, refused.code)
            assertEquals(VfsEffect.NONE, refused.effect, "一个字节都没删")
            assertEquals(NodeType.FILE, disk.typeOfOrNull("a/b.txt"), "非空目录里的内容一个都没少")
            assertEquals(NodeType.FILE, disk.typeOfOrNull("a/c.txt"))
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty(), "被拒绝的删除不发成功事件")

            harness.vfs.delete(uri("/a"), DeleteOptions(recursive = true))
            assertNull(disk.typeOfOrNull("a"), "递归删整棵子树")
            assertEquals(listOf(VfsEventType.DIRECTORY_DELETED), harness.committedEvents().map { it.type })
        }

    @Test
    fun `A01 deleting a directory does not sweep away the empty parents around it`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.makeDirectory("notes/sub")
            disk.withFile("notes/sub/a.txt", "x") // notes/sub 只会因为这次删除变空

            harness.vfs.delete(uri("/notes/sub"), DeleteOptions(recursive = true))

            assertNull(disk.typeOfOrNull("notes/sub"), "目标删掉了")
            assertEquals(NodeType.DIRECTORY, disk.typeOfOrNull("notes"), "目标之外的父目录保留，不顺带清空")
        }

    @Test
    fun `A02 the configured structure is protected before any backend call`() =
        runBlocking {
            // 父盘 /resources 上再挂子盘 /resources/ct：/resources/ct/medical 这个祖先也成了配置目录。
            val parent = StorageFakeImpl()
            val child = StorageFakeImpl()
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", parent, key = "parent"),
                        VfsHarness.Mounted("/resources/ct/medical", child, key = "child"),
                    ),
                )
            parent.withFile("note.md", "hi")
            parent.calls.clear()

            val protected =
                listOf(
                    VfsUri.parse("alcyone://") to "逻辑根",
                    uri("") to "命名空间根",
                    uri("/") to "挂载根",
                    uri("/ct") to "承载子挂载的祖先",
                    uri("/ct/medical/") to "子挂载根",
                )
            for ((target, why) in protected) {
                for (options in listOf(DeleteOptions(), DeleteOptions(recursive = true))) {
                    val failure =
                        assertFailsWith<VfsException>("$why recursive=${options.recursive} 应该被拒") { harness.vfs.delete(target, options) }
                    assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code, "$why")
                    assertEquals(VfsEffect.NONE, failure.effect, "$why 零副作用")
                }
            }
            assertTrue(parent.calls.isEmpty(), "结构保护之前一次 Storage 都不碰：${parent.calls}")
            assertTrue(child.calls.isEmpty(), "子盘也不碰：${child.calls}")
            assertTrue(harness.committedEvents().isEmpty())

            harness.vfs.delete(uri("/note.md"))
            assertNull(parent.typeOfOrNull("note.md"), "普通文件不在整树限制里，照常能删")
        }

    @Test
    fun `A02 a missing mount a read-only backend and a type conflict are refused before the delete`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hi")
            // 逻辑记录说它是目录，盘上却是文件：状态和现实对不上。
            harness.seedNode("/resources/a.txt", type = NodeType.DIRECTORY, id = "018f0a5c-1b2c-7def-8abc-0000000000e2")
            disk.calls.clear()

            val noMount = assertFailsWith<VfsException> { harness.vfs.delete(VfsUri.parse("alcyone://elsewhere/a.txt")) }
            assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, noMount.code)

            val conflict = assertFailsWith<VfsException> { harness.vfs.delete(uri("/a.txt")) }
            assertEquals(VfsErrorCode.CONFLICT, conflict.code, "类型冲突不当场静默修复")
            assertEquals(VfsEffect.NONE, conflict.effect)

            assertTrue(disk.calls.none { it.startsWith("delete:") }, "拒绝时没删任何东西：${disk.calls}")
            assertEquals("hi", disk.readText("a.txt"))
            assertTrue(harness.committedNodes().size == 1, "逻辑记录保持原样")
        }

    @Test
    fun `A02 a read-only backend is refused after the structure check`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val readOnlyHarness =
                harness(
                    listOf(VfsHarness.Mounted("/resources", disk)),
                    capabilityOverrides = mapOf("/resources" to StorageCapabilities(readOnly = true)),
                )
            disk.withFile("a.txt", "hi")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { readOnlyHarness.vfs.delete(uri("/a.txt")) }

            assertEquals(VfsErrorCode.READ_ONLY, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "只读盘一次都没被删：${disk.calls}")
            assertEquals("hi", disk.readText("a.txt"))
        }

    @Test
    fun `A03 the registered subtree goes with the directory even when the root was never registered`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.makeDirectory("a")
            disk.makeDirectory("a-old")
            disk.withFile("a/b.txt", "one")
            disk.withFile("a/deep/c.txt", "two")
            disk.makeDirectory("a/deep")
            disk.withFile("a-old/keep.txt", "sibling prefix")
            disk.withFile("A.txt", "case sibling")
            val childB = harness.seedNode("/resources/a/b.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000b1")
            val childC = harness.seedNode("/resources/a/deep/c.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000c1")
            val sibling = harness.seedNode("/resources/a-old/keep.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000d1")
            val caseSibling = harness.seedNode("/resources/A.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000a1")
            harness.seedMetadata(childB.id, NodeMetadata(description = "b"))
            harness.seedMetadata(childC.id, NodeMetadata(description = "c"))
            harness.seedMetadata(sibling.id, NodeMetadata(description = "keep"))
            // a 自己从没登记过：子记录登记了，根没登记。
            assertTrue(harness.committedNodes().none { it.path.toString() == "/resources/a" })

            harness.vfs.delete(uri("/a"), DeleteOptions(recursive = true))

            assertNull(disk.typeOfOrNull("a"), "物理子树删掉了")
            val left = harness.committedNodes().map { it.path.toString() }.sorted()
            assertEquals(listOf("/resources/A.txt", "/resources/a-old/keep.txt"), left, "完整段边界：同名前缀兄弟和大小写兄弟都不动")
            assertNull(harness.uow.snapshot().second[childB.id], "子树里每个 Node 的 Metadata 都清掉")
            assertNull(harness.uow.snapshot().second[childC.id])
            assertEquals(NodeMetadata(description = "keep"), harness.uow.snapshot().second[sibling.id], "兄弟的 Metadata 不受影响")
            val events = harness.committedEvents()
            assertEquals(listOf(VfsEventType.DIRECTORY_DELETED), events.map { it.type }, "不为后代逐个造事件")
            assertNull(events.single().nodeId, "根没登记，目标级事件就没有身份")
        }

    @Test
    fun `A03 a directory delete also retires records whose file is already gone`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.makeDirectory("a") // 目录里什么都没有，只是逻辑上还挂着一条子记录
            val ghost = harness.seedNode("/resources/a/ghost.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000d1")
            harness.seedMetadata(ghost.id, NodeMetadata(description = "left behind"))
            val directory =
                harness.seedNode("/resources/a", type = NodeType.DIRECTORY, id = "018f0a5c-1b2c-7def-8abc-0000000000e3")

            harness.vfs.delete(uri("/a")) // 非递归，但目录物理上确实是空的

            assertTrue(harness.committedNodes().isEmpty(), "物理上已缺失的已登记子记录一并失效")
            assertNull(harness.uow.snapshot().second[ghost.id])
            val events = harness.committedEvents()
            assertEquals(listOf(VfsEventType.DIRECTORY_DELETED), events.map { it.type })
            assertEquals(directory.id, events.single().nodeId, "已登记的目录带自己的 ID")
        }

    @Test
    fun `A05 the backend effect is passed through unchanged`() =
        runBlocking {
            val effects =
                listOf(
                    VfsEffect.NONE to VfsErrorCode.STORAGE_ACCESS_DENIED,
                    VfsEffect.PARTIAL to VfsErrorCode.STORAGE_ERROR,
                    VfsEffect.UNKNOWN to VfsErrorCode.STORAGE_ERROR,
                )
            for ((effect, code) in effects) {
                val disk = StorageFakeImpl()
                val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
                disk.makeDirectory("a")
                disk.withFile("a/b.txt", "x")
                harness.seedNode("/resources/a/b.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000b1")
                disk.failures.onDelete =
                    VfsException(
                        code,
                        "backend failed while deleting",
                        effect = effect,
                    )

                val failure =
                    assertFailsWith<VfsException> {
                        harness.vfs.delete(uri("/a"), DeleteOptions(recursive = true))
                    }

                assertEquals(code, failure.code, "保留后端自己的错误码（effect=$effect）")
                assertEquals(effect, failure.effect, "effect 保真，PARTIAL / UNKNOWN 不降级成 NONE")
                assertEquals(NodeType.FILE, disk.typeOfOrNull("a/b.txt"), "故障注入了，物理文件当然还在")
                assertTrue(harness.committedNodes().isNotEmpty(), "物理失败就不清理逻辑状态，不按猜测部分删除")
                assertTrue(harness.committedEvents().isEmpty(), "物理失败不发成功事件")
            }
        }

    @Test
    fun `A05 a commit failure after the delete reports STATE_ERROR with PARTIAL`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hi")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(description = "x"))
            harness.uow.failOnEventAppend = VfsException(VfsErrorCode.STATE_ERROR, "event log rejected the row")

            val failure = assertFailsWith<VfsException> { harness.vfs.delete(uri("/a.txt")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect, "文件真的删掉了，删不回来")
            assertNull(disk.typeOfOrNull("a.txt"), "物理删除不自动补偿")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "同批的 Node 一起回滚")
            assertEquals(NodeMetadata(description = "x"), harness.uow.snapshot().second[original.id], "同批的 Metadata 一起回滚")
            assertTrue(harness.committedEvents().isEmpty(), "同批的事件一起回滚，没有成功通知")
        }
}
