package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
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
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T25 A03 / A04：公开 Runtime 上的**移动**——同 WebDAV Mount 与 Local FS ↔ WebDAV 双向文件 / 目录。
 *
 * A03：同 WebDAV Mount 的文件移动走经验证的原生 rename；跨后端文件移动走 Core 复制删除。目标不覆盖；
 * 本地文件身份（Node ID / Metadata）保留，旧 ID 指向新路径，源在物理上消失。
 * A04：目录移动双向都保留空目录与已登记子树；沿用整树确认后删源、只发一条根级 DIRECTORY_MOVED 的规则，
 * 不为复制出的未登记后代批量登记或发事件。代表用例直接查真 event 表并在正常重开后比对身份。
 *
 * 断言对着服务端物理文件、真实 SQLite 与公开 API 的返回，不用替身。
 */
class T25WebDavMoveTest {
    @TempDir
    lateinit var tempDir: Path

    private fun db() = tempDir.resolve("state.db")

    private fun remote(path: String) = VfsUri.parse("alcyone://resources/remote$path")

    private fun local(path: String) = VfsUri.parse("alcyone://resources/local$path")

    private fun physical(relative: String): Path = WebDavFixture.dataRoot().resolve(relative)

    // ------------------------------------------------------------------ A03 文件移动

    /**
     * A03：同 WebDAV Mount 文件移动（原生 rename）+ 跨后端双向。
     *
     * 本地文件保留 Node ID / Metadata，跨到远端后新身份指向新路径；源物理消失、内容一致；目标已存在反例拒绝。
     */
    @Test
    @Timeout(180)
    fun `A03 a file moves natively within the webdav mount and across backends in both directions`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            val database = db()
            val tag = NodeMetadata(setOf("t25"), "移动保留 Metadata")
            var localFileId: NodeId? = null

            WebDavFixture.withVfs(WebDavFixture.crossBackendConfig(database, localRoot)) { vfs ->
                // 1) 同 WebDAV Mount 原生文件移动：内容与身份都在，源物理消失。
                vfs.write(remote("/a03/within.txt"), "within".toByteArray())
                val within = vfs.move(remote("/a03/within.txt"), remote("/a03/within-moved.txt"))
                assertEquals(NodeType.FILE, within.type)
                assertArrayEquals("within".toByteArray(), withTimeout30k { vfs.read(remote("/a03/within-moved.txt")) })
                assertTrue(Files.exists(physical("a03/within-moved.txt")), "原生移动后目标物理文件在服务端")
                assertFalse(Files.exists(physical("a03/within.txt")), "原生移动后源物理文件消失")
                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> { vfs.read(remote("/a03/within.txt")) }.code,
                    "旧路径读回来是 NOT_FOUND",
                )

                // 2) Local FS → WebDAV 文件移动：Node ID / Metadata 保留，旧 ID 指向新路径。
                val localFile = Files.writeString(localRoot.resolve("a03-local.txt"), "local-content")
                val info = vfs.stat(local("/" + localFile.fileName.toString()), StatOptions(includeStorage = true))
                vfs.setMetadata(info.id, tag)
                val registeredAt = vfs.getNode(info.id).registeredAt

                val moved = vfs.move(local("/a03-local.txt"), remote("/a03/from-local.txt"))
                assertEquals(NodeType.FILE, moved.type)
                assertEquals(info.id, moved.id, "文件移动保留 Node ID（旧 ID 指向新路径）")
                assertFalse(Files.exists(localRoot.resolve("a03-local.txt")), "本地源物理消失")
                assertArrayEquals("local-content".toByteArray(), withTimeout30k { vfs.read(remote("/a03/from-local.txt")) })

                // 已登记的本地 Node：身份、Metadata、registeredAt 保留，路径迁到远端前缀。
                assertEquals(info.id, vfs.getNode(info.id).id, "已登记文件移动后 Node ID 不变")
                assertEquals(
                    "/resources/remote/a03/from-local.txt",
                    vfs
                        .getNode(info.id)
                        .uri.path
                        .toString(),
                )
                assertEquals(tag, vfs.getMetadata(info.id), "Metadata 原样保留")
                assertEquals(registeredAt, vfs.getNode(info.id).registeredAt, "registeredAt 不变")
                localFileId = info.id

                // 3) WebDAV → Local FS 文件移动：源物理消失，落到本地盘；保留 Node ID / Metadata。
                vfs.write(remote("/a03/to-local.txt"), "remote-content".toByteArray())
                val remoteInfo = vfs.stat(remote("/a03/to-local.txt"))
                val remoteTag = NodeMetadata(setOf("t25-remote"), "远端回迁保留 Metadata")
                vfs.setMetadata(remoteInfo.id, remoteTag)
                val remoteRegisteredAt = vfs.getNode(remoteInfo.id).registeredAt

