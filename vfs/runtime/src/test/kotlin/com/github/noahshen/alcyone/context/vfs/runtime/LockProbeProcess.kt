package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 最小双 JVM 探针的子进程入口：只 `create`、不 `close`，然后正常退出。
 *
 * 两个用法：
 * - 父进程正持锁 → 拿不到锁，打印 `REJECTED <错误码> <消息>` 后退出 0；
 * - 没有别人持锁 → 拿到锁，打印 `LOCKED` 后**故意不关**，直接退出，让操作系统在进程结束时放掉文件锁。
 *
 * 参数：`<状态库文件> <磁盘目录>`。磁盘目录必须和父进程**用同一个**：
 * 否则子进程会被「同一个 storageKey 指向了另一个物理根」这条挂载冲突先拒掉，测的就不是独占锁了。
 * 只用项目内 JDK 拉起，不做平台压力测试。
 */
object LockProbeProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val database = Path.of(args[0])
        val disk = Files.createDirectories(Path.of(args[1]))
        val config =
            VfsRuntimeConfig(
                stateDatabase = database,
                namespaces = setOf("resources"),
                mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk)),
            )
        val outcome =
            try {
                runBlocking { AlcyoneVfs.create(config) }
                "LOCKED"
            } catch (failure: VfsException) {
                if (failure.code != VfsErrorCode.CONFLICT) throw failure
                "REJECTED ${failure.code} ${failure.message}"
            }
        println(outcome)
        System.out.flush()
        // 故意不 close：进程退出时由操作系统放掉文件锁，这正是要验证的「退出也算释放」。
        exitProcess(0)
    }
}
