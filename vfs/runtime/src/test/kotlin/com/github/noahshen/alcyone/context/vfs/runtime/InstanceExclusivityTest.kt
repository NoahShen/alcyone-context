package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * T18 A03：同一个状态库同时只能有一个实例。
 *
 * 全部用真的文件锁与真的 SQLite：同进程第二实例、释放后重开、别名不绕过、锁文件残留不等于占用，
 * 外加一个最小双 JVM 探针（用项目内 JDK 拉起一个只 create 不 close 的子进程）。
 */
class InstanceExclusivityTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("disk"))
    }

    private fun config(db: Path = database) =
        VfsRuntimeConfig(
            stateDatabase = db,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk)),
        )

    private fun lockFile(db: Path = database): Path = db.resolveSibling(db.fileName.toString() + ".lock")

    @Test
    @Timeout(60)
    fun `a second instance on the same database is refused and accepted again after close`() =
        runBlocking {
            prepare()
            AlcyoneVfs.create(config()).use { first ->
                val failure = assertFailsWith<VfsException> { AlcyoneVfs.create(config()) }

                assertEquals(VfsErrorCode.CONFLICT, failure.code, "同一状态库已经有实例在跑")
                assertTrue(Files.exists(lockFile()), "锁文件留着是对的")
            }

            AlcyoneVfs.create(config()).use { reopened ->
                val root = reopened.stat(VfsUri.parse("alcyone://resources"))

                assertEquals("/resources", root.uri.path.toString(), "释放之后同一个库能再次打开并正常服务")
            }
        }

    /** 换个写法指向同一个状态库（相对路径 + 符号链接别名）照样拿不到独占。 */
    @Test
    @Timeout(60)
    fun `a path alias does not bypass the exclusive lock`() =
        runBlocking {
            prepare()
            val alias = Files.createSymbolicLink(tempDir.resolve("alias"), tempDir)
            AlcyoneVfs.create(config()).use {
                val failure =
                    assertFailsWith<VfsException> {
                        AlcyoneVfs.create(config(alias.resolve("state.db")))
                    }

                assertEquals(VfsErrorCode.CONFLICT, failure.code, "别名指向同一个库，必须被认出来")
            }
        }

    /** 锁文件残留在盘上不等于还被占用：正常关闭之后同一个库能再次打开。 */
    @Test
    @Timeout(60)
    fun `a leftover lock file does not count as taken`() =
        runBlocking {
            prepare()
            AlcyoneVfs.create(config()).use { }
            assertTrue(Files.exists(lockFile()), "关闭后锁文件仍然留在盘上")

            AlcyoneVfs.create(config()).use { }
        }

    /**
     * 双 JVM 探针：父进程持锁时子进程拿不到；父进程关闭后子进程能拿到；
     * 子进程只 create 不 close 直接退出，退出后锁又被放掉，父进程能再拿一次。
     *
     * 只证明「同协议的多进程启动互斥」，不是平台压力测试，也不覆盖外部工具直接写库的情况。
     */
    @Test
    @Timeout(120)
    fun `a second process on the same database is refused and the lock dies with the process`() =
        runBlocking {
            prepare()

            AlcyoneVfs.create(config()).use {
                val refused = runProbe(database, disk)
                assertTrue(
                    refused.first.startsWith("REJECTED CONFLICT") && refused.first.contains("already in use"),
                    "父进程持锁时子进程必须因独占锁被拒：${refused.second}",
                )
            }

            val granted = runProbe(database, disk)
            assertEquals("LOCKED", granted.first, "父进程关闭之后子进程应当能拿到锁：${granted.second}")

            // 子进程是「只 create 不 close」退出的：进程一死，文件锁就没了。
            AlcyoneVfs.create(config()).use {
                assertEquals(
                    "/resources",
                    it
                        .stat(VfsUri.parse("alcyone://resources"))
                        .uri.path
                        .toString(),
                )
            }
        }

    /** 拉起一个子进程跑 [LockProbeProcess]，拿到它打印的那一行（以及失败时的全部输出）。 */
    private fun runProbe(
        stateDatabase: Path,
        childDisk: Path,
    ): Pair<String, String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                LockProbeProcess::class.java.name,
                stateDatabase.toString(),
                childDisk.toString(),
            ).redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "子进程 90 秒内没有退出")
        assertEquals(0, process.exitValue(), "子进程非正常退出：$output")
        val line = output.lineSequence().lastOrNull { it.startsWith("LOCKED") || it.startsWith("REJECTED") }
        requireNotNull(line) { "子进程没有打印结果：$output" }
        return line to output
    }
}
