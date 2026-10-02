package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 存放一个还没读完的文件流，并保证它只被关闭一次。
 *
 * 场景：调用方拿到 [StorageInputStream] 之后只关闭了外层的 [StorageStream]，
 * 底层这个流也必须跟着关掉，否则文件句柄会一直被占着。
 *
 * 三条约定：
 * 1. 关闭是幂等的。谁先调用 [release] 谁关，后面再调用没有效果。
 * 2. 正在读的时候不关闭。[read] 和 [release] 用同一把锁排队，
 *    正在进行的读取结束后才轮到关闭。
 * 3. 它不看 [NativeLifetime]。锁是自己的，所以外层存储已经关闭时，
 *    调用方再关闭流依然是合法的空操作。
 */
internal class ReaderHandle(
    /** OpenDAL 创建的文件流。只有拿到下面的锁才能碰它。 */
    val native: InputStream,
    /** 关闭之后回调，用来把它从 [LocalFsStorage] 的待关闭列表里摘掉。 */
    private val onReleased: (ReaderHandle) -> Unit = {},
) {
    private val gate = ReentrantLock()
    private var released = false

    /** 排队执行一次读取；已经关闭就直接报 `CLOSED`，不碰底层。 */
    fun <T> read(block: () -> T): T =
        gate.withLock {
            if (released) throw VfsException(VfsErrorCode.CLOSED, "local storage read stream is closed")
            block()
        }

    /**
     * 关闭底层文件流，只能生效一次。
     *
     * 关闭动作刻意留在锁里面，这样「读取还没返回就关掉了」不会发生。
     * 抛出的异常由调用方转成 VFS 错误码。
     */
    fun release() {
        gate.lock()
        try {
            if (released) return
            released = true
            try {
                native.close()
            } finally {
                onReleased(this)
            }
        } finally {
            gate.unlock()
        }
    }

    fun isReleased(): Boolean = released
}
