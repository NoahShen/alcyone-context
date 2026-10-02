package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsEffect
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
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * A05：限额按**实际读取**生效，流生命周期明确（T12 §2.4）。
 *
 * 后端不支持 range read，所以有界读取只能靠包装流计数；`BoundedInputStreamTest` 直接对这个机制做白盒验证。
 */
class LocalFsLimitTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path

    private suspend fun openStorage(options: LocalFsOptions = LocalFsOptions()) = LocalFsStorage.create(root, options)

    @Test
    fun `the default write limit is 16 MiB`() {
        assertEquals(16L * 1024 * 1024, LocalFsOptions.DEFAULT_WRITE_LIMIT_BYTES)
        assertEquals(LocalFsOptions.DEFAULT_WRITE_LIMIT_BYTES, LocalFsOptions().defaultWriteLimitBytes)
    }

    @Test
    fun `an empty file reads as empty bytes at a zero limit`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("empty.bin"), ByteArray(0), StorageWriteMode.UPSERT)

                val content = storage.read(StoragePath.parse("empty.bin"), 0)

                assertEquals(0, content.bytes.size)
                assertEquals(0L, content.attributes.sizeBytes)
            }
        }

    @Test
    fun `a read below and exactly at the limit succeeds`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.bin"), ByteArray(8) { it.toByte() }, StorageWriteMode.UPSERT)

                assertEquals(8, storage.read(StoragePath.parse("a.bin"), 16).bytes.size)
                assertEquals(8, storage.read(StoragePath.parse("a.bin"), 8).bytes.size)
            }
        }

    @Test
    fun `one byte over the read limit fails with LIMIT_EXCEEDED`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.bin"), ByteArray(9) { it.toByte() }, StorageWriteMode.UPSERT)

                val failure = assertFailsWith<VfsException> { storage.read(StoragePath.parse("a.bin"), 8) }

                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)
                assertEquals(VfsEffect.NONE, failure.effect, "读取失败没有任何副作用")
            }
        }

    @Test
    fun `a stream with a null limit reads a file that is larger than the write limit`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
            Files.write(root.resolve("big.bin"), payload) // 直接落在宿主磁盘上，绕开写入限额
            openStorage(LocalFsOptions(defaultWriteLimitBytes = 1024)).use { storage ->
                storage.readStream(StoragePath.parse("big.bin")).use { stream ->
                    val read = stream.openStream().use { it.readAllBytes() }

                    assertTrue(read.contentEquals(payload), "无上限流必须能读完整文件，而不是先转成受限 ByteArray")
                }
            }
        }

    @Test
    fun `a stream limit applies to the bytes actually read`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.bin"), ByteArray(20) { it.toByte() }, StorageWriteMode.UPSERT)

                storage.readStream(StoragePath.parse("a.bin"), 20).use { stream ->
                    assertEquals(20, stream.openStream().use { it.readAllBytes() }.size)
                }

                val failure =
                    assertFailsWith<VfsException> {
                        storage.readStream(StoragePath.parse("a.bin"), 19).use { bounded ->
                            bounded.openStream().use { it.readAllBytes() }
                        }
                    }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)
            }
        }

    @Test
    fun `skip cannot be used to bypass the limit`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.bin"), ByteArray(100) { it.toByte() }, StorageWriteMode.UPSERT)

                val failure =
                    assertFailsWith<VfsException> {
                        storage.readStream(StoragePath.parse("a.bin"), 10).use { stream ->
                            stream.openStream().use { it.skip(50) }
                        }
                    }

                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "跳过的字节同样消耗额度")
            }
        }

    @Test
    fun `available never promises more bytes than the remaining allowance`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.bin"), ByteArray(100) { it.toByte() }, StorageWriteMode.UPSERT)

                storage.readStream(StoragePath.parse("a.bin"), 10).use { stream ->
                    stream.openStream().use { input ->
                        assertTrue(input.available() <= 10)
                        input.readNBytes(4)
                        assertTrue(input.available() <= 6)
                    }
                }
            }
        }

    @Test
    fun `a stream is single-shot and refuses to be reused after close`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.bin"), "0123456789".toByteArray(), StorageWriteMode.UPSERT)

                val stream = storage.readStream(StoragePath.parse("a.bin"))
                val first = stream.openStream()
                assertEquals("0", first.read().toChar().toString())
                assertEquals(
                    VfsErrorCode.STATE_ERROR,
                    assertFailsWith<VfsException> { stream.openStream() }.code,
                    "同一个流只能打开一次；需要再读请再次调用 readStream",
                )
                first.close()
                stream.close()
                stream.close() // 幂等
                assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { stream.openStream() }.code)
            }
        }

    @Test
    fun `closing a stream that was never opened still releases the native reader`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.bin"), "abc".toByteArray(), StorageWriteMode.UPSERT)

                storage.readStream(StoragePath.parse("a.bin")).use { stream ->
                    assertEquals(3L, stream.attributes.sizeBytes)
                }

                // 未打开就关闭必须释放底层读取器；再读一个新流即可验证 Operator 仍然可用。
                assertEquals("abc", String(storage.read(StoragePath.parse("a.bin"), 8).bytes))
            }
        }

    @Test
    fun `an oversized write is refused before any side effect`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("existing.txt"), "old")
            openStorage(LocalFsOptions(defaultWriteLimitBytes = 4)).use { storage ->
                val failure =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("new.txt"), ByteArray(5), StorageWriteMode.UPSERT)
                    }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)

                val replaceFailure =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("existing.txt"), ByteArray(5), StorageWriteMode.REPLACE_EXISTING)
                    }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, replaceFailure.code)

                assertFalse(Files.exists(root.resolve("new.txt")), "超限不能先写再报错")
                assertEquals("old", Files.readString(root.resolve("existing.txt")), "超限不能截断原文件")
            }
        }

    @Test
    fun `a write exactly at the configured limit is allowed`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage(LocalFsOptions(defaultWriteLimitBytes = 4)).use { storage ->
                storage.write(StoragePath.parse("a.bin"), ByteArray(4) { 1 }, StorageWriteMode.CREATE_NEW)

                assertEquals(4L, storage.stat(StoragePath.parse("a.bin")).sizeBytes)
            }
        }

    @Test
    fun `a negative write limit is rejected at construction`() {
        val failure = assertFailsWith<VfsException> { LocalFsOptions(defaultWriteLimitBytes = -1) }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }
}

