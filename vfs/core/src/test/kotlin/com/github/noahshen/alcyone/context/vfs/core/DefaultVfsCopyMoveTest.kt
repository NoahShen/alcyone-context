package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageContent
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * T21 S3：复制回退（同 Mount 无原生 move）与跨 Mount 文件移动的编排事实。
 *
 * 用例盯的是「顺序、接线与已知事实」：源与目标各按自己的路由取 Storage 与相对路径；复制按
 * 「读源 → 补目标父目录 → 写目标（CREATE_NEW）→ stat 确认 → 删源 → 提交」；
 * 确认不过就不删源；删源之后才提交同一 Node 的新路径与一条 `FILE_MOVED`。
 * 替身盘记下每次调用，所以「没调用 native move」「确认失败后没碰 delete」都是可断言的事实。
 *
 * 真实 SQLite 事务回滚、双 Local FS 与重开证据分别放在 `DefaultVfsCopyMoveRealStackTest` 与
 * `RuntimeCopyMoveTest`，替身证据不冒充真栈证据。
 */
class DefaultVfsCopyMoveTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行，不留分发协程。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    /**
     * 两块普通 Mount 的组装：`/resources` 归 [sourceKey]，`/resources/archive` 归 [targetKey]。
     *
     * 挂载后端换成测试自己的替身（[storages] 显式给出），所以替身能记下每次后端调用；
     * 能力用 [capabilityOverrides] 单独喂，不依赖挂载上那个占位盘。
     */
    private fun crossMountHarness(
        source: Storage,
        target: Storage,
        limits: VfsLimits = VfsLimits(),
        sourceCapabilities: StorageCapabilities? = null,
        targetCapabilities: StorageCapabilities? = null,
    ) = VfsHarness(
        mounts =
            listOf(
                VfsHarness.Mounted("/resources", StorageFakeImpl(), key = "left"),
                VfsHarness.Mounted("/resources/archive", StorageFakeImpl(), key = "right"),
            ),
        capabilityOverrides =
            buildMap {
                sourceCapabilities?.let { put("left", it) }
                targetCapabilities?.let { put("right", it) }
            },
        limits = limits,
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
        // 懒注册走自动提交、提交阶段又在事务视图里读同一条记录，所以这里让两份状态共享。
        shareNodeState = true,
    )

    /** 单挂载（`/resources`）的复制回退组装：能力位声明这个后端**没有**原生文件移动。 */
    private fun fallbackHarness(
        disk: Storage,
        limits: VfsLimits = VfsLimits(),
    ) = VfsHarness(
        mounts = listOf(VfsHarness.Mounted("/resources", StorageFakeImpl(), key = "left")),
        capabilityOverrides = mapOf("left" to StorageCapabilities(nativeFileMove = false)),
        limits = limits,
        storages = { disk },
        notifier = notifiers.create(),
        clock = { VfsHarness.FIXED_CLOCK },
        shareNodeState = true,
    )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    /** A01：同 Mount 回退走「读 → 写 → 确认 → 删源」，一次 native move 都不调。 */
    @Test
    fun `A01 the copy fallback copies confirms then deletes the source without calling native move`() =
        runBlocking {
            val disk = RecordingStorage(StorageFakeImpl())
            val harness = fallbackHarness(disk)
            disk.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(setOf("ct"), "胸部 CT"))
            disk.calls.clear()

            val info = harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt"))

            assertEquals(
                listOf(
                    "stat:a.txt",
                    "stat:archive/b.txt",
                    "read:a.txt",
                    "stat:archive",
                    "createDirectory:archive",
                    "write:archive/b.txt(CREATE_NEW)",
                    "stat:archive/b.txt",
                    "delete:a.txt",
                ),
                disk.calls.toList(),
                "预检 stat 源 / 目标 → 读源 → 补目标父目录 → CREATE_NEW 写入 → stat 确认 → 删源；全程没有 native move",
            )
            assertEquals(0, disk.nativeMoveCalls, "同 Mount 回退不允许下探到 storage.move")
            assertEquals("hello", disk.readText("archive/b.txt"), "内容逐字节落到目标")
            assertNull(disk.typeOfOrNull("a.txt"), "确认通过后才删源，源真的消失")

            assertEquals(original.id, info.id, "同 Mount 回退保留同一个 Node ID")
            assertEquals(uri("/archive/b.txt"), info.uri)
            assertEquals(NodeType.FILE, info.type)
            assertEquals(5L, info.storage?.sizeBytes, "返回的 storage 属性来自确认阶段那次目标 stat，不为拼返回值再查一次")
            assertEquals(5L, disk.statSizeOrNull("archive/b.txt"), "确认用的就是目标 stat 拿到的长度")

            val record = harness.committedNodes().single()
            assertEquals("/resources/archive/b.txt", record.path.toString(), "状态库里路径迁到目标")
            assertEquals(original.id, record.id)
            assertEquals(VfsHarness.FIXED_CLOCK, record.registeredAt, "registeredAt 不被重建")

            val events = harness.committedEvents()
            assertEquals(listOf(VfsEventType.FILE_MOVED), events.map { it.type }, "一次复制移动只发一条 FILE_MOVED")
            assertEquals(original.id, events.single().nodeId)
            assertEquals(uri("/archive/b.txt"), events.single().uri)
            assertEquals(uri("/a.txt"), events.single().sourceUri)
            assertEquals(uri("/archive/b.txt"), events.single().targetUri)
        }

    /** A01：跨 Mount 正例——源与目标各按自己的路由取 Storage 与相对路径，源盘消失、目标盘内容一致。 */
    @Test
    fun `A01 a cross mount move copies to the other disk deletes the source and commits one FILE_MOVED`() =
        runBlocking {
            val source = RecordingStorage(StorageFakeImpl())
            val target = RecordingStorage(StorageFakeImpl())
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(setOf("ct"), "胸部 CT"))
            source.calls.clear()
            target.calls.clear()

            val info = harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt"))

            assertEquals(
                listOf("stat:a.txt", "read:a.txt", "delete:a.txt"),
                source.calls.toList(),
                "源盘只按自己路由取 Storage 与相对路径：stat 源 → 读源 → 删源",
            )
            assertEquals(
                listOf("stat:b.txt", "write:b.txt(CREATE_NEW)", "stat:b.txt"),
                target.calls.toList(),
                "目标盘按自己路由确认目标全新、CREATE_NEW 写入、再 stat 确认长度",
            )
            assertEquals(0, source.nativeMoveCalls + target.nativeMoveCalls, "跨 Mount 一律复制，不下探到原生 move")
            assertEquals("hello", target.readText("b.txt"), "内容落到另一块盘")
            assertNull(source.typeOfOrNull("a.txt"), "源盘上的文件真的消失")

            assertEquals(original.id, info.id, "跨 Mount 移动保留同一个 Node ID")
            assertEquals(uri("/archive/b.txt"), info.uri)
            assertEquals(
                listOf("/resources/archive/b.txt"),
                harness.committedNodes().map { it.path.toString() },
                "状态库里路径迁到目标，父目录不登记",
            )
            assertEquals(original.id, harness.committedNodes().single().id)
            assertEquals(VfsHarness.FIXED_CLOCK, harness.committedNodes().single().registeredAt)
            assertEquals(NodeMetadata(setOf("ct"), "胸部 CT"), harness.uow.snapshot().second[original.id], "Metadata 随身份保留")
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type })
        }

    /** A01：跨 Mount 不因 `storageKey` 相同就改用原生 move——策略只由 mount 判定。 */
    @Test
    fun `A01 a cross mount move over one shared storage key still copies instead of moving natively`() =
        runBlocking {
            // 两个 mount 指向同一个 storageKey / 同一个 Storage 实例：即便后端支持原生 move，
            // 跨 Mount 仍必须是读 → 写 → 确认 → 删源（守卫 R2：不能只看 storageKey 就省成 rename）。
            val shared = RecordingStorage(StorageFakeImpl())
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources/one", StorageFakeImpl(), key = "shared"),
                            VfsHarness.Mounted("/resources/two", StorageFakeImpl(), key = "shared"),
                        ),
                    storages = { shared },
                    notifier = notifiers.create(),
                    clock = { VfsHarness.FIXED_CLOCK },
                    shareNodeState = true,
                )
            shared.withFile("a.txt", "hello")
            harness.seedNode("/resources/one/a.txt")
            shared.calls.clear()

            val info =
                harness.vfs.move(
                    VfsUri.parse("alcyone://resources/one/a.txt"),
                    VfsUri.parse("alcyone://resources/two/b.txt"),
                )

            assertEquals(
                listOf("stat:a.txt", "stat:b.txt", "read:a.txt", "write:b.txt(CREATE_NEW)", "stat:b.txt", "delete:a.txt"),
                shared.calls.toList(),
                "同一块盘上的跨 Mount 也是复制删除：两侧各自用相对路径 a.txt / b.txt",
            )
            assertEquals(0, shared.nativeMoveCalls, "storageKey 相同不改变策略")
            assertEquals("hello", shared.readText("b.txt"))
            assertNull(shared.typeOfOrNull("a.txt"))
            assertEquals("/resources/two/b.txt", info.uri.path.toString())
            assertEquals(
                listOf("/resources/two/b.txt"),
                harness.committedNodes().map { it.path.toString() },
            )
        }

    /** A02：未登记源只注册一次，目标不另建身份，没有中间的创建 / 删除事件。 */
    @Test
    fun `A02 an unregistered source is registered exactly once and the target gets no second identity`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = StorageFakeImpl()
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello") // 盘上有、状态库没有
            assertTrue(harness.committedNodes().isEmpty())

            val info = harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt"))

            assertEquals(
                listOf("/resources/archive/b.txt"),
                harness.committedNodes().map { it.path.toString() },
                "只建立一个身份，而且已经在目标路径，旧路径不留副本",
            )
            assertEquals(info.id, harness.committedNodes().single().id)
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type }, "没有中间的创建 / 删除事件")
            assertEquals(info.id, harness.committedEvents().single().nodeId)
        }

    /** A03：读取有界，超限报 LIMIT_EXCEEDED，源保留、目标不写、逻辑路径不动。 */
    @Test
    fun `A03 a copy over the read limit fails with LIMIT_EXCEEDED keeps the source and never writes the target`() =
        runBlocking {
            val source = RecordingStorage(StorageFakeImpl())
            val target = RecordingStorage(StorageFakeImpl())
            // 4 字节上限、6 字节文件：Core 读 / 写限额都是 4，所以「跨 Mount 复制」这道坎先挡住。
            val harness = crossMountHarness(source, target, limits = VfsLimits(defaultReadMaxBytes = 4, defaultWriteMaxBytes = 4))
            source.withFile("a.txt", "hello!")
            harness.seedNode("/resources/a.txt")
            source.calls.clear()
            target.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "超限由有界读取直接报，不先读进来再检查")
            assertEquals("hello!", source.readText("a.txt"), "超限时源一个字节都没动")
            assertEquals(
                listOf("stat:a.txt", "read:a.txt"),
                source.calls.toList(),
                "超限发生在读源那一刻，源盘没走到 delete",
            )
            assertEquals(listOf("stat:b.txt"), target.calls.toList(), "超限时目标盘只做了预检那次 stat，没建目录也没写")
            assertNull(target.typeOfOrNull("b.txt"), "目标文件没有出现")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "逻辑路径仍指向源")
            assertTrue(harness.committedEvents().isEmpty(), "失败不发成功事件")
        }

    /** A03：超限判定来自有界读取本身，不是 stat 报出来的长度。 */
    @Test
    fun `A03 the limit is enforced by the bounded read itself not by the stat length`() =
        runBlocking {
            // stat 谎报 2 字节（不超 4 字节上限），真实内容 6 字节：有界读取仍必须拒。
            val source = UnderreportingSizeStorage(StorageFakeImpl(), reportedSize = 2)
            val target = StorageFakeImpl()
            val harness = crossMountHarness(source, target, limits = VfsLimits(defaultReadMaxBytes = 4, defaultWriteMaxBytes = 4))
            source.withFile("a.txt", "hello!")
            harness.seedNode("/resources/a.txt")

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "stat 说 2 字节不算数，读取途中才算数")
            assertEquals("hello!", source.readText("a.txt"), "源保留")
            assertNull(target.typeOfOrNull("b.txt"), "目标没写")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A03：目标已存在 / 目标只读 / 目标父路径是文件，都先于注册源、补目录与物理复制。 */
    @Test
    fun `A03 the known refusals happen before the source is registered the parents are created or bytes move`() =
        runBlocking {
            val source = RecordingStorage(StorageFakeImpl())
            val target = RecordingStorage(StorageFakeImpl())
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello") // 未登记源
            target.withFile("taken.txt", "taken")
            source.calls.clear()
            target.calls.clear()

            // 目标已存在：ALREADY_EXISTS，不覆盖、不注册源、不建目录。
            val exists = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/taken.txt")) }
            assertEquals(VfsErrorCode.ALREADY_EXISTS, exists.code)
            assertEquals(VfsEffect.NONE, exists.effect, "预检拒绝，零副作用")
            assertTrue(harness.committedNodes().isEmpty(), "未登记源没有被注册")
            assertNull(
                harness.nodes.findByPath(
                    com.github.noahshen.alcyone.context.vfs.VfsPath
                        .parse("/resources/a.txt"),
                ),
                "自动提交那份也没登记",
            )
            assertEquals(listOf("stat:a.txt"), source.calls.toList(), "源盘只 stat 了一次，没读没删")
            assertEquals(listOf("stat:taken.txt"), target.calls.toList(), "目标盘只 stat 了一次，没建目录也没写")
            assertEquals("taken", target.readText("taken.txt"), "已有目标不被覆盖")
            assertEquals("hello", source.readText("a.txt"), "源原样留着")
            assertTrue(harness.committedEvents().isEmpty())

            // 目标只读：READ_ONLY，同样在读源之前就拒。
            val readOnlySource = RecordingStorage(StorageFakeImpl())
            val readOnlyTarget = RecordingStorage(StorageFakeImpl())
            val readOnlyHarness =
                crossMountHarness(readOnlySource, readOnlyTarget, targetCapabilities = StorageCapabilities(readOnly = true))
            readOnlySource.withFile("a.txt", "hello")
            readOnlySource.calls.clear()
            readOnlyTarget.calls.clear()

            val readOnly = assertFailsWith<VfsException> { readOnlyHarness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }
            assertEquals(VfsErrorCode.READ_ONLY, readOnly.code)
            assertEquals(VfsEffect.NONE, readOnly.effect)
            // 只读判定排在「为拿到实际类型那一次 stat 源」之后，但排在读 / 写 / 补目录 / 删源之前。
            assertEquals(listOf("stat:a.txt"), readOnlySource.calls.toList(), "只读盘上没读过、没写过、没建过目录：${readOnlySource.calls}")
            assertEquals(emptyList<String>(), readOnlyTarget.calls.toList(), "目标盘一次都没被碰")
            assertTrue(readOnlyHarness.committedNodes().isEmpty(), "只读目标下连懒注册都没发生")
            assertTrue(readOnlyHarness.committedEvents().isEmpty())

            // 目标父路径是文件：TYPE_MISMATCH——读完源才发现，但不写目标、不删源。
            val blockedSource = RecordingStorage(StorageFakeImpl())
            val blockedTarget = RecordingStorage(StorageFakeImpl())
            val blocked = crossMountHarness(blockedSource, blockedTarget)
            blockedSource.withFile("a.txt", "hello")
            blockedTarget.withFile("blocker", "i am a file")
            blockedTarget.calls.clear()

            val mismatched = assertFailsWith<VfsException> { blocked.vfs.move(uri("/a.txt"), uri("/archive/blocker/b.txt")) }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, mismatched.code)
            assertEquals("i am a file", blockedTarget.readText("blocker"), "遮蔽文件不被改写")
            assertNull(blockedTarget.typeOfOrNull("blocker/b.txt"), "目标没有写出来")
            assertEquals("hello", blockedSource.readText("a.txt"), "源没被删")
            assertTrue(blockedSource.calls.none { it.startsWith("delete:") }, "父路径冲突时绝不删源：${blockedSource.calls}")
            assertTrue(blocked.committedEvents().isEmpty())
        }

    /** A04：源读取失败 → 不补目录、不写目标、不删源、不提交。 */
    @Test
    fun `A04 a source read failure keeps the source and never creates parents or writes the target`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = RecordingStorage(StorageFakeImpl())
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            harness.seedNode("/resources/a.txt")
            source.failures.onRead = VfsException(VfsErrorCode.STORAGE_ERROR, "backend refused the read")
            target.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "保留后端原始错误码")
            assertEquals(VfsEffect.NONE, failure.effect, "读失败时还没建任何目录、也没写目标")
            source.failures.onRead = null // 只撤掉注入，下面要读回源的真实内容来核对
            assertEquals("hello", source.readText("a.txt"), "源没被删")
            assertEquals(listOf("stat:b.txt"), target.calls.toList(), "读失败后目标盘只有预检那次 stat：${target.calls}")
            assertNull(target.typeOfOrNull("b.txt"))
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "逻辑路径仍指向源")
            assertTrue(harness.committedEvents().isEmpty(), "失败不发成功事件")
        }

    /** A04：目标写入失败 → 源保留；已经建出来的父目录合并进 effect。 */
    @Test
    fun `A04 a target write failure keeps the source and merges the created parents into the effect`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = StorageFakeImpl()
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            harness.seedNode("/resources/a.txt")
            // 目标补完父目录之后写入才失败：archive/sub 这次真建在目标盘上了。
            target.failures.onWrite = VfsException(VfsErrorCode.STORAGE_ERROR, "backend refused the write")
            target.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/sub/b.txt")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect, "sub 这次真建出来了，报 NONE 才是错的")
            // 目标盘上的相对路径是 /resources/archive 挂载根内的 `sub`——前缀 archive 是挂载点，不在盘上。
            assertEquals(NodeType.DIRECTORY, target.typeOfOrNull("sub"), "补出的父目录留在目标盘上，不自动回删")
            assertNull(target.typeOfOrNull("sub/b.txt"), "目标文件没写成")
            assertEquals("hello", source.readText("a.txt"), "源保留")
            assertTrue(target.calls.none { it.startsWith("delete:") }, "写失败绝不删源：${target.calls}")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() })
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A04：确认失败（写入之后的目标 stat 报错）→ CONFLICT，源保留、残留目标不自动删除、不提交。 */
    @Test
    fun `A04 a target confirmation failure keeps the source and leaves the written target behind`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = ConfirmingStorage(StorageFakeImpl())
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            source.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "确认阶段失败按 CONFLICT 报告")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "目标已经写出来了，这是已知变更")
            assertNotNull(failure.cause, "后端原始错误保留在 cause 上")
            assertEquals("hello", source.readText("a.txt"), "确认不过就不删源")
            assertEquals("hello", target.readText("b.txt"), "残留目标不自动删除，如实留在目标盘上")
            assertEquals(0, target.deleteCalls, "确认失败绝不调用 delete")
            assertEquals(1, target.statAfterWrite, "确认只发生一次：写入之后对目标本身的 stat")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "逻辑路径仍指向源")
            assertEquals(original.id, harness.committedNodes().single().id)
            assertTrue(harness.committedEvents().isEmpty(), "失败不发成功事件")
        }

    /** A04：确认时长度不一致（复制 5 字节、目标只有 3 字节）→ CONFLICT，源保留、残留目标留着。 */
    @Test
    fun `A04 a length mismatch after the write fails with CONFLICT keeps the source and does not delete anything`() =
        runBlocking {
            val source = StorageFakeImpl()
            // 目标后端把写入截断成 3 字节：写入回执与 stat 都只说 3，与实际复制的 5 对不上。
            val target = TruncatingStorage(StorageFakeImpl(), writtenSize = 3)
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello") // 5 字节
            harness.seedNode("/resources/a.txt")
            source.calls.clear()

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "已知长度变化按 CONFLICT 报")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "目标已经写了，这是已知变更")
            assertEquals("hello", source.readText("a.txt"), "源保留")
            assertEquals(0, target.deleteCalls, "长度对不上就绝不删源")
            assertEquals(3L, target.fileSizeOrNull("b.txt"), "被截断的目标留在盘上，不自动清理")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() })
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A05：删源失败 → 逻辑路径仍指向源、没有成功事件，两份文件都在盘上。 */
    @Test
    fun `A05 a source delete failure after a good copy keeps the logical mapping at the source`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = StorageFakeImpl()
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(description = "keep"))
            source.failures.onDelete = VfsException(VfsErrorCode.STORAGE_ERROR, "backend refused the delete")

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "删源失败保留后端原始错误码")
            assertEquals(VfsEffect.PARTIAL, failure.effect, "目标已经写出来了，这是已知变更")
            assertNotNull(failure.cause, "原始失败保留在 cause 上")
            assertEquals("hello", source.readText("a.txt"), "源还在")
            assertEquals("hello", target.readText("b.txt"), "两份文件都在盘上，如实反映")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "逻辑路径仍指向源，不提交新映射")
            assertEquals(original.id, harness.committedNodes().single().id)
            assertEquals(NodeMetadata(description = "keep"), harness.uow.snapshot().second[original.id])
            assertTrue(harness.committedEvents().isEmpty(), "删源失败没有成功事件")
        }

    /** A05：删源成功但状态 / 事件提交失败 → STATE_ERROR + PARTIAL，保留 cause、不回移、不通知成功。 */
    @Test
    fun `A05 a commit failure after the source delete reports STATE_ERROR with PARTIAL and no compensation`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = StorageFakeImpl()
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(original.id, NodeMetadata(description = "keep"))
            harness.uow.failOnEventAppend = VfsException(VfsErrorCode.STATE_ERROR, "event log rejected the row")

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect, "源已删、目标已写，这是已知变更")
            assertNotNull(failure.cause, "原始失败保留在 cause 上")
            assertNull(source.typeOfOrNull("a.txt"), "源已删除，不回移")
            assertEquals("hello", target.readText("b.txt"), "目标内容保留")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "同批的路径更新一起回滚")
            assertEquals(original.id, harness.committedNodes().single().id)
            assertEquals(NodeMetadata(description = "keep"), harness.uow.snapshot().second[original.id], "Metadata 不动")
            assertTrue(harness.committedEvents().isEmpty(), "同批的事件一起回滚，没有成功通知")
        }

    /** A06：后端 UNKNOWN 不因为「目录已经建出来」而降级成 PARTIAL。 */
    @Test
    fun `A06 a backend UNKNOWN on the target write is preserved and never downgraded to PARTIAL`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = StorageFakeImpl()
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            harness.seedNode("/resources/a.txt")
            // 先建出 archive/sub，再让写入报 UNKNOWN：后端说不清就说 UNKNOWN，不被已知变更压成 PARTIAL。
            target.failures.onWrite = VfsException(VfsErrorCode.STORAGE_ERROR, "connection lost mid write", effect = VfsEffect.UNKNOWN)

            val failure = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/sub/b.txt")) }

            assertEquals(VfsEffect.UNKNOWN, failure.effect, "后端说不清就是说不清，不改报 NONE / PARTIAL")
            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
            assertEquals(NodeType.DIRECTORY, target.typeOfOrNull("sub"), "已建目录是真实事实，但 effect 仍按后端说的来")
            assertEquals("hello", source.readText("a.txt"), "源保留")
            assertTrue(harness.committedEvents().isEmpty())
        }

    /** A07：进入复制流程后的受控取消原样传播、不删源、不提交，边界随后可放行。 */
    @Test
    fun `A07 a copy move cancelled at the target write propagates and commits nothing`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = PausingWriteStorage(StorageFakeImpl())
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            val original = harness.seedNode("/resources/a.txt")

            val cancellation = CancellationException("cancelled while writing the copy target")
            // 捕获发生在**调用 DefaultVfs 的协程内部**：被取消的 Deferred.await() 必然抛取消，
            // 拿它当证据等于什么都没测，所以要留下复制链自己真正抛出来的那一个。
            val escaped = CompletableDeferred<Throwable?>()
            val job =
                async(Dispatchers.Default) {
                    try {
                        harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt"))
                        escaped.complete(null)
                    } catch (failure: Throwable) {
                        escaped.complete(failure)
                    }
                }
            try {
                // 复制流程已经走到写入目标这一步（读源、补父目录都真的发生了），取消就发生在这个挂起点上。
                target.awaitWrite()
                job.cancel(cancellation)

                withTimeout(30_000) { job.join() }
                val escapedFailure = withTimeout(30_000) { escaped.await() }
                assertNotNull(escapedFailure, "移动链应该把取消抛出来，而不是安静地结束")
                assertFalse(escapedFailure is VfsException, "取消没有被包装成 VfsException：$escapedFailure")
                assertTrue(escapedFailure is CancellationException, "CancellationException 原样传播：$escapedFailure")
                assertSame(cancellation, escapedFailure?.cause ?: escapedFailure, "取消原因一致（协程栈帧恢复可能复制异常，原实例在 cause 上）")

                // 取消点的物理事实：读过了、父目录补出来了、目标还没写成、源没被删。
                assertEquals("hello", source.readText("a.txt"), "取消不删源")
                assertNull(target.typeOfOrNull("b.txt"), "取消时目标还没写出来")
                assertEquals(0, target.deleteCalls, "取消后没有继续删源")
                assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "取消不提交路径更新")
                assertEquals(original.id, harness.committedNodes().single().id, "源身份不变")
                assertTrue(harness.committedEvents().isEmpty(), "取消不发事件")
            } finally {
                // 失败路径也要可清理：先放门，再取消 / 等待未完成的协程。
                target.releaseWrite()
                job.cancelAndJoin()
            }

            // 边界已放行：紧接着的移动能正常拿到同一把锁并完成。
            val after = harness.vfs.move(uri("/a.txt"), uri("/archive/b.txt"))
            assertEquals(original.id, after.id)
            assertEquals("hello", target.readText("b.txt"))
            assertNull(source.typeOfOrNull("a.txt"))
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type })
            assertEquals(1, target.pauseCount, "只有第一次进到写入时才挂过")
        }

    /** A07：被拒绝的那次不占着边界也不动记录，后续操作照常。 */
    @Test
    fun `A07 a refused copy move leaves the boundary usable and does not touch the node count`() =
        runBlocking {
            val source = StorageFakeImpl()
            val target = StorageFakeImpl()
            val harness = crossMountHarness(source, target)
            source.withFile("a.txt", "hello")
            target.withFile("taken.txt", "taken")
            val original = harness.seedNode("/resources/a.txt")

            val refused = assertFailsWith<VfsException> { harness.vfs.move(uri("/a.txt"), uri("/archive/taken.txt")) }
            assertEquals(VfsErrorCode.ALREADY_EXISTS, refused.code)
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() })
            assertEquals(original.id, harness.committedNodes().single().id)

            val after = harness.vfs.move(uri("/a.txt"), uri("/archive/ok.txt"))
            assertEquals(original.id, after.id, "被拒绝的那次没有把边界留坏，后续移动能正常完成")
            assertEquals("hello", target.readText("ok.txt"))
            assertNull(source.typeOfOrNull("a.txt"))
            assertFalse(harness.committedEvents().isEmpty())
            assertEquals(1, harness.committedEvents().count { it.type == VfsEventType.FILE_MOVED })
        }

    /**
     * 包一层存储：把每次后端调用记成可断言的字符串（写入带模式），并数住原生 move 的次数。
     * 其余行为全部转发给内存盘——这是「能力受控的包装」，不是另一套文件系统语义。
     */
    private class RecordingStorage(
        private val delegate: StorageFakeImpl,
    ) : Storage by delegate {
        val calls: MutableList<String> = mutableListOf()
        var nativeMoveCalls: Int = 0
            private set

        override suspend fun stat(path: StoragePath): StorageAttributes {
            calls += "stat:${path.toRelativeString()}"
            return delegate.stat(path)
        }

        override suspend fun read(
            path: StoragePath,
            maxBytes: Long,
        ): StorageContent {
            calls += "read:${path.toRelativeString()}"
            return delegate.read(path, maxBytes)
        }

        override suspend fun write(
            path: StoragePath,
            content: ByteArray,
            mode: StorageWriteMode,
        ): StorageAttributes {
            calls += "write:${path.toRelativeString()}(${mode.name})"
            return delegate.write(path, content, mode)
        }

        override suspend fun createDirectory(path: StoragePath) {
            calls += "createDirectory:${path.toRelativeString()}"
            delegate.createDirectory(path)
        }

        override suspend fun move(
            source: StoragePath,
            target: StoragePath,
        ): StorageAttributes {
            nativeMoveCalls++
            calls += "move:${source.toRelativeString()}->${target.toRelativeString()}"
            return delegate.move(source, target)
        }

        override suspend fun delete(
            path: StoragePath,
            recursive: Boolean,
        ) {
            calls += "delete:${path.toRelativeString()}"
            delegate.delete(path, recursive)
        }

        override fun capabilities(): StorageCapabilities = delegate.capabilities()

        fun withFile(
            path: String,
            content: String,
        ) {
            delegate.withFile(path, content)
        }

        fun readText(path: String): String = delegate.readText(path)

        fun typeOfOrNull(path: String): NodeType? = delegate.typeOfOrNull(path)

        suspend fun statSizeOrNull(path: String): Long? =
            delegate.typeOfOrNull(path)?.let { delegate.read(StoragePath.parse(path), Long.MAX_VALUE).attributes.sizeBytes }
    }

    /**
     * 包一层存储：写入真正落盘之后，让**确认阶段**（写入之后对目标本身的 stat）报错。
     * 用来造「目标已写、确认失败」的受控反例——真实磁盘不会恰好在确认那一刻停住。
     */
    private class ConfirmingStorage(
        private val delegate: StorageFakeImpl,
    ) : Storage by delegate {
        private var written = false
        var deleteCalls: Int = 0
            private set
        var statAfterWrite: Int = 0
            private set

        override suspend fun write(
            path: StoragePath,
            content: ByteArray,
            mode: StorageWriteMode,
        ): StorageAttributes {
            val attributes = delegate.write(path, content, mode)
            written = true
            return attributes
        }

        override suspend fun stat(path: StoragePath): StorageAttributes {
            if (written && path.toRelativeString() == "b.txt") {
                statAfterWrite++
                throw VfsException(VfsErrorCode.STORAGE_ERROR, "target confirmation could not read the backend")
            }
            return delegate.stat(path)
        }

        override suspend fun delete(
            path: StoragePath,
            recursive: Boolean,
        ) {
            deleteCalls++
            delegate.delete(path, recursive)
        }

        fun readText(path: String): String = delegate.readText(path)
    }

    /**
     * 包一层存储：把写入内容截断成 [writtenSize] 字节——盘上真的只有这么多字节，
     * 所以写入回执与之后的 stat 都会如实报这个更小的长度。
     */
    private class TruncatingStorage(
        private val delegate: StorageFakeImpl,
        private val writtenSize: Int,
    ) : Storage by delegate {
        var deleteCalls: Int = 0
            private set

        override suspend fun write(
            path: StoragePath,
            content: ByteArray,
            mode: StorageWriteMode,
        ): StorageAttributes = delegate.write(path, content.copyOf(writtenSize), mode)

        override suspend fun delete(
            path: StoragePath,
            recursive: Boolean,
        ) {
            deleteCalls++
            delegate.delete(path, recursive)
        }

        suspend fun fileSizeOrNull(path: String): Long? =
            delegate.typeOfOrNull(path)?.let { delegate.read(StoragePath.parse(path), Long.MAX_VALUE).attributes.sizeBytes }
    }

    /**
     * 包一层存储：`stat` 谎报一个比实际小的长度（读仍按真实内容校验上限）。
     * 用来证明「超限判定来自有界读取本身，而不是 stat 的长度」。
     */
    private class UnderreportingSizeStorage(
        private val delegate: StorageFakeImpl,
        private val reportedSize: Long,
    ) : Storage by delegate {
        override suspend fun stat(path: StoragePath): StorageAttributes =
            delegate.stat(path).let { if (it.sizeBytes != null) it.copy(sizeBytes = reportedSize) else it }

        fun withFile(
            path: String,
            content: String,
        ) {
            delegate.withFile(path, content)
        }

        fun readText(path: String): String = delegate.readText(path)
    }

    /**
     * 包一层存储：在**目标真正落盘之前**挂住，由测试放行或取消——这是 T21 选定的那个受控取消点。
     * 挂住的位置是「读源与补父目录都完成之后」，所以取消时能同时核对「已发生的复制事实」与「没发生的删源」。
     */
    private class PausingWriteStorage(
        private val delegate: StorageFakeImpl,
    ) : Storage by delegate {
        private val arrival = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()
        private var paused = false
        var pauseCount: Int = 0
            private set
        var deleteCalls: Int = 0
            private set

        override suspend fun write(
            path: StoragePath,
            content: ByteArray,
            mode: StorageWriteMode,
        ): StorageAttributes {
            if (!paused) {
                paused = true
                pauseCount++
                arrival.complete(Unit)
                release.await()
            }
            return delegate.write(path, content, mode)
        }

        override suspend fun delete(
            path: StoragePath,
            recursive: Boolean,
        ) {
            deleteCalls++
            delegate.delete(path, recursive)
        }

        /** 等复制流程走到写入目标这一步。 */
        suspend fun awaitWrite() {
            withTimeout(30_000) { arrival.await() }
        }

        fun releaseWrite() {
            release.complete(Unit)
        }

        fun typeOfOrNull(path: String): NodeType? = delegate.typeOfOrNull(path)

        fun readText(path: String): String = delegate.readText(path)
    }
}
