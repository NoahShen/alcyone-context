package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一个 native reader 的**唯一所有者**，自带释放闸门（T12 R1）。
 *
 * 之前是「谁后拿到谁负责关」，结果出现空档：`StorageStream` 把 reader 交给 `StorageInputStream` 之后
 * 就不再持有它，于是关谁都不释放——实测 `readerCloseCalls = 0`，Operator 释放也不会连带释放 reader。
 *
 * 现在三处入口（[StorageInputStream] 的读与关、[StorageStream.close]、[LocalFsStorage.close] 的排空）
 * 都走同一个句柄，闸门保证：
 *
 * 1. **恰好释放一次**：[release] 幂等，谁先到谁关，后到的都是空操作。
 * 2. **读与关互斥**：[read] 与 [release] 抢同一把锁，正在进行的读取返回之后才可能释放，
 *    不会出现「close 进入时 read 还没返回」。
 * 3. **释放不依赖 Adapter 生命周期**：闸门是 reader 自己的，不看 [NativeLifetime]。
 *    所以 Adapter 先关时，调用方后续 `close()` 仍然是合法的幂等空操作，不会被 `CLOSED` 挡住。
 *
 * **锁顺序**：`NativeLifetime` 锁 → 本闸门。Adapter 的排空持写锁再取本闸门，与读取路径同序，
 * 不会互相死锁。
 */
internal class ReaderHandle(
    /** 后端创建的 native reader；只有持有闸门时才允许触碰。 */
    val native: InputStream,
    /** 首次释放后的回调，用来把它从 Adapter 的登记表里摘掉。 */
    private val onReleased: (ReaderHandle) -> Unit = {},
) {
    private val gate = ReentrantLock()
    private var released = false

    /** 持闸门执行一次读取。已释放则抛 `CLOSED`，不碰 native。 */
    fun <T> read(block: () -> T): T =
        gate.withLock {
            if (released) throw VfsException(VfsErrorCode.CLOSED, "local storage read stream is closed")
            block()
        }

    /**
     * 幂等：第一次真正关掉 native 并回调，之后是空操作。抛出的异常由调用方映射。
     *
     * `native.close()` 刻意留在闸门内：正在进行的读取必须先返回，才可能走到这里。
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
