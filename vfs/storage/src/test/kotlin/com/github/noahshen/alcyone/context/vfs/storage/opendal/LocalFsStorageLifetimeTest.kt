package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A01 补充：把「检查」与「释放」互斥化的行为证据（T12 R1）。
 *
 * 竞争用**单线程 + 显式闩锁**编排，不靠压力重试碰运气：一个线程顺序地做「开始读 → 放行 close →
 * 让读收尾」，close 必须被读锁挡住而不是把句柄从读操作脚下抽走。
 *
 * [Timeout]：这类用例靠放行信号推进，哪一步等不到就会挂死整个套件；超时把它变成一次失败。
 */
@Timeout(60)
class LocalFsStorageLifetimeTest {
    @TempDir
    lateinit var tempDir: Path

    /** 记录关闭次数的替身 reader：释放证据直接看它，不碰 native。 */
    private class RecordingReader(
        payload: ByteArray,
    ) : InputStream() {
        private val source = ByteArrayInputStream(payload)

        var closeCalls = 0
            private set

        override fun read(): Int = source.read()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = source.read(buffer, offset, length)

        override fun close() {
            closeCalls++
        }
    }

    /**
     * 可控时序的替身：native 读会阻塞在 [allowReadExit] 上。
     *
     * [closedWhileReading] 由替身自己记录，所以「关是否插进读中间」不依赖主线程何时去看。
     */
    private class BlockingReader : InputStream() {
        val readEntered = CountDownLatch(1)
        val allowReadExit = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)

        @Volatile
        var closedWhileReading = false
        var closeCalls = 0
            private set

        @Volatile
        private var reading = false

        override fun read(): Int {
            reading = true
            readEntered.countDown()
            allowReadExit.await(5, TimeUnit.SECONDS)
            reading = false
            return 'a'.code
        }

