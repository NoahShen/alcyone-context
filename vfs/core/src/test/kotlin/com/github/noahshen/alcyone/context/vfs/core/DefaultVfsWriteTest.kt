package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.makeDirectory
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T15 S2：写一条链的编排顺序、模式矩阵、父目录与失败 effect。
 *
 * 这些用例证明的是「顺序和分支」：替身盘记下每次调用，所以「拒绝时一次都没动手」是可断言的事实。
 * 真实事务回滚（SQLite 真的 roll back）放在 `DefaultVfsRealStackTest`。
 */
class DefaultVfsWriteTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行，不留分发协程。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    private fun harness(
        mounts: List<VfsHarness.Mounted> = listOf(VfsHarness.Mounted("/resources")),
        limits: VfsLimits = VfsLimits(),
        capabilityOverrides: Map<String, StorageCapabilities> = emptyMap(),
        clock: () -> Instant = { VfsHarness.FIXED_CLOCK },
    ) = VfsHarness(mounts, limits = limits, capabilityOverrides = capabilityOverrides, notifier = notifiers.create(), clock = clock)

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    @Test
    fun `A02 a new file registers the node and appends FILE_CREATED in one commit`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))

            val info = harness.vfs.write(uri("/a.txt"), "hello".toByteArray())

            val nodes = harness.committedNodes()
            val events = harness.committedEvents()
            assertEquals(1, nodes.size, "只登记写好的那个文件")
            assertEquals("/resources/a.txt", nodes.single().path.toString())
            assertEquals(NodeType.FILE, nodes.single().type)
            assertTrue(nodes.single().physical)
            assertEquals(listOf(VfsEventType.FILE_CREATED), events.map { it.type }, "第一次写是 FILE_CREATED")
            assertEquals(info.id, events.single().nodeId)
            assertEquals(5L, info.storage?.sizeBytes, "返回值复用写入拿到的磁盘属性，不为返回值再 stat")
            assertEquals(listOf("stat:a.txt", "write:a.txt"), disk.calls, "父目录齐全就不建目录")
        }

    @Test
    fun `A02 overwriting a registered file keeps the identity and reports FILE_WRITTEN`() =
        runBlocking {
            var now = VfsHarness.FIXED_CLOCK
            val disk = StorageFakeImpl()
            val seeded = VfsHarness.Mounted("/resources", disk)
            val harness = harness(listOf(seeded), clock = { now })
            disk.withFile("a.txt", "old") // 磁盘上真的有这个文件，事件才判成覆盖
            val original = harness.seedNode("/resources/a.txt")

            now = VfsHarness.FIXED_CLOCK.plusSeconds(60)
            val info = harness.vfs.write(uri("/a.txt"), "second".toByteArray())

            assertEquals(original.id, info.id, "覆盖保留同一个 Node ID")
            assertEquals(original.registeredAt, info.registeredAt, "登记时间不变")
            assertEquals(now, info.updatedAt, "updatedAt 随成功变更推进")
            assertEquals(listOf(VfsEventType.FILE_WRITTEN), harness.committedEvents().map { it.type }, "覆盖已有文件是 FILE_WRITTEN")
            assertEquals("second", disk.readText("a.txt"))
        }

    @Test
    fun `A02 overwriting a file that was never registered still says FILE_WRITTEN`() =
        runBlocking {
            // 磁盘上有这个文件，但状态库没有它的身份：事件按**文件实际存在性**判，不看 Registry 有没有记录。
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "old")

            val info = harness.vfs.write(uri("/a.txt"), "new".toByteArray())

            assertEquals(listOf(VfsEventType.FILE_WRITTEN), harness.committedEvents().map { it.type }, "文件本来就存在")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "成功写入后才建立身份")
            assertEquals("new", disk.readText("a.txt"))
            assertEquals(info.id, harness.committedEvents().single().nodeId)
        }

    @Test
    fun `A03 every rejectable case is refused before a directory or a byte is created`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)), limits = VfsLimits(defaultWriteMaxBytes = 4))
            disk.withFile("exists.txt", "old")
            disk.makeDirectory("dir")

            val rejections =
                listOf(
                    Rejection("内容超限", uri("/big.txt"), "x".repeat(5).toByteArray(), VfsErrorCode.LIMIT_EXCEEDED),
                    Rejection("没有挂载", VfsUri.parse("alcyone://elsewhere/a.txt"), "hi".toByteArray(), VfsErrorCode.MOUNT_NOT_FOUND),
                    Rejection("写命名空间根", uri(""), "hi".toByteArray(), VfsErrorCode.UNSUPPORTED_OPERATION),
                    Rejection("写挂载根", uri("/"), "hi".toByteArray(), VfsErrorCode.UNSUPPORTED_OPERATION),
                    Rejection("目标已是目录", uri("/dir"), "hi".toByteArray(), VfsErrorCode.TYPE_MISMATCH),
                    Rejection("逻辑记录类型冲突", uri("/conflict.txt"), "hi".toByteArray(), VfsErrorCode.CONFLICT),
                )
            harness.seedNode("/resources/conflict.txt", type = NodeType.DIRECTORY, id = "018f0a5c-1b2c-7def-8abc-0000000000e2")
            for (case in rejections) {
                disk.calls.clear()
                val failure = assertFailsWith<VfsException>("${case.why} 应该被拒绝") { harness.vfs.write(case.target, case.content) }
                assertEquals(case.expected, failure.code, case.why)
                assertEquals(VfsEffect.NONE, failure.effect, "${case.why} 没有任何副作用")
                assertTrue(
                    disk.calls.none { it.startsWith("write:") || it.startsWith("createDirectory:") },
                    "${case.why} 之前不该建目录或写内容，实际调用：${disk.calls}",
                )
            }
        }

    /** 一条「本该被拒」的用例：理由、目标、内容和期望错误码。 */
    private data class Rejection(
        val why: String,
        val target: VfsUri,
        val content: ByteArray,
        val expected: VfsErrorCode,
    )

    @Test
    fun `A02 the mode matrix is enforced on the real existence of the target`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("exists.txt", "old")

            val alreadyThere =
                assertFailsWith<VfsException> {
                    harness.vfs.write(uri("/exists.txt"), "new".toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
                }
            assertEquals(VfsErrorCode.ALREADY_EXISTS, alreadyThere.code)
            assertEquals("old", disk.readText("exists.txt"), "被拒绝的写没有碰内容")

            val missing =
                assertFailsWith<VfsException> {
                    harness.vfs.write(uri("/gone.txt"), "new".toByteArray(), WriteOptions(WriteMode.REPLACE_EXISTING))
                }
            assertEquals(VfsErrorCode.NOT_FOUND, missing.code)
            assertTrue(
                disk.calls.none { it.startsWith("createDirectory:") },
                "REPLACE_EXISTING 目标不存在时不产生父目录副作用：${disk.calls}",
            )

            harness.vfs.write(uri("/fresh.txt"), "new".toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
            assertEquals("new", disk.readText("fresh.txt"), "CREATE_NEW 在目标缺失时就是创建")
        }

    @Test
    fun `A03 missing parents are created inside the mount only and are never registered`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))

            harness.vfs.write(uri("/a/b/c.txt"), "deep".toByteArray())

            assertEquals(
                listOf("stat:a/b/c.txt", "stat:a", "stat:a/b", "createDirectory:a", "createDirectory:a/b", "write:a/b/c.txt"),
                disk.calls,
                "先确认目标、再把所有缺失的父目录看一遍、然后逐层建、最后写内容",
            )
            assertEquals(listOf("/resources/a/b/c.txt"), harness.committedNodes().map { it.path.toString() }, "父目录不批量登记")
            assertEquals(1, harness.committedEvents().size, "补目录不发单独的事件")
            assertEquals(NodeType.DIRECTORY, disk.typeOf("a/b"))
            assertEquals("deep", disk.readText("a/b/c.txt"))
        }

    @Test
    fun `A03 a physical file on the parent path is a reject and it stays untouched`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a", "I am a file")
            disk.calls.clear() // 下面的断言只看这次写造成的调用

            val failure = assertFailsWith<VfsException> { harness.vfs.write(uri("/a/b.txt"), "x".toByteArray()) }

            assertEquals(VfsErrorCode.TYPE_MISMATCH, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertEquals("I am a file", disk.readText("a"), "遮蔽的物理文件一个字节都不改")
            assertTrue(disk.calls.none { it.startsWith("write:") }, "没写内容：${disk.calls}")
            assertEquals(VfsEffect.NONE, failure.effect, "父路径本来就是文件，一个目录也没建")
        }

    @Test
    fun `A03 a storage without directory creation only fails when a parent is really missing`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness =
                harness(
                    listOf(VfsHarness.Mounted("/resources", disk)),
                    capabilityOverrides = mapOf("/resources" to StorageCapabilities(createDirectory = false)),
                )

            val failure = assertFailsWith<VfsException> { harness.vfs.write(uri("/missing/b.txt"), "x".toByteArray()) }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code, "确实要补目录而后端不能建")
            assertEquals(VfsEffect.NONE, failure.effect, "一个目录也没建成")

            harness.vfs.write(uri("/top.txt"), "x".toByteArray())
            assertEquals("x", disk.readText("top.txt"), "父目录已经齐全时不因缺能力而拒绝")
        }

    @Test
    fun `A05 a write that fails after the parents were created keeps PARTIAL, never NONE`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.failures.onWrite = VfsException(VfsErrorCode.STORAGE_ERROR, "disk is full")

            val failure = assertFailsWith<VfsException> { harness.vfs.write(uri("/a/b.txt"), "x".toByteArray()) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect, "留下的空目录 a 是真实副作用")
            assertEquals(NodeType.DIRECTORY, disk.typeOf("a"), "建出来的目录确实还在")
            assertTrue(harness.committedNodes().isEmpty(), "没有提交任何 Node")
            assertTrue(harness.committedEvents().isEmpty(), "没有提交任何事件")
        }

    @Test
    fun `A05 an UNKNOWN backend failure is not downgraded even when parents were created`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.failures.onWrite =
                VfsException(VfsErrorCode.STORAGE_ERROR, "connection lost mid write", effect = VfsEffect.UNKNOWN)

            val withParents =
                assertFailsWith<VfsException> { harness.vfs.write(uri("/a/b.txt"), "x".toByteArray()) }
            assertEquals(VfsEffect.UNKNOWN, withParents.effect, "后端说不清就是说不清")

            val withoutParents =
                assertFailsWith<VfsException> { harness.vfs.write(uri("/c.txt"), "x".toByteArray()) }
            assertEquals(VfsEffect.UNKNOWN, withoutParents.effect, "没补目录也不能改报 NONE")
        }

    @Test
    fun `A05 a commit failure after the file was written reports STATE_ERROR with PARTIAL`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            harness.uow.failOnEventAppend = VfsException(VfsErrorCode.STATE_ERROR, "event log rejected the row")

            val failure = assertFailsWith<VfsException> { harness.vfs.write(uri("/a.txt"), "x".toByteArray()) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect, "文件已经写进去了，这是已知变更")
            assertEquals("x", disk.readText("a.txt"), "物理内容保留，不自动补偿")
            assertTrue(harness.committedNodes().isEmpty(), "同批的 Node 一起回滚")
            assertTrue(harness.committedEvents().isEmpty(), "同批的事件一起回滚，也没有成功通知")
        }

    @Test
    fun `A05 a state failure before anything was written stays effect NONE`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.failures.onStat = VfsException(VfsErrorCode.STORAGE_ERROR, "backend is gone")

            val failure = assertFailsWith<VfsException> { harness.vfs.write(uri("/a.txt"), "x".toByteArray()) }

            assertEquals(VfsEffect.NONE, failure.effect)
            assertTrue(disk.calls.none { it.startsWith("write:") }, "连确认都过不去，不该动手")
        }

    @Test
    fun `A03 a write over the configured limit is refused before the backend is touched`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)), limits = VfsLimits(defaultWriteMaxBytes = 3))

            harness.vfs.write(uri("/a.txt"), "abc".toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
            val failure =
                assertFailsWith<VfsException> {
                    harness.vfs.write(uri("/b.txt"), "abcd".toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
                }

            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)
            assertTrue(disk.calls.none { it.startsWith("write:b.txt") }, "超限的写根本没碰后端：${disk.calls}")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() })
        }

    @Test
    fun `A02 the returned id is a uuid v7 and two writes of one path share it`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))

            val first = harness.vfs.write(uri("/a.txt"), "one".toByteArray())
            val second = harness.vfs.write(uri("/a.txt"), "two".toByteArray())

            assertEquals(first.id, second.id, "同一路径的两次写是同一个身份")
            assertEquals('7', second.id.toString()[14], "ID 是 UUIDv7")
            assertEquals(listOf(VfsEventType.FILE_CREATED, VfsEventType.FILE_WRITTEN), harness.committedEvents().map { it.type })
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() }, "覆盖不产生第二条记录")
        }
}
