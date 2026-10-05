package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
 * T18 A02：挂载身份持久化与冲突。全部走公开入口 `AlcyoneVfs.create`，用的是真 SQLite 与真本机目录。
 *
 * 判据：首次启动原子保存映射；相同配置重开保留身份与已有 Node / Metadata；
 * 旧 Mount 被移除、逻辑位置变化、同 key 换物理根一律 `CONFLICT`，且**原配置仍然能启动**；
 * 新增挂载不许遮蔽旧挂载的覆盖范围；独立的新命名空间可以加。
 */
class MountConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("docs"))
    }

    private fun otherDisk(name: String): Path = Files.createDirectory(tempDir.resolve(name))

    private fun config(
        mounts: List<MountConfig>,
        namespaces: Set<String> = setOf("resources"),
    ) = VfsRuntimeConfig(stateDatabase = database, namespaces = namespaces, mounts = mounts)

    private fun mount(
        path: String,
        key: String,
        root: Path,
    ) = MountConfig(VfsPath.parse(path), key, root)

    private val original get() = config(listOf(mount("/resources/docs", "docs", disk)))

    private fun uri(path: String) = VfsUri.parse("alcyone://resources/docs$path")

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

    /** 挂载映射行：逻辑路径、storageKey、后端类型、物理根。 */
    private fun mountRows(): List<List<String?>> =
        query("SELECT vfs_path, storage_key, backend_type, physical_root FROM mount ORDER BY vfs_path").map { it.toList() }

    /** 首次启动把映射存下去；相同配置重开时身份与已有 Node / Metadata 都在。 */
    @Test
    @Timeout(60)
    fun `the first start stores the mount identity and a reopen keeps everything`() =
        runBlocking {
            prepare()
            val written =
                AlcyoneVfs.create(original).use { vfs ->
                    val info = vfs.write(uri("/a.txt"), "hello".toByteArray())
                    vfs.setMetadata(info.id, NodeMetadata(setOf("ct"), "胸部 CT"))
                    info
                }

            val stored = listOf(listOf("/resources/docs", "docs", "local-fs", disk.toRealPath().toString()))
            assertEquals(stored, mountRows(), "首启原子保存了包含物理身份的映射")

            AlcyoneVfs.create(original).use { vfs ->
                assertEquals("hello", vfs.read(uri("/a.txt")).toString(Charsets.UTF_8))
                assertEquals(setOf("ct"), vfs.getMetadata(written.id).tags, "重开后 Node 与 Metadata 都还在")
                assertEquals(stored, mountRows(), "相同配置重开不改写映射")
            }
        }

    /** 同一个 storageKey 换物理根：认得出来；冲突之后原配置仍然能启动。 */
    @Test
    @Timeout(60)
    fun `the same storage key pointing at a different root is a conflict`() =
        runBlocking {
            prepare()
            val elsewhere = otherDisk("other")
            AlcyoneVfs.create(original).use { vfs -> vfs.write(uri("/a.txt"), "hello".toByteArray()) }

            val failure =
                assertFailsWith<VfsException> {
                    AlcyoneVfs.create(config(listOf(mount("/resources/docs", "docs", elsewhere))))
                }

            assertEquals(VfsErrorCode.CONFLICT, failure.code)
            assertTrue(failure.message!!.contains("changed identity"), "冲突消息要点名是身份变了：${failure.message}")

            AlcyoneVfs.create(original).use { vfs ->
                assertEquals("hello", vfs.read(uri("/a.txt")).toString(Charsets.UTF_8), "冲突之后原配置仍然能启动")
            }
        }

    /** 旧 Mount 被移除 / 逻辑位置换到另一块盘 / 命名空间被删：都拒绝，理由各自说清。 */
    @Test
    @Timeout(60)
    fun `removing a stored mount or moving it is a conflict`() =
        runBlocking {
            prepare()
            val elsewhere = otherDisk("other")
            AlcyoneVfs.create(original).use { vfs -> vfs.write(uri("/a.txt"), "hello".toByteArray()) }

            val removed =
                assertFailsWith<VfsException> { AlcyoneVfs.create(config(listOf(mount("/resources/notes", "notes", elsewhere)))) }
            assertEquals(VfsErrorCode.CONFLICT, removed.code)
            assertTrue(removed.message!!.contains("no longer configured"), "消息要点名是旧挂载没了：${removed.message}")

            val moved =
                assertFailsWith<VfsException> { AlcyoneVfs.create(config(listOf(mount("/resources/notes", "docs", disk)))) }
            assertEquals(VfsErrorCode.CONFLICT, moved.code)
            assertTrue(moved.message!!.contains("no longer configured"), "旧位置没了也算移除：${moved.message}")

            val withoutNamespace = assertFailsWith<VfsException> { AlcyoneVfs.create(config(emptyList(), namespaces = setOf("memory"))) }
            assertEquals(VfsErrorCode.CONFLICT, withoutNamespace.code)

            AlcyoneVfs.create(original).use { vfs ->
                assertEquals("hello", vfs.read(uri("/a.txt")).toString(Charsets.UTF_8), "三次拒绝之后原配置仍然能启动")
            }
        }

    /**
     * 新增挂载遮蔽旧挂载的覆盖范围就拒：已有 `/resources/docs → docs` 时，在它下面再加
     * `/resources/docs/photos → photos` 不行，**哪怕 photos 子树里一个 Node 都没登记**。
     */
    @Test
    @Timeout(60)
    fun `a new mount that shadows an existing one is refused`() =
        runBlocking {
            prepare()
            val photos = otherDisk("photos")
            AlcyoneVfs.create(original).use { vfs -> vfs.write(uri("/a.txt"), "hello".toByteArray()) }

            val failure =
                assertFailsWith<VfsException> {
                    AlcyoneVfs.create(
                        config(listOf(mount("/resources/docs", "docs", disk), mount("/resources/docs/photos", "photos", photos))),
                    )
                }

            assertEquals(VfsErrorCode.CONFLICT, failure.code)
            assertTrue(failure.message!!.contains("take over part of"), "消息要点名是遮蔽：${failure.message}")
            assertEquals(1, mountRows().size, "被拒时不写任何新映射")

            AlcyoneVfs.create(original).use { vfs ->
                assertEquals("hello", vfs.read(uri("/a.txt")).toString(Charsets.UTF_8), "被拒之后原配置仍然能启动")
            }
        }

    /** 加到另一个命名空间、不碰旧映射：接受，旧映射原样保留，且不产生文件事件。 */
    @Test
    @Timeout(60)
    fun `an independent new namespace is accepted and leaves the stored mapping alone`() =
        runBlocking {
            prepare()
            val memory = otherDisk("memory")
            AlcyoneVfs.create(original).use { vfs -> vfs.write(uri("/a.txt"), "hello".toByteArray()) }

            AlcyoneVfs
                .create(
                    config(
                        listOf(mount("/resources/docs", "docs", disk), mount("/memory", "memory", memory)),
                        namespaces = setOf("resources", "memory"),
                    ),
                ).use { vfs ->
                    vfs.write(VfsUri.parse("alcyone://memory/note.md"), "note".toByteArray())
                    assertEquals("note", vfs.read(VfsUri.parse("alcyone://memory/note.md")).toString(Charsets.UTF_8))
                }

            assertEquals(
                listOf(
                    listOf("/memory", "memory", "local-fs", memory.toRealPath().toString()),
                    listOf("/resources/docs", "docs", "local-fs", disk.toRealPath().toString()),
                ),
                mountRows(),
                "旧映射没被改写，新映射追加在后面",
            )
            assertEquals(2, query("SELECT event_type FROM event").size, "只追加了真正发生的两个文件事件，新增挂载本身不产事件")
        }

    /**
     * 真实旧版本库（mount 表只有两列）里已有挂载时必须明确拒绝，并说清怎么办。
     *
     * 旧库造法：先按当前代码建库并写入真实 Node 与挂载，再把 mount 表退回版本 1 的两列形状、
     * 把 `user_version` 置回 1。版本 1 与版本 2 的差别**只有**这两列，其余表逐字未变。
     */
    @Test
    @Timeout(60)
    fun `a stored mount without a physical identity is refused with a way out`() =
        runBlocking {
            prepare()
            val node = writeVersionOneDatabase("/resources/docs" to "docs")

            val failure = assertFailsWith<VfsException> { AlcyoneVfs.create(original) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code)
            assertTrue(failure.message!!.contains("did not record the physical identity"), "消息要说清缺的是什么：${failure.message}")
            assertTrue(failure.message!!.contains("clear the 'mount' table"), "消息要给出处理办法：${failure.message}")
            assertEquals(listOf("1"), query("SELECT count(*) FROM node WHERE id = '$node'").map { it[0] }, "拒绝时不动已有 Node")

            // 处理办法照消息做：清掉没有身份的旧映射之后，同一个配置就能正常初始化。
            raw("DELETE FROM mount")
            AlcyoneVfs.create(original).use { vfs ->
                assertEquals(
                    listOf(listOf("/resources/docs", "docs", "local-fs", disk.toRealPath().toString())),
                    mountRows(),
                    "清完之后按当前配置重新存一遍身份",
                )
            }
        }

    /** 建一个真的版本 1 库：当前结构写入数据，再退回 mount 的两列形状并把版本号置回 1。 */
    private suspend fun writeVersionOneDatabase(mount: Pair<String, String>): String =
        VfsStateDatabase
            .file(database)
            .use { state ->
                val node =
                    SqliteNodeRepository(state).register(
                        NodeRecord(
                            NodeId.parse(newUuidV7().toString()),
                            VfsPath.parse("/resources/docs/a.txt"),
                            NodeType.FILE,
                            true,
                            Instant.now(),
                            Instant.now(),
                        ),
                    )
                node.id.toString()
            }.also {
                // 旧库的 mount 行只有逻辑路径和 storageKey 两列。
                raw("INSERT INTO mount (vfs_path, storage_key) VALUES ('${mount.first}', '${mount.second}')")
                raw("ALTER TABLE mount DROP COLUMN physical_root")
                raw("ALTER TABLE mount DROP COLUMN backend_type")
                raw("PRAGMA user_version = 1")
            }

    private fun raw(sql: String) {
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
