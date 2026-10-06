package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * T18 配置的几道防线：同一个 key 不能指两个目录、悬空的库链接不能放行、
 * 状态库不能放在挂载目录里（否则公开的 delete 能把它和锁删掉）、
 * 删掉还挂着已登记 Node 的命名空间要拒绝。
 *
 * 前三个只调用 `resolve()`，不碰磁盘以外的东西；最后一个用真的状态库跑一遍启动。
 */
class VfsRuntimeConfigGuardTest {
    @TempDir
    lateinit var tempDir: Path

    private fun disk(name: String): Path = Files.createDirectory(tempDir.resolve(name))

    private fun mount(
        path: String,
        key: String,
        root: Path,
    ) = MountConfig(VfsPath.parse(path), key, root)

    private fun config(
        mounts: List<MountConfig>,
        database: Path = tempDir.resolve("state.db"),
        namespaces: Set<String> = setOf("resources"),
    ) = VfsRuntimeConfig(stateDatabase = database, namespaces = namespaces, mounts = mounts)

    /** 同一个 key 写了两个目录：后一个本来会被静默丢掉，读写却都落到前一个，必须在配置这步就拒。 */
    @Test
    @Timeout(60)
    fun `one storage key cannot point at two directories`() {
        val failure =
            assertFailsWith<VfsException> {
                config(
                    listOf(
                        mount("/resources/a", "same", disk("first")),
                        mount("/resources/b", "same", disk("second")),
                    ),
                ).resolve()
            }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    /** create → 用 → close 的三步。`close()` 是挂起函数，所以用 `try/finally` 而不是 `use`。 */
    private suspend fun <T> withVfs(
        config: VfsRuntimeConfig,
        block: suspend (AlcyoneVfs) -> T,
    ): T {
        val vfs = AlcyoneVfs.create(config)
        return try {
            block(vfs)
        } finally {
            vfs.close()
        }
    }

    /** 同一个 key 指向同一个目录（这里一个是符号链接别名）不算冲突：这就是一块盘挂两个逻辑位置。 */
    @Test
    @Timeout(60)
    fun `one storage key on the same directory is accepted through an alias`() {
        val real = disk("docs")
        val alias = Files.createSymbolicLink(tempDir.resolve("alias-docs"), real)

        val resolved =
            config(
                listOf(
                    mount("/resources/a", "same", real),
                    mount("/resources/b", "same", alias),
                ),
            ).resolve()

        assertEquals(1, resolved.roots.size, "同一个 key 只开一个存储实例")
        assertEquals(2, resolved.mounts.size, "两个逻辑挂载点都在")
        resolved.mounts.forEach { record ->
            assertEquals(real.toRealPath().toString(), record.physicalRoot, "别名与真实路径归一之后是同一块盘")
        }
    }

    /** 库文件是个指向还不存在的目标的链接：不能放行，否则锁加在链接上、SQLite 建在链接指向的地方。 */
    @Test
    @Timeout(60)
    fun `a dangling symbolic link as the state database is rejected`() {
        val link = Files.createSymbolicLink(tempDir.resolve("alias.db"), tempDir.resolve("never-created.db"))

        val failure = assertFailsWith<VfsException> { config(listOf(mount("/resources", "local", disk("a"))), database = link).resolve() }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    /** 目标已经存在的链接照走：路径必须收敛到真实文件，不能停在链接名上。 */
    @Test
    @Timeout(60)
    fun `a symbolic link to an existing database resolves to the target`() {
        val real = Files.createFile(tempDir.resolve("real.db"))
        val link = Files.createSymbolicLink(tempDir.resolve("alias.db"), real)

        val resolved = config(listOf(mount("/resources", "local", disk("a"))), database = link).resolve()

        assertEquals(real.toRealPath(), resolved.databasePath)
        assertEquals(
            real.toRealPath().resolveSibling("real.db" + ".lock"),
            resolved.lockPath,
            "锁文件跟着真实路径走",
        )
    }

    /** 状态库放进挂载目录：那里面的文件能通过 VFS 删掉，独占锁就被绕过了，配置阶段就拒。 */
    @Test
    @Timeout(60)
    fun `a state database inside a mounted root is rejected`() {
        val root = disk("data")

        val failure =
            assertFailsWith<VfsException> {
                config(
                    listOf(mount("/resources", "local", root)),
                    database = root.resolve("state.db"),
                ).resolve()
            }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    /** 状态库放在所有挂载目录之外就是正常用法。 */
    @Test
    @Timeout(60)
    fun `a state database outside every mounted root is accepted`() {
        val resolved = config(listOf(mount("/resources/docs", "docs", disk("docs")))).resolve()

        assertEquals(tempDir.toRealPath().resolve("state.db"), resolved.databasePath)
    }

    /**
     * `/memory` 没挂物理盘，但 stat 出来的虚拟目录已经在库里登记了；下次启动把 memory 从配置里删掉，
     * 那个 Node 就再也寻址不到。这种删除必须拒绝——哪怕这次配置一个挂载都没新增。
     */
    @Test
    @Timeout(60)
    fun `dropping a namespace that still holds a virtual node is refused`() =
        runBlocking {
            val disk = disk("docs")
            val database = tempDir.resolve("state.db")
            val first = config(listOf(mount("/resources", "local", disk)), database, setOf("resources", "memory"))

            withVfs(first) { vfs ->
                val node = vfs.stat(VfsUri.parse("alcyone://memory"))
                assertEquals("/memory", node.uri.path.toString(), "命名空间根就是一个虚拟目录")
            }

            val failure =
                assertFailsWith<VfsException> {
                    AlcyoneVfs.create(config(listOf(mount("/resources", "local", disk)), database, setOf("resources")))
                }

            assertEquals(VfsErrorCode.CONFLICT, failure.code)
        }
}
