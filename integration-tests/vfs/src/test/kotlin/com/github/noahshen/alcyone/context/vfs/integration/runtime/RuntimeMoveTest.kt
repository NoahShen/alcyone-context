package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/**
 * T20 A01 / A02 / A05：公开入口上的同 Mount 原生文件移动闭环。
 *
 * 这里用的是**公开 [AlcyoneVfs]**（真 Runtime + 真 SQLite + 真本机目录，状态库在挂载物理根之外）：
 * 文件真的从旧位置挪到新位置、同一 Node ID / Metadata 跨正常重开保持、旧路径重建得到新 ID、
 * 事件字段按移动语义落库。Core 层的顺序 / 失败注入 / 取消证据放 `DefaultVfsMoveRealStackTest`，
 * 两类证据分开。只读 SQL 只用来确认持久化事实，不替被测操作写数据。
 */
class RuntimeMoveTest {
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

    /** A01 / A02 / A05：已登记源重命名与跨子目录移动，不覆盖目标，保留 ID / Metadata，旧路径消失。 */
    @Test
    @Timeout(60)
    fun `A01 a registered file moves to another subdirectory with one FILE_MOVED and the same identity`() =
        runBlocking {
            prepare()
            val tag = NodeMetadata(setOf("ct", "影像"), "胸部 CT 报告")
            val original =
                withVfs(config()) { vfs ->
                    val info = vfs.write(uri("/note.txt"), "hello".toByteArray())
                    vfs.setMetadata(info.id, tag)
                    info.id
                }
            val digestBefore = sha256(disk.resolve("note.txt"))

            withVfs(config()) { vfs ->
                val moved = vfs.move(uri("/note.txt"), uri("/archive/note.txt"))

                assertEquals(original, moved.id, "移动保留同一个 Node ID")
                assertFalse(Files.exists(disk.resolve("note.txt")), "旧物理位置真的消失")
                assertTrue(Files.exists(disk.resolve("archive/note.txt")), "缺失的目标父目录已补出来")
                assertEquals(digestBefore, sha256(disk.resolve("archive/note.txt")), "内容逐字节一致")
                assertEquals(original, vfs.stat(uri("/archive/note.txt")).id, "新路径 stat 得到同一身份")
                assertEquals(tag, vfs.getMetadata(original), "Metadata 随身份保留")
                assertEquals(
                    listOf("/resources/docs/archive/note.txt"),
                    activePaths(),
                    "状态库里只有目标路径这一条有效记录，父目录不登记",
                )
            }
        }

