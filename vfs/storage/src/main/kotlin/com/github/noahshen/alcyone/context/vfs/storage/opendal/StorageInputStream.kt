package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.InputStream

/**
 * 读流包装：可选限额 + 生命周期守卫，三态确定（T12 R1 / R3）。
 *
 * `limitBytes = null` 表示调用方不设上限；非 null 时按**实际读取量**计费。`BoundedInputStream` 与
 * `GuardedInputStream` 已合并为这一个类，两者行为逐条一致。
 *
 * 三态（状态一旦离开 [ACTIVE] 就不再回去）：
 *
 * | 状态 | read / skip / available | close |
 * | --- | --- | --- |
 * | `ACTIVE` | 正常；越界的那一次读取当场抛 `LIMIT_EXCEEDED` | 关闭 delegate，转 `CLOSED` |
 * | `LIMIT_FAILED` | 一律抛 `LIMIT_EXCEEDED` | 幂等清理 delegate，转 `CLOSED` |
 * | `CLOSED` | 一律抛 `CLOSED` | 幂等空操作 |
 *
 * 四条不变量：
 *
 * 1. **自己关闭后不再触碰 delegate**。检查自身状态发生在任何一次 delegate 调用之前；
 *    之前分开实现时漏了这条，`StorageStream.close()` 之后已打开的流仍能读出字节。
 * 2. **`available()` 在任何状态下都不为负**。`LIMIT_FAILED` 抛异常而不是返回负数；
 *    `delegate.available()` 为负时归零，调用方不会陷入无进展循环。
 * 3. **正长度读永不返回 0**。额度不足时至少放行 1 字节用于判定超限，读到 0 一定是真的到流尾
 *    （委托给 `InputStream.readAllBytes` / `transferTo`，它们在返回 0 时会继续读，直接返回 0 会死循环）。
 * 4. **计费包含 skip**。跳过的字节同样消耗额度，否则可以跳过整个文件再读 1 字节绕过限额。
 *
 * [scope] 在**每一次** delegate 调用前执行：`LocalFsStorage` 传 `lifetime`，此时已持有读锁，
 * 每次读取都与 [NativeLifetime.close] 的写锁互斥，Adapter 不会在流还在被读时释放 native 句柄。
 *
 * 例：`StorageInputStream(source, limitBytes = 8, scope = lifetime)`，读到第 9 个字节抛 `LIMIT_EXCEEDED`。
 */
internal class StorageInputStream(
    private val delegate: InputStream,
    private val limitBytes: Long?,
    private val scope: NativeCallScope = NativeCallScope.Direct,
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
        val byte = mapped("read stream") { delegate.read() }
        if (byte >= 0) charge(1)
        return byte
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        // 先校验入参：负 length 传进 native 流的行为未定义，可能直接崩 JVM。
        requireRange(offset, length, buffer)
        beforeRead("read")
        val read = mapped("read stream") { delegate.read(buffer, offset, requestLimit(length)) }
        if (read > 0) charge(read.toLong())
        return read
    }

    /**
     * 跳过的字节同样计费。
     *
     * 负 `count` 抛 `IllegalArgumentException`，这是对 `InputStream.skip`「负数返回 0」宽松契约的**有意偏离**：
     * 限额流里「负数静默成功」会让调用方以为跳过了内容，实际一个字节都没跳，计费与实际位置脱节。
     */
    override fun skip(count: Long): Long {
        require(count >= 0) { "skip count must not be negative: $count" }
        beforeRead("skip")
        val request = requestLimit(if (count > Int.MAX_VALUE) Int.MAX_VALUE else count.toInt()).toLong()
        val skipped = mapped("skip read stream") { delegate.skip(request) }
        if (skipped > 0) charge(skipped)
        return skipped
    }

    override fun available(): Int {
        beforeRead("available")
        val available = mapped("check read stream") { delegate.available() }.coerceAtLeast(0)
        val limit = limitBytes ?: return available
        return if (available > remaining()) remaining().toInt() else available
    }

    /** `mark` / `reset` 会让「已读取量」失去意义，直接不支持。 */
    override fun markSupported(): Boolean = false

    /**
     * 幂等。`LIMIT_FAILED` 也必须走到这里：超限后流已经不可用，但仍需释放 delegate 持有的 native 句柄。
     */
    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        // 先置 CLOSED 再关：即使 delegate.close() 抛错，本流也已经不可用，重试 close 是幂等空操作。
        mapped("close read stream") { delegate.close() }
    }

    /**
     * 每次 delegate 调用都先经 [scope] 再经 [mapStorageErrors]：
     * 前者保证与 Adapter 关闭互斥，后者把后端异常转成 `STORAGE_ERROR` 且保留 `cause`。
     * 公开消息只说「读流的哪一步失败」，不拼接物理路径或后端原始响应。
     */
    private fun <T> mapped(
        operation: String,
        block: () -> T,
    ): T = mapStorageErrors(operation) { scope.call(block) }

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
     * 记账并在**越界的那一次读取上立即抛错**。
     *
     * 不延迟到下一次调用：否则调用方会先拿到超出额度的那个字节，再在下一次读取才知道超限。
     * 状态先置 [State.LIMIT_FAILED] 再抛，抛完之后 [close] 仍是唯一能释放句柄的路径。
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

    /** 额度不足时只多读 1 字节用于判定超限：永远不会把整个文件读进内存。 */
    private fun requestLimit(length: Int): Int {
        val limit = limitBytes ?: return length
        val remaining = remaining()
        if (remaining >= length.toLong()) return length
        // 此处 remaining < length <= Int.MAX_VALUE，加 1 不会溢出。
        return (remaining + 1).toInt()
    }

    /**
     * `length > buffer.size - offset` 写法而不是 `offset + length > buffer.size`：后者在 offset + length
     * 溢出时会被绕过，正是把负 length 递进 native 的入口。
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
