package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** A01：NodeRepository 落在真实 SQLite 上，当前有效路径唯一性由部分唯一索引裁定。 */
class NodeRepositorySqliteTest {
    // A01-1：同路径二次注册返回既有有效记录，且只有一条有效记录
    @Test
    fun `second registration of the same path returns the existing record`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val first = nodes.register(testRecord("/notes/a.txt"))
                val candidate = testRecord("/notes/a.txt")
                val second = nodes.register(candidate)

                assertEquals(first, second)
                assertEquals(1L, state.activeNodeCount())
                assertNull(nodes.findById(candidate.id), "落败的注册不应留下自己的记录")
            }
        }

    // A01-1 的库内约束：绕过 Repository 直接插两次，唯一索引必须拒绝第二条
    @Test
    fun `partial unique index rejects a second active row for the same path`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val queries = state.database.nodeQueries
                val first = testRecord("/notes/a.txt")
                val second = testRecord("/notes/a.txt")
                listOf(first, second).forEach { candidate ->
                    queries.insertActive(
                        candidate.id.value,
                        candidate.path.toString(),
                        candidate.type.name,
                        1L,
                        TEST_NOW.toEpochMilli(),
                        TEST_NOW.toEpochMilli(),
                    )
                }

                assertEquals(1L, state.activeNodeCount())
                assertEquals(
                    first.id.value,
                    queries.selectActiveByPath("/notes/a.txt").executeAsOne().id,
                )
                assertNull(queries.selectActiveById(second.id.value).executeAsOneOrNull())
            }
        }

    // A01-2：标记删除后旧路径与旧 ID 都查不到，同路径可注册新 ID
    @Test
    fun `marking a node deleted releases its path for a new id`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val original = nodes.register(testRecord("/notes/a.txt"))
                nodes.markDeleted(listOf(original.id), TEST_NOW)

                assertNull(nodes.findByPath(original.path))
                assertNull(nodes.findById(original.id))
                assertEquals(0L, state.activeNodeCount())

                val replacement = nodes.register(testRecord("/notes/a.txt"))
                assertNotEquals(original.id, replacement.id)
                assertEquals(replacement, nodes.findByPath(original.path))
                assertNull(nodes.findById(original.id))
            }
        }

    // A01-3：竞争注册不产生双有效记录
    @Test
    fun `competing registrations of one path yield a single active record`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val registered =
                    (1..8)
                        .map { async { nodes.register(testRecord("/notes/race.txt")) } }
                        .awaitAll()

                assertEquals(1, registered.distinct().size)
                assertEquals(1L, state.activeNodeCount())
                assertEquals(registered.first().id, nodes.findByPath(VfsPath.parse("/notes/race.txt"))?.id)
            }
        }

    // A01-4：路径比较大小写敏感，/A 与 /a 是两个独立节点
    @Test
    fun `paths differing only in case are distinct nodes`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val upper = nodes.register(testRecord("/notes/A.txt"))
                val lower = nodes.register(testRecord("/notes/a.txt"))

                assertNotEquals(upper.id, lower.id)
                assertEquals(2L, state.activeNodeCount())
                assertEquals(upper, nodes.findByPath(upper.path))
                assertEquals(lower, nodes.findByPath(lower.path))
            }
        }

    // A01-5：注册字段完整回读
    @Test
    fun `registered fields are read back unchanged`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val input = testRecord("/medical/ct", type = NodeType.DIRECTORY, physical = false)

                assertEquals(input, nodes.register(input))
                assertEquals(input, nodes.findByPath(input.path))
                assertEquals(input, nodes.findById(input.id))
            }
        }

    // 正常关闭后重启仍能读到已提交的 Node（生命周期最小验证）
    @Test
    fun `committed nodes survive closing and reopening the file database`() =
        runBlocking {
            val dir = Files.createTempDirectory("alcyone-vfs-state")
            val file: Path = dir.resolve("state.db")
            try {
                val registered =
                    VfsStateDatabase.file(file).use { state ->
                        SqliteNodeRepository(state).register(testRecord("/notes/persisted.txt"))
                    }

                VfsStateDatabase.file(file).use { state ->
                    assertEquals(registered, SqliteNodeRepository(state).findByPath(registered.path))
                }
            } finally {
                Files.deleteIfExists(file)
                Files.deleteIfExists(dir)
            }
        }
}
