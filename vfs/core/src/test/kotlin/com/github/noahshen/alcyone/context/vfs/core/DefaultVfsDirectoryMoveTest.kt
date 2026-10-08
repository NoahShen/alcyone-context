package com.github.noahshen.alcyone.context.vfs.core

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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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

    /**
     * A03：源是承载子 Mount 的**普通祖先目录**（不是挂载根）时整个拒绝；目标路径会包含已配置 Mount 时同样拒绝。
     * 两条都发生在碰后端之前。
     */
    @Test
    @Timeout(60)
    fun `A03 a plain ancestor that contains another mount and a target that would contain one are both refused`() =
        runBlocking {
            val root = StorageFakeImpl()
            val nested = StorageFakeImpl()
            val scratch = StorageFakeImpl()
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources", root, key = "root"),
                            VfsHarness.Mounted("/resources/a/b/ct", nested, key = "nested"),
                            VfsHarness.Mounted("/scratch", scratch, key = "scratch"),
                        ),
                    namespaces = setOf("resources", "scratch"),
                    notifier = notifiers.create(),
                    clock = { VfsHarness.FIXED_CLOCK },
                )
            root.withFile("plain/a.txt", "A")
            root.calls.clear()
            nested.calls.clear()
            scratch.calls.clear()

            // 源 /resources/a 是普通祖先（不是挂载根），它承载子挂载 /resources/a/b/ct。
            val sourceContainsMount =
                assertFailsWith<VfsException> { harness.vfs.move(uri("/a"), VfsUri.parse("alcyone://scratch/target")) }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, sourceContainsMount.code, "承载子挂载的普通祖先不能移动")
            assertEquals(VfsEffect.NONE, sourceContainsMount.effect)
            assertTrue(root.calls.isEmpty(), "结构保护在碰后端之前：${root.calls}")
            assertTrue(nested.calls.isEmpty(), "嵌套挂载的盘一次都没碰")
            assertTrue(scratch.calls.isEmpty(), "目标盘一次都没碰")

            // 目标 /resources/a 会包含已配置 Mount：同样在物理变更前拒绝。
            val targetContainsMount = assertFailsWith<VfsException> { harness.vfs.move(uri("/plain"), uri("/a")) }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, targetContainsMount.code, "目标包含挂载也要拒")
            assertEquals(VfsEffect.NONE, targetContainsMount.effect)
            assertTrue(root.calls.isEmpty(), "目标包含挂载在碰后端之前拒绝：${root.calls}")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A04：前一个文件成功、后一个文件写失败时，前项目标内容残留、整棵源树保持、不删源、PARTIAL。 */
    @Test
    @Timeout(60)
    fun `A04 a later file failure leaves the earlier copy on the target and keeps the whole source`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            disk.withFile("work/b.txt", "B")
            // 只让后项 archive/work/b.txt 写失败；前项 a.txt 正常复制。
            val failing =
                FailingWriteAtStorage(
                    disk,
                    "archive/work/b.txt",
                    VfsException(VfsErrorCode.STORAGE_ERROR, "backend refused the later write"),
                )
            val harness = copyHarness(failing)
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端码")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "前项已复制、目标根 / 父目录已建")
            assertEquals("A", disk.readText("archive/work/a.txt"), "前项目标内容残留")
            assertEquals("A", disk.readText("work/a.txt"), "整棵源树完整")
            assertEquals("B", disk.readText("work/b.txt"), "后项源也没丢")
            assertEquals(0, failing.deleteCalls, "一个源删除都没发生")
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "源盘 delete 零调用：${disk.calls}")
            assertTrue(harness.committedEvents().isEmpty(), "不发成功事件")
            assertTrue(
                harness.committedNodes().all { it.path.toString().startsWith("/resources/work") },
                "逻辑记录仍指向源路径：${harness.committedNodes().map { it.path }}",
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

    /**
     * R1：整树最终枚举时目标后端失败 → 保留原 code / cause，合并已知目标变化（NONE 升 PARTIAL），不删源；
     * `UNKNOWN` 不降级。
     */
    @Test
    @Timeout(60)
    fun `R1 a failure enumerating the target during confirmation keeps the source and reports the known residue`() =
        runBlocking {
            data class Case(
                val label: String,
                val failingPath: String,
                val backend: VfsException,
                val expected: VfsEffect,
            )
            val cases =
                listOf(
                    Case(
                        "target root list",
                        "archive/work",
                        VfsException(VfsErrorCode.STORAGE_ERROR, "target root list failed"),
                        VfsEffect.PARTIAL,
                    ),
                    Case(
                        "target descendant list",
                        "archive/work/sub",
                        VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "descendant list denied"),
                        VfsEffect.PARTIAL,
                    ),
                    Case(
                        "target root UNKNOWN",
                        "archive/work",
                        VfsException(VfsErrorCode.STORAGE_ERROR, "connection lost while listing", effect = VfsEffect.UNKNOWN),
                        VfsEffect.UNKNOWN,
                    ),
                )
            for (case in cases) {
                val disk = StorageFakeImpl()
                disk.makeDirectory("work")
                disk.makeDirectory("work/sub")
                disk.withFile("work/sub/c.txt", "C")
                val failing = FailingTargetListStorage(disk, case.failingPath, case.backend)
                val harness = copyHarness(failing)
                disk.calls.clear()

                val failure =
                    assertFailsWith<VfsException>(case.label) { harness.vfs.move(uri("/work"), uri("/archive/work")) }

                assertEquals(case.backend.code, failure.code, "${case.label}：保留后端 code")
                assertEquals(case.expected, failure.effect, "${case.label}：目标已写完，NONE 升 PARTIAL / UNKNOWN 不降级")
                assertSame(case.backend, failure.cause ?: failure, "${case.label}：cause 保留")
                assertTrue(disk.calls.none { it.startsWith("delete:") }, "${case.label}：未调用源 delete：${disk.calls}")
                assertEquals("C", disk.readText("work/sub/c.txt"), "${case.label}：源完整")
                assertEquals("C", disk.readText("archive/work/sub/c.txt"), "${case.label}：目标残留")
                assertTrue(harness.committedEvents().isEmpty(), "${case.label}：无成功事件")
                assertTrue(
                    harness.committedNodes().all { it.path.toString().startsWith("/resources/work") },
                    "${case.label}：逻辑未迁移",
                )
            }
        }

    /** R2：目标文件在自己的逐文件确认通过后又被截短，最终 list 报的已知长度不符 → CONFLICT，不删源。 */
    @Test
    @Timeout(60)
    fun `R2 a target file truncated after its own confirmation is caught by the final length check`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "0123456789") // 10 字节
            val truncated = TruncatedTargetListStorage(disk, "archive/work", "a.txt", 3L)
            val harness = copyHarness(truncated)
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "最终 list 报的已知长度与实际复制不符")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "目标已写")
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "确认不过就不许删源：${disk.calls}")
            assertEquals("0123456789", disk.readText("work/a.txt"), "源树保持")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** R2：复制途中源树多出一项，删源前复查可见 → CONFLICT，禁止进入删除。 */
    @Test
    @Timeout(60)
    fun `R2 a source entry added while copying is caught before the source is deleted`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            val growing = GrowingSourceStorage(disk, "work", "c.txt", "C")
            val harness = copyHarness(growing)
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "源集合在复制期间变化")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "目标已写")
            assertTrue(disk.calls.none { it.startsWith("delete:") }, "禁止进入删除：${disk.calls}")
            assertEquals("C", disk.readText("work/c.txt"), "多出的源项还在")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A06：受控部分删源——目标完整、源残缺、逻辑映射 / Metadata 保留、无成功事件，effect 不降级。 */
    @Test
    @Timeout(60)
    fun `A06 a partially failed source delete keeps the target complete and the mapping at the source`() =
        runBlocking {
            val cases =
                listOf(
                    VfsEffect.PARTIAL to
                        VfsException(VfsErrorCode.STORAGE_ERROR, "connection dropped mid delete", effect = VfsEffect.PARTIAL),
                    VfsEffect.UNKNOWN to
                        VfsException(VfsErrorCode.STORAGE_ERROR, "connection lost mid delete", effect = VfsEffect.UNKNOWN),
                )
            for ((expectedEffect, backendFailure) in cases) {
                val disk = StorageFakeImpl()
                disk.makeDirectory("work")
                disk.withFile("work/a.txt", "A")
                disk.withFile("work/b.txt", "B")
                val partiallyDeleting = PartiallyDeletingSourceStorage(disk, backendFailure)
                val harness = copyHarness(partiallyDeleting)
                val root = harness.seedNode("/resources/work", type = NodeType.DIRECTORY)
                harness.seedMetadata(root.id, NodeMetadata(description = "keep"))
                disk.calls.clear()

                val failure =
                    assertFailsWith<VfsException>("$expectedEffect") { harness.vfs.move(uri("/work"), uri("/archive/work")) }

                assertEquals(backendFailure.code, failure.code, "$expectedEffect：保留后端 code")
                assertEquals(expectedEffect, failure.effect, "$expectedEffect：不降级")
                assertSame(backendFailure, failure.cause ?: failure, "$expectedEffect：cause 保留")
                assertEquals("A", disk.readText("archive/work/a.txt"), "$expectedEffect：目标完整 a")
                assertEquals("B", disk.readText("archive/work/b.txt"), "$expectedEffect：目标完整 b")
                assertNull(disk.typeOfOrNull("work/a.txt"), "$expectedEffect：源残缺（a 已被移除）")
                assertEquals("B", disk.readText("work/b.txt"), "$expectedEffect：源残缺但 b 还在")
                assertEquals(
                    listOf("/resources/work"),
                    harness.committedNodes().map { it.path.toString() },
                    "$expectedEffect：逻辑映射仍在源",
                )
                assertEquals(NodeMetadata(description = "keep"), harness.uow.snapshot().second[root.id], "$expectedEffect：Metadata 保留")
                assertTrue(harness.committedEvents().isEmpty(), "$expectedEffect：无成功事件")
            }
        }

    /** A07：目录复制持锁期间，第二次变更已启动但一个后端调用都还没发生。 */
    @Test
    @Timeout(60)
    fun `A07 a second change waits at the boundary while a directory copy is in flight`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            disk.makeDirectory("other")
            disk.withFile("other/x.txt", "X")
            val gate = CompletableDeferred<Unit>()
            val arrival = CompletableDeferred<Unit>()
            var paused = false
            val pausing =
                object : Storage by disk {
                    override suspend fun write(
                        path: StoragePath,
                        content: ByteArray,
                        mode: StorageWriteMode,
                    ): StorageAttributes {
                        val result = disk.write(path, content, mode)
                        if (!paused) {
                            paused = true
                            arrival.complete(Unit)
                            gate.await()
                        }
                        return result
                    }
                }
            val harness = copyHarness(pausing)
            disk.calls.clear()

            var second: Deferred<*>? = null
            val first = async(start = CoroutineStart.UNDISPATCHED) { harness.vfs.move(uri("/work"), uri("/archive/work")) }
            try {
                withTimeout(30_000) { arrival.await() }
                val statCallsBefore = disk.calls.count { it.startsWith("stat:") }
                // 第二次变更已启动（UNDISPATCHED 跑到边界锁上挂住），但一个后端调用都还没发生。
                second = async(start = CoroutineStart.UNDISPATCHED) { harness.vfs.move(uri("/other"), uri("/moved")) }
                assertTrue(second!!.isActive, "第二次变更已启动但还没完成")
                assertEquals(
                    statCallsBefore,
                    disk.calls.count { it.startsWith("stat:") },
                    "第二次变更还没碰后端：${disk.calls}",
                )
                gate.complete(Unit)
                first.await()
                second!!.await()
                assertEquals("A", disk.readText("archive/work/a.txt"))
                assertEquals("X", disk.readText("moved/x.txt"))
            } finally {
                gate.complete(Unit)
                first.cancelAndJoin()
                second?.cancelAndJoin()
            }
        }

    /** A07：取消发生在复制途中 → 原样传播，不再继续复制 / 删源 / 提交，且边界随后可再用。 */
    @Test
    @Timeout(60)
    fun `A07 a cancellation during the tree copy stops before deleting the source and leaves the boundary usable`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "A")
            disk.withFile("work/b.txt", "B")
            val arrival = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var paused = false
            val pausing =
                object : Storage by disk {
                    override suspend fun write(
                        path: StoragePath,
                        content: ByteArray,
                        mode: StorageWriteMode,
                    ): StorageAttributes {
                        val result = disk.write(path, content, mode)
                        if (!paused) {
                            paused = true
                            arrival.complete(Unit)
                            gate.await()
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
            val injected = CancellationException("cancel injected during copy")
            try {
                withTimeout(30_000) { arrival.await() }
                job.cancel(injected)
                withTimeout(30_000) { runCatching { job.join() } }

                val thrown = withTimeout(30_000) { escaped.await() }
                assertTrue(thrown is CancellationException, "取消原样传播：$thrown")
                assertSame(injected, thrown.cause ?: thrown, "注入的取消原异常保留")
                assertTrue(disk.calls.none { it.startsWith("delete:") }, "取消后一个源删除都没发生：${disk.calls}")
                assertEquals("A", disk.readText("work/a.txt"), "源树完整")
                assertEquals("B", disk.readText("work/b.txt"))
                assertTrue(harness.committedEvents().isEmpty(), "没有成功事件")
                assertTrue(
                    harness.committedNodes().all { it.path.toString().startsWith("/resources/work") },
                    "取消不提交路径更新",
                )
            } finally {
                gate.complete(Unit)
                job.cancelAndJoin()
            }

            // 边界已放行：紧接着的目录移动能正常完成。
            harness.vfs.move(uri("/work"), uri("/archive/work2"))
            assertEquals("A", disk.readText("archive/work2/a.txt"))
            assertEquals("B", disk.readText("archive/work2/b.txt"))
            assertEquals(listOf(VfsEventType.DIRECTORY_MOVED), harness.committedEvents().map { it.type })
        }

    /**
     * R2：初次枚举的源长度是 `null`（属性整体存在、只是长度未知），删源前复查读到 5 字节——
     * 必须与**实际复制的 3 字节**对上，不能因为「初始未知」就跳过。
     */
    @Test
    @Timeout(60)
    fun `R2 a source that becomes a different known length than the copied bytes is caught`() =
        runBlocking {
            val inner = StorageFakeImpl()
            inner.makeDirectory("work")
            inner.withFile("work/a.txt", "abc") // 3 字节
            var sourceListings = 0
            val source =
                object : Storage by inner {
                    override suspend fun list(path: StoragePath): List<StorageEntry> {
                        val entries = inner.list(path)
                        if (path.segments == listOf("work")) {
                            sourceListings++
                            // 第一次（初快照）长度未知；删源前那次报 5 字节。
                            return entries.map { entry ->
                                if (entry.type == NodeType.FILE) {
                                    entry.copy(attributes = entry.attributes?.copy(sizeBytes = if (sourceListings == 1) null else 5L))
                                } else {
                                    entry
                                }
                            }
                        }
                        return entries
                    }
                }
            val harness = copyHarness(source)
            inner.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/work"), uri("/archive/work")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "当前源长度 5 与已复制 3 字节矛盾")
            assertEquals(VfsEffect.PARTIAL, failure.effect)
            assertTrue(inner.calls.none { it.startsWith("delete:") }, "矛盾时不许删源：${inner.calls}")
            assertEquals("abc", inner.readText("work/a.txt"), "源内容仍在")
            assertTrue(harness.committedEvents().isEmpty())
            assertTrue(
                harness.committedNodes().all { it.path.toString().startsWith("/resources/work") },
                "逻辑路径仍指向源：${harness.committedNodes().map { it.path }}",
            )
        }

    /** R2 对照：当前源长度仍未知、或与复制字节一致时，移动照常完成。 */
    @Test
    @Timeout(60)
    fun `R2 a still unknown or matching source length does not block the move`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.makeDirectory("work")
            disk.withFile("work/a.txt", "abc")
            var listed = 0
            val source =
                object : Storage by disk {
                    override suspend fun list(path: StoragePath): List<StorageEntry> {
                        val entries = disk.list(path)
                        listed++
                        if (listed == 1) {
                            return entries.map {
                                if (it.type ==
                                    NodeType.FILE
                                ) {
                                    it.copy(attributes = it.attributes?.copy(sizeBytes = null))
                                } else {
                                    it
                                }
                            }
                        }
                        return entries
                    }
                }
            val harness = copyHarness(source)

            harness.vfs.move(uri("/work"), uri("/archive/work"))

            assertEquals("abc", disk.readText("archive/work/a.txt"), "内容搬到目标")
            assertNull(disk.typeOfOrNull("work"), "源树已删")
            assertEquals(listOf(VfsEventType.DIRECTORY_MOVED), harness.committedEvents().map { it.type })
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

/** 只让指定相对路径的 write 失败，其余转发；用来制造「前项成功后后项失败」。 */
private class FailingWriteAtStorage(
    private val delegate: StorageFakeImpl,
    private val failingPath: String,
    private val failure: VfsException,
) : Storage by delegate {
    var deleteCalls: Int = 0
        private set

    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: StorageWriteMode,
    ): StorageAttributes {
        if (path.toRelativeString() == failingPath) throw failure
        return delegate.write(path, content, mode)
    }

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        deleteCalls++
        delegate.delete(path, recursive)
    }
}

