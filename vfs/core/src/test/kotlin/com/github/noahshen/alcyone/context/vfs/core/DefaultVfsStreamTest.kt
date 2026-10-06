package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
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
 * T18 S3：`openStream` 的路由与限额口径（核心逻辑，替身盘）。
 *
 * 只钉住 Core 这一层该保证的东西：路由错误码、effective 限额是「配置与调用里较小的那个」、
 * 读流不登记 Node 也不发事件。真实的分块读、超限中断、关闭与取消交接口径在 Runtime 层用真 Local FS 验。
 */
class DefaultVfsStreamTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    private fun harness(
        disk: StorageFakeImpl,
        limits: VfsLimits = VfsLimits(),
        streamTotalLimit: Long? = null,
    ) = VfsHarness(
        listOf(VfsHarness.Mounted("/resources", disk)),
        limits = limits,
        streamTotalLimit = streamTotalLimit,
        notifier = notifiers.create(),
    )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    @Test
    fun `A05 a stream reads an unregistered file in chunks and registers nothing`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(disk)
            disk.withFile("a.txt", "hello stream")
            disk.calls.clear() // 造文件的 write 不算数

            val result = harness.vfs.openStream(uri("/a.txt"))
            val content = result.stream.readBytes().toString(Charsets.UTF_8)
            result.close()

            assertEquals("hello stream", content)
            assertEquals(12L, result.sizeBytes, "结果带上后端报的文件长度")
            assertTrue(result.isClosed)
            assertNull(harness.nodes.findByPath(VfsPath.parse("/resources/a.txt")), "读流不登记 Node")
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty(), "读流不发事件")
            assertEquals(listOf("readStream:a.txt"), disk.calls, "只问后端一次")
        }

    @Test
    fun `A05 directories and missing paths are refused before any stream exists`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(disk)
            disk.makeDirectory("dir")

            val physical = assertFailsWith<VfsException> { harness.vfs.openStream(uri("/dir")) }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, physical.code, "盘上的目录不能开流")

            val namespaceRoot = assertFailsWith<VfsException> { harness.vfs.openStream(uri("")) }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, namespaceRoot.code, "命名空间根是配置目录")

            val missing = assertFailsWith<VfsException> { harness.vfs.openStream(uri("/nope.txt")) }
            assertEquals(VfsErrorCode.NOT_FOUND, missing.code)

            val unmounted = assertFailsWith<VfsException> { harness.vfs.openStream(VfsUri.parse("alcyone://elsewhere/a.txt")) }
            assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, unmounted.code)
        }

    @Test
    fun `A05 the stream limit is the configured one and a per call limit may only tighten it`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.withFile("ten.txt", "0123456789")

            val configured = harness(disk, streamTotalLimit = 5)
            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, assertFailsWith<VfsException> { configured.vfs.openStream(uri("/ten.txt")) }.code)
            assertEquals(
                VfsErrorCode.LIMIT_EXCEEDED,
                assertFailsWith<VfsException> {
                    configured.vfs.openStream(uri("/ten.txt"), VfsStreamOptions(maxTotalBytes = 20))
                }.code,
                "调用级上限更宽也不放宽配置上限",
            )

            val loose = harness(disk, streamTotalLimit = 20)
            assertEquals(
                VfsErrorCode.LIMIT_EXCEEDED,
                assertFailsWith<VfsException> {
                    loose.vfs.openStream(uri("/ten.txt"), VfsStreamOptions(maxTotalBytes = 5))
                }.code,
                "调用级上限更紧时以它为准",
            )

            loose.vfs.openStream(uri("/ten.txt"), VfsStreamOptions(maxTotalBytes = 100)).use { result ->
                assertEquals(10, result.stream.readBytes().size, "两边都够大就整份读完")
            }
        }

    @Test
    fun `A05 the stream limit is independent from the byte array read limit`() =
        runBlocking {
            val disk = StorageFakeImpl()
            // ByteArray 读只允许 1 字节，流式读取却不设总量上限。
            val harness = harness(disk, limits = VfsLimits(defaultReadMaxBytes = 1))
            disk.withFile("ten.txt", "0123456789")

            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, assertFailsWith<VfsException> { harness.vfs.read(uri("/ten.txt")) }.code)
            harness.vfs.openStream(uri("/ten.txt")).use { result ->
                assertEquals("0123456789", result.stream.readBytes().toString(Charsets.UTF_8), "流式读取不受 ByteArray 限额影响")
            }
        }

    /**
     * R8 场景 1：openStream() 自身失败时，清理抛出的异常不顶掉原异常，而是挂在 suppressed 上。
     */
    @Test
    fun `open failure keeps the original exception and attaches close failure as suppressed`() =
        runBlocking {
            val disk =
                StorageFakeImpl(
                    openStreamThrows = true,
                    closeThrows = true,
                )
            val harness = harness(disk)
            disk.withFile("a.txt", "content")

            val thrown =
                assertFailsWith<IllegalStateException> {
                    harness.vfs.openStream(uri("/a.txt"))
                }

            assertEquals("open failed", thrown.message, "主异常是 openStream 的原异常")
            val suppressed = thrown.suppressed.filterIsInstance<IllegalStateException>()
            assertEquals(1, suppressed.size, "close 抛出的异常挂在 suppressed 里")
            assertEquals("close failed", suppressed.single().message)
        }

    /**
     * R8 场景 2：流包装器 close 时，内容流先报错、后端存储流后报错，主异常是内容流的，后端流异常挂在 suppressed。
     */
    @Test
    fun `both closes fail keeps stream close as main and attaches storage close as suppressed`() =
        runBlocking {
            val disk =
                StorageFakeImpl(
                    openStreamCloseThrows = true,
                    closeThrows = true,
                )
            val harness = harness(disk)
            disk.withFile("a.txt", "content")

            val result = harness.vfs.openStream(uri("/a.txt"))

            val thrown =
                assertFailsWith<IllegalStateException> {
                    result.close()
                }

            assertEquals("stream close failed", thrown.message, "主异常保留最先发生的 stream close 异常")
            val suppressed = thrown.suppressed.filterIsInstance<IllegalStateException>()
            assertEquals(1, suppressed.size, "后发生的 storage close 异常挂在 suppressed 里")
            assertEquals("close failed", suppressed.single().message)
        }
}