        override fun close() {
            if (reading) closedWhileReading = true
            closeCalls++
        }
    }

    @Test
    fun `close waits for an in-flight native operation instead of releasing under it`() {
        val inOperation = CountDownLatch(1)
        val operationDone = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val lifetime = NativeLifetime { released.set(true) }

        val worker =
            Thread {
                lifetime.call {
                    inOperation.countDown()
                    // 读锁仍被持有：另一个线程上的 close 取不到写锁。
                    Thread.sleep(300)
                }
                operationDone.countDown()
            }
        worker.start()
        assertTrue(inOperation.await(5, TimeUnit.SECONDS), "读操作没有进入 lifetime.call")

        val closer = Thread { lifetime.close() }
        closer.start()
        Thread.sleep(100) // 给 close 足够时间尝试抢写锁
        assertTrue(worker.isAlive, "close 必须在进行中的操作收尾之后才释放")
        assertFalse(released.get(), "操作还在进行中就释放，正是会崩 JVM 的那个窗口")

        assertTrue(operationDone.await(5, TimeUnit.SECONDS), "读操作没有收尾")
        closer.join(5_000)
        worker.join(5_000)
        assertTrue(released.get(), "读操作收尾后必须完成释放")
    }

    @Test
    fun `a call that starts after close is refused instead of reaching the native handle`() {
        val lifetime = NativeLifetime { }
        lifetime.close()

        val failure = assertFailsWith<VfsException> { lifetime.call { error("不该进到这里") } }

        assertEquals(VfsErrorCode.CLOSED, failure.code)
    }

    @Test
    fun `close is idempotent and releases the native handle exactly once`() {
        var releases = 0
        val lifetime = NativeLifetime { releases++ }

        lifetime.close()
        lifetime.close()

        assertEquals(1, releases)
        assertTrue(lifetime.isClosed())
    }

    /** 三种关闭顺序都必须幂等，而且不能让 Adapter 之后的使用崩在 native 上。 */
    @Test
    fun `every close order for a stream and its adapter is idempotent and leaves the operator usable`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "0123456789")

            // 顺序一：先关流、再关 Adapter。
            LocalFsStorage.create(root).use { storage ->
                storage.readStream(StoragePath.parse("a.txt")).use { stream ->
                    assertEquals('0'.code, stream.openStream().use { it.read() })
                }
            }
            assertEquals("0123456789", Files.readString(root.resolve("a.txt")))

            // 顺序二：Adapter 先关、再关流。
            val storage = LocalFsStorage.create(root)
            val stream = storage.readStream(StoragePath.parse("a.txt"))
            val opened = stream.openStream()
            storage.close()
            // 流的后续读取必须变成 VfsException，而不是碰到已释放的 native 句柄。
            assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { opened.read() }.code)
            // Adapter 关闭时已在写锁内排空 reader，所以这里再关是**幂等空操作，不报错也不泄漏**。
            opened.close()
            opened.close()
            stream.close()
            stream.close()

            // 顺序三：流根本没打开就关 Adapter。
            LocalFsStorage.create(root).use { fresh ->
                fresh.readStream(StoragePath.parse("a.txt")).use { neverOpened ->
                    neverOpened.close()
                    neverOpened.close()
                }
                // Operator 仍然可用，说明没有把句柄泄漏成「永远关不掉」。
                assertEquals("0123456789", String(fresh.read(StoragePath.parse("a.txt"), 16).bytes))
            }
        }

    /** 已关闭实例上调用挂载根本身也必须报 CLOSED：不能因为「根没有可新建的目录」就绕过生命周期检查。 */
    @Test
    fun `createDirectory of the mount root still fails with CLOSED after close`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            val storage = LocalFsStorage.create(root)
            storage.close()

            val failure = assertFailsWith<VfsException> { storage.createDirectory(StoragePath.root) }

            assertEquals(VfsErrorCode.CLOSED, failure.code)
        }

    /** 有界与无界两条路径行为一致：限额只影响「能读多少」，不影响关闭与状态机。 */
    @Test
    fun `bounded and unbounded streams share the same close and state behaviour`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "0123456789")
            LocalFsStorage.create(root).use { storage ->
                val bounded = storage.readStream(StoragePath.parse("a.txt"), maxBytes = 4).openStream()
                val unbounded = storage.readStream(StoragePath.parse("a.txt")).openStream()

                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, assertFailsWith<VfsException> { bounded.readAllBytes() }.code)
                assertEquals("0123456789", String(unbounded.readAllBytes()))

                bounded.close()
                unbounded.close()
                bounded.close()
                unbounded.close()

                listOf(bounded, unbounded).forEach { closed ->
                    assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { closed.read() }.code)
                    assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { closed.available() }.code)
                }
            }
        }

    /**
     * 真实 Adapter 上 `read` 与 `readStream` 的符号链接闸门一致。
     * `read` 曾经漏掉闸门（只 stat 不 resolve），会直接读到链接目标。
     */
    @Test
    fun `a symlinked target is refused by both read and readStream`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            val outside = Files.createDirectory(tempDir.resolve("outside"))
            Files.writeString(outside.resolve("secret.txt"), "SECRET")
            Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"))
            LocalFsStorage.create(root).use { storage ->
                assertEquals(
                    VfsErrorCode.STORAGE_ACCESS_DENIED,
                    assertFailsWith<VfsException> { storage.read(StoragePath.parse("link.txt"), 64) }.code,
                )
                assertEquals(
                    VfsErrorCode.STORAGE_ACCESS_DENIED,
                    assertFailsWith<VfsException> { storage.readStream(StoragePath.parse("link.txt")) }.code,
                )
                assertEquals("SECRET", Files.readString(outside.resolve("secret.txt")))
            }
        }

    /** 流在超限之后必须仍然能被关掉，否则 native 句柄会泄漏。 */
    @Test
    fun `a stream that exceeded its limit can still be closed and the operator stays usable`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            LocalFsStorage.create(root).use { storage ->
                storage.write(StoragePath.parse("a.bin"), ByteArray(50) { it.toByte() }, StorageWriteMode.UPSERT)

                val stream = storage.readStream(StoragePath.parse("a.bin"), maxBytes = 4)
                val input = stream.openStream()
                assertFailsWith<VfsException> { input.readAllBytes() }
                input.close()
                input.close()
                stream.close()

                assertEquals(50L, storage.stat(StoragePath.parse("a.bin")).sizeBytes)
            }
        }

    /** 替身流只用于确认「包装流在关闭后不再触碰 delegate」，不参与任何 native 路径。 */
    @Test
    fun `closing the returned stream stops the delegate from being read again`() {
        var reads = 0
        val delegate =
            object : java.io.InputStream() {
                private val source = ByteArrayInputStream("abc".toByteArray())

                override fun read(): Int {
                    reads++
                    return source.read()
                }

                override fun close() = Unit
            }
        val bounded = StorageInputStream(ReaderHandle(delegate), limitBytes = null, lifetime = NativeLifetime { })

        assertEquals('a'.code, bounded.read())
        bounded.close()
        val readsAtClose = reads
        assertFailsWith<VfsException> { bounded.read() }

        assertEquals(readsAtClose, reads, "关闭后不得再读底层流")
    }

    /**
     * R1 核心证据（本轮修复的原缺陷）：reader 必须**真正释放且恰好一次**。
     *
     * 修复前 `StorageStream.close()` 把 `native` 置空后就不再持有它，于是关谁都不释放——
     * 实测 `readerCloseCalls = 0`，而且 `StorageStream.close()` 连错误都不报。
     * 断言用的是替身的关闭次数，不是「之后还能打开」那种对泄漏恒真的弱断言。
     */
    @Test
    fun `closing the StorageStream closes the stream it handed out`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "0123456789")
            val reader = RecordingReader("0123456789".toByteArray())
            val storage = LocalFsStorage.open(root, LocalFsOptions(), readerFactory = { _ -> { reader } })

            val stream = storage.readStream(StoragePath.parse("a.txt"))
            val opened = stream.openStream()
            assertEquals(0, reader.closeCalls, "打开不等于关闭")

            // 只关 StorageStream（openStream 已调用），不关 returned InputStream。
            stream.close()

            assertEquals(1, reader.closeCalls, "StorageStream.close 必须释放它交出去的流")
            storage.close()
            assertEquals(1, reader.closeCalls, "Adapter 关闭不能重复释放")
        }

    /** Adapter 先关 + 流已打开：写锁内排空释放，事后 close() 是幂等空操作。 */
    @Test
    fun `closing the adapter first releases an opened reader exactly once and later close is a no-op`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "0123456789")
            val reader = RecordingReader("0123456789".toByteArray())
            val storage = LocalFsStorage.open(root, LocalFsOptions(), readerFactory = { _ -> { reader } })

            val stream = storage.readStream(StoragePath.parse("a.txt"))
            val opened = stream.openStream()
            storage.close()

            assertEquals(1, reader.closeCalls, "Adapter 关闭时必须在写锁内排空 reader")
            assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { opened.read() }.code)

            opened.close()
            opened.close()
            stream.close()
            stream.close()
            assertEquals(1, reader.closeCalls, "事后关流是幂等空操作：不报错，也不重复释放")
        }

    /** Adapter 先关 + 流尚未打开：同样由排空释放。 */
    @Test
    fun `closing the adapter first releases a never opened reader exactly once`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "0123456789")
            val reader = RecordingReader("0123456789".toByteArray())
            val storage = LocalFsStorage.open(root, LocalFsOptions(), readerFactory = { _ -> { reader } })

            val stream = storage.readStream(StoragePath.parse("a.txt"))
            storage.close()

            assertEquals(1, reader.closeCalls, "未打开的 reader 也必须被排空释放")
            stream.close()
            assertEquals(1, reader.closeCalls)
        }

    /**
     * 同一 reader 上的「读」与「关」必须互斥。
     *
     * 双向闩锁编排，不用 `Thread.sleep` 判时序：
     * 1. 读线程进入 native 读并阻塞在 `allowReadExit`；
     * 2. 关线程**已经发起** `close()`（`closeAttempted` 放行）；
     * 3. 主线程才放行读返回。
     *
     * 没有闸门时，关线程会在第 2、3 步之间进入 `native.close()`，替身自己把
     * `closedWhileReading` 记为 true——这个标记由替身记录，不依赖主线程的观察时机。
     */
    @Test
    fun `a read in flight is never released by a concurrent close`() {
        val reader = BlockingReader()

        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "0123456789")
            LocalFsStorage.open(root, LocalFsOptions(), readerFactory = { _ -> { reader } }).use { storage ->
                val stream = storage.readStream(StoragePath.parse("a.txt"))
                val opened = stream.openStream()

                val readerThread = Thread { opened.read() }
                readerThread.start()
                assertTrue(reader.readEntered.await(5, TimeUnit.SECONDS), "读没有进入 native")

                val closerThread =
                    Thread {
                        reader.closeAttempted.countDown()
                        opened.close()
                    }
                closerThread.start()
                assertTrue(reader.closeAttempted.await(5, TimeUnit.SECONDS), "关没有发起")

                reader.allowReadExit.countDown()
                readerThread.join(5_000)
                closerThread.join(5_000)

                assertFalse(reader.closedWhileReading, "读取还没返回就释放了 reader")
                assertEquals(1, reader.closeCalls, "恰好释放一次")
                stream.close()
            }
        }
    }

    /** `readStream` 交接取消与 Adapter 关闭**重合**：释放结果确定，仍是恰好一次。 */
    @Test
    fun `a cancelled handoff overlapping an adapter close still releases the reader exactly once`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "0123456789")
            val reader = RecordingReader("0123456789".toByteArray())
            val inFactory = CountDownLatch(1)
            val allowFactory = CountDownLatch(1)
            val delivered = AtomicBoolean(false)

            val storage =
                LocalFsStorage.open(
                    root,
                    LocalFsOptions(),
                    readerFactory = { _ ->
                        { _ ->
                            // reader 已建好、IO 块还没跑完：此时 Adapter 关闭会等写锁。
                            inFactory.countDown()
                            allowFactory.await(5, TimeUnit.SECONDS)
                            reader
                        }
                    },
                )

            val job =
                CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                    storage.readStream(StoragePath.parse("a.txt"))
                    delivered.set(true)
                }
            assertTrue(inFactory.await(5, TimeUnit.SECONDS), "没有进入 reader 工厂")

            // 两个并发事件同时发生：取消协程 + 关闭 Adapter。
            val closer = Thread { storage.close() }
            closer.start()
            job.cancel()
            allowFactory.countDown()
            job.join()
            closer.join(5_000)

            assertFalse(delivered.get(), "取消后调用方不能拿到成功结果")
            assertEquals(1, reader.closeCalls, "交接取消与 Adapter 关闭重合时仍恰好释放一次")
        }
}

