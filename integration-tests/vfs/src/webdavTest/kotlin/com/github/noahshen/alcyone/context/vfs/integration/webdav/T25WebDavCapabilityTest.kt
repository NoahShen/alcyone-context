package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import com.github.noahshen.alcyone.context.vfs.storage.opendal.WebDavStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * T25 A05：能力声明符合实测，不支持的能力明确拒绝；远端拒写不伪装成成功。
 *
 * - **能力表**：对真实服务打开 Adapter，核对 `nativeFileMove = true`、`nativeDirectoryMove = false`、
 *   `createDirectory` / `boundedRead = true`、`readOnly = false`；用真实原生文件移动证明 `nativeFileMove` 不是空报。
 * - **机制层能力不足**：直接对目录调 `Storage.move`，实测返回 `UNSUPPORTED_OPERATION`（后端 rename 目录返回 `IsADirectory`）。
 * - **服务端权限反例**：一块**真实只读**共享（WsgiDAV `readonly: true`）能读、能 stat，写请求被服务端 403 拒绝，
 *   落到 `STORAGE_ACCESS_DENIED`，不伪装成成功；文件与建目录两条写入路径都验。
 *
 * 层级：能力表与目录拒绝在 Storage Adapter 层断言；只读共享经公开 Runtime 断言。
 */
class T25WebDavCapabilityTest {
    @TempDir
    lateinit var tempDir: Path

    private fun db() = tempDir.resolve("state.db")

    private fun remote(path: String) = VfsUri.parse("alcyone://resources/remote$path")

    private fun readOnly(path: String) = VfsUri.parse("alcyone://resources/readonly$path")

    private fun physical(relative: String): Path = WebDavFixture.dataRoot().resolve(relative)

    /** A05：打开真实服务上的 Adapter 核对能力表，并用一次真实原生文件移动证明 `nativeFileMove` 属实。 */
    @Test
    @Timeout(120)
    fun `A05 the capability table matches what the real service actually does`() =
        runBlocking {
            val storage = WebDavStorage.create(WebDavFixture.endpoint, "/", WebDavFixture.user, WebDavFixture.password)
            try {
                val caps = storage.capabilities()
                assertTrue(caps.nativeFileMove, "实测 webdav rename 可用，nativeFileMove 如实报 true")
                assertFalse(caps.nativeDirectoryMove, "实测 rename 目录返回 IsADirectory，nativeDirectoryMove 必须报 false")
                assertTrue(caps.createDirectory, "MKCOL 最小闭环验过")
                assertTrue(caps.boundedRead, "有界流读验过")
                assertFalse(caps.readOnly, "只读与否由服务端账号决定，打开时猜不出来")

                // 真实原生文件移动：写一个文件，storage.move 后目标在、源消失（证明 nativeFileMove 不是空报）。
                val source = StoragePath.parse("t25-cap/native-source.txt")
                val target = StoragePath.parse("t25-cap/native-target.txt")
                storage.createDirectory(StoragePath.parse("t25-cap"))
                storage.write(source, "native-move".toByteArray(), StorageWriteMode.CREATE_NEW)
                storage.move(source, target)
                assertTrue(Files.exists(physical("t25-cap/native-target.txt")), "原生移动后目标物理文件在")
                assertFalse(Files.exists(physical("t25-cap/native-source.txt")), "原生移动后源物理文件消失")

                // 机制层：目录移动明确拒绝，不伪装成功。
                storage.createDirectory(StoragePath.parse("t25-cap/a-dir"))
                val directoryMove =
                    assertFailsWith<VfsException> {
                        storage.move(StoragePath.parse("t25-cap/a-dir"), StoragePath.parse("t25-cap/a-dir-moved"))
                    }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, directoryMove.code, "目录移动在下发前就拒绝")
                assertTrue(Files.exists(physical("t25-cap/a-dir")), "拒绝后源目录没动")

                // 收尾：删掉远端测试目录（非空，递归删）。
                storage.delete(StoragePath.parse("t25-cap"), recursive = true)
                assertFalse(Files.exists(physical("t25-cap")), "收尾后远端目录不在")
            } finally {
                storage.close()
            }
        }

    /**
     * A05：真实只读服务——能读、能 stat，写文件与建目录都被服务端 403 拒绝，落到
     * `STORAGE_ACCESS_DENIED`，不伪装成功；失败后远端物理内容没有新增。
     */
    @Test
    @Timeout(120)
    fun `A05 a real read only share reads but refuses every write without pretending success`() =
        runBlocking {
            val config =
                VfsRuntimeConfig(
                    stateDatabase = db(),
                    namespaces = setOf("resources"),
                    mounts = listOf(WebDavFixture.readOnlyMount()),
                )
            WebDavFixture.withVfs(config) { vfs ->
                assertArrayEquals(
                    "read-only fixture content".toByteArray(),
                    vfs.read(readOnly("/preset.txt")),
                    "只读共享里的文件能读",
                )
                assertEquals(NodeType.FILE, vfs.stat(readOnly("/preset.txt")).type, "只读共享里的文件能 stat")

                // 写新文件：服务端 403，落到 STORAGE_ACCESS_DENIED。
                val refusedWrite =
                    assertFailsWith<VfsException> {
                        vfs.write(readOnly("/new-file.txt"), "should not land".toByteArray())
                    }
                assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, refusedWrite.code, "只读共享写文件被拒")

                // 在只读共享下建目录再写：同样被拒，建目录路径也走不通。
                val refusedDir =
                    assertFailsWith<VfsException> {
                        vfs.write(readOnly("/new-dir/inner.txt"), "should not land".toByteArray())
                    }
                assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, refusedDir.code, "只读共享建目录被拒")

                // 拒绝后远端物理内容没有任何新增。
                val root = WebDavFixture.readOnlyRoot()
                assertFalse(Files.exists(root.resolve("new-file.txt")), "拒绝后远端没有新文件")
                assertFalse(Files.exists(root.resolve("new-dir")), "拒绝后远端没有新目录")
                assertArrayEquals(
                    "read-only fixture content".toByteArray(),
                    Files.readAllBytes(root.resolve("preset.txt")),
                    "预置文件内容未变",
                )
            }
        }
}