/**
 * 白盒验证限额机制本身：这里既没有文件也没有 `stat`，所以超限判断不可能来自元数据。
 */
class BoundedInputStreamTest {
    @Test
    fun `the limit is charged on actual reads, not on any known length`() {
        val source = ByteArrayInputStream(ByteArray(50) { it.toByte() })

        val failure =
            assertFailsWith<VfsException> {
                BoundedInputStream(source, limitBytes = 8).readAllBytes()
            }

        assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)
    }

    @Test
    fun `a source that lies about its size still cannot exceed the limit`() {
        // available() 报告 1 MiB，实际只有 10 字节：只信 available 就会把限额放过去。
        val source =
            object : InputStream() {
                private val delegate = ByteArrayInputStream(ByteArray(10) { it.toByte() })

                override fun read(): Int = delegate.read()

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = delegate.read(buffer, offset, length)

                override fun available(): Int = 1024 * 1024
            }

        val bounded = BoundedInputStream(source, limitBytes = 4)

        assertEquals(4, bounded.available())
        assertFailsWith<VfsException> { bounded.readAllBytes() }
    }

    @Test
    fun `counted bytes include skipped ones`() {
        val bounded = BoundedInputStream(ByteArrayInputStream(ByteArray(20) { 1 }), limitBytes = 5)

        bounded.skip(4)

        assertEquals(4L, bounded.consumedBytes())
        assertEquals(1, bounded.read())
        assertFailsWith<VfsException> { bounded.read() }
    }

    @Test
    fun `mark and reset are refused because they would break accounting`() {
        val bounded = BoundedInputStream(ByteArrayInputStream(ByteArray(10)), limitBytes = 10)

        assertFalse(bounded.markSupported())
        assertFailsWith<java.io.IOException> { bounded.reset() }
    }
}
