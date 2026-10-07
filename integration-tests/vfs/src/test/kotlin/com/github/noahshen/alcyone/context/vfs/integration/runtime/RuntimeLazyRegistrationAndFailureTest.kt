package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/**
 * T19 A02 / A06：公开入口上的懒注册闭环与失败反例。
 *
 * A02 证明「读 / 列不注册、stat 才给身份」在**公开入口**上成立，并确认读取不产生变更事件；
 * 这与 T13 / T18 在 Registry 层 / Core 层的用例区分开：这里宿主只提交配置，用的是
 * [AlcyoneVfs] 这一层的真实栈结果与状态库事实。
 *
 * A06 是少量参数 / 结构反例：缺失 `NOT_FOUND`、`CREATE_NEW` 冲突 `ALREADY_EXISTS`、
 * 受保护挂载目录变更被结构保护拒 `UNSUPPORTED_OPERATION`、`move` 阶段没交付也 `UNSUPPORTED_OPERATION`；
 * 每次都核对失败前后内容与有效状态没被意外改动。
 *
 * 只读 SQL 只用来确认持久化事实，不替 SDK 写数据。
 */
class RuntimeLazyRegistrationAndFailureTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("docs"))
    }

    private fun config() =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", disk)),
        )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources/docs$path")

    /**
     * A02：物理预置文件在 `read` / `list` 之后仍未注册，`stat` 之后才拿到稳定 ID 并可按 ID 查询；
     * 读取过程不产生任何变更事件。
     */
    @Test
    @Timeout(60)
    fun `a physically preset file is not registered by read or list and only stat gives it an identity`() =
        runBlocking {
            prepare()
            // 绕过 VFS 直接把文件放到临时磁盘上：它没有身份，内容却真的在。
            Files.writeString(disk.resolve("external.txt"), "outside")
            Files.createDirectory(disk.resolve("nested"))
            Files.writeString(disk.resolve("nested/inner.txt"), "inner")

            withVfs(config()) { vfs ->
                // 先把事件通路证明是活的：写一个种子文件，它的 FILE_CREATED 会进事件日志。
                vfs.write(uri("/seed.txt"), "seed".toByteArray())
                val eventsAfterSeed = eventRows()

                // read 只读内容，不注册、不发事件。
                assertEquals("outside", vfs.read(uri("/external.txt")).toString(Charsets.UTF_8), "没登记也读得到")
                assertNull(findByPath("/resources/docs/external.txt"), "read 不登记")

                // list 把未登记项列为 nodeId == null；目录内的 inner 一样没身份。
                val listed = vfs.list(VfsUri.parse("alcyone://resources/docs"))
                assertTrue(
                    listed.any { it.uri.path.toString() == "/resources/docs/external.txt" && it.nodeId == null },
                    "list 的未登记项 nodeId 为空：${listed.map { it.uri.path to it.nodeId }}",
                )
                assertTrue(
                    listed.any { it.uri.path.toString() == "/resources/docs/seed.txt" && it.nodeId != null },
                    "写过的那条才带着身份",
                )
                assertNull(findByPath("/resources/docs/external.txt"), "list 也不登记")

                // 不过 nested/ 目录本身没登记，inner 也没身份：列它的父级时 inner 同样 nodeId 为空。
                assertNull(findByPath("/resources/docs/nested/inner.txt"), "预置的 nested/inner.txt 没身份")

                // 读到这儿为止，事件日志没有多出任何一行（读取是只读的，提交是同步的，直接比行数即可）。
                assertEquals(eventsAfterSeed, eventRows(), "read / list 不产生变更事件")
                assertEquals(0, countActive("/resources/docs/external.txt"), "读取没在状态库留下有效记录")

                // stat 才懒注册：拿到稳定 ID，getNode 可按 ID 查询；再 stat 身份不变。
                val first = vfs.stat(uri("/external.txt"))
                assertEquals(NodeType.FILE, first.type)
                assertEquals(first.id, vfs.getNode(first.id).id, "stat 之后可按 ID 查询")
                assertEquals(first.id, vfs.stat(uri("/external.txt")).id, "再 stat 还是同一个身份")
                assertEquals(first.id, findByPath("/resources/docs/external.txt"), "状态库里登记了这条")

                // 注册本身也不发变更事件（T13 语义：懒注册不公开事件）。
                assertEquals(eventsAfterSeed, eventRows(), "stat 懒注册不追加变更事件")
            }
        }

    /**
     * A06：四类公开入口反例。失败前后物理内容与有效状态都不被意外改变，错误码与 effect 按契约。
     */
    @Test
    @Timeout(60)
    fun `public entry refusals use exact codes and leave facts unchanged`() =
        runBlocking {
            prepare()

            withVfs(config()) { vfs ->
                val info = vfs.write(uri("/a.txt"), "hello".toByteArray())
                vfs.setMetadata(info.id, NodeMetadata(setOf("ct"), "胸部 CT"))
                val before = snapshot()

                // 1) 缺失文件：read / delete / stat 都是 NOT_FOUND，且 effect 为 NONE。
                val missingRead = assertFailsWith<VfsException> { vfs.read(uri("/missing.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missingRead.code)
                val missingDelete = assertFailsWith<VfsException> { vfs.delete(uri("/missing.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missingDelete.code)
                assertEquals(VfsEffect.NONE, missingDelete.effect, "删不存在的目标一个字节都没动")

                // 2) CREATE_NEW 冲突：目标已存在，ALREADY_EXISTS，内容不被改写。
                val conflict =
                    assertFailsWith<VfsException> {
                        vfs.write(uri("/a.txt"), "changed".toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
                    }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, conflict.code)
                assertEquals("hello", Files.readString(disk.resolve("a.txt")), "CREATE_NEW 冲突不改磁盘内容")

                // 3) 受保护挂载目录：写 / 删挂载根都被结构保护拒。
                val writeRoot = assertFailsWith<VfsException> { vfs.write(VfsUri.parse("alcyone://resources/docs"), "x".toByteArray()) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, writeRoot.code, "挂载根是配置目录，写不了")
                val deleteRoot = assertFailsWith<VfsException> { vfs.delete(VfsUri.parse("alcyone://resources/docs")) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, deleteRoot.code, "挂载根不能删")
                assertEquals(VfsEffect.NONE, deleteRoot.effect, "结构保护拒绝零副作用")

                // 4) move 阶段没交付：明确 UNSUPPORTED_OPERATION，源和目标都不动。
                val moved = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/b.txt")) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, moved.code, "move 属于 T20～T22")
                assertTrue(Files.exists(disk.resolve("a.txt")), "move 被拒后源文件还在")
                assertFalse(Files.exists(disk.resolve("b.txt")), "move 被拒后没有新文件")

                // 失败前后事实一致：内容、有效 Node、Metadata、事件日志都没变。
                assertEquals(before, snapshot(), "所有反例都没有改变持久化事实")
            }
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

    /** 只读查库：有效路径集合、Metadata 行、事件行——压成一个可比较的快照。 */
    private data class Facts(
        val activePaths: List<String>,
        val metadata: List<String>,
        val events: List<String>,
        val disk: List<String>,
    )

    private fun snapshot(): Facts =
        Facts(
            activePaths = query("SELECT vfs_path FROM node WHERE deleted_at IS NULL ORDER BY vfs_path").map { it[0]!! },
            metadata = query("SELECT node_id, payload FROM metadata ORDER BY node_id").map { "${it[0]}=${it[1]}" },
            events = eventRows(),
            disk =
                Files.walk(disk).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .map { disk.relativize(it).toString() }
                        .sorted()
                        .toList()
                },
        )

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

    private fun eventRows(): List<String> =
        query("SELECT event_type || '|' || COALESCE(node_id, '') || '|' || uri FROM event ORDER BY rowid").map { it[0]!! }

    private fun countActive(path: String): Int =
        query("SELECT COUNT(*) FROM node WHERE deleted_at IS NULL AND vfs_path = '$path'").first()[0]!!.toInt()

    private fun findByPath(path: String): NodeId? =
        query("SELECT id FROM node WHERE deleted_at IS NULL AND vfs_path = '$path'")
            .firstOrNull()
            ?.let { NodeId.parse(it[0]!!) }
}
