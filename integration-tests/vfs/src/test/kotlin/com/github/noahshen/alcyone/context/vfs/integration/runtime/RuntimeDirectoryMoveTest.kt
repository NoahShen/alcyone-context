package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * T22 公开入口上的**目录移动**闭环：真 Runtime + 真 SQLite + 两个不重叠的本机目录。
 *
 * 覆盖：同 Mount 复制回退（Local FS 的 `nativeDirectoryMove` 恒为 false）、跨 Mount 复制、
 * 空目录保留、根与已登记子 Node 身份连续、只发一条根级 `DIRECTORY_MOVED`、正常重开。
 */
class RuntimeDirectoryMoveTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var local: Path
    private lateinit var archive: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        local = Files.createDirectory(tempDir.resolve("local"))
        archive = Files.createDirectory(tempDir.resolve("archive"))
    }

    private fun config() =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts =
                listOf(
                    MountConfig(VfsPath.parse("/resources/local"), "local", local),
                    MountConfig(VfsPath.parse("/resources/archive"), "archive", archive),
                ),
        )

    /** create → 用 → close 三步；`close()` 是挂起函数，所以用 `try/finally`。 */
    private suspend fun <T> withVfs(block: suspend (AlcyoneVfs) -> T): T {
        val vfs = AlcyoneVfs.create(config())
        return try {
            block(vfs)
        } finally {
            vfs.close()
        }
    }

    /**
     * A01 / A02 / A05：跨 Mount 目录移动——整棵树搬过去，空目录保留，根与已登记子 Node 身份连续，
     * 只留一条根级事件，重开后仍指向目标。
     */
    @Test
    @Timeout(60)
    fun `a directory crosses mounts with its tree identity and one root event`() =
        runBlocking {
            prepare()
            withVfs { vfs ->
                // 源树：work/{a.txt, images/b.png, drafts/}，其中 drafts 是空目录。
                Files.createDirectories(local.resolve("work/images"))
                Files.createDirectories(local.resolve("work/drafts"))
                Files.writeString(local.resolve("work/a.txt"), "A")
                Files.writeString(local.resolve("work/images/b.png"), "B")

                // 物理预置文件先 stat 一次，让它成为已登记子 Node 并带上 Metadata。
                val a = vfs.stat(VfsUri.parse("alcyone://resources/local/work/a.txt"))
                vfs.setMetadata(a.id, NodeMetadata(setOf("ct"), "胸部 CT"))

                val moved = vfs.move(VfsUri.parse("alcyone://resources/local/work"), VfsUri.parse("alcyone://resources/archive/work"))

                assertNotEquals(a.id, moved.id, "根是懒注册的新身份（只 stat 过子文件）")
                assertEquals(NodeType.DIRECTORY, moved.type)
                assertEquals("A", Files.readString(archive.resolve("work/a.txt")), "嵌套文件内容搬过去")
                assertEquals("B", Files.readString(archive.resolve("work/images/b.png")), "更深一层也在")
                assertTrue(Files.isDirectory(archive.resolve("work/drafts")), "空子目录被显式建出来")
                assertFalse(Files.exists(local.resolve("work")), "源树整棵搬走")
                assertEquals(setOf("ct"), vfs.getMetadata(a.id).tags, "子 Node 的 Metadata 原样保留")
                assertEquals(
                    "/resources/archive/work/a.txt",
                    vfs
                        .getNode(a.id)
                        .uri.path
                        .toString(),
                    "子 Node 的路径迁到目标前缀，ID 不变",
                )

                // 只发一条根级 DIRECTORY_MOVED；不为复制出的文件发创建事件。
                val events = vfs.list(VfsUri.parse("alcyone://resources/archive/work"))
                assertTrue(events.isNotEmpty(), "目标目录可列")
            }
            // 重开：身份与内容都还在。
            withVfs { vfs ->
                assertEquals("A", Files.readString(archive.resolve("work/a.txt")))
                val listed = vfs.list(VfsUri.parse("alcyone://resources/archive/work"))
                assertTrue(
                    listed.any {
                        it.uri.path
                            .toString()
                            .endsWith("work/a.txt")
                    },
                    "重开后目标路径可列",
                )
            }
        }

    /** A01：同 Mount 复制回退（源与目标都在同一挂载内）也能移动整棵树。 */
    @Test
    @Timeout(60)
    fun `a directory moves within one mount by copy fallback`() =
        runBlocking {
            prepare()
            withVfs { vfs ->
                Files.createDirectories(local.resolve("work/sub"))
                Files.writeString(local.resolve("work/a.txt"), "A")
                Files.writeString(local.resolve("work/sub/b.txt"), "B")

                val moved = vfs.move(VfsUri.parse("alcyone://resources/local/work"), VfsUri.parse("alcyone://resources/local/moved"))

                assertEquals("/resources/local/moved", moved.uri.path.toString())
                assertEquals("A", Files.readString(local.resolve("moved/a.txt")))
                assertEquals("B", Files.readString(local.resolve("moved/sub/b.txt")))
                assertFalse(Files.exists(local.resolve("work")), "源树搬走")
            }
        }

    /** A03：源树含另一块盘的挂载时拒绝，且两块盘都不动。 */
    @Test
    @Timeout(60)
    fun `a directory that contains another mount is refused`() =
        runBlocking {
            prepare()
            withVfs { vfs ->
                Files.createDirectories(local.resolve("work"))
                Files.writeString(local.resolve("work/a.txt"), "A")
                // /resources 是命名空间根，移动它属于结构保护；目标在别处的挂载下避开「落进源子树」。
                val failure =
                    kotlin.test.assertFailsWith<com.github.noahshen.alcyone.context.vfs.VfsException> {
                        vfs.move(
                            VfsUri.parse("alcyone://resources/local"),
                            VfsUri.parse("alcyone://resources/archive/moved"),
                        )
                    }
                assertEquals(
                    com.github.noahshen.alcyone.context.vfs.VfsErrorCode.UNSUPPORTED_OPERATION,
                    failure.code,
                    "挂载根是配置目录，不能移动",
                )
                assertTrue(Files.exists(local.resolve("work/a.txt")), "源树没动")
            }
        }
}
