package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * T18 A01 的一部分：配置里的非法值必须**在创建任何资源之前**被拒掉。
 *
 * 这里只验证「配置被拒」，不验证错误之前有没有开库——[InitializationFailureTest] 负责那半边。
 */
class VfsRuntimeConfigTest {
    @TempDir
    lateinit var tempDir: Path

    private fun disk(name: String): Path = Files.createDirectory(tempDir.resolve(name))

    private fun config(
        database: Path = tempDir.resolve("state.db"),
        namespaces: Set<String> = setOf("resources"),
        mounts: List<MountConfig> = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk("a"))),
    ) = VfsRuntimeConfig(stateDatabase = database, namespaces = namespaces, mounts = mounts)

    private fun assertRejected(
        config: VfsRuntimeConfig,
        because: String,
    ): VfsException = assertFailsWith<VfsException>(because) { config.resolve() }

    @Test
    fun `a valid configuration resolves to real absolute paths`() {
        val root = disk("docs")
        val resolved = config(mounts = listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", root))).resolve()

        assertEquals(root.toRealPath(), resolved.roots.getValue("docs"), "物理根应当是消解过符号链接的真实目录")
        assertEquals("local-fs", resolved.mounts.single().backendType, "Local FS 的后端类型固定")
        assertTrue(resolved.databasePath.isAbsolute, "状态库路径收敛成绝对路径")
        assertEquals(resolved.databasePath.fileName.toString() + ".lock", resolved.lockPath.fileName.toString())
    }

    /** 同一个目录挂两个逻辑位置是「一块盘挂两次」，不算重叠，不该被拒。 */
    @Test
    fun `two logical mounts on one directory share a single storage`() {
        val root = disk("shared")
        val resolved =
            config(
                mounts =
                    listOf(
                        MountConfig(VfsPath.parse("/resources/a"), "local", root),
                        MountConfig(VfsPath.parse("/resources/b"), "local", root),
                    ),
            ).resolve()

        assertEquals(1, resolved.roots.size, "同一个 key 只开一个存储实例")
        assertEquals(2, resolved.mounts.size, "两个逻辑挂载点都在")
    }

    @Test
    fun `a missing root is rejected before anything is created`() {
        val failure =
            assertRejected(
                config(mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", tempDir.resolve("nope")))),
                because = "根目录不存在",
            )

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code, "根目录必须已经存在")
    }

    @Test
    fun `roots that contain each other are rejected`() {
        val outer = Files.createDirectory(tempDir.resolve("outer"))
        val inner = Files.createDirectory(outer.resolve("inner"))

        val failure =
            assertRejected(
                config(
                    mounts =
                        listOf(
                            MountConfig(VfsPath.parse("/resources"), "a", outer),
                            MountConfig(VfsPath.parse("/resources/x"), "b", inner),
                        ),
                ),
                because = "两个根互相包含",
            )

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun `a mount outside the configured namespaces is rejected`() {
        val failure =
            assertRejected(
                config(mounts = listOf(MountConfig(VfsPath.parse("/memory"), "local", disk("m")))),
                because = "挂载不在已配置的命名空间下",
            )

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun `a blank storage key is rejected`() {
        val failure =
            assertRejected(
                config(mounts = listOf(MountConfig(VfsPath.parse("/resources"), "  ", disk("k")))),
                because = "storageKey 不能是空白",
            )

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    /** 相对路径与符号链接别名必须收敛成同一个真实路径，否则独占锁会被绕过。 */
    @Test
    fun `relative and symlinked database paths collapse to one real path`() {
        val real = Files.createDirectory(tempDir.resolve("real"))
        val alias = Files.createSymbolicLink(tempDir.resolve("alias"), real)
        val viaAlias = alias.resolve("state.db")
        val viaRelative =
            tempDir
                .resolve("real")
                .resolve("sub")
                .resolve("..")
                .resolve("state.db")

        assertEquals(normalizeStateDatabasePath(viaAlias), normalizeStateDatabasePath(viaRelative))
        assertEquals(normalizeStateDatabasePath(real.resolve("state.db")), normalizeStateDatabasePath(viaAlias))
    }
}
