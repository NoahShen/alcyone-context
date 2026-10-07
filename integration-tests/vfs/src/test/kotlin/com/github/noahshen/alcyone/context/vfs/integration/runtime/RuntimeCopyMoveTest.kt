package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
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
 * T21 A01 / A02 / A03 / A05：公开入口上的**跨 Mount 文件移动**闭环。
 *
 * 用的是**公开 [AlcyoneVfs]**（真 Runtime + 真 SQLite + **两个不重叠的本机目录**，状态库在两块挂载根之外）：
 * 文件真的从第一块盘挪到第二块盘、同一 Node ID / Metadata 跨正常重开保持、旧路径重建得到新 ID、
 * 事件字段按移动语义落库。Core 层的调用顺序 / 失败注入 / 取消证据放 `DefaultVfsCopyMoveRealStackTest`，
 * 两类证据分开。
 *
 * 关键点：两个挂载点背后**都是 Local FS**，策略仍必须是复制删除而不是 rename——这正是跨 Mount 的判定依据
 * （Guard 看 `source.route.mount == target.route.mount`，不看 `storageKey`、也不看后端是否支持原生 move）。
 */
class RuntimeCopyMoveTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var source: Path
    private lateinit var archive: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        source = Files.createDirectory(tempDir.resolve("source"))
        archive = Files.createDirectory(tempDir.resolve("archive"))
    }

    private fun config(limits: VfsLimits = VfsLimits()) =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts =
                listOf(
                    MountConfig(VfsPath.parse("/resources/local"), "local", source),
                    MountConfig(VfsPath.parse("/resources/archive"), "archive", archive),
                ),
            limits = limits,
        )

    private fun sourceUri(path: String) = VfsUri.parse("alcyone://resources/local$path")

    private fun archiveUri(path: String) = VfsUri.parse("alcyone://resources/archive$path")

    /** A01 / A02：跨 Mount 搬运保留身份与 Metadata，第一块盘消失、第二块盘逐字节一致。 */
    @Test
    @Timeout(60)
    fun `A01 a file moves across two local mounts with one FILE_MOVED and the same identity`() =
        runBlocking {
            prepare()
            val tag = NodeMetadata(setOf("ct", "影像"), "跨盘搬运的报告")
            val original =
                withVfs(config()) { vfs ->
                    val info = vfs.write(sourceUri("/note.txt"), "hello across two disks".toByteArray())
                    vfs.setMetadata(info.id, tag)
                    info.id
                }
            val digestBefore = sha256(source.resolve("note.txt"))

            withVfs(config()) { vfs ->
                val moved = vfs.move(sourceUri("/note.txt"), archiveUri("/2026/note.txt"))

                assertEquals(original, moved.id, "跨 Mount 移动保留同一个 Node ID")
                assertFalse(Files.exists(source.resolve("note.txt")), "第一块盘上的旧位置真的消失")
                assertTrue(Files.isDirectory(archive.resolve("2026")), "缺失的目标父目录已补在第二块盘上")
                assertEquals(digestBefore, sha256(archive.resolve("2026/note.txt")), "内容逐字节一致")
                assertEquals(original, vfs.stat(archiveUri("/2026/note.txt")).id, "新路径 stat 得到同一身份")
                assertEquals(tag, vfs.getMetadata(original), "Metadata 随身份保留")
                assertEquals(
                    listOf("/resources/archive/2026/note.txt"),
                    activePaths(),
                    "状态库里只有目标路径这一条有效记录，补出的父目录不登记",
                )
            }

            // 正常重开：同一个 ID 仍指向目标位置。
            withVfs(config()) { vfs ->
                assertEquals(original, vfs.getNode(original).id, "重开后旧 ID 有效")
                assertEquals(
                    "note.txt",
                    vfs
                        .getNode(original)
                        .uri.path.segments
                        .last(),
                    "重开后指向目标位置",
                )
                assertEquals("hello across two disks", vfs.read(archiveUri("/2026/note.txt")).toString(Charsets.UTF_8))
                val missing = assertFailsWith<VfsException> { vfs.stat(sourceUri("/note.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "旧路径报 NOT_FOUND")

                // 在旧路径另建文件：新文件是新的身份，不用新目标身份掩盖迁移。
                val rebuilt = vfs.write(sourceUri("/note.txt"), "rebuilt".toByteArray())
                assertNotEquals(original, rebuilt.id, "旧路径重建获得新 Node ID")
            }
        }

    /**
     * A01：跨 Mount 移动在第二块盘上真的留下内容、源盘消失、只补出目标父目录。
     *
     * 注意：本用例**不**声称「排除 rename」——两个本地目录未必是两个文件系统，存在、内容与目录列表
     * 都不能区分 rename 与复制删除。策略证据用 `DefaultVfsCopyMoveTest` 里的 Core 后端调用记录（`nativeMoveCalls == 0`）。
     */
    @Test
    @Timeout(60)
    fun `A01 the cross mount move leaves a real file on the second disk and removes it from the source`() =
        runBlocking {
            prepare()
            withVfs(config()) { vfs ->
                vfs.write(sourceUri("/a.txt"), "copy me".toByteArray())

                vfs.move(sourceUri("/a.txt"), archiveUri("/nested/b.txt"))

                assertTrue(Files.exists(archive.resolve("nested/b.txt")), "第二块盘上真的有这个文件")
                assertEquals("copy me", Files.readString(archive.resolve("nested/b.txt")))
                assertFalse(Files.exists(source.resolve("a.txt")), "第一块盘上原位置已经没有它了")
                assertEquals(listOf("nested"), dirNames(archive), "只有目标的父目录被补出来")
                assertEquals(emptyList<String>(), dirNames(source), "源盘上不留残留目录")
            }
        }

    /** A02 / A05：未登记源只建立一个身份；跨盘移动只发一条 FILE_MOVED，事件字段正确。 */
    @Test
    @Timeout(60)
    fun `A05 the cross mount move emits exactly one FILE_MOVED with the right identity and uris`() =
        runBlocking {
            prepare()
            withVfs(config()) { vfs ->
                val info = vfs.write(sourceUri("/a.txt"), "hello".toByteArray())
                vfs.move(sourceUri("/a.txt"), archiveUri("/b.txt"))

                val events = eventRows()
                assertEquals(2, events.size, "一次写 + 一次移动，共两条事件")
                val move = events.last()
                assertEquals("FILE_MOVED", move[1], "跨 Mount 移动发的是 FILE_MOVED，不冒充删除加创建")
                assertEquals(info.id.value, move[2], "nodeId 为原 ID")
                assertEquals("alcyone://resources/archive/b.txt", move[3], "uri 是目标位置")
                assertEquals("alcyone://resources/local/a.txt", move[4], "sourceUri 是本次源")
                assertEquals("alcyone://resources/archive/b.txt", move[5], "targetUri 是本次目标")
                assertEquals(1, events.count { it[1] == "FILE_MOVED" }, "一次跨 Mount 移动只发一条 FILE_MOVED")
            }
        }

    /** A02：绕过 VFS 预置的源文件跨盘移动后，重开仍指向目标，旧路径重建得到新 ID。 */
    @Test
    @Timeout(60)
    fun `A02 an unregistered source is given one identity that survives a cross mount move and a reopen`() =
        runBlocking {
            prepare()
            Files.writeString(source.resolve("external.txt"), "outside")

            val movedId =
                withVfs(config()) { vfs ->
                    assertNull(findByPath("/resources/local/external.txt"), "预置文件没登记")
                    val moved = vfs.move(sourceUri("/external.txt"), archiveUri("/moved.txt"))
                    assertEquals("outside", vfs.read(archiveUri("/moved.txt")).toString(Charsets.UTF_8), "内容搬到第二块盘")
                    assertEquals(moved.id, findByPath("/resources/archive/moved.txt")?.let { NodeId.parse(it) }, "状态库里记录已迁到目标")
                    assertNull(findByPath("/resources/local/external.txt"), "旧路径不再有记录")
                    assertEquals(listOf("FILE_MOVED"), eventTypes(), "源是绕过 VFS 预置的，没有创建事件，只发一条 FILE_MOVED")
                    moved.id
                }

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
                val missing = assertFailsWith<VfsException> { vfs.stat(sourceUri("/external.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "旧路径读取报 NOT_FOUND")
                val rebuilt = vfs.write(sourceUri("/external.txt"), "rebuilt".toByteArray())
                assertNotEquals(movedId, rebuilt.id, "旧路径重建获得新 Node ID")
            }
        }

    /** A03：默认限额下小文件可以跨 Mount 移动；超限文件拒绝并保留源。 */
    @Test
    @Timeout(60)
    fun `A03 a small file crosses mounts under the default limit and a large one is refused with the source kept`() =
        runBlocking {
            prepare()
            // 20 MiB > 默认 16 MiB：跨 Mount 复制要真的读进 ByteArray，所以必然撞上限；
            // 同一份文件在**同 Mount 内**用原生 move 却不受影响（那条已在 RuntimeMoveTest 里证明）。
            val big = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }
            Files.write(source.resolve("big.bin"), big)
            val digestBefore = sha256(source.resolve("big.bin"))
            Files.writeString(source.resolve("small.txt"), "small")

            withVfs(config()) { vfs ->
                vfs.move(sourceUri("/small.txt"), archiveUri("/small.txt"))
                assertEquals("small", Files.readString(archive.resolve("small.txt")), "默认限额下小文件可以跨 Mount 移动")

                val failure = assertFailsWith<VfsException> { vfs.move(sourceUri("/big.bin"), archiveUri("/big.bin")) }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, failure.code, "超限文件在跨 Mount 复制时被拒")
                assertEquals(VfsEffect.NONE, failure.effect, "超限时源没动、目标没写，报 NONE 才对")
                assertTrue(Files.exists(source.resolve("big.bin")), "超限文件保留在第一块盘上")
                assertEquals(digestBefore, sha256(source.resolve("big.bin")), "源一个字节都没改")
                assertFalse(Files.exists(archive.resolve("big.bin")), "第二块盘上什么都没写")

                // 反证：同 Mount 内原生 move 不受这道坎影响，所以限额是「复制路径」独有的差异。
                val sameMount = vfs.move(sourceUri("/big.bin"), sourceUri("/moved-big.bin"))
                assertNotEquals(null, sameMount.id)
                assertEquals(digestBefore, sha256(source.resolve("moved-big.bin")), "同 Mount 原生移动照常搬 20 MiB")
            }
        }

    /** A03：目标已存在 / 目录移动 / 受保护挂载根这些反例在跨 Mount 上同样成立，事实不变。 */
    @Test
    @Timeout(60)
    fun `A03 the still-unsupported and invalid cross mount moves are refused with facts unchanged`() =
        runBlocking {
            prepare()
            withVfs(config()) { vfs ->
                val info = vfs.write(sourceUri("/a.txt"), "hello".toByteArray())
                Files.writeString(archive.resolve("existing.txt"), "taken")
                Files.createDirectory(archive.resolve("dir"))
                val before = snapshot()

                // 目标已存在：ALREADY_EXISTS，不覆盖（普通文件与普通目录都算）。
                val exists = assertFailsWith<VfsException> { vfs.move(sourceUri("/a.txt"), archiveUri("/existing.txt")) }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, exists.code)
                val intoDir = assertFailsWith<VfsException> { vfs.move(sourceUri("/a.txt"), archiveUri("/dir")) }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, intoDir.code, "目标目录不被当作移入其中")

                // 目标落进源自己的子树：纯参数错误。
                val selfContained = assertFailsWith<VfsException> { vfs.move(sourceUri("/a.txt"), sourceUri("/a.txt/inner")) }
                assertEquals(VfsErrorCode.INVALID_ARGUMENT, selfContained.code)

                // 缺失源：NOT_FOUND。
                val missing = assertFailsWith<VfsException> { vfs.move(sourceUri("/gone.txt"), archiveUri("/new.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code)

                // 目录移动：阶段拒绝（T22），跨 Mount 的目录也一样。
                val directory = assertFailsWith<VfsException> { vfs.move(archiveUri("/dir"), sourceUri("/dir2")) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, directory.code)

                // 受保护挂载根：结构保护。
                val protected = assertFailsWith<VfsException> { vfs.move(sourceUri("/a.txt"), archiveUri("/")) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, protected.code)

                assertEquals(before, snapshot(), "所有反例都没有改变持久化事实")
                assertEquals("hello", Files.readString(source.resolve("a.txt")), "a.txt 原样不动")
                assertEquals("taken", Files.readString(archive.resolve("existing.txt")), "已有目标不被覆盖")
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

    private fun findByPath(path: String): String? =
        query("SELECT id FROM node WHERE deleted_at IS NULL AND vfs_path = '$path'").firstOrNull()?.get(0)

    /** 一块盘根下的直接子目录名（不看文件），用于核对补目录的结果。 */
    private fun dirNames(root: Path): List<String> =
        Files.list(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

    /** 有效路径集合、Metadata 行、事件行、挂载根文件集合——压成一个可比较的快照。 */
    private data class Facts(
        val activePaths: List<String>,
        val metadata: List<String>,
        val events: List<String>,
        val sourceDisk: List<String>,
        val archiveDisk: List<String>,
    )

    private fun snapshot(): Facts =
        Facts(
            activePaths = activePaths(),
            metadata = query("SELECT node_id, payload FROM metadata ORDER BY node_id").map { "${it[0]}=${it[1]}" },
            events = query("SELECT event_type || '|' || COALESCE(node_id, '') || '|' || uri FROM event ORDER BY rowid").map { it[0]!! },
            sourceDisk = diskListing(source),
            archiveDisk = diskListing(archive),
        )

    private fun diskListing(root: Path): List<String> =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .map { root.relativize(it).toString() }
                .sorted()
                .toList()
        }
}
