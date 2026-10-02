package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * 限额流机制的白盒验证（T12 R1 / R3）。
 *
 * 这里**没有文件也没有 native**：所有断言只依赖 [StorageInputStream] 自身，因此不会靠「赌 native 崩溃」制造失败。
 */
class StorageInputStreamTest {
    /** 白盒用例不需要真的 Adapter 生命周期：给一个永不释放的 [NativeLifetime]。 */
    private val testLifetime = NativeLifetime { }

    /** 把替身流包成被测对象，避开与 reader 所有权无关的样板。 */
    private fun wrapStream(
        native: java.io.InputStream,
        limitBytes: Long?,
    ): StorageInputStream = StorageInputStream(ReaderHandle(native), limitBytes, testLifetime)

    /** 计数替身流：记录每次 delegate 调用，用来证明包装流「没再碰过 delegate」。 */
    private open class CountingInputStream(
        private var remaining: Int = Int.MAX_VALUE,
        private val chunk: Int = 8,
    ) : InputStream() {
        var readCalls = 0
        var closeCalls = 0
        var skipCalls = 0
        var availableCalls = 0

        override fun read(): Int {
            readCalls++
            if (remaining <= 0) return -1
            remaining--
            return 97 // 字节 'a'
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readCalls++
            if (length == 0) return 0
            if (remaining <= 0) return -1
            val count = minOf(length, chunk, remaining)
            ByteBuffer.wrap(buffer, offset, count).put(ByteArray(count) { 97 })
            remaining -= count
            return count
        }

        override fun skip(count: Long): Long {
            skipCalls++
            val skipped = minOf(count, remaining.toLong())
            remaining -= skipped.toInt()
            return skipped
        }

        override fun available(): Int {
            availableCalls++
            return remaining.coerceAtMost(Int.MAX_VALUE)
        }

        override fun close() {
            closeCalls++
        }
    }

    /** 报假 `available` 的流：只剩 10 字节，却声称有 1 MiB。只信 available 就会把限额放过去。 */
    private class LyingAboutSizeStream : InputStream() {
        private val delegate = ByteArrayInputStream(ByteArray(10) { it.toByte() })

        override fun read(): Int = delegate.read()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(buffer, offset, length)

        override fun available(): Int = 1024 * 1024
    }