/** 让指定相对路径的 list 失败，其余转发；用来制造「整树最终确认时后端失败」。 */
private class FailingTargetListStorage(
    private val delegate: StorageFakeImpl,
    private val failingPath: String,
    private val failure: VfsException,
) : Storage by delegate {
    override suspend fun list(path: StoragePath): List<StorageEntry> {
        if (path.toRelativeString() == failingPath) throw failure
        return delegate.list(path)
    }
}

/** 目标 list 把某个文件的可用长度改小，其余转发；用来制造「逐文件确认之后又被截短」。 */
private class TruncatedTargetListStorage(
    private val delegate: StorageFakeImpl,
    private val rootPath: String,
    private val fileName: String,
    private val reportedSize: Long,
) : Storage by delegate {
    override suspend fun list(path: StoragePath): List<StorageEntry> {
        val entries = delegate.list(path)
        if (path.toRelativeString() != rootPath) return entries
        return entries.map { entry ->
            if (entry.name == fileName) {
                entry.attributes?.let { entry.copy(attributes = it.copy(sizeBytes = reportedSize)) } ?: entry
            } else {
                entry
            }
        }
    }
}

/** 第二次 list 源根前真的写入一个额外源项，其余转发；用来制造「复制中源树多出一项」。 */
private class GrowingSourceStorage(
    private val delegate: StorageFakeImpl,
    private val rootPath: String,
    private val addedName: String,
    private val content: String,
) : Storage by delegate {
    private var rootLists = 0

    override suspend fun list(path: StoragePath): List<StorageEntry> {
        if (path.toRelativeString() == rootPath) {
            rootLists++
            if (rootLists == 2) {
                delegate.write(StoragePath.parse("$rootPath/$addedName"), content.toByteArray(), StorageWriteMode.UPSERT)
            }
        }
        return delegate.list(path)
    }
}

/** 递归删源时先真的移除一个源条目，再抛错；用来制造「删源部分失败」。 */
private class PartiallyDeletingSourceStorage(
    private val delegate: StorageFakeImpl,
    private val failure: VfsException,
) : Storage by delegate {
    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        if (path.toRelativeString() == "work" && recursive) {
            delegate.delete(StoragePath.parse("work/a.txt"), false)
            throw failure
        }
        delegate.delete(path, recursive)
    }
}