    /** A02：未登记源只建立一个身份；重开后仍指向目标；旧路径重建得到不同 ID。 */
    @Test
    @Timeout(60)
    fun `A02 an unregistered source is given one identity that survives a reopen and the old path rebuilds fresh`() =
        runBlocking {
            prepare()
            // 绕过 VFS 直接把文件放到磁盘上：它没有身份，内容却真的在。
            Files.writeString(disk.resolve("external.txt"), "outside")

            val movedId =
                withVfs(config()) { vfs ->
                    assertNull(findByPath("/resources/docs/external.txt"), "预置文件没登记")
                    val moved = vfs.move(uri("/external.txt"), uri("/moved.txt"))
                    assertEquals("outside", vfs.read(uri("/moved.txt")).toString(Charsets.UTF_8), "内容搬到目标")
                    assertEquals(moved.id, findByPath("/resources/docs/moved.txt")?.let { NodeId.parse(it) }, "状态库里记录已迁到目标")
                    assertNull(findByPath("/resources/docs/external.txt"), "旧路径不再有记录")
                    assertEquals(
                        listOf("FILE_MOVED"),
                        eventTypes(),
                        "源是绕过 VFS 预置的，没有创建事件，只发一条 FILE_MOVED",
                    )
                    moved.id
                }

            // 正常重开：同一个 ID 仍指向目标位置。
            withVfs(config()) { vfs ->
                assertEquals(movedId, vfs.getNode(movedId).id, "重开后旧 ID 有效")
                assertEquals(
                    "moved.txt",
                    vfs
                        .getNode(movedId)
                        .uri.path.segments
                        .last(),
                    "重开后指向目标位置",
                )
                assertEquals(movedId, vfs.stat(uri("/moved.txt")).id)
                val missing = assertFailsWith<VfsException> { vfs.stat(uri("/external.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "旧路径读取报 NOT_FOUND")

                // 在旧路径另建文件：新文件是新的身份，不用新目标身份掩盖迁移。
                val rebuilt = vfs.write(uri("/external.txt"), "rebuilt".toByteArray())
                assertNotEquals(movedId, rebuilt.id, "旧路径重建获得新 Node ID")
            }

            withVfs(config()) { vfs ->
                assertEquals(
                    listOf("FILE_MOVED", "FILE_CREATED"),
                    eventTypes(),
                    "历史事件保留：移动只发一条，重建再发一条创建",
                )
                assertEquals(movedId.value, eventNodeIds()[0], "移动事件的 nodeId 是原身份")
            }
        }

    /** A01 / A05：移动事件四个身份 / 路径字段正确，一次移动只发一条。 */
    @Test
    @Timeout(60)
    fun `A05 the committed move event carries the target uri target and source on a single FILE_MOVED`() =
        runBlocking {
            prepare()
            withVfs(config()) { vfs ->
                val info = vfs.write(uri("/a.txt"), "hello".toByteArray())
                vfs.move(uri("/a.txt"), uri("/sub/b.txt"))

                val events = eventRows()
                assertEquals(2, events.size, "一次写 + 一次移动，共两条事件")
                val move = events.last()
                assertEquals("FILE_MOVED", move[1], "移动发的是 FILE_MOVED，不冒充删除加创建")
                assertEquals(info.id.value, move[2], "nodeId 为原 ID")
                assertEquals("alcyone://resources/docs/sub/b.txt", move[3], "uri 是目标位置")
                assertEquals("alcyone://resources/docs/a.txt", move[4], "sourceUri 是本次源")
                assertEquals("alcyone://resources/docs/sub/b.txt", move[5], "targetUri 是本次目标")
                assertEquals(1, events.count { it[1] == "FILE_MOVED" }, "一次移动只发一条 FILE_MOVED")
            }
        }

    /** A01：同一 Mount 内移动不把内容读进 ByteArray，大文件也照常移动（不触发 read 的 16 MiB 上限）。 */
    @Test
    @Timeout(60)
    fun `A01 a large file moves natively without going through the 16 MiB read limit`() =
        runBlocking {
            prepare()
            // 20 MiB > 默认 read 上限 16 MiB：若移动是「读进 ByteArray 再写」，这里会 LIMIT_EXCEEDED。
            val big = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }
            Files.write(disk.resolve("big.bin"), big)
            val digestBefore = sha256(disk.resolve("big.bin"))

            withVfs(config()) { vfs ->
                val moved = vfs.move(uri("/big.bin"), uri("/archive/big.bin"))
                assertNotEquals(null, moved.id)
                assertFalse(Files.exists(disk.resolve("big.bin")))
                assertEquals(digestBefore, sha256(disk.resolve("archive/big.bin")), "大文件逐字节一致")

                // 反证：同样的文件用 read 会被 16 MiB 上限挡住，说明原生移动确实没走 read。
                val tooBig = assertFailsWith<VfsException> { vfs.read(uri("/archive/big.bin")) }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, tooBig.code, "read 有 16 MiB 上限")
            }
        }

    /** A03：公开入口上仍属阶段拒绝的反例（跨 Mount / 目录）与常规拒绝，事实不变。 */
    @Test
    @Timeout(60)
    fun `A03 the still-unsupported and invalid moves are refused with facts unchanged`() =
        runBlocking {
            prepare()
            withVfs(config()) { vfs ->
                val info = vfs.write(uri("/a.txt"), "hello".toByteArray())
                Files.createDirectory(disk.resolve("dir"))
                Files.writeString(disk.resolve("existing.txt"), "taken")
                val before = snapshot()

                // 同路径：INVALID_ARGUMENT。
                val same = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/a.txt")) }
                assertEquals(VfsErrorCode.INVALID_ARGUMENT, same.code)

                // 缺失源：NOT_FOUND。
                val missing = assertFailsWith<VfsException> { vfs.move(uri("/gone.txt"), uri("/new.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code)

                // 目标已存在：ALREADY_EXISTS，不覆盖（普通文件与普通目录都算）。
                val exists = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/existing.txt")) }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, exists.code)
                val intoDir = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/dir")) }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, intoDir.code, "目标目录不被当作移入其中")

                // 目录移动：阶段拒绝（T22）。
                val directory = assertFailsWith<VfsException> { vfs.move(uri("/dir"), uri("/dir2")) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, directory.code)

                // 受保护挂载根：结构保护（目标是配置目录）。
                val protected = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/")) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, protected.code)

                assertEquals(before, snapshot(), "所有反例都没有改变持久化事实")
                assertEquals("hello", Files.readString(disk.resolve("a.txt")), "a.txt 原样不动")
                assertEquals("taken", Files.readString(disk.resolve("existing.txt")), "已有目标不被覆盖")
                assertEquals(info.id, vfs.getNode(info.id).id, "反例不影响已有身份")
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

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    private fun activePaths(): List<String> = query("SELECT vfs_path FROM node WHERE deleted_at IS NULL ORDER BY vfs_path").map { it[0]!! }

    private fun eventTypes(): List<String> = query("SELECT event_type FROM event ORDER BY rowid").map { it[0]!! }

    /** 一条事件取 `event_id / event_type / node_id / uri / source_uri / target_uri` 六项。 */
    private fun eventRows(): List<List<String?>> =
        query("SELECT event_id, event_type, node_id, uri, source_uri, target_uri FROM event ORDER BY rowid").map { it.toList() }

    private fun eventNodeIds(): List<String?> = query("SELECT node_id FROM event ORDER BY rowid").map { it[0] }

    private fun findByPath(path: String): String? =
        query("SELECT id FROM node WHERE deleted_at IS NULL AND vfs_path = '$path'").firstOrNull()?.get(0)

    /** 有效路径集合、Metadata 行、事件行、磁盘文件集合——压成一个可比较的快照。 */
    private data class Facts(
        val activePaths: List<String>,
        val metadata: List<String>,
        val events: List<String>,
        val disk: List<String>,
    )

    private fun snapshot(): Facts =
        Facts(
            activePaths = activePaths(),
            metadata = query("SELECT node_id, payload FROM metadata ORDER BY node_id").map { "${it[0]}=${it[1]}" },
            events = query("SELECT event_type || '|' || COALESCE(node_id, '') || '|' || uri FROM event ORDER BY rowid").map { it[0]!! },
            disk =
                Files.walk(disk).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                        .map { disk.relativize(it).toString() }
                        .sorted()
                        .toList()
                },
        )
}
