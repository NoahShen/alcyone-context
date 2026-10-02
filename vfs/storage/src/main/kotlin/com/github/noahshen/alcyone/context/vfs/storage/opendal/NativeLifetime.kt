package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Adapter 生命周期守卫（T12 R1）：把「Operator 还可用」与「释放 Operator」变成互斥。
 *
 * 为什么不能只用一个布尔标志：实测 OpenDAL 0.50.6 在 Operator 关闭后调用 `stat` 直接 SIGSEGV，
 * 所以「检查通过」和「真正使用」之间必须有锁——本类用一把读写锁表达这件事：
 *
 * - 存储操作与每次流读取持**读锁**：已经进入的操作做完，[close] 才能拿到写锁释放 Operator；释放后不再有操作进入。
 * - [close] 持**写锁**：等进行中的操作收尾，然后幂等释放。
 *
 * 例：线程 A 正在 `stat`，线程 B 调 [close]，B 会等到 A 的 `stat` 返回。
 *
 * **可重入**：读锁本身可重入，同一线程可以嵌套 `call { call { … } }`——JDK 对排队中的写者有专门放行分支，
 * 不会自锁。所以不存在「嵌套取读锁会死锁」，**不要**为了规避并不存在的风险去绕开 [call]。
 * 真正不可行的是**持读锁再升级写锁**：同一线程先拿读锁、再要写锁会死锁（本类没有任何这种用法）。
 *
 * **取舍**：阻塞的 native 调用期间 [close] 会短暂阻塞，这是有意为之——本任务不要求 native 调用
 * 可被即时中断（阻塞调用本来就不响应协程取消），而错误的「立即释放」会直接崩 JVM。
 * 因此**不要**在任何存储操作内部调用 [close]：那会拿写锁去等自己持有的读锁。
 * 停机超时策略由 T18 / T27 承接，本轮不做。
 */
internal class NativeLifetime(
    private val release: () -> Unit,
) {
    private val lock = ReentrantReadWriteLock()
    private val closed = AtomicBoolean(false)

    /** 执行一次 native 操作；Adapter 已关闭时抛 `CLOSED`，不进 native。可重入。 */
    fun <T> call(block: () -> T): T {
        lock.readLock().lock()
        try {
            if (closed.get()) throw VfsException(VfsErrorCode.CLOSED, "local storage is closed")
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    /** 幂等；等待进行中的操作结束后才真正释放。 */
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
