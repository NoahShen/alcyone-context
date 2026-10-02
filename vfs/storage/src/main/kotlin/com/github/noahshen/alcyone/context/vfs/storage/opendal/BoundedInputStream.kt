package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.InputStream

/**
 * 按**实际读取量**计数的限额流（T12 §2.4）。
 *
 * 三点必须成立：
 *
 * - `read` / `skip` 都计费。`skip` 丢弃的字节同样消耗额度，否则调用方可以跳过整个文件再读 1 字节绕过限额。
 * - 请求量被裁到「剩余额度 + 1」：最多只多读 1 个字节用来判定是否超限，不会先把整个文件读进内存。
 *   [InputStream.readAllBytes] / `readNBytes` / `transferTo` 都经由 `read(byte[], int, int)`，所以一并受限。
 * - `available()` 不返回超出剩余额度的字节数。
 *
 * [guard] 在每次触碰底层 native 流之前执行：实测 OpenDAL 0.50.6 的 Operator 关闭后再使用会让 JVM 崩溃
 * （SIGSEGV），所以「Adapter 已关闭」必须在进 native 之前变成一个 VfsException。
 */
internal class BoundedInputStream(
    private val delegate: InputStream,
    private val limitBytes: Long,
    private val guard: () -> Unit = {},
) : InputStream() {
    private var consumed = 0L
    private var closed = false

    override fun read(): Int {
        guard()
        val byte = delegate.read()
        if (byte >= 0) charge(1)
        return byte
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        guard()
        val allowed = requestLimit(length)
        val read = delegate.read(buffer, offset, allowed)
        if (read > 0) charge(read.toLong())
        return read
    }

    override fun skip(count: Long): Long {
        guard()
        val request = requestLimit(if (count > Int.MAX_VALUE) Int.MAX_VALUE else count.toInt()).toLong()
        val skipped = delegate.skip(request)
        if (skipped > 0) charge(skipped)
        return skipped
    }

    override fun available(): Int {
        guard()
        val available = delegate.available()
        return if (available > remaining()) remaining().toInt() else available
    }

    /** `mark` / `reset` 会让「已读取量」失去意义，直接不支持。 */
    override fun markSupported(): Boolean = false

    override fun close() {
        if (closed) return
        closed = true
        // Adapter 已经关闭时不再触碰 native 句柄（见类注释）。流本身已经不可用。
        if (guardsPass()) delegate.close()
    }

    /** 已计费的字节数，供测试与诊断使用。 */
    fun consumedBytes(): Long = consumed

    private fun remaining(): Long = limitBytes - consumed

    private fun charge(count: Long) {
        consumed += count
        if (consumed > limitBytes) {
            throw VfsException(
                VfsErrorCode.LIMIT_EXCEEDED,
                "stream read exceeded the limit of $limitBytes bytes",
            )
        }
    }

    private fun requestLimit(length: Int): Int {
        val remaining = remaining()
        if (remaining >= length.toLong()) return length
        // 额度不足：只多读 1 字节用于判定超限（此处 remaining < Int.MAX_VALUE，加 1 不会溢出）。
        return (remaining + 1).toInt()
    }

    private fun guardsPass(): Boolean =
        try {
            guard()
            true
        } catch (e: VfsException) {
            false
        }
}