/**
 * 关停时一个文件流关不掉，其余资源仍然必须被清理（T12 R7）。
 *
 * 场景：调用方同时开着三个文件流，关 Adapter 时其中一个关失败。
 * 正确的行为是三个都试一次、Operator 也照关，然后把错误抛回去。
 */
@Timeout(60)
class LocalFsDrainFailureTest {
    @TempDir
    lateinit var tempDir: Path

    /** 纯 JVM 对象，不碰 native。`closeFailure` 非空时 `close()` 抛 [IOException]。 */
    private class FailingOnClose(
        private val label: String,
        private val closeFailure: IOException?,
    ) : InputStream() {
        private val source = ByteArrayInputStream(label.toByteArray())

        var closeCalls = 0
            private set

        override fun read(): Int = source.read()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = source.read(buffer, offset, length)

        override fun close() {
            closeCalls++
            closeFailure?.let { throw it }
        }
    }

    /** 按后端路径给出不同替身：同一个 Adapter 上的三个文件流，其中哪些关得掉由参数决定。 */
    private suspend fun openStorage(
        readers: Map<String, FailingOnClose>,
        onOperator: (org.apache.opendal.Operator) -> Unit = {},
    ) = LocalFsStorage.open(
        root,
        LocalFsOptions(),
        operatorFactory = { path ->
            org.apache.opendal.Operator
                .of(
                    org.apache.opendal.ServiceConfig
                        .Fs
                        .builder()
                        .root(path.toString())
                        .build(),
                ).also(onOperator)
        },
        readerFactory = { _ -> { path -> readers.getValue(path) } },
    )

