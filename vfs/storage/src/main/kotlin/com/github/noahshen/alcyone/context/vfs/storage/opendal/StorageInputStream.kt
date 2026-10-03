package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.InputStream

/**
 * 读文件时套在外面的一层：数着读了多少字节，超了上限就报错。
 *
 * 场景：调用方想读 `notes/todo.md`，但只允许读 8 个字节。
 * 读到第 9 个字节时抛 `LIMIT_EXCEEDED`，并且不会把整个文件读进内存。
 * `limitBytes` 传 `null` 表示不限量。
 *
 * 三个状态，只能往前走不能回头：
 *
 * | 状态 | read / skip / available | close |
 * | --- | --- | --- |
 * | 正在读 | 正常；超限的那一次读取当场抛 `LIMIT_EXCEEDED` | 关闭底层流，转「已关闭」 |
 * | 已超限 | 一律抛 `LIMIT_EXCEEDED` | 关闭底层流，转「已关闭」 |
 * | 已关闭 | 一律抛 `CLOSED` | 什么都不做 |
 *
 * 四条要守住的规则：
 *
 * 1. 关掉之后不再碰底层流。
 * 2. `available()` 不会返回负数：超限时直接抛异常，后端报了负数也归零。调用方拿到负数容易写出死循环。
 * 3. 正常读取不会返回 0（除非真的读完）。`readAllBytes()` 这类方法遇到 0 会继续读，
 *    返回 0 就转圈出不来了。所以额度快用完时至少放行 1 个字节，用来判断是不是超限。
 * 4. `skip` 跳过的字节也算进额度。不然「先跳过整个文件再读 1 个字节」就能绕过限制。
 *
 * 底层文件流由 [ReaderHandle] 持有，本类只是它的一层包装。[close] 会转调 [ReaderHandle.release]，
 * 所以外层 [StorageStream] 和 [ReaderHandle] 谁先关都行，文件流只会被关一次。
 *
 * 每次读之前都先经过 [lifetime]：这样存储正在关闭时，本次读取不会和关闭撞在一起。
 */
internal class StorageInputStream(
    private val handle: ReaderHandle,
    private val limitBytes: Long?,
    private val lifetime: NativeLifetime,
) : InputStream() {
    private var consumed = 0L
    private var state = State.ACTIVE

    private enum class State {
        ACTIVE,

        LIMIT_FAILED,
        CLOSED,
    }

    /** 已计费的字节数（含 skip），供测试与诊断。 */
    fun consumedBytes(): Long = consumed

    /** 当前状态名，供测试断言三态迁移。 */
    fun stateName(): String = state.name

    override fun read(): Int {
        beforeRead("read")
        val byte = mapped("read stream") { handle.read { handle.native.read() } }
        if (byte >= 0) charge(1)
        return byte
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        // 先校验入参：负 length 传进后端的行为没定义，可能直接让 JVM 崩掉。
        requireRange(offset, length, buffer)
        beforeRead("read")
        // 要 0 个字节就直接回 0，不去问后端。后端对 length = 0 会回 -1（它当自己读完了），
        // 那是它的内部判断，不该顺着传出去：有界和无界两种流在这里必须一致。
        if (length == 0) return 0
        val read = mapped("read stream") { handle.read { handle.native.read(buffer, offset, requestLimit(length)) } }
        if (read > 0) charge(read.toLong())
        return read
    }

    /**
     * 跳过一些字节，跳过的也算进额度。
     *
     * `count` 为负数时报 `IllegalArgumentException`。`InputStream.skip` 原本允许负数返回 0，
     * 这里故意不同：调用方以为跳过了内容、实际一个字节没跳，额度就和实际位置对不上了。
     */
    override fun skip(count: Long): Long {
        require(count >= 0) { "skip count must not be negative: $count" }
        beforeRead("skip")
        val request = requestLimit(if (count > Int.MAX_VALUE) Int.MAX_VALUE else count.toInt()).toLong()
        val skipped = mapped("skip read stream") { handle.read { handle.native.skip(request) } }
        if (skipped > 0) charge(skipped)
        return skipped
    }

    override fun available(): Int {
        beforeRead("available")
        val available = mapped("check read stream") { handle.read { handle.native.available() } }.coerceAtLeast(0)
        val limit = limitBytes ?: return available
        return if (available > remaining()) remaining().toInt() else available
    }

    /** 不支持 `mark` / `reset`：回退之后「已经读了多少」就说不清了，限额没法算。 */
    override fun markSupported(): Boolean = false

    /** 关闭底层流，只能生效一次；已经超限时也要走这里，否则文件句柄会漏掉。 */
    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        // 先标记已关闭再关：万一关闭失败，再调一次也不会有别的动作。
        // 不经过 lifetime：外层存储已经关闭时，这里也要能正常关掉或者什么都不做。
        mapStorageErrors("close read stream") { handle.release() }
    }

    /**
     * 先经过 [NativeLifetime.call] 再经过 [mapStorageErrors]：
     * 前者避免和关闭撞车，后者把后端异常转成 `STORAGE_ERROR` 并保留 `cause`。
     * 错误消息只说读流的哪一步失败，不带磁盘路径或后端原文。
     */
    private fun <T> mapped(
        operation: String,
        block: () -> T,
    ): T = mapStorageErrors(operation) { lifetime.call(block) }

    private fun beforeRead(operation: String) {
        when (state) {
            State.ACTIVE -> Unit
            State.LIMIT_FAILED ->
                throw VfsException(
                    VfsErrorCode.LIMIT_EXCEEDED,
                    "stream read exceeded the limit of $limitBytes bytes",
                )

            State.CLOSED -> throw VfsException(VfsErrorCode.CLOSED, "local storage stream is closed ($operation)")
        }
    }

    /**
     * 记账；超了就在**这一次读取上**立刻抛错，不拖到下一次。
     * 拖到下一次的话，调用方会先拿到超出额度的那个字节才被告知超限。
     */
    private fun charge(count: Long) {
        consumed += count
        val limit = limitBytes
        if (limit != null && consumed > limit) {
            state = State.LIMIT_FAILED
            throw VfsException(
                VfsErrorCode.LIMIT_EXCEEDED,
                "stream read exceeded the limit of $limit bytes",
            )
        }
    }

    private fun remaining(): Long = (limitBytes ?: Long.MAX_VALUE) - consumed

    /** 额度快用完时只多要 1 个字节来判断超限，不会把整个文件读进内存。 */
    private fun requestLimit(length: Int): Int {
        val limit = limitBytes ?: return length
        val remaining = remaining()
        if (remaining >= length.toLong()) return length
        // 此处 remaining < length <= Int.MAX_VALUE，加 1 不会溢出。
        return (remaining + 1).toInt()
    }

    /**
     * 用 `length > buffer.size - offset` 判断，而不是 `offset + length > buffer.size`：
     * 后者在相加溢出时会漏判，负数就会一路传到后端去。
     */
    private fun requireRange(
        offset: Int,
        length: Int,
        buffer: ByteArray,
    ) {
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset=$offset length=$length bufferSize=${buffer.size}")
        }
    }
}
