package com.github.noahshen.alcyone.context.vfs

import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式读取的选项。
 *
 * 和 [ReadOptions] 分开：[read] 拿的是整份 ByteArray，所以有一个默认的 16 MiB 天花板；
 * 流式读取**默认不设总量上限**（仍然一块一块读，不会把整个文件塞进内存），上限由这里单独给。
 *
 * @param maxTotalBytes 这一次最多读多少字节：`null` 表示用 Runtime 配置的总量上限（**不是无限**），
 *   `0` 表示只接受空内容。调用级上限只能**收紧**配置上限，不能放宽；
 *   负数在构造时抛 [VfsErrorCode.INVALID_ARGUMENT]，构造与 `copy()` 都绕不过。
 *
 * 例：Runtime 配了 1 GiB 总量上限，这次只想试读前 10 MiB，就传 `VfsStreamOptions(maxTotalBytes = 10 * 1024 * 1024)`。
 */
data class VfsStreamOptions(
    val maxTotalBytes: Long? = null,
) {
    init {
        if (maxTotalBytes != null && maxTotalBytes < 0) {
            throw VfsException(
                VfsErrorCode.INVALID_ARGUMENT,
                "VfsStreamOptions.maxTotalBytes must not be negative: $maxTotalBytes",
            )
        }
    }
}

/**
 * 一次流式读取的结果：[stream] 是这次读的字节流，[close] 负责放掉底层资源。
 *
 * 三条规则：
 *
 * 1. **拿到之后要 close**。忘记关的话 Runtime 在 `close()` 时会替你收回来（见使用说明）。
 * 2. **重复 close 是空操作**，不会报错也不会重复关底层句柄。
 * 3. **关闭之后不能再读**：底层流已经放掉，读取会失败（后端报 `CLOSED`，普通流按 JDK 惯例抛 `IOException`）。
 *
 * 例：逐块读完一个大文件，读完顺手关掉：
 *
 * ```
 * val chunk = ByteArray(64 * 1024)
 * vfs.openStream(VfsUri.parse("alcyone://resources/big.bin")).use { result ->
 *     while (true) {
 *         val read = result.stream.read(chunk)
 *         if (read < 0) break
 *         // 处理 chunk 的前 read 个字节
 *     }
 * }
 * ```
 */
class VfsStreamResult(
    /** 这次读的是哪个逻辑文件；出错时也用它做诊断。 */
    val uri: VfsUri,
    /** 后端报告的文件长度，不知道时为 `null`。用它提前判断大小，不要当作已读字节数。 */
    val sizeBytes: Long?,
    /** 内容流。分块读，不要 `readAllBytes()` 一次性拉全。 */
    val stream: InputStream,
    /**
     * 关闭时额回调。Runtime 用它把这次读取从「在途资源」里摘掉，普通调用方不用传。
     * 无论底层流关成功还是关失败，这个回调都会执行一次。
     */
    private val onClose: () -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** 是否已经关闭（诊断用）。 */
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 两步都要试：底层流关不掉时，回调也得跑，否则 Runtime 会一直以为这个流还开着。
        var failure: Throwable? = null
        try {
            stream.close()
        } catch (problem: Throwable) {
            failure = problem
        }
        try {
            onClose()
        } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        }
        failure?.let { throw it }
    }
}
