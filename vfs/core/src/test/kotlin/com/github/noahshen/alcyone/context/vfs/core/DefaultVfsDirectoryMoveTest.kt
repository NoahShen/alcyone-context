package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.makeDirectory
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageContent
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageEntry
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertFailsWith

/**
 * T22：目录移动。真实策略是复制回退（Local FS 的 `nativeDirectoryMove` 恒为 false），
 * 所以 Core 替身默认也走复制回退；原生目录分支另有能力受控用例。
 *
 * 关键契约：整树复制并确认之后才删源；根与全部已登记后代在同一事务里迁移路径；
 * 只发一条根级 `DIRECTORY_MOVED`；未登记后代不批量注册。
 */
class DefaultVfsDirectoryMoveTest {
    private val notifiers = TrackedNotifiers()

    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    /** 单挂载 + 复制回退（声明后端没有原生目录移动，与 Local FS 一致）。 */
    private fun copyHarness(
        disk: Storage,
        shareNodeState: Boolean = true,
    ) = VfsHarness(
        mounts = listOf(VfsHarness.Mounted("/resources", StorageFakeImpl(), key = "left")),
        capabilityOverrides = mapOf("left" to StorageCapabilities(nativeDirectoryMove = false)),
        storages = { disk },
        notifier = notifiers.create(),
        clock = { VfsHarness.FIXED_CLOCK },
        shareNodeState = shareNodeState,
    )

    /** 跨挂载 + 复制：源与目标各按各自路由取盘。 */
    private fun crossHarness(
        source: Storage,
        target: Storage,
    ) = VfsHarness(
        mounts =
            listOf(
                VfsHarness.Mounted("/resources", StorageFakeImpl(), key = "left"),
                VfsHarness.Mounted("/resources/archive", StorageFakeImpl(), key = "right"),
            ),
        capabilityOverrides =
            mapOf(
                "left" to StorageCapabilities(nativeDirectoryMove = false),
                "right" to StorageCapabilities(nativeDirectoryMove = false),
            ),
        storages = { key ->
            if (key == "left") {
                source
            } else if (key == "right") {
                target
            } else {
                null
            }
        },
        notifier = notifiers.create(),
        clock = { VfsHarness.FIXED_CLOCK },
        shareNodeState = true,
    )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    /** A01：同 Mount 复制回退移动目录，嵌套文件与空目录都不丢，一条根级事件。 */
    @Test
    @Timeout(60)
    fun `A01 a directory moves by copy and keeps nested files and empty subdirectories`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = copyHarness(disk)
            disk.makeDirectory("work")
            disk.makeDirectory("work/images")
            disk.makeDirectory("work/drafts") // 空子目录
            disk.withFile("work/a.txt", "A")
            disk.withFile("work/images/b.png", "B")
            val root = harness.seedNode("/resources/work", type = NodeType.DIRECTORY)
            val a = harness.seedNode("/resources/work/a.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000a1")
            harness.seedMetadata(a.id, NodeMetadata(setOf("ct"), "胸部 CT"))
            disk.calls.clear()

            val info = harness.vfs.move(uri("/work"), uri("/archive/work"))

