package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
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
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T22 公开入口上的**目录移动**闭环：真 Runtime + 真 SQLite + 多个不重叠的本机目录。
 *
 * 覆盖：同 Mount 复制回退（Local FS 的 `nativeDirectoryMove` 恒为 false）、跨 Mount 复制、
 * 空目录保留、根与已登记子 Node 身份连续、只发一条根级 `DIRECTORY_MOVED`、正常重开；
 * 以及普通祖先目录含子 Mount 的结构拒绝。事件字段读真实 event 表，不拿目录列表当事件。
 */
class RuntimeDirectoryMoveTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var local: Path
    private lateinit var archive: Path
    private lateinit var inner: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        local = Files.createDirectory(tempDir.resolve("local"))
        archive = Files.createDirectory(tempDir.resolve("archive"))
        // 第三块盘的物理根放在 /resources/work/inner 挂载点下，用来造「普通祖先目录承载子 Mount」。
        inner = Files.createDirectories(tempDir.resolve("work/inner"))
    }

    private fun config() =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts =
                listOf(
                    MountConfig(VfsPath.parse("/resources/local"), "local", local),
                    MountConfig(VfsPath.parse("/resources/archive"), "archive", archive),
                    MountConfig(VfsPath.parse("/resources/work/inner"), "inner", inner),
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

    data class Identity(
        val rootId: NodeId,
        val childId: NodeId,
        val childRegisteredAt: Instant,
        val tag: NodeMetadata,
    )

    /**
     * A01 / A02 / A05：跨 Mount 目录移动——整棵树搬过去，空目录保留，根与已登记子 Node 身份连续，
     * 只留一条根级事件，重开后身份 / Metadata / registeredAt 都稳定。
     */
    @Test
    @Timeout(60)
    fun `a directory crosses mounts with its tree identity and one root event`() =
        runBlocking {
            prepare()
            val tag = NodeMetadata(setOf("ct"), "胸部 CT")
            val identity =
                withVfs { vfs ->
                    // 源树：work/{a.txt, images/b.png, drafts/}，其中 drafts 是空目录。
                    Files.createDirectories(local.resolve("work/images"))
                    Files.createDirectories(local.resolve("work/drafts"))
                    Files.writeString(local.resolve("work/a.txt"), "A")
                    Files.writeString(local.resolve("work/images/b.png"), "B")

                    // 物理预置文件先 stat 一次，让它成为已登记子 Node 并带上 Metadata。
                    val a = vfs.stat(VfsUri.parse("alcyone://resources/local/work/a.txt"))
                    vfs.setMetadata(a.id, tag)
                    val childRegisteredAt = vfs.getNode(a.id).registeredAt

                    val moved =
                        vfs.move(VfsUri.parse("alcyone://resources/local/work"), VfsUri.parse("alcyone://resources/archive/work"))

                    assertNotEquals(a.id, moved.id, "根是懒注册的新身份（只 stat 过子文件）")
                    assertEquals(NodeType.DIRECTORY, moved.type)
                    assertEquals("A", Files.readString(archive.resolve("work/a.txt")), "嵌套文件内容搬过去")
                    assertEquals("B", Files.readString(archive.resolve("work/images/b.png")), "更深一层也在")
                    assertTrue(Files.isDirectory(archive.resolve("work/drafts")), "空子目录被显式建出来")
                    assertFalse(Files.exists(local.resolve("work")), "源树整棵搬走")
                    assertEquals(tag, vfs.getMetadata(a.id), "子 Node 的 Metadata 原样保留")
                    assertEquals(
                        "/resources/archive/work/a.txt",
                        vfs
                            .getNode(a.id)
                            .uri.path
                            .toString(),
                        "子 Node 的路径迁到目标前缀，ID 不变",
                    )

                    // 真实 event 表：只有一条 DIRECTORY_MOVED，字段是根级源 / 目标；没有任何子项事件。
                    val moveEvents = eventRows().filter { it[0] == "DIRECTORY_MOVED" }
                    assertEquals(1, moveEvents.size, "一次目录移动恰好一条 DIRECTORY_MOVED：${eventRows()}")
                    assertEquals(moved.id.value, moveEvents.single()[1], "nodeId 是源根身份")
                    assertEquals("alcyone://resources/archive/work", moveEvents.single()[2], "uri 是目标根")
                    assertEquals("alcyone://resources/local/work", moveEvents.single()[3], "sourceUri 是源根")
                    assertEquals("alcyone://resources/archive/work", moveEvents.single()[4], "targetUri 是目标根")
                    assertTrue(
                        eventRows().none { it[0] == "FILE_CREATED" || it[0] == "FILE_MOVED" || it[0] == "DIRECTORY_CREATED" },
                        "不为复制出的文件 / 子项发事件：${eventRows()}",
                    )
                    Identity(moved.id, a.id, childRegisteredAt, tag)
                }
            // 重开：直接按 ID 查身份与 Metadata，而不是只列目录。
            withVfs { vfs ->
                assertEquals(identity.rootId, vfs.getNode(identity.rootId).id, "重开后根 ID 有效")
                assertEquals(
                    "/resources/archive/work",
                    vfs
                        .getNode(identity.rootId)
                        .uri.path
                        .toString(),
                )
                assertEquals(identity.childId, vfs.getNode(identity.childId).id, "重开后子 ID 有效")
                assertEquals(
                    "/resources/archive/work/a.txt",
                    vfs
                        .getNode(identity.childId)
                        .uri.path
                        .toString(),
                )
                assertEquals(identity.childRegisteredAt, vfs.getNode(identity.childId).registeredAt, "registeredAt 不变")
                assertEquals(identity.tag, vfs.getMetadata(identity.childId), "重开后 Metadata 还在")
                assertEquals("A", Files.readString(archive.resolve("work/a.txt")))
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

    /**
     * A03：源是承载子 Mount 的**普通祖先目录**（不是挂载根）时整个拒绝，源树与后端都不动。
     */
    @Test
    @Timeout(60)
    fun `a plain ancestor that contains another mount is refused`() =
        runBlocking {
            prepare()
            withVfs { vfs ->
                Files.createDirectories(tempDir.resolve("work/plain"))
                Files.writeString(tempDir.resolve("work/plain/a.txt"), "A")
                // /resources/work 不是挂载根，而是子挂载 /resources/work/inner 的祖先。
                val failure =
                    assertFailsWith<VfsException> {
                        vfs.move(
                            VfsUri.parse("alcyone://resources/work"),
                            VfsUri.parse("alcyone://resources/archive/moved"),
                        )
                    }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code, "承载子挂载的普通祖先不能移动")
                assertTrue(Files.exists(tempDir.resolve("work/plain/a.txt")), "源树没动")
            }
        }

    /** 只读查库，确认持久化事实；不替 SDK 写数据。 */
    private fun query(sql: String): List<Array<String?>> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        val columns = rows.metaData.columnCount
                        while (rows.next()) add(Array(columns) { rows.getString(it + 1) })
                    }
                }
            }
        }

    /** 一条事件取 `event_type / node_id / uri / source_uri / target_uri`。 */
    private fun eventRows(): List<List<String?>> =
        query("SELECT event_type, node_id, uri, source_uri, target_uri FROM event ORDER BY rowid").map { it.toList() }
}
