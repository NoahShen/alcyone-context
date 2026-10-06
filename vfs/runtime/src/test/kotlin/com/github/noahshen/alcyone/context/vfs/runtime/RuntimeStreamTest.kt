package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsStreamResult
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith

/**
 * T18 A05：公共流式读取入口。真的 SQLite + 真的本机目录，走 `AlcyoneVfs` 公开方法。
 *
 * 这里防的是：分块读是不是真的分块（不是偷偷整份进内存）、限额口径是不是「配置与调用里较小的那个」、
 * 读流会不会顺手登记 Node 或发事件、流的关闭与交接有没有漏。
 */
class RuntimeStreamTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("docs"))
    }

    private fun config(streamTotalLimit: Long? = null) =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk)),
            streamTotalLimit = streamTotalLimit,
        )

    private fun uri(name: String) = VfsUri.parse("alcyone://resources/$name")

    /** 直接往磁盘上放文件：绕开 VFS 的 16 MiB 写限额，才能造出「大文件」。 */
    private fun putOnDisk(
        name: String,
        sizeBytes: Int,
    ) {
        Files.write(disk.resolve(name), ByteArray(sizeBytes) { (it % 251).toByte() })
    }

    /** 一次分块读的结果：读了多少字节、读了几次、有没有报错。 */
    private data class Drain(
        val bytes: Long,
        val reads: Int,
        val failure: Throwable?,
    )

    /**
     * 分块读，数出总共读到多少字节；读到一半报错时把已经拿到的字节也带出来。
     *
     * 限额用例靠 `bytes` 断言“正好停在上限”：超出的那个字节不交出去，所以读到的一定等于上限。
     */
    private fun drainCatching(stream: InputStream): Drain {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var reads = 0
        return try {
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                reads++
            }
            Drain(total, reads, null)
        } catch (failure: Throwable) {
            Drain(total, reads, failure)
        }
    }

    /** 把「读到一半报错」的结果翻译成 VFS 错误码，顺手断言字节数正好停在上限上。 */
    private fun limitFailure(
        drain: Drain,
        expectedBytes: Long,
    ): VfsException {
        assertNotNull(drain.failure, "应该正好在上限处报错")
        assertEquals(expectedBytes, drain.bytes, "读到的字节数正好停在上限，超出的那一个字节不交出去")
        assertTrue(drain.failure is VfsException, "越界要报 VFS 错误，不是底层异常：${drain.failure}")
        return drain.failure as VfsException
    }

    /** create → 用 → close 的三步。`close()` 是挂起函数，所以用 `try/finally` 而不是 `use`。 */
    private suspend fun <T> withVfs(
        config: VfsRuntimeConfig,
        block: suspend (AlcyoneVfs) -> T,
    ): T {
        val vfs = AlcyoneVfs.create(config)
        return try {
            block(vfs)
        } finally {
            vfs.close()
        }
    }

    private fun nodeCount(): Int =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM node WHERE deleted_at IS NULL").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /** 超过 16 MiB 的文件：`read` 按默认限额拒绝，流式读取却能一块一块读完。 */
    @Test
    @Timeout(60)
    fun `a file larger than the byte array default can be streamed in chunks`() =
        runBlocking {
            prepare()
            val size = 17 * 1024 * 1024
            putOnDisk("big.bin", size)

            withVfs(config()) { vfs ->
                val refused = assertFailsWith<VfsException> { vfs.read(uri("big.bin")) }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, refused.code, "默认 16 MiB 限额对 read 仍然生效")

                vfs.openStream(uri("big.bin")).use { result ->
                    assertEquals(size.toLong(), result.sizeBytes, "结果带上报给调用方的文件长度")
                    val drain = drainCatching(result.stream)
                    assertNull(drain.failure, "没有上限时读到底不报错")
                    assertEquals(size.toLong(), drain.bytes, "逐块读完，总字节数一样")
                    assertTrue(drain.reads > 1, "必须是分块读的，不能一口气交出整份：${drain.reads} 次")
                }
            }
        }

    /** 配置了总量上限之后，超过上限就在读到的那一次报 LIMIT_EXCEEDED。 */
    @Test
    @Timeout(60)
    fun `a stream over its total limit fails with LIMIT_EXCEEDED`() =
        runBlocking {
            prepare()
            putOnDisk("two.bin", 2 * 1024 * 1024)
            val limit = 1024L * 1024

            withVfs(config(streamTotalLimit = limit)) { vfs ->
                vfs.openStream(uri("two.bin")).use { result ->
                    val failure = limitFailure(drainCatching(result.stream), limit)
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "超过总量上限就停")

                    val again = assertFailsWith<VfsException> { result.stream.read() }
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, again.code, "超限之后再读还是超限，不会悄悄继续")
                }
            }
        }

    /** 调用级上限只能收紧：更宽的调用值不会把配置上限顶开。 */
    @Test
    @Timeout(60)
    fun `a call level limit can only tighten the configured one`() =
        runBlocking {
            prepare()
            // 文件要比配置上限还大：只有这样才能证明「调用级更宽时还是配置上限拦下来」。
            putOnDisk("three.bin", 5 * 1024 * 1024)
            val configured = 4L * 1024 * 1024

            withVfs(config(streamTotalLimit = configured)) { vfs ->
                vfs.openStream(uri("three.bin"), VfsStreamOptions(maxTotalBytes = 2 * 1024 * 1024)).use { result ->
                    val failure = limitFailure(drainCatching(result.stream), 2 * 1024 * 1024)
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "调用级更紧，以它为准")
                }

                vfs.openStream(uri("three.bin"), VfsStreamOptions(maxTotalBytes = 8 * 1024 * 1024)).use { result ->
                    val failure = limitFailure(drainCatching(result.stream), configured)
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "调用级更宽也还是配置上限说了算")
                }
            }
        }

    /** 目录和根本不存在的路径都开不出流：目录报类型错，路径不存在报 NOT_FOUND。 */
    @Test
    @Timeout(60)
    fun `opening a stream on a directory or a missing path fails`() =
        runBlocking {
            prepare()
            Files.createDirectory(disk.resolve("sub"))

            withVfs(config()) { vfs ->
                val directory = assertFailsWith<VfsException> { vfs.openStream(uri("sub")) }
                assertEquals(VfsErrorCode.TYPE_MISMATCH, directory.code, "盘上的真目录不能开流")

                val mountRoot = assertFailsWith<VfsException> { vfs.openStream(VfsUri.parse("alcyone://resources")) }
                assertEquals(VfsErrorCode.TYPE_MISMATCH, mountRoot.code, "挂载点是配置目录")

                val missing = assertFailsWith<VfsException> { vfs.openStream(uri("nope.bin")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "路径不存在")
            }
        }

    /** 读流只读内容：不登记 Node，也不发事件（先用一次 write 把事件通路证明是活的）。 */
    @Test
    @Timeout(60)
    fun `opening a stream does not register a node or emit an event`() =
        runBlocking {
            prepare()
            putOnDisk("plain.bin", 4096)
            val received = CopyOnWriteArrayList<VfsEvent>()

            withVfs(config()) { vfs ->
                vfs.subscribe { received.add(it) }
                vfs.write(uri("seed.txt"), "seed".toByteArray())
                withTimeout(30_000) { while (received.isEmpty()) delay(10) }
                val nodesAfterWrite = nodeCount()

                vfs.openStream(uri("plain.bin")).use { result ->
                    val drain = drainCatching(result.stream)
                    assertNull(drain.failure, "读完不该报错")
                    assertEquals(4096L, drain.bytes)
                }
                delay(300) // 给通知队列一点时间：真有事件的话早就到了

                assertEquals(1, received.size, "只收到 write 那一条，读流不发事件")
                assertEquals(nodesAfterWrite, nodeCount(), "读流不新增 Node 记录")
            }
        }

    /** 重复 close 不报错（第二次是空操作）；关闭之后再读必须失败，不给读到旧数据的口子。 */
    @Test
    @Timeout(60)
    fun `closing the stream twice is harmless and reading after close fails`() =
        runBlocking {
            prepare()
            putOnDisk("a.bin", 2048)

            withVfs(config()) { vfs ->
                val result = vfs.openStream(uri("a.bin"))
                result.close()
                result.close() // 第二次是空操作：不报错，也不重复关底层句柄
                assertTrue(result.isClosed)

                val thrown = assertFailsWith<Exception> { result.stream.read() }
                assertTrue(
                    thrown is IOException || thrown is VfsException,
                    "关闭之后读取必须失败（CLOSED 或 IOException），实际是 $thrown",
                )
            }
        }

    /** 取消恰好落在「流已建好、还没交给调用方」这一刻：流要当场关掉，不能变成没人管的句柄。 */
    @Test
    @Timeout(60)
    fun `cancelling during stream handover releases the stream`() =
        runBlocking {
            prepare()
            putOnDisk("handover.bin", 2048)
            val captured = CompletableDeferred<VfsStreamResult>()

            val vfs = AlcyoneVfs.create(config())
            try {
                // 钩子在这里挂住：流已经建好、还没返回给调用方。
                vfs.streamHandover = { stream ->
                    captured.complete(stream)
                    CompletableDeferred<Unit>().await() // 一直挂着，等取消
                }
                val job = async(start = CoroutineStart.UNDISPATCHED) { vfs.openStream(uri("handover.bin")) }

                val stream = withTimeout(30_000) { captured.await() }
                job.cancel(CancellationException("cancel injected by the test"))
                withTimeout(30_000) { job.join() }

                assertTrue(stream.isClosed, "取消之后这条流必须已经放掉")
                assertEquals(0, vfs.trackedStreamCount, "也不该留在 Runtime 的在途列表里")
            } finally {
                vfs.close()
            }
        }

    /** 调用方忘了关流：`close()` 负责把它收回来，不留悬着的文件句柄。 */
    @Test
    @Timeout(60)
    fun `close reclaims a stream the caller forgot to close`() =
        runBlocking {
            prepare()
            putOnDisk("forgotten.bin", 2048)

            val vfs = AlcyoneVfs.create(config())
            val result = vfs.openStream(uri("forgotten.bin"))
            assertEquals(1, vfs.trackedStreamCount, "交出去的流先记在 Runtime 这里")

            vfs.close()

            assertTrue(result.isClosed, "Runtime 替调用方把流关了")
            val thrown = assertFailsWith<Exception> { result.stream.read() }
            assertTrue(thrown is IOException || thrown is VfsException, "收走之后读取必须失败，实际是 $thrown")

            // 锁也放掉了：同一个状态库还能再开一个实例。
            withVfs(config()) { reopened ->
                assertEquals(
                    "/resources",
                    reopened
                        .stat(VfsUri.parse("alcyone://resources"))
                        .uri.path
                        .toString(),
                )
            }
        }

    /** 调用方自己关掉的流要从在途列表里摘掉，否则长跑的实例会越攒越多没人要的流。 */
    @Test
    @Timeout(60)
    fun `the runtime stops tracking a stream once the caller closes it`() =
        runBlocking {
            prepare()
            putOnDisk("tracked.bin", 2048)

            withVfs(config()) { vfs ->
                val result = vfs.openStream(uri("tracked.bin"))
                assertEquals(1, vfs.trackedStreamCount)

                result.close()
                assertEquals(0, vfs.trackedStreamCount, "调用方关掉的流不再占着在途列表")

                // 连开带关好几轮，计数要一直是 0，不能越攒越多。
                repeat(5) {
                    vfs.openStream(uri("tracked.bin")).close()
                }
                assertEquals(0, vfs.trackedStreamCount, "反复开关也不会累积")
            }
        }
}
