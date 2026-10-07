package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.makeDirectory
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T20 S2：同 Mount 文件移动一条链的预检顺序、身份连续性、补父目录、阶段拒绝与失败 effect。
 * T21：复制回退（同 Mount 无原生 move）与跨 Mount 复制移动。
 *
 * 这些用例证明「顺序和分支」：替身盘记下每次调用，所以「拒绝时一次都没动手」「物理移动前父目录已补」「原生移动不 read / write」
 * 都是可断言的事实。真实 SQLite 回滚、真实重开与真盘移动放 `DefaultVfsMoveRealStackTest`。
 */
class DefaultVfsMoveTest {
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
    ) = VfsHarness(
        mounts,
        capabilityOverrides = capabilityOverrides,
        notifier = notifiers.create(),
        clock = clock,
        // 移动编排的懒注册走自动提交，提交阶段又在事务视图里读同一条记录，所以这里让两份状态共享。
        shareNodeState = true,
    )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    @Test
    fun `A01 a registered file moves natively and keeps one FILE_MOVED without read or write`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            disk.makeDirectory("archive")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(setOf("ct"), "胸部 CT"))
            disk.calls.clear()

            val info = harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt"))

            assertEquals(
                listOf("stat:a.txt", "stat:archive/b.txt", "stat:archive", "move:a.txt->archive/b.txt"),
                disk.calls,
                "只 stat 源一次、stat 目标一次确认不存在、stat 已有父目录，然后直接交给 Storage 原生移动；不 read / write 内容",
            )
            assertNull(disk.typeOfOrNull("a.txt"), "旧物理位置消失")
            assertEquals("hello", disk.readText("archive/b.txt"), "内容原样搬到新位置")
            assertEquals(original.id, info.id, "移动保留同一个 Node ID")
            assertEquals(uri("/archive/b.txt"), info.uri)
            val record = harness.committedNodes().single()
            assertEquals("/resources/archive/b.txt", record.path.toString(), "状态库里记录已迁到目标")
            assertEquals(original.id, record.id)
            val events = harness.committedEvents()
            assertEquals(listOf(VfsEventType.FILE_MOVED), events.map { it.type }, "一次移动只发一条 FILE_MOVED")
            assertEquals(original.id, events.single().nodeId)
            assertEquals(uri("/archive/b.txt"), events.single().uri, "事件 uri 是目标位置")
            assertEquals(uri("/a.txt"), events.single().sourceUri)
            assertEquals(uri("/archive/b.txt"), events.single().targetUri)
        }

    @Test
    fun `A01 a move to another subdirectory creates the missing parents first`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            disk.calls.clear()

            harness.vfs.move(uri("/a.txt"), uri("/notes/sub/b.txt"))

            assertEquals(
                listOf(
                    "stat:a.txt",
                    "stat:notes/sub/b.txt",
                    "stat:notes",
                    "stat:notes/sub",
                    "createDirectory:notes",
                    "createDirectory:notes/sub",
                    "move:a.txt->notes/sub/b.txt",
                ),
                disk.calls,
                "先把缺失父目录全部看出来，再逐层补出，最后原生移动；父目录不批量登记、不发目录事件",
            )
            assertEquals("hello", disk.readText("notes/sub/b.txt"))
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type })
            assertEquals(
                listOf("/resources/notes/sub/b.txt"),
                harness.committedNodes().map { it.path.toString() },
                "只有被移动的文件有记录，父目录一条都不登记",
            )
            assertEquals(original.id, harness.committedNodes().single().id)
        }

    @Test
    fun `A02 an unregistered source gets exactly one identity and it survives the move`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello") // 盘上有、状态库没有
            assertTrue(harness.committedNodes().isEmpty())

            val info = harness.vfs.move(uri("/a.txt"), uri("/b.txt"))

            assertNull(disk.typeOfOrNull("a.txt"))
            assertEquals("hello", disk.readText("b.txt"))
            assertEquals(listOf("/resources/b.txt"), harness.committedNodes().map { it.path.toString() }, "只建立一个身份，且已在目标路径，旧路径不留副本")
            assertEquals(info.id, harness.committedNodes().single().id)
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type })
            assertEquals(info.id, harness.committedEvents().single().nodeId)
        }

    @Test
    fun `A02 a missing node in the committing view fails with STATE_ERROR + PARTIAL instead of recreating identity`() =
        runBlocking {
            // R1 受控反例：源已登记，但事务视图里查不到它（模拟「最后一步发现记录不见了」）。
            // 生产代码必须抛状态异常（STATE_ERROR + PARTIAL），绝不静默重建身份。
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            disk.calls.clear()

            harness.uow.hideNodesInTransactionView = true

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/b.txt")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code, "提交视图缺源记录 → 状态异常")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "物理移动已发生，这是已知变更")
            assertNotNull(failure.cause, "原始失败保留在 cause 上")

            // 没有在目标位置新建 Node，没有 FILE_MOVED 事件
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "没有在目标路径新建身份")
            assertEquals(original.id, harness.committedNodes().single().id, "源记录 ID 未变")
            assertEquals(
                listOf("/resources/a.txt"),
                harness.nodes.findByPaths(listOf(VfsPath.parse("/resources/a.txt"))).map {
                    it.path.toString()
                },
                "自动提交那份仍能看到源记录",
            )
            assertTrue(harness.committedEvents().isEmpty(), "失败不发事件")

            // 物理移动已发生（这是允许的：物理在前、提交在后）
            assertNull(disk.typeOfOrNull("a.txt"), "物理源已消失")
            assertEquals("hello", disk.readText("b.txt"), "目标内容保留")
        }

    @Test
    fun `A03 the pure-argument and stage-boundary refusals happen before any side effect`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            disk.withFile("b.txt", "taken")
            disk.makeDirectory("dir")
            harness.seedNode("/resources/a.txt")
            harness.seedNode("/resources/b.txt")
            disk.calls.clear()

            // 同路径：纯参数错误，先于一切（连后端都不问）。
            val same = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/a.txt")) }
            assertEquals(VfsErrorCode.INVALID_ARGUMENT, same.code)
            assertEquals(VfsEffect.NONE, same.effect)

            // 目标落进源子树：同样是纯参数错误。
            val selfContained = assertFailsWith<VfsException> { harness.vfs.move(uri("/dir"), uri("/dir/inner")) }
            assertEquals(VfsErrorCode.INVALID_ARGUMENT, selfContained.code)

            // 缺失源：NOT_FOUND，不当作「移动成功」。
            val missing = assertFailsWith<VfsException> { harness.vfs.move(uri("/gone.txt"), uri("/new.txt")) }
            assertEquals(VfsErrorCode.NOT_FOUND, missing.code)
            assertEquals(VfsEffect.NONE, missing.effect)

            // 目标已存在（普通文件）：ALREADY_EXISTS，不覆盖。
            val exists = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/b.txt")) }
            assertEquals(VfsErrorCode.ALREADY_EXISTS, exists.code)
            assertEquals(VfsEffect.NONE, exists.effect)

            // 目标已存在（普通目录）：不把目录解释成「放进去」，一样 ALREADY_EXISTS。
            val intoDir = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/dir")) }
            assertEquals(VfsErrorCode.ALREADY_EXISTS, intoDir.code, "目标目录不被当作移入其中")

            assertTrue(disk.calls.none { it.startsWith("move:") }, "所有拒绝都发生在物理移动之前：${disk.calls}")
            assertEquals("hello", disk.readText("a.txt"), "源原样留在旧位置")
            assertEquals("taken", disk.readText("b.txt"), "已有目标不被覆盖")
            assertTrue(harness.committedEvents().isEmpty(), "被拒绝的移动不发成功事件")
        }

    @Test
    fun `A03 protected configured directories and unimplemented strategies are stage-refused`() =
        runBlocking {
            // /resources/a/b/ct 是子挂载点：/resources/a 与 /resources/a/b 因此都成为受保护配置目录；
            // /resources/z 是另一块普通盘，给受保护源的移动提供一个「不在源子树里」的目标。
            val parent = StorageFakeImpl()
            val child = StorageFakeImpl()
            val other = StorageFakeImpl()
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", parent, key = "parent"),
                        VfsHarness.Mounted("/resources/a/b/ct", child, key = "child"),
                        VfsHarness.Mounted("/resources/z", other, key = "other"),
                    ),
                )
            other.withFile("x.txt", "hi")
            parent.makeDirectory("dir")
            parent.withFile("dir/inner.txt", "inner")
            other.calls.clear()

            // 源 / 目标命中受保护配置目录：结构保护，物理副作用为零。
            for (
            (source, target, why) in
            listOf(
                Triple("/a", "/z/b.txt", "源是承载子挂载的配置祖先"),
                Triple("/a/b", "/z/b.txt", "源是缺层配置祖先"),
                Triple("/z/x.txt", "/a/b/ct", "目标是子挂载根"),
            )
            ) {
                val failure = assertFailsWith<VfsException>(why) { harness.vfs.move(uri(source), uri(target)) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code, why)
                assertEquals(VfsEffect.NONE, failure.effect, "$why：零副作用")
            }
            assertTrue(other.calls.none { it.startsWith("move:") }, "结构保护之前不移动：${other.calls}")
            assertTrue(harness.committedEvents().isEmpty())

            // 目录移动：阶段拒绝（T22 接续）。
            val directory = assertFailsWith<VfsException> { harness.vfs.move(uri("/dir"), uri("/z/dir2")) }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, directory.code, "目录移动还没交付")

            // 同 Mount 但后端不支持原生 move：复制回退（T21）现在已实现，应该成功。
            val noNativeDisk = StorageFakeImpl()
            val noNative =
                harness(
                    listOf(VfsHarness.Mounted("/resources", noNativeDisk)),
                    capabilityOverrides = mapOf("/resources" to StorageCapabilities(nativeFileMove = false)),
                )
            noNativeDisk.withFile("a.txt", "hi")
            noNativeDisk.calls.clear()
            val fallback = noNative.vfs.move(uri("/a.txt"), uri("/b.txt"))
            // 先取快照：后面的断言自己也会 stat / read 盘。
            val afterFallback = noNativeDisk.calls.toList()

            assertEquals("hi", noNativeDisk.readText("b.txt"), "复制回退：内容复制到目标")
            assertNull(noNativeDisk.typeOfOrNull("a.txt"), "复制回退：源被删除")
            assertEquals(
                listOf("stat:a.txt", "stat:b.txt", "read:a.txt", "write:b.txt", "stat:b.txt", "delete:a.txt"),
                afterFallback,
                "预检 stat 源与目标后进复制流程：读源 → 写目标（CREATE_NEW）→ stat 确认目标 → 删源；一次 native move 都没调",
            )
            assertEquals(listOf(VfsEventType.FILE_MOVED), noNative.committedEvents().map { it.type })
            assertEquals(fallback.id, noNative.committedNodes().single().id)

            assertTrue(other.calls.none { it.startsWith("move:") }, "结构保护之前不移动：${other.calls}")
            assertTrue(noNative.committedEvents().isNotEmpty(), "复制回退发 FILE_MOVED")
        }

    @Test
    fun `A03 a plain file across two mounts uses copy-move and succeeds`() =
        runBlocking {
            // 两块普通 Mount（都不是受保护配置目录，源也是普通文件）：Guard 给出 CROSS_MOUNT_COPY_MOVE，
            // T21 实现复制→确认→删源→提交，跨 Mount 也能成功移动。
            val parent = StorageFakeImpl()
            val child = StorageFakeImpl()
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", parent, key = "parent"),
                        VfsHarness.Mounted("/resources/other", child, key = "other"),
                    ),
                )
            parent.withFile("a.txt", "hello") // 未登记的普通文件
            parent.calls.clear()
            child.calls.clear()

            val moved = harness.vfs.move(uri("/a.txt"), uri("/other/missing/b.txt"))
            // 先取快照：后面的断言自己也会 stat / read 两块盘。
            val onSource = parent.calls.toList()
            val onTarget = child.calls.toList()

            assertEquals("hello", child.readText("missing/b.txt"), "跨 Mount：内容复制到目标")
            assertNull(parent.typeOfOrNull("a.txt"), "跨 Mount：源被删除")
            assertEquals(
                listOf("stat:a.txt", "read:a.txt", "delete:a.txt"),
                onSource,
                "源盘只按自己的路由取 Storage 与相对路径：stat 源 → 读源 → 删源；从不 native move",
            )
            assertEquals(
                listOf("stat:missing/b.txt", "stat:missing", "createDirectory:missing", "write:missing/b.txt", "stat:missing/b.txt"),
                onTarget,
                "目标盘按自己的路由确认目标全新、补父目录、CREATE_NEW 写入、再 stat 确认长度",
            )
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type })
            assertEquals(moved.id, harness.committedNodes().single().id)
            assertEquals(
                "/resources/other/missing/b.txt",
                harness
                    .committedNodes()
                    .single()
                    .path
                    .toString(),
            )
        }

    @Test
    fun `A04 a parent that exists as a file is refused with TYPE_MISMATCH and nothing moves`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            disk.withFile("blocker", "i am a file")
            harness.seedNode("/resources/a.txt")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/blocker/b.txt")) }

            assertEquals(VfsErrorCode.TYPE_MISMATCH, failure.code, "父路径是文件，按契约拒绝")
            assertTrue(disk.calls.none { it.startsWith("move:") }, "没移动：${disk.calls}")
            assertEquals("hello", disk.readText("a.txt"))
            assertEquals("i am a file", disk.readText("blocker"), "遮蔽文件不被改写")
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `A04 a parent created halfway then failing says PARTIAL and never reports NONE`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            // 第一层 notes 真建出来，第二层 notes/sub 在这里失败——「补父目录建到一半」。
            disk.failures.onCreateDirectoryAt =
                "notes/sub" to VfsException(VfsErrorCode.STORAGE_ERROR, "backend refused the second directory")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/notes/sub/b.txt")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端原始错误码")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "notes 已经真建出来，报 NONE 才是错的")
            assertEquals(NodeType.DIRECTORY, disk.typeOfOrNull("notes"), "第一层留在盘上，不回删")
            assertNull(disk.typeOfOrNull("notes/sub"), "第二层确实没建成")
            assertEquals("hello", disk.readText("a.txt"), "源的物理内容一个字节都没动")
            assertTrue(disk.calls.none { it.startsWith("move:") })
            assertTrue(harness.committedEvents().isEmpty(), "失败不发成功事件")
        }

    @Test
    fun `A04 a physical move failure after the parents keeps its effect and merges the created directories`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            harness.seedNode("/resources/a.txt")
            // 后端移动失败，什么 effect 都没声明（NONE）——但 notes/sub 这次真的建出来了，必须合并成 PARTIAL。
            disk.failures.onMove = VfsException(VfsErrorCode.STORAGE_ERROR, "backend failed while moving")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/notes/sub/b.txt")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect, "建出来的父目录是真实副作用，不降级成 NONE")
            assertEquals(NodeType.DIRECTORY, disk.typeOfOrNull("notes/sub"), "父目录留着")
            assertEquals("hello", disk.readText("a.txt"), "物理文件没被搬走")
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `A05 a commit failure after the physical move reports STATE_ERROR with PARTIAL and no success notification`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(description = "keep"))
            harness.uow.failOnEventAppend = VfsException(VfsErrorCode.STATE_ERROR, "event log rejected the row")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/b.txt")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect, "文件真的搬到目标了，这是已知变更")
            assertNull(disk.typeOfOrNull("a.txt"), "物理移动不回移")
            assertEquals("hello", disk.readText("b.txt"), "目标内容保留")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "同批的路径更新一起回滚")
            assertEquals(NodeMetadata(description = "keep"), harness.uow.snapshot().second[original.id], "Metadata 不动")
            assertTrue(harness.committedEvents().isEmpty(), "同批的事件一起回滚，没有成功通知")
        }

    @Test
    fun `A06 a backend UNKNOWN effect is preserved and a missing note is not invented`() =
        runBlocking {
            val unknown = VfsException(VfsErrorCode.STORAGE_ERROR, "connection lost mid move", effect = VfsEffect.UNKNOWN)
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            harness.seedNode("/resources/a.txt")
            // 目标父目录已经齐全，不下探到建目录，只让物理移动报 UNKNOWN。
            disk.failures.onMove = unknown
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/b.txt")) }

            assertEquals(VfsEffect.UNKNOWN, failure.effect, "后端说不清就是说不清，不改报 NONE / PARTIAL")
            assertEquals("hello", disk.readText("a.txt"))
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `A06 a move against a read-only backend is refused with READ_ONLY and no side effect`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness =
                harness(
                    listOf(VfsHarness.Mounted("/resources", disk)),
                    capabilityOverrides = mapOf("/resources" to StorageCapabilities(readOnly = true)),
                )
            disk.withFile("a.txt", "hello")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/b.txt")) }

            assertEquals(VfsErrorCode.READ_ONLY, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertTrue(disk.calls.none { it.startsWith("move:") }, "只读盘一次都没被移动：${disk.calls}")
            assertEquals("hello", disk.readText("a.txt"))
        }

    @Test
    fun `A07 the refused move leaves the node count unchanged and a later move still works`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            disk.withFile("b.txt", "taken")
            val a = harness.seedNode("/resources/a.txt")

            assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/b.txt")) }
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "被拒绝的移动不动记录")
            assertEquals(a.id, harness.committedNodes().single().id)

            // 紧接着的合法移动能正常拿到同一把边界并完成——上一个拒绝没有把锁留坏。
            val info = harness.vfs.move(uri("/a.txt"), uri("/c.txt"))
            assertEquals(a.id, info.id)
            assertEquals("hello", disk.readText("c.txt"))
            assertEquals(NodeId.parse(a.id.value), info.id)
            assertFalse(harness.committedEvents().isEmpty()) // 只统计合法那次的事件
            assertEquals(1, harness.committedEvents().count { it.type == VfsEventType.FILE_MOVED })
        }
}
