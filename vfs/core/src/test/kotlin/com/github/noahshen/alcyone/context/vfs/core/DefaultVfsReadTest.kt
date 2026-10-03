package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.ReadOptions
import com.github.noahshen.alcyone.context.vfs.StatOptions
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
 * T15 S1：read 的路由与限额，以及 stat / getNode 对 T13 Registry 的接线。
 *
 * read 不登记 Node、不改 Metadata、不发事件；stat 委托 Registry，所以懒注册与 includeStorage 两种语义
 * 一律沿用 T13 已确认的行为，这里不重新实现一遍，只钉住「DefaultVfs 确实把它接上了」。
 */
class DefaultVfsReadTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    private fun harness(
        mounts: List<VfsHarness.Mounted> = listOf(VfsHarness.Mounted("/resources")),
        limits: VfsLimits = VfsLimits(),
    ) = VfsHarness(mounts, limits = limits, notifier = notifiers.create())

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    @Test
    fun `A01 an unregistered file can be read and reading it registers nothing`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")

            assertEquals("hello", harness.vfs.read(uri("/a.txt")).toString(Charsets.UTF_8))
            assertNull(harness.nodes.findByPath(VfsPath.parse("/resources/a.txt")), "读一个文件不登记 Node")
            assertTrue(harness.committedNodes().isEmpty(), "也没有任何状态写入")
            assertTrue(harness.committedEvents().isEmpty(), "读不发事件")
        }

    @Test
    fun `A01 stat is the first thing that hands out an identity and later stats reuse it`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")

            val first = harness.vfs.stat(uri("/a.txt"))
            val second = harness.vfs.stat(uri("/a.txt"))

            assertEquals(first.id, second.id, "同一个文件始终同一个 ID")
            assertEquals(5L, first.storage?.sizeBytes, "stat 默认问后端要属性")
            assertNull(harness.vfs.getNode(first.id).storage, "getNode 是纯逻辑查询")
        }

    @Test
    fun `A01 stat with includeStorage false answers an existing node without asking the backend`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.withFile("a.txt", "hello")
            val registered = harness.vfs.stat(uri("/a.txt"))
            disk.calls.clear()

            val logical = harness.vfs.stat(uri("/a.txt"), StatOptions(includeStorage = false))

            assertEquals(registered.id, logical.id)
            assertNull(logical.storage, "不查后端就没有磁盘属性")
            assertTrue(disk.calls.isEmpty(), "includeStorage=false 一次后端都不碰：${disk.calls}")
        }

    @Test
    fun `A01 a directory is not readable and a missing mount is MOUNT_NOT_FOUND`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)))
            disk.makeDirectory("dir")

            val directory = assertFailsWith<VfsException> { harness.vfs.read(uri("/dir")) }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, directory.code)

            val namespaceRoot = assertFailsWith<VfsException> { harness.vfs.read(uri("")) }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, namespaceRoot.code, "命名空间根是配置目录")

            val unmounted = assertFailsWith<VfsException> { harness.vfs.read(VfsUri.parse("alcyone://elsewhere/a.txt")) }
            assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, unmounted.code)

            val missing = assertFailsWith<VfsException> { harness.vfs.read(uri("/nope.txt")) }
            assertEquals(VfsErrorCode.NOT_FOUND, missing.code)
        }

    @Test
    fun `A01 the effective read limit is the smaller of the configured one and the per call one`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(listOf(VfsHarness.Mounted("/resources", disk)), limits = VfsLimits(defaultReadMaxBytes = 10))
            disk.withFile("ten.txt", "0123456789")
            disk.withFile("eleven.txt", "0123456789x")
            disk.withFile("empty.txt", "")

            assertEquals("0123456789", harness.vfs.read(uri("/ten.txt")).toString(Charsets.UTF_8), "恰好等于上限可以读")
            assertEquals("", harness.vfs.read(uri("/empty.txt"), ReadOptions(0)).toString(Charsets.UTF_8), "0 只接受空文件")

            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, assertFailsWith<VfsException> { harness.vfs.read(uri("/eleven.txt")) }.code)
            assertEquals(
                VfsErrorCode.LIMIT_EXCEEDED,
                assertFailsWith<VfsException> { harness.vfs.read(uri("/ten.txt"), ReadOptions(5)) }.code,
                "单次上限更小时按小的来",
            )
            assertEquals(
                VfsErrorCode.LIMIT_EXCEEDED,
                assertFailsWith<VfsException> { harness.vfs.read(uri("/eleven.txt"), ReadOptions(100)) }.code,
                "单次上限不能放宽配置上限：null 不是无限",
            )
        }

    @Test
    fun `A01 a file hidden by a configured directory is not readable through that path`() =
        runBlocking {
            // 父盘上的 a 是文件，却在其下挂了 /resources/a/b：那条逻辑路径被配置目录遮蔽。
            val parent = StorageFakeImpl()
            parent.withFile("a", "shadowed content")
            val child = StorageFakeImpl()
            val harness =
                harness(
                    listOf(
                        VfsHarness.Mounted("/resources", parent, key = "parent"),
                        VfsHarness.Mounted("/resources/a/b", child, key = "child"),
                    ),
                )

            val failure = assertFailsWith<VfsException> { harness.vfs.read(uri("/a")) }

            assertEquals(VfsErrorCode.TYPE_MISMATCH, failure.code, "配置目录优先，读不出那个物理文件")
            assertTrue(parent.calls.none { it.startsWith("read:") }, "根本不该去读父盘那个文件")
        }

    @Test
    fun `A01 getNode of an unknown id is NOT_FOUND`() =
        runBlocking {
            val harness = harness()

            val failure = assertFailsWith<VfsException> { harness.vfs.getNode(NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000ff")) }

            assertEquals(VfsErrorCode.NOT_FOUND, failure.code)
        }

    // __READ_TEST_APPEND__
}
