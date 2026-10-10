package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 保证「存储还能用」和「把存储关掉」不会同时发生。
 *
 * 为什么不用一个布尔标志：OpenDAL 的 [org.apache.opendal.Operator] 一旦关闭，
 * 再拿它做任何操作都会让 JVM 直接崩溃。所以必须用锁把「先检查、后使用」框起来：
 *
 * - [call]：每个存储操作都拿读锁。已经在进行的操作会先做完。
 * - [close]：拿写锁，等所有操作收尾，然后关闭 [org.apache.opendal.Operator]。
 *
 * 例：线程 A 正在读文件，线程 B 调 [close]，B 会一直等到 A 读完。
 *
 * **警告：不要在任何存储操作内部调用 [close]** —— 那等于拿着读锁去等写锁，会死锁。
 *
 * [call] 可以嵌套调用自己（同一线程重复拿读锁是安全的），但**读锁不能升级成写锁**。
 *
 * [close] 会等在进行的操作做完，这是有意的：宁可多等一下，也不要强杀正在用的句柄。
 * 停机超时策略由 T18 / T27 承接，本轮不做。
 */
internal class NativeLifetime(
    /** 错误消息里的后端名；本地盘与 WebDAV 共用这把锁，消息不能互相说错。 */
    private val label: String = "local storage",
    private val release: () -> Unit,
) {
    private val lock = ReentrantReadWriteLock()
    private val closed = AtomicBoolean(false)

    /** 后端名，供错误消息使用（如 [StorageInputStream] 的关闭消息）。 */
    internal fun label(): String = label

    /** 执行一次存储操作；已关闭时抛 `CLOSED`，不进入操作本身。 */
    fun <T> call(block: () -> T): T {
        lock.readLock().lock()
        try {
            if (closed.get()) throw VfsException(VfsErrorCode.CLOSED, "$label is closed")
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    /** 关闭，只能生效一次；会等在进行的操作结束。 */
    fun close() {
        lock.writeLock().lock()
        try {
            if (closed.compareAndSet(false, true)) release()
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun isClosed(): Boolean = closed.get()
}
