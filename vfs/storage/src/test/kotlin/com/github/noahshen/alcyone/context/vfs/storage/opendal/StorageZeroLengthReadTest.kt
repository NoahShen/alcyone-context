package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** 读 0 个字节时不去问后端：后端会回 -1，那是它的内部判断，不该传出去。 */
class StorageZeroLengthReadTest {
    @TempDir
    lateinit var tempDir: Path

    /** 数着后端被读了几次，用来证明「零长度读一个字节都没动后端」。 */
    private class CountingReader(
        payload: ByteArray,
    ) : InputStream() {
        private val source = ByteArrayInputStream(payload)

        var readCalls = 0
            private set

        override fun read(): Int {
            readCalls++
            return source.read()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readCalls++
            return source.read(buffer, offset, length)
        }
    }

    private val lifetime = NativeLifetime { }

    @Test
    fun `a zero length read returns zero without touching the backend`() {
        val delegate = CountingReader("0123456789".toByteArray())
        val bounded = StorageInputStream(ReaderHandle(delegate), limitBytes = 10L, lifetime = lifetime)
        val buffer = ByteArray(8)

        assertEquals(0, bounded.read(buffer, 0, 0))
        assertEquals(0, bounded.read(ByteArray(8), 4, 0))

        assertEquals(0, delegate.readCalls, "零长度读不该去问后端")
        assertEquals(0L, bounded.consumedBytes(), "零字节不消耗额度")

        // 正长度读照常工作，不变量没被破坏。
        assertEquals(3, bounded.read(buffer, 0, 3))
        assertEquals(1, delegate.readCalls)
        assertEquals(3L, bounded.consumedBytes())
    }

    @Test
    fun `a zero length read after reaching the end still returns zero`() {
        val delegate = CountingReader(ByteArray(0))
        val bounded = StorageInputStream(ReaderHandle(delegate), limitBytes = null, lifetime = lifetime)
        val buffer = ByteArray(4)

        assertEquals(-1, bounded.read(buffer, 0, 4), "真的读完了才是 -1")

        assertEquals(0, bounded.read(buffer, 0, 0), "要 0 个字节就是 0，不是 -1")
        assertEquals(1, delegate.readCalls, "只有真的去读的那次才动了后端")
    }

    @Test
    fun `bounded and unbounded streams agree on a zero length read`() {
        val delegate = CountingReader("abc".toByteArray())
        val bounded = StorageInputStream(ReaderHandle(delegate), limitBytes = 2L, lifetime = lifetime)
        val unbounded = StorageInputStream(ReaderHandle(ByteArrayInputStream("abc".toByteArray())), limitBytes = null, lifetime = lifetime)

        assertEquals(bounded.read(ByteArray(4), 0, 0), unbounded.read(ByteArray(4), 0, 0))
    }

    /** 真实空文件上跑一遍，确认不是替身才有的现象。 */
    @Test
    fun `a zero length read on a real empty file returns zero`() =
        runBlocking {
            val root = Files.createDirectory(tempDir.resolve("root"))
            Files.write(root.resolve("empty.bin"), ByteArray(0))

            LocalFsStorage.create(root).use { storage ->
                storage.readStream(StoragePath.parse("empty.bin")).use { stream ->
                    val input = stream.openStream()

                    assertEquals(0, input.read(ByteArray(8), 0, 0), "空文件上要 0 个字节也是 0")
                    assertEquals(-1, input.read(ByteArray(8), 0, 8), "真的读完了才是 -1")
                    assertEquals(0, input.read(ByteArray(8), 0, 0), "读完之后再要 0 个字节还是 0")
                }
            }
        }
}
