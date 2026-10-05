package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * T18 A02 的持久化一半：挂载映射的物理身份能存能读，真实旧版本库能迁移。
 *
 * 「旧版本库」是真的 SQLite 文件：先用当前代码建库写入数据，再把 mount 表退回版本 1 的两列形状
 * 并把 `user_version` 置回 1。版本 1 与版本 2 的差别**只有** mount 这两列，
 * node / metadata / event 三张表逐字未变，所以这样得到的文件与版本 1 代码写出来的等价。
 */
class MountIdentitySqliteTest {
    @TempDir
    lateinit var tempDir: Path

    private fun mount(
        path: String,
        key: String,
        backend: String = "local-fs",
        root: String = "/data/disk",
    ) = MountRecord(VfsPath.parse(path), key, backend, root)

    /** 首启保存的映射原样存住：身份三件套一个不少，路由只读的那两列不被改动。 */
    @Test
    fun `the mount identity survives a save and a reopen`() =
        runBlocking {
            val file = tempDir.resolve("state.db")
            val mapping =
                listOf(mount("/memory", "disk-b", root = "/data/b"), mount("/resources", "disk-a", root = "/data/a"))

            VfsStateDatabase.file(file).use { state ->
                SqliteMountRepository(state).replaceAll(mapping)
            }

            VfsStateDatabase.file(file).use { state ->
                // 读出来按 vfs_path 排序，比较时也按同一个顺序，两边才对得上。
                assertEquals(mapping, SqliteMountRepository(state).list(), "重开后身份一个字节都不该变")
                assertTrue(SqliteMountRepository(state).list().all { it.hasPhysicalIdentity })
            }
        }

    /** 整批替换是一个事务：中途撞上主键就整体回滚，不会留下写了一半的映射。 */
    @Test
    fun `a batch that fails halfway leaves the previous mapping untouched`() =
        runBlocking {
            val file = tempDir.resolve("state.db")
            VfsStateDatabase.file(file).use { state ->
                val mounts = SqliteMountRepository(state)
                mounts.replaceAll(listOf(mount("/resources", "disk-a")))

                // 同一个逻辑路径写两遍：第二条撞主键，整批必须回滚。
                val failure =
                    assertThrows(RuntimeException::class.java) {
                        runBlocking {
                            mounts.replaceAll(listOf(mount("/memory", "disk-b"), mount("/memory", "disk-c")))
                        }
                    }

                assertEquals(listOf(mount("/resources", "disk-a")), mounts.list(), "回滚后仍是原来的那一条")
                assertTrue(failure.stackTrace.isNotEmpty())
            }
        }

    /**
     * 真实旧版本库（`user_version = 1`、mount 只有两列）升级到版本 2：
     * 表结构到位、旧数据一个不少，但**物理身份是空的**——这时候必须拒绝，不能拿新配置自动认证。
     */
    @Test
    fun `a real version one database migrates and its old mounts have no physical identity`() =
        runBlocking {
            val file = tempDir.resolve("state.db")
            val node = writeVersionOneDatabase(file, mounts = listOf("/resources" to "disk-a"))

            VfsStateDatabase.file(file).use { state ->
                assertEquals(2L, userVersion(file), "打开后应已升级到当前版本")
                assertEquals(node, SqliteNodeRepository(state).findByPath(node.path), "旧 Node 一个不少")
                assertEquals(1L, state.eventCount(), "旧事件一个不少")

                val migrated = SqliteMountRepository(state).list()
                assertEquals(listOf(VfsPath.parse("/resources") to "disk-a"), migrated.map { it.path to it.storageKey })
                assertFalse(migrated.single().hasPhysicalIdentity, "旧库行拿不到物理身份")
            }
        }

    /** 旧版本库没有挂载时迁移后正常初始化，可以照常存进第一批映射。 */
    @Test
    fun `a real version one database without mounts initializes normally`() =
        runBlocking {
            val file = tempDir.resolve("state.db")
            writeVersionOneDatabase(file, mounts = emptyList())

            VfsStateDatabase.file(file).use { state ->
                assertEquals(2L, userVersion(file))
                val mounts = SqliteMountRepository(state)
                assertEquals(emptyList<Any>(), mounts.list())

                mounts.replaceAll(listOf(mount("/resources", "disk-a")))
                assertEquals(listOf(mount("/resources", "disk-a")), mounts.list())
            }
        }

    /**
     * 造一个真的版本 1 库：先按当前代码建库并写入真实 Node 与事件，
     * 再把 mount 表退回版本 1 的两列形状（`ALTER TABLE ... DROP COLUMN`）并把版本号置回 1。
     *
     * 版本 1 与版本 2 的差异只有 mount 这两列，另外三张表逐字未变，所以这个文件与旧代码写出来的等价。
     *
     * @return 写入的那个 Node，供升级后核对旧数据没丢
     */
    private suspend fun writeVersionOneDatabase(
        file: Path,
        mounts: List<Pair<String, String>>,
    ): com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord =
        VfsStateDatabase
            .file(file)
            .use { state ->
                val node = SqliteNodeRepository(state).register(testRecord("/resources/notes/a.txt"))
                SqliteEventRepository(state).append(testEvent(node.id, "alcyone://resources/notes/a.txt"))
                mounts.forEach { (path, key) -> state.insertMount(path, key) }
                node
            }.also {
                raw(file, "ALTER TABLE mount DROP COLUMN physical_root")
                raw(file, "ALTER TABLE mount DROP COLUMN backend_type")
                raw(file, "PRAGMA user_version = 1")
                assertEquals(1L, userVersion(file), "造出来的库必须是版本 1")
            }

    /** 直连读 `PRAGMA user_version`，绕开打开逻辑看文件本身。 */
    private fun userVersion(file: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    private fun raw(
        file: Path,
        sql: String,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