            assertEquals(root.id, info.id, "根保持同一个 ID")
            assertEquals(NodeType.DIRECTORY, info.type)
            assertEquals("A", disk.readText("archive/work/a.txt"), "嵌套文件内容搬过去")
            assertEquals("B", disk.readText("archive/work/images/b.png"), "更深一层也在")
            assertEquals(NodeType.DIRECTORY, disk.typeOfOrNull("archive/work/drafts"), "空子目录被显式建出来")
            assertNull(disk.typeOfOrNull("work"), "源树整棵搬走")
            assertEquals(
                listOf("/resources/archive/work", "/resources/archive/work/a.txt"),
                harness.committedNodes().map { it.path.toString() }.sorted(),
                "根与已登记子 Node 的路径都迁到目标前缀；未登记的 b.png / drafts 没有被注册",
            )
            assertEquals(a.id, harness.committedNodes().first { it.path.segments.size == 4 }.id, "子 Node 保持原 ID")
            assertEquals(
                listOf(VfsEventType.DIRECTORY_MOVED),
                harness.committedEvents().map { it.type },
                "只发一条根级目录事件，不为复制出的文件发创建事件",
            )
            assertEquals(root.id, harness.committedEvents().single().nodeId)
        }

    /** A01：跨 Mount 目录移动，同样保留整棵树。 */
    @Test
    @Timeout(60)
    fun `A01 a cross mount directory move copies the whole tree`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = StorageFakeImpl()
            val harness = crossHarness(source, target)
            source.makeDirectory("work")
            source.makeDirectory("work/empty")
            source.withFile("work/a.txt", "A")
            harness.seedNode("/resources/work", type = NodeType.DIRECTORY)
            source.calls.clear()
            target.calls.clear()

            harness.vfs.move(uri("/work"), uri("/archive/work"))

            assertEquals("A", target.readText("work/a.txt"), "内容落到另一块盘")
            assertEquals(NodeType.DIRECTORY, target.typeOfOrNull("work/empty"), "空目录也跨盘建好")
            assertNull(source.typeOfOrNull("work"), "源树删掉")
            assertEquals(listOf(VfsEventType.DIRECTORY_MOVED), harness.committedEvents().map { it.type })
            assertTrue(target.calls.none { it.startsWith("move:") }, "跨 Mount 不调原生 move：${target.calls}")
        }

    /** A02：段边界——`a` 迁移不能动 `a-old` 与 `A`。 */
    @Test
    @Timeout(60)
    fun `A02 only the exact subtree moves and similar names stay put`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = copyHarness(disk)
            disk.makeDirectory("a")
            disk.makeDirectory("a-old")
            disk.makeDirectory("A")
            disk.withFile("a/x.txt", "x")
            disk.withFile("a-old/keep.txt", "keep-old")
            disk.withFile("A/keep.txt", "keep-upper")
            harness.seedNode("/resources/a", type = NodeType.DIRECTORY)
            val x = harness.seedNode("/resources/a/x.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000b1")

            harness.vfs.move(uri("/a"), uri("/b"))

            assertEquals("x", disk.readText("b/x.txt"))
            assertEquals("keep-old", disk.readText("a-old/keep.txt"), "a-old 不是 a 的子树")
            assertEquals("keep-upper", disk.readText("A/keep.txt"), "A 与 a 是不同段")
            assertEquals(
                listOf("/resources/b", "/resources/b/x.txt"),
                harness.committedNodes().map { it.path.toString() }.sorted(),
                "只迁移了 a 那棵子树（含根）的已登记记录；a-old / A 从未登记，也不会被批量注册",
            )
            assertEquals(x.id, harness.committedNodes().first { it.path.toString().endsWith("b/x.txt") }.id)
        }

    /** A02：根未登记、子 Node 已登记时，只为根建一次 DIRECTORY 身份，子 ID 不动。 */
    @Test
    @Timeout(60)
    fun `A02 an unregistered root gets one directory identity and registered children keep theirs`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = copyHarness(disk)
            disk.makeDirectory("work")
            disk.withFile("work/x.txt", "x")
            val x = harness.seedNode("/resources/work/x.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000c1")

            val info = harness.vfs.move(uri("/work"), uri("/moved"))

            assertEquals(NodeType.DIRECTORY, info.type, "根建立的是 DIRECTORY 身份")
            assertEquals(x.id, harness.committedNodes().first { it.path.toString().endsWith("moved/x.txt") }.id, "子 ID 不动")
            assertEquals(2, harness.committedNodes().size, "只多出根这一条，没有批量登记")
            assertEquals(NodeType.DIRECTORY, harness.committedNodes().first { it.path.toString() == "/resources/moved" }.type)
        }

    /** A03：目标已存在（文件或目录）都拒绝，且不碰源。 */
    @Test
    @Timeout(60)
    fun `A03 an existing target is refused and the source tree stays`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = copyHarness(disk)
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            disk.withFile("taken", "taken")
            disk.calls.clear()

            val file = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/taken")) }
            assertEquals(VfsErrorCode.ALREADY_EXISTS, file.code)
            assertEquals("A", disk.readText("work/a.txt"), "源树没动")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A03：源树含嵌套 Mount 时整个拒绝，且不动后端。 */
    @Test
    @Timeout(60)
    fun `A03 a source tree that contains another mount is refused before touching storage`() =
        runBlocking {
            val parent = StorageFakeImpl()
            val nested = StorageFakeImpl()
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources/work", parent, key = "parent"),
                            VfsHarness.Mounted("/resources/work/inner", nested, key = "nested"),
                            VfsHarness.Mounted("/scratch", StorageFakeImpl(), key = "scratch"),
                        ),
                    namespaces = setOf("resources", "scratch"),
                    notifier = notifiers.create(),
                    clock = { VfsHarness.FIXED_CLOCK },
                )
            parent.withFile("work/a.txt", "A")
            parent.calls.clear()
            nested.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), VfsUri.parse("alcyone://scratch/work")) }

            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code, "承载子挂载的目录不能移动")
            assertEquals(com.github.noahshen.alcyone.context.vfs.VfsEffect.NONE, failure.effect)
            assertTrue(parent.calls.isEmpty(), "结构保护在碰后端之前：${parent.calls}")
            assertTrue(nested.calls.isEmpty(), "嵌套挂载的盘一次都没碰")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A04：第二个文件失败时整棵源树保持、不删源，目标允许留部分内容。 */
    @Test
    @Timeout(60)
    fun `A04 a failure on a later file keeps the whole source tree`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = copyHarness(disk)
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            disk.withFile("work/b.txt", "B")
            // 第二个文件写失败：第一个已经复制过去了，但删源一步都还没开始。
            disk.failures.onWrite = VfsException(VfsErrorCode.STORAGE_ERROR, "backend refused the write")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端码")
            assertEquals("A", disk.readText("work/a.txt"), "源树完整")
            assertEquals("B", disk.readText("work/b.txt"), "第二份也没丢")
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "一个源删除都没发生：${disk.calls}")
            assertTrue(harness.committedEvents().isEmpty(), "不发成功事件")
            assertTrue(
                harness.committedNodes().all { it.path.toString().startsWith("/resources/work") },
                "逻辑记录仍指向源路径（允许源懒注册保留）：${harness.committedNodes().map { it.path }}",
            )
        }

    /** A05：整树搬完后提交失败 → STATE_ERROR + PARTIAL，全部逻辑路径回滚、无事件、物理不回移。 */
    @Test
    @Timeout(60)
    fun `A05 a commit failure after the tree move rolls every path back and reports PARTIAL`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = copyHarness(disk)
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            disk.withFile("work/b.txt", "B")
            val root = harness.seedNode("/resources/work", type = NodeType.DIRECTORY)
            val a = harness.seedNode("/resources/work/a.txt", id = "018f0a5c-1b2c-7def-8abc-0000000000d1")
            harness.uow.failOnEventAppend = VfsException(VfsErrorCode.STATE_ERROR, "event log rejected the row")
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(com.github.noahshen.alcyone.context.vfs.VfsEffect.PARTIAL, failure.effect, "物理已搬完")
            assertNull(disk.typeOfOrNull("work"), "物理不回移")
            assertEquals("A", disk.readText("archive/work/a.txt"))
            assertEquals(
                listOf("/resources/work", "/resources/work/a.txt"),
                harness.committedNodes().map { it.path.toString() }.sorted(),
                "根与子 Node 的路径更新一起回滚",
            )
            assertEquals(root.id, harness.committedNodes().first { it.path.toString() == "/resources/work" }.id)
            assertEquals(a.id, harness.committedNodes().first { it.path.toString().endsWith("work/a.txt") }.id)
            assertTrue(harness.committedEvents().isEmpty(), "同批的事件一起回滚")
        }

    /** A01：能力声明支持原生目录移动时走 `storage.move`，不逐条复制。 */
    @Test
    @Timeout(60)
    fun `A01 a backend with native directory move uses a single native move`() =
        runBlocking {
            val backing = StorageFakeImpl()
            val disk = CountingNativeMoveStorage(backing)
            val harness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", backing, key = "left")),
                    capabilityOverrides = mapOf("left" to StorageCapabilities(nativeDirectoryMove = true)),
                    storages = { disk },
                    notifier = notifiers.create(),
                    clock = { VfsHarness.FIXED_CLOCK },
                    shareNodeState = true,
                )
            backing.makeDirectory("work")
            backing.withFile("work/a.txt", "A")
            harness.seedNode("/resources/work", type = NodeType.DIRECTORY)
            backing.calls.clear()

            harness.vfs.move(uri("/work"), uri("/moved"))

            assertEquals("A", backing.readText("moved/a.txt"))
            assertEquals(1, disk.nativeMoveCalls, "原生分支只调一次 move：${backing.calls}")
            assertEquals(listOf(VfsEventType.DIRECTORY_MOVED), harness.committedEvents().map { it.type })
        }

    /** A04：源侧长度矛盾在建目录 / 写目标之前发现（复用 T21 规则）。 */
    @Test
    @Timeout(60)
    fun `A04 a source length mismatch is detected before any target side effect`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "0123456789")
            // 读回执谎报 5 字节：读了才知道实际 10 字节，此时目标一个字节都没写。
            val lying =
                object : Storage by disk {
                    override suspend fun read(
                        path: StoragePath,
                        maxBytes: Long,
                    ): StorageContent {
                        val content = disk.read(path, maxBytes)
                        return StorageContent(content.bytes, content.attributes.copy(sizeBytes = 5L))
                    }
                }
            val harness = copyHarness(lying)
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code)
            assertTrue(disk.calls.none { it.startsWith("write:") }, "还没写任何文件：${disk.calls}")
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "没删源：${disk.calls}")
            assertEquals("0123456789", disk.readText("work/a.txt"))
        }

    /** A04：整树确认发现目标条目集合与源不一致 → CONFLICT，且不删源。 */
    @Test
    @Timeout(60)
    fun `A04 a target entry set that disagrees with the source is a conflict`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            // 目标树的 list 少报一个条目，模拟「复制没搬全」。
            var targetRootListed = false
            val lying =
                object : Storage by disk {
                    override suspend fun list(path: StoragePath): List<StorageEntry> {
                        val entries = disk.list(path)
                        if (path.segments == listOf("archive", "work")) {
                            targetRootListed = true
                            return entries.filterNot { it.name == "a.txt" }
                        }
                        return entries
                    }
                }
            val harness = copyHarness(lying)
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "条目集合对不上")
            assertTrue(targetRootListed, "确实做了整树确认")
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "确认没过就不许删源：${disk.calls}")
            assertEquals("A", disk.readText("work/a.txt"), "源树保持")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A07：取消发生在复制途中 → 原样传播，不再继续复制 / 删源 / 提交。 */
    @Test
    @Timeout(60)
    fun `A07 a cancellation during the tree copy stops before deleting the source`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            disk.withFile("work/b.txt", "B")
            val copyStarted = CompletableDeferred<Unit>()
            var writes = 0
            val pausing =
                object : Storage by disk {
                    override suspend fun write(
                        path: StoragePath,
                        content: ByteArray,
                        mode: StorageWriteMode,
                    ): StorageAttributes {
                        val result = disk.write(path, content, mode)
                        writes++
                        if (writes == 1) {
                            copyStarted.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                        return result
                    }
                }
            val harness = copyHarness(pausing)
            disk.calls.clear()

            val escaped = CompletableDeferred<Throwable>()
            val job =
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        harness.vfs.move(uri("/work"), uri("/archive/work"))
                        escaped.complete(IllegalStateException("move returned after cancellation"))
                    } catch (failure: Throwable) {
                        escaped.complete(failure)
                        throw failure
                    }
                }
            withTimeout(30_000) { copyStarted.await() }
            val injected = CancellationException("cancel injected during copy")
            job.cancel(injected)
            withTimeout(30_000) { runCatching { job.join() } }

            val thrown = withTimeout(30_000) { escaped.await() }
            assertTrue(thrown is CancellationException, "取消原样传播：$thrown")
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "取消后一个源删除都没发生：${disk.calls}")
            assertEquals("A", disk.readText("work/a.txt"), "源树完整")
            assertEquals("B", disk.readText("work/b.txt"))
            assertTrue(harness.committedEvents().isEmpty(), "没有成功事件")
        }
}

/** 数住原生目录 move 的次数，其余转发给内存盘。 */
private class CountingNativeMoveStorage(
    private val delegate: StorageFakeImpl,
) : Storage by delegate {
    var nativeMoveCalls: Int = 0
        private set

    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes {
        nativeMoveCalls++
        return delegate.move(source, target)
    }
}
