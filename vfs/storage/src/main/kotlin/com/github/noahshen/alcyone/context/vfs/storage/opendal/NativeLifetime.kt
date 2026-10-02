package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 一次 native 操作的执行范围。
 *
 * [NativeLifetime] 是生产实现：持读锁执行，保证释放 Operator 与使用句柄互斥。
 * [Direct] 不加锁，只能用在**已经处在** [NativeLifetime.call] 内部的嵌套调用点。
 */
internal interface NativeCallScope {
    /**
     * 执行一次 native 操作；Adapter 已关闭时抛 `CLOSED`，不进 native。
     *
     * 例：`scope.call { operator.stat(path) }`。
     */
    fun <T> call(block: () -> T): T

    companion object {
        /**
         * 直调，不做生命周期检查。
         *
         * 为什么嵌套处不能用 [NativeLifetime]：非公平读写锁在有写者排队时会挡住新的读锁，
         * 而读锁本身在调用链外层已被持有（写者在等它），再取一次读锁就是死锁。
         * 所以「已经在 `lifetime.call` 里」必须显式声明成 Direct。
         */
        val Direct: NativeCallScope =
            object : NativeCallScope {
                override fun <T> call(block: () -> T): T = block()
            }
    }
}

/**
 * Adapter 生命周期守卫（T12 R1）：把「Operator 还可用」与「释放 Operator」变成互斥。
 *
 * 为什么不能只用一个布尔标志：实测 OpenDAL 0.50.6 在 Operator 关闭后调用 `stat` 直接 SIGSEGV，
 * 所以「检查通过」和「真正使用」之间必须有锁——本类用一把读写锁表达这件事：
 *
 * - 存储操作持**读锁**：已经进入的操作做完，[close] 才能拿到写锁释放 Operator；释放后不再有操作进入。
 * - [close] 持**写锁**：等进行中的操作收尾，然后幂等释放。
 *
 * 例：线程 A 正在 `stat`，线程 B 调 [close]，B 会等到 A 的 `stat` 返回。
 *
 * **取舍**：阻塞的 native 调用期间 [close] 会短暂阻塞，这是有意为之——本任务不要求 native 调用
 * 可被即时中断（阻塞调用本来就不响应协程取消），而错误的「立即释放」会直接崩 JVM。
 * 因此**不要**在任何存储操作内部调用 [close]：那会拿写锁去等自己持有的读锁。
 */
internal class NativeLifetime(
    private val release: () -> Unit,
) : NativeCallScope {
    private val lock = ReentrantReadWriteLock()
    private val closed = AtomicBoolean(false)

    override fun <T> call(block: () -> T): T {
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
