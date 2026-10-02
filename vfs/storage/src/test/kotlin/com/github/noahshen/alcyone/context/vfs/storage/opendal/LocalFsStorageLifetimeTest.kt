package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith

/**
 * A01 补充：把「检查」与「释放」互斥化的行为证据（T12 R1）。
 *
 * 竞争用**单线程 + 显式闩锁**编排，不靠压力重试碰运气：一个线程顺序地做「开始读 → 放行 close →
 * 让读收尾」，close 必须被读锁挡住而不是把句柄从读操作脚下抽走。
 */
class LocalFsStorageLifetimeTest {
    @TempDir
    lateinit var tempDir: Path

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
            // 先关流：此时 Operator 已释放，包装流直接把关闭报告为 CLOSED，仍然幂等、不碰 native。
            assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { opened.close() }.code)
            opened.close()
            opened.close() // 幂等
            stream.close()
            stream.close() // 幂等

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
        val bounded = StorageInputStream(delegate, limitBytes = null)

        assertEquals('a'.code, bounded.read())
        bounded.close()
        val readsAtClose = reads
        assertFailsWith<VfsException> { bounded.read() }

        assertEquals(readsAtClose, reads, "关闭后不得再读底层流")
    }
}
