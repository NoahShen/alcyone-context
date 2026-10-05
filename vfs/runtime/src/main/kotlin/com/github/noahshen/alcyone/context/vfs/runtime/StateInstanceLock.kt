package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 状态库独占：用 OS 文件锁占住状态库旁边的 `.lock` 文件，**在打开状态库和写入配置之前**先拿到。
 *
 * 例：两个进程用同一份配置启动 VFS，第二个直接拿到 `CONFLICT`，不会两个进程各开一条连接写同一个库。
 *
 * 简单机制，边界说清楚：
 * - 覆盖同一 JVM 内的第二个实例，也覆盖遵守同一协议的多进程启动（`FileChannel.tryLock` 的 POSIX 文件锁）；
 * - **只挡住遵守本协议的启动**。别的工具绕过 VFS 直接写这个 SQLite 文件，本锁管不着；
 * - 锁文件本身**留在盘上**：正常关闭、初始化失败回滚、甚至进程被杀掉，文件都还在。
 *   文件存在只说明「曾经用过这个库」，不代表现在有人占着——能不能启动看的是锁，不是文件在不在。
 * - 关闭时先释放锁再关通道；重复关闭是空操作。
 */
internal class StateInstanceLock private constructor(
    private val lock: FileLock,
    private val channel: FileChannel,
) : AutoCloseable {
    private var released = false

    override fun close() {
        if (released) return
        released = true
        // 两步都尝试：一处失败不挡住另一处；第一个失败当主异常，其余进 suppressed。
        val failures = mutableListOf<Throwable>()
        runCatching { lock.release() }.exceptionOrNull()?.let { failures += it }
        runCatching { channel.close() }.exceptionOrNull()?.let { failures += it }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw VfsException(VfsErrorCode.STATE_ERROR, "instance lock could not be released (${first::class.simpleName})")
                .apply { initCause(first) }
        }
    }

    companion object {
        /**
         * 尝试取得独占；拿不到就报 `CONFLICT`，不等待。
         *
         * 例：状态库旁边的目录没有写权限 → `STATE_ERROR`（不是 `CONFLICT`）：
         * 「没法开锁文件」和「有人已经占着」是两回事，调用方要能分开处理。
         */
        fun acquire(path: Path): StateInstanceLock {
            val channel =
                try {
                    FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                } catch (failure: IOException) {
                    throw VfsException(
                        VfsErrorCode.STATE_ERROR,
                        "instance lock file cannot be opened (${failure::class.simpleName})",
                    ).apply { initCause(failure) }
                }
            val lock =
                try {
                    channel.tryLock()
                } catch (failure: OverlappingFileLockException) {
                    // 同一个 JVM 里第二个实例：JVM 不给重叠锁，抛异常而不是返回 null。一样是「已占用」。
                    null
                } catch (failure: IOException) {
                    channel.close()
                    throw VfsException(
                        VfsErrorCode.STATE_ERROR,
                        "instance lock cannot be acquired (${failure::class.simpleName})",
                    ).apply { initCause(failure) }
                }
            if (lock == null) {
                closeQuietly(channel)
                throw VfsException(VfsErrorCode.CONFLICT, "the VFS state database is already in use by another instance")
            }
            return StateInstanceLock(lock, channel)
        }

        /** 取锁失败时把通道收拾干净；锁文件留在盘上，下次启动照样能拿到锁。 */
        private fun closeQuietly(channel: FileChannel) {
            runCatching { channel.close() }
        }
    }
}