                val back = vfs.move(remote("/a03/to-local.txt"), local("/a03-from-remote.txt"))
                assertEquals(NodeType.FILE, back.type)
                assertEquals(remoteInfo.id, back.id, "反向移动保留 Node ID")
                assertEquals("remote-content", Files.readString(localRoot.resolve("a03-from-remote.txt")), "内容落到本地")
                assertFalse(Files.exists(physical("a03/to-local.txt")), "远端源物理消失")
                assertEquals(remoteInfo.id, vfs.getNode(remoteInfo.id).id, "已登记节点 ID 保持不变")
                assertEquals(
                    "/resources/local/a03-from-remote.txt",
                    vfs
                        .getNode(remoteInfo.id)
                        .uri.path
                        .toString(),
                    "路径迁到本地",
                )
                assertEquals(remoteTag, vfs.getMetadata(remoteInfo.id), "反向移动 Metadata 保留")
                assertEquals(remoteRegisteredAt, vfs.getNode(remoteInfo.id).registeredAt, "registeredAt 不变")

                // 4) 目标已存在：文件移动拒绝，源与目标都不动。
                vfs.write(remote("/a03/one.txt"), "one".toByteArray())
                vfs.write(remote("/a03/two.txt"), "two".toByteArray())
                val refuse = assertFailsWith<VfsException> { vfs.move(remote("/a03/one.txt"), remote("/a03/two.txt")) }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, refuse.code, "目标已存在要拒")
                assertArrayEquals("one".toByteArray(), withTimeout30k { vfs.read(remote("/a03/one.txt")) }, "源不动")
                assertArrayEquals("two".toByteArray(), withTimeout30k { vfs.read(remote("/a03/two.txt")) }, "目标不覆盖")
            }

            // 重开：已登记文件 ID 仍有效，Metadata 还在。
            WebDavFixture.withVfs(WebDavFixture.crossBackendConfig(database, localRoot)) { vfs ->
                val id = localFileId!!
                assertEquals(id, vfs.getNode(id).id, "重开后已登记文件 ID 有效")
                assertEquals(tag, vfs.getMetadata(id), "重开后 Metadata 还在")
            }
        }

    // ------------------------------------------------------------------ A04 目录移动

    /**
     * A04：Local FS → WebDAV 目录移动——空目录保留、已登记后代身份不变、未登记文件被搬运但不登记；
     * 只发一条根级 DIRECTORY_MOVED；代表用例正常重开比对身份。
     */
    @Test
    @Timeout(180)
    fun `A04 a directory moves from local to webdav keeping its tree and emitting one root event`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            val database = db()
            val tag = NodeMetadata(setOf("d"), "目录内已登记文件")

            // 源树：notes/{a.txt, empty/, sub/b.txt}
            Files.createDirectories(localRoot.resolve("notes/empty"))
            Files.createDirectories(localRoot.resolve("notes/sub"))
            Files.writeString(localRoot.resolve("notes/a.txt"), "A")
            Files.writeString(localRoot.resolve("notes/sub/b.txt"), "B")

            data class Identity(
                val rootId: NodeId,
                val childId: NodeId,
                val childRegisteredAt: Instant,
            )

            val identity =
                WebDavFixture.withVfs(WebDavFixture.crossBackendConfig(database, localRoot)) { vfs ->
                    // 先 stat 一下 a.txt，让它成为已登记后代（b.txt 保持未登记）。
                    val a = vfs.stat(local("/notes/a.txt"))
                    vfs.setMetadata(a.id, tag)
                    val childRegisteredAt = vfs.getNode(a.id).registeredAt
                    val nodesBefore = activeNodes(database)

                    val moved = vfs.move(local("/notes"), remote("/notes"))
                    assertEquals(NodeType.DIRECTORY, moved.type)

                    // 树整棵搬过去：嵌套文件与空目录都在。
                    assertEquals("A", Files.readString(physical("notes/a.txt")), "浅层文件搬过去")
                    assertEquals("B", Files.readString(physical("notes/sub/b.txt")), "深层文件搬过去")
                    assertTrue(Files.isDirectory(physical("notes/empty")), "空目录被显式建出来")
                    assertFalse(Files.exists(localRoot.resolve("notes")), "源树整棵搬走")

                    // 已登记后代：身份、Metadata、registeredAt 保留，路径迁到远端前缀。
                    assertEquals(a.id, vfs.getNode(a.id).id, "已登记后代 ID 不变")
                    assertEquals(
                        "/resources/remote/notes/a.txt",
                        vfs
                            .getNode(a.id)
                            .uri.path
                            .toString(),
                    )
                    assertEquals(tag, vfs.getMetadata(a.id), "Metadata 保留")
                    assertEquals(childRegisteredAt, vfs.getNode(a.id).registeredAt, "registeredAt 不变")

                    // 只发一条根级 DIRECTORY_MOVED，字段是根级源 / 目标；没有为子项批量发事件，也没批量登记。
                    val moveEvents = WebDavFixture.eventRows(database).filter { it[0] == "DIRECTORY_MOVED" }
                    assertEquals(1, moveEvents.size, "一次目录移动恰好一条 DIRECTORY_MOVED：${WebDavFixture.eventRows(database)}")
                    assertEquals("alcyone://resources/local/notes", moveEvents.single()[3], "sourceUri 是源根")
                    assertEquals("alcyone://resources/remote/notes", moveEvents.single()[4], "targetUri 是目标根")
                    assertTrue(
                        WebDavFixture.eventRows(database).none {
                            it[0] == "FILE_CREATED" ||
                                it[0] == "FILE_MOVED" ||
                                it[0] == "DIRECTORY_CREATED"
                        },
                        "不为复制出的文件 / 子项发事件：${WebDavFixture.eventRows(database)}",
                    )
                    // 未登记的 b.txt 不会被批量登记：活跃 Node 只多了目录根一个（懒注册）。
                    assertEquals(nodesBefore + 1, activeNodes(database), "只为根级移动登记一个目录 Node，不批量登记未登记后代")

                    Identity(moved.id, a.id, childRegisteredAt)
                }

            // 代表用例正常重开：根 ID 与已登记后代身份 / Metadata / registeredAt 都稳定。
            WebDavFixture.withVfs(WebDavFixture.crossBackendConfig(database, localRoot)) { vfs ->
                assertEquals(identity.rootId, vfs.getNode(identity.rootId).id, "重开后目录根 ID 有效")
                assertEquals(
                    "/resources/remote/notes",
                    vfs
                        .getNode(identity.rootId)
                        .uri.path
                        .toString(),
                )
                assertEquals(identity.childId, vfs.getNode(identity.childId).id, "重开后已登记后代 ID 有效")
                assertEquals(identity.childRegisteredAt, vfs.getNode(identity.childId).registeredAt, "registeredAt 不变")
                assertEquals(tag, vfs.getMetadata(identity.childId), "重开后 Metadata 还在")
            }
        }

    /** A04：WebDAV → Local FS 目录移动——空目录与子树都落到本地盘，源树在远端消失。 */
    @Test
    @Timeout(180)
    fun `A04 a directory moves from webdav to local with its empty subtree and source removal`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            val database = db()

            WebDavFixture.withVfs(WebDavFixture.crossBackendConfig(database, localRoot)) { vfs ->
                // 远端源树：docs/{readme.txt, empty/, nested/deep.txt}
                vfs.write(remote("/docs/readme.txt"), "R".toByteArray())
                vfs.write(remote("/docs/nested/deep.txt"), "D".toByteArray())
                vfs.write(remote("/docs/empty/placeholder.txt"), "P".toByteArray())
                vfs.delete(remote("/docs/empty/placeholder.txt"))
                assertEquals(NodeType.DIRECTORY, vfs.stat(remote("/docs/empty")).type, "远端空目录存在")

                val remoteChild = vfs.stat(remote("/docs/readme.txt"))
                val remoteTag = NodeMetadata(setOf("remote-child"), "远端子文件元数据")
                vfs.setMetadata(remoteChild.id, remoteTag)
                val remoteChildRegisteredAt = vfs.getNode(remoteChild.id).registeredAt

                val moved = vfs.move(remote("/docs"), local("/docs"))
                assertEquals(NodeType.DIRECTORY, moved.type)

                assertEquals("R", Files.readString(localRoot.resolve("docs/readme.txt")), "浅层文件落到本地")
                assertEquals("D", Files.readString(localRoot.resolve("docs/nested/deep.txt")), "深层文件落到本地")
                assertTrue(Files.isDirectory(localRoot.resolve("docs/empty")), "空目录被显式建出来")
                assertFalse(Files.exists(physical("docs")), "远端源树整棵消失")

                // 比对已登记子项身份与元数据
                assertEquals(remoteChild.id, vfs.getNode(remoteChild.id).id, "反向目录移动已登记子项 ID 保留")
                assertEquals(
                    "/resources/local/docs/readme.txt",
                    vfs
                        .getNode(remoteChild.id)
                        .uri.path
                        .toString(),
                )
                assertEquals(remoteTag, vfs.getMetadata(remoteChild.id), "Metadata 保留")
                assertEquals(remoteChildRegisteredAt, vfs.getNode(remoteChild.id).registeredAt, "registeredAt 保留")

                val moveEvents = WebDavFixture.eventRows(database).filter { it[0] == "DIRECTORY_MOVED" }
                assertEquals(1, moveEvents.size, "只发一条根级 DIRECTORY_MOVED")
                val rootMoveEvent = moveEvents.single()
                assertEquals(moved.id.toString(), rootMoveEvent[1], "DIRECTORY_MOVED 关联根节点 ID")
                assertEquals("alcyone://resources/local/docs", rootMoveEvent[2], "DIRECTORY_MOVED 的 uri 为目标根")
                assertEquals("alcyone://resources/remote/docs", rootMoveEvent[3], "sourceUri 是远端源根")
                assertEquals("alcyone://resources/local/docs", rootMoveEvent[4], "targetUri 是本地目标根")

                // 收尾清理：删掉搬到本地的目录树。
                vfs.delete(local("/docs"), DeleteOptions(recursive = true))
            }
        }

    private suspend fun <T> withTimeout30k(block: suspend () -> T): T = kotlinx.coroutines.withTimeout(30_000) { block() }

    private fun activeNodes(database: Path): Int =
        WebDavFixture.query(database, "SELECT COUNT(*) FROM node WHERE deleted_at IS NULL").single().toInt()
}