    @Test
    fun `the limit is charged on actual reads, not on any known length`() {
        val source = ByteArrayInputStream(ByteArray(50) { it.toByte() })

        val failure = assertFailsWith<VfsException> { wrapStream(source, limitBytes = 8).readAllBytes() }

        assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code)
    }

    @Test
    fun `a source that lies about its size still cannot exceed the limit`() {
        val bounded = wrapStream(LyingAboutSizeStream(), limitBytes = 4)

        assertEquals(4, bounded.available())
        assertFailsWith<VfsException> { bounded.readAllBytes() }
    }

    @Test
    fun `counted bytes include skipped ones`() {
        val bounded = wrapStream(ByteArrayInputStream(ByteArray(20) { 1 }), limitBytes = 5)

        bounded.skip(4)

        assertEquals(4L, bounded.consumedBytes())
        assertEquals(1, bounded.read()) // 第 5 个字节：正好用完额度
        assertFailsWith<VfsException> { bounded.read() } // 第 6 个字节：超限，进入 LIMIT_FAILED
    }

    @Test
    fun `mark and reset are refused because they would break accounting`() {
        val bounded = wrapStream(ByteArrayInputStream(ByteArray(10)), limitBytes = 10)

        assertFalse(bounded.markSupported())
        assertFailsWith<IOException> { bounded.reset() }
    }

    /** 超限是**粘性**的：之后每一次 read / skip / available 都报同一个错，不回退到可读。 */
    @Test
    fun `after a limit failure every read skip and available reports LIMIT_EXCEEDED`() {
        val bounded = wrapStream(CountingInputStream(remaining = 10), limitBytes = 4)

        repeat(4) { assertEquals(97, bounded.read(), "额度内的字节正常返回") }
        assertFailsWith<VfsException> { bounded.read() } // 第 5 个字节：超限，进入 LIMIT_FAILED // 第 5 个字节超限
        assertEquals("LIMIT_FAILED", bounded.stateName())

        listOf<() -> Any>(
            { bounded.read() },
            { bounded.read(ByteArray(4)) },
            { bounded.skip(1) },
            { bounded.available() },
        ).forEach { call ->
            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, assertFailsWith<VfsException> { call() }.code)
        }
    }

    /** 曾经的缺陷：超限后 `remaining` 变负，`available()` 返回 -1。 */
    @Test
    fun `available is never negative and never exceeds the remaining allowance`() {
        val bounded = wrapStream(CountingInputStream(remaining = 100), limitBytes = 10)

        assertTrue(bounded.available() in 1..10)
        bounded.readNBytes(4)
        assertTrue(bounded.available() in 1..6, "不能承诺超出剩余额度的字节数")

        // 读到超出额度（consumed 4 + 3 = 7 > 6）：available() 此时必须抛错，而不是返回负数（曾经的缺陷是返回 -1）。
        assertFailsWith<VfsException> { bounded.read(ByteArray(16)) }
        assertFailsWith<VfsException> { bounded.available() }
    }

    /** 曾经的缺陷：额度不足时 `read` 返回 0，调用方（readAllBytes / transferTo）会陷入无进展循环。 */
    @Test
    fun `a positive length read never returns zero`() {
        val bounded = wrapStream(CountingInputStream(remaining = 64), limitBytes = 4)
        val buffer = ByteArray(32)

        // 每次请求都裁到「剩余额度 + 1」：要么读满该请求，要么真正到流尾（-1），绝不会是 0。
        // 第 4 次请求被裁到 2 字节：读到它就当场报超限（额度 4，内容 64）。
        for (attempt in 1..4) {
            val read = assertFailsWith<VfsException> { bounded.read(buffer) }
            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, read.code, "第 $attempt 次读取越过额度")
        }
        assertEquals(5L, bounded.consumedBytes())
    }

    /** 曾经的缺陷：关闭后包装流仍会碰 delegate，于是 `StorageStream.close()` 之后还能读出字节。 */
    @Test
    fun `a stream closed by the caller never touches the delegate again`() {
        val delegate = CountingInputStream(remaining = 64)
        val bounded = wrapStream(delegate, limitBytes = null)

        assertEquals(97, bounded.read())
        bounded.close()
        val callsAfterClose = delegate.readCalls + delegate.skipCalls + delegate.availableCalls

        listOf<() -> Any>(
            { bounded.read() },
            { bounded.read(ByteArray(4)) },
            { bounded.skip(1) },
            { bounded.available() },
        ).forEach { call ->
            assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { call() }.code)
        }
        assertEquals(callsAfterClose, delegate.readCalls + delegate.skipCalls + delegate.availableCalls, "关闭后不得再碰 delegate")
        assertEquals("CLOSED", bounded.stateName())
    }

    @Test
    fun `close is idempotent and closes the delegate exactly once`() {
        val delegate = CountingInputStream(remaining = 4)
        val bounded = wrapStream(delegate, limitBytes = 2)

        bounded.read()
        bounded.close()
        bounded.close()

        assertEquals(1, delegate.closeCalls)
    }

    /** 超限之后仍必须能清理，否则 native 句柄会泄漏。 */
    @Test
    fun `close is the only cleanup path and it works after a limit failure`() {
        val delegate = CountingInputStream(remaining = 32)
        val bounded = wrapStream(delegate, limitBytes = 1)

        bounded.read()
        assertFailsWith<VfsException> { bounded.read() } // 第 2 个字节超限，进入 LIMIT_FAILED
        bounded.close()
        bounded.close()

        assertEquals(1, delegate.closeCalls)
        assertEquals("CLOSED", bounded.stateName())
    }

    @Test
    fun `a null limit reads everything and behaves like a plain stream`() {
        val payload = ByteArray(200) { it.toByte() }
        val bounded = wrapStream(ByteArrayInputStream(payload), limitBytes = null)

        assertTrue(bounded.readAllBytes().contentEquals(payload))
        assertEquals(200L, bounded.consumedBytes())
        assertEquals("ACTIVE", bounded.stateName())
    }

    @Test
    fun `a zero length read is allowed and charges nothing`() {
        val delegate = CountingInputStream(remaining = 8)
        val bounded = wrapStream(delegate, limitBytes = 4)

        assertEquals(0, bounded.read(ByteArray(4), 0, 0))
        assertEquals(0L, bounded.consumedBytes())
    }

    @Test
    fun `an out of range offset or length is rejected before the delegate is touched`() {
        val delegate = CountingInputStream(remaining = 8)
        val bounded = wrapStream(delegate, limitBytes = null)
        val buffer = ByteArray(4)

        assertFailsWith<IndexOutOfBoundsException> { bounded.read(buffer, 0, -1) }
        assertFailsWith<IndexOutOfBoundsException> { bounded.read(buffer, -1, 2) }
        // offset + length 溢出：只有 `length > size - offset` 写法拦得住。
        assertFailsWith<IndexOutOfBoundsException> { bounded.read(buffer, 4, Int.MAX_VALUE) }
        assertFailsWith<IndexOutOfBoundsException> { bounded.read(buffer, 3, 2) }

        assertEquals(0, delegate.readCalls, "入参非法时不得触碰 delegate")
        assertEquals(0L, bounded.consumedBytes())
    }

    /** 负 length 传进 native 流的行为未定义，可能直接崩 JVM，所以在包装层就拒绝。 */
    @Test
    fun `a negative length never reaches the delegate`() {
        val delegate = CountingInputStream(remaining = 8)
        val bounded = wrapStream(delegate, limitBytes = null)

        assertFailsWith<IndexOutOfBoundsException> { bounded.read(ByteArray(8), 0, -1) }
        assertEquals(0, delegate.readCalls)
    }

    @Test
    fun `a negative skip is refused instead of silently succeeding`() {
        val delegate = CountingInputStream(remaining = 8)
        val bounded = wrapStream(delegate, limitBytes = 4)

        assertFailsWith<IllegalArgumentException> { bounded.skip(-1) }
        assertEquals(0, delegate.skipCalls, "非法入参不得触碰 delegate")
        assertEquals(0L, bounded.consumedBytes())
    }

    @Test
    fun `skip beyond the allowance charges exactly the allowance plus the detecting byte`() {
        val bounded = wrapStream(CountingInputStream(remaining = 100), limitBytes = 5)

        // 跳到额度边界后仍多读 1 字节用于判定超限；这一次调用当场抛错，不返回「跳过了多少」。
        assertEquals(VfsErrorCode.LIMIT_EXCEEDED, assertFailsWith<VfsException> { bounded.skip(50) }.code)
        assertEquals(6L, bounded.consumedBytes())
        assertEquals("LIMIT_FAILED", bounded.stateName())

        // 之后是粘性的，不会因为「额度已经用光」而安静下来。
        assertEquals(VfsErrorCode.LIMIT_EXCEEDED, assertFailsWith<VfsException> { bounded.skip(1) }.code)
    }

    @Test
    fun `an IOException from the delegate is mapped to STORAGE_ERROR and keeps its cause`() {
        val cause = IOException("backend says no")
        val delegate =
            object : InputStream() {
                override fun read(): Int = throw cause
            }

        val failure = assertFailsWith<VfsException> { wrapStream(delegate, limitBytes = null).read() }

        assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
        assertSame(cause, failure.cause)
        assertFalse(failure.message!!.contains("backend says no"), "公开消息不拼接后端原始响应")
    }

    @Test
    fun `a VfsException from the delegate passes through unchanged`() {
        val original = VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "symbolic link refused")
        val delegate =
            object : InputStream() {
                override fun read(): Int = throw original
            }

        val failure = assertFailsWith<VfsException> { wrapStream(delegate, limitBytes = null).read() }

        assertSame(original, failure)
    }

    @Test
    fun `a CancellationException from the delegate is not converted`() {
        val cancellation = java.util.concurrent.CancellationException("cancelled")
        val delegate =
            object : InputStream() {
                override fun read(): Int = throw cancellation
            }

        val failure = assertFailsWith<java.util.concurrent.CancellationException> { wrapStream(delegate, limitBytes = null).read() }

        assertSame(cancellation, failure)
    }

    @Test
    fun `an IOException while closing the delegate is mapped and close stays idempotent`() {
        val delegate =
            object : CountingInputStream(remaining = 1) {
                override fun close(): Unit = throw IOException("close failed")
            }
        val bounded = wrapStream(delegate, limitBytes = null)

        val failure = assertFailsWith<VfsException> { bounded.close() }
        assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
        // 即使 close 抛错，流也已经不可用：重试是幂等空操作，不再触碰 delegate。
        bounded.close()
        assertEquals("CLOSED", bounded.stateName())
    }
}