    private lateinit var root: Path

    @Test
    fun `a reader that fails to close does not strand the others or the operator`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "a")
            Files.writeString(root.resolve("b.txt"), "b")
            Files.writeString(root.resolve("c.txt"), "c")

            val boom = IOException("b.txt cannot be closed")
            val readers =
                mapOf(
                    "a.txt" to FailingOnClose("a", null),
                    "b.txt" to FailingOnClose("b", boom),
                    "c.txt" to FailingOnClose("c", null),
                )
            var operator: org.apache.opendal.Operator? = null
            val storage = openStorage(readers) { operator = it }

            // 三个流都保持打开（不关），这样它们会一起进入关停时的排空。
            storage.readStream(StoragePath.parse("a.txt"))
            storage.readStream(StoragePath.parse("b.txt"))
            storage.readStream(StoragePath.parse("c.txt"))

            val failure = assertFailsWith<VfsException> { storage.close() }

            // 三个文件流都获得了一次关闭尝试：关不掉的那个也不能把另外两个跳过。
            readers.values.forEach { reader ->
                assertEquals(1, reader.closeCalls, "每个文件流都要试一次，不因前一个失败而跳过")
            }
            assertTrue(operator!!.isDisposed, "排空失败也不能跳过 Operator 的关闭")

            // 失败要如实报给调用方，并保留原始异常。
            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
            assertSame(boom, failure.cause, "原始 IOException 挂在 cause 上，不能被吞掉")

            // 重调 close 是幂等空操作：已处理过的资源不重复释放，也不重复抛。
            storage.close()
            readers.values.forEach { reader -> assertEquals(1, reader.closeCalls, "重调 close 不重复释放") }
        }

    /** 多个文件流都关失败时，第一个失败当主异常，其余挂在 suppressed 上，一个都不吞。 */
    @Test
    fun `when several readers fail the first failure stays primary and the rest are suppressed`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "a")
            Files.writeString(root.resolve("b.txt"), "b")

            val first = IOException("a.txt cannot be closed")
            val second = IOException("b.txt cannot be closed")
            val readers =
                mapOf(
                    "a.txt" to FailingOnClose("a", first),
                    "b.txt" to FailingOnClose("b", second),
                )
            var operator: org.apache.opendal.Operator? = null
            val storage = openStorage(readers) { operator = it }

            // 两个流都保持打开（不关），关停时会一起被排空。
            storage.readStream(StoragePath.parse("a.txt"))
            storage.readStream(StoragePath.parse("b.txt"))

            val failure = assertFailsWith<VfsException> { storage.close() }

            // 排空顺序不保证，所以只断言「两个失败都在、都报告了」，不指定哪个当主异常。
            val mainCause = failure.cause
            assertTrue(mainCause === first || mainCause === second, "主异常是两者之一，实际：$mainCause")
            val other = if (mainCause === first) second else first
            assertEquals(
                1,
                failure.suppressed.count { it.cause === other },
                "另一个失败挂在 suppressed 上，没有被丢弃或覆盖",
            )
            assertTrue(operator!!.isDisposed, "两个都失败时 Operator 仍然要关")
            readers.values.forEach { reader -> assertEquals(1, reader.closeCalls) }
        }
}
