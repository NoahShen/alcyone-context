package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * A04 / A05：真实 SQLite 文件上的打开、关闭、重开与版本管理。
 *
 * 每个用例都用独立的临时目录，跑完删除；macOS 上删除被打开的文件不报错，
 * 所以「close 后无句柄残留」只能部分验证，真正的释放路径靠 [SingleConnectionJdbcDriver.close] 的代码审查。
 */
class VfsStateDatabaseLifecycleTest {
    private val metadata = NodeMetadata(setOf("ct", "影像"), "胸部 CT", JsonObject(emptyMap()))
    private val event =
        EventRecord(
            id = VfsEventId.parse("0194b5f0-0000-7000-8000-000000000001"),
            type = VfsEventType.FILE_CREATED,
            nodeId = null,
            occurredAt = TEST_NOW,
            uri = VfsUri.parse("alcyone://notes/a.txt"),
        )

    private suspend fun withTempFile(block: suspend (Path) -> Unit) {
        val dir = Files.createTempDirectory("alcyone-vfs-lifecycle")
        val file = dir.resolve("state.db")
        try {
            block(file)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
    }

    /** 事务写入 Node + Metadata + Event 后正常关闭重开，三者都要完整可读（A04）。 */
    @Test
    fun `committed node, metadata and event survive reopening the file database`() =
        runBlocking {
            withTempFile { file ->
                val registered: NodeRecord =
                    VfsStateDatabase.file(file).use { state ->
                        SqliteUnitOfWork(state).inTransaction { scope ->
                            val node = scope.nodes.register(testRecord("/notes/a.txt"))
                            scope.metadata.put(node.id, metadata)
                            scope.events.append(event.copy(nodeId = node.id))
                            node
                        }
                    }

                VfsStateDatabase.file(file).use { state ->
                    assertEquals(registered, SqliteNodeRepository(state).findByPath(registered.path))
                    assertEquals(metadata, SqliteMetadataRepository(state).get(registered.id))
                    assertEquals(
                        listOf(event.id.value),
                        state.eventRows().map { it.eventId },
                    )
                }
            }
        }

    /** 同一文件里先提交、后回滚：重开后只剩提交的那部分（A04）。 */
    @Test
    fun `only committed work survives a restart`() =
        runBlocking {
            withTempFile { file ->
                val kept = testRecord("/notes/kept.txt")
                val dropped = testRecord("/notes/dropped.txt")

                VfsStateDatabase.file(file).use { state ->
                    val uow = SqliteUnitOfWork(state)
                    uow.inTransaction { scope ->
                        scope.nodes.register(kept)
                        scope.metadata.put(kept.id, metadata)
                    }
                    runCatching {
                        uow.inTransaction { scope ->
                            scope.nodes.register(dropped)
                            scope.events.append(event)
                            error("boom")
                        }
                    }
                }

                VfsStateDatabase.file(file).use { state ->
                    val nodes = SqliteNodeRepository(state)
                    assertEquals(kept, nodes.findByPath(kept.path))
                    assertNull(nodes.findByPath(dropped.path))
                    assertNull(nodes.findById(dropped.id))
                    assertEquals(metadata, SqliteMetadataRepository(state).get(kept.id))
                    assertEquals(0L, state.eventCount())
                    assertEquals(1L, state.activeNodeCount())
                }
            }
        }

    /** 软删除状态是持久的：重开后不会复活，也不需要重新标记（A04）。 */
    @Test
    fun `soft deleted nodes stay deleted after reopening`() =
        runBlocking {
            withTempFile { file ->
                val doomed = testRecord("/notes/doomed.txt")
                val survivor = testRecord("/notes/survivor.txt")

                VfsStateDatabase.file(file).use { state ->
                    SqliteNodeRepository(state).register(doomed)
                    SqliteNodeRepository(state).register(survivor)
                    SqliteNodeRepository(state).markDeleted(listOf(doomed.id), TEST_NOW)
                }

                VfsStateDatabase.file(file).use { state ->
                    val nodes = SqliteNodeRepository(state)
                    assertNull(nodes.findByPath(doomed.path))
                    assertNull(nodes.findById(doomed.id))
                    assertEquals(listOf(survivor), nodes.findSubtree(VfsPath.parse("/notes")))
                    // 删除标记仍在库里，不是"查不到所以像没了"。
                    assertEquals(1L, state.deletedNodeCount())
                }
            }
        }

    /** 空库按当前版本建表；再次打开既不重建也不改版本，数据完好（A05）。 */
    @Test
    fun `a fresh database is created once and reopened without recreating the schema`() =
        runBlocking {
            withTempFile { file ->
                assertTrue(!Files.exists(file), "打开前不应存在文件")

                val registered =
                    VfsStateDatabase.file(file).use { state ->
                        assertEquals(VfsDatabase.Schema.version, state.driver.schemaVersion())
                        SqliteNodeRepository(state).register(testRecord("/notes/a.txt"))
                    }

                repeat(2) {
                    VfsStateDatabase.file(file).use { state ->
                        assertEquals(VfsDatabase.Schema.version, state.driver.schemaVersion())
                        assertEquals(registered, SqliteNodeRepository(state).findByPath(registered.path))
                        assertEquals(1L, state.activeNodeCount())
                    }
                }
            }
        }

    /** 基线 schema 是版本 2（T18 给 mount 表补物理身份，1.sqm 把 1 → 2）。 */
    @Test
    fun `the baseline schema is version two`() {
        assertEquals(2L, VfsDatabase.Schema.version)
    }

    /**
     * A05 / R2：库版本比代码新时拒绝打开，原文件的版本号与数据都不许动。
     *
     * 修复前 `currentVersion != schema.version` 会把新版库也送进 `migrate`（当前是空操作），
     * 随后无条件写回 `PRAGMA user_version = 1`，用户的新版库被旧代码静默改低版本号。
     */
    @Test
    fun `a database newer than the code is rejected and left untouched`() =
        runBlocking {
            withTempFile { file ->
                val registered =
                    VfsStateDatabase.file(file).use { state ->
                        SqliteNodeRepository(state).register(testRecord("/notes/a.txt"))
                    }
                val newerVersion = VfsDatabase.Schema.version + 1

                // 模拟「新版本代码写出来的库」：结构不变，只把版本号抬高。
                assertEquals(VfsDatabase.Schema.version, rawLong(file, "PRAGMA user_version"))
                rawStatement(file, "PRAGMA user_version = $newerVersion")

                val failure = assertThrows(VfsException::class.java) { VfsStateDatabase.file(file) }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(newerVersion, rawLong(file, "PRAGMA user_version"), "被拒绝后不允许改写原库版本号")
                assertEquals(1L, rawLong(file, "SELECT count(*) FROM node"), "被拒绝后不允许改动原库数据")

                // 把版本号放回当前版本后照常打开：数据确实一个字节没少。
                rawStatement(file, "PRAGMA user_version = ${VfsDatabase.Schema.version}")
                VfsStateDatabase.file(file).use { state ->
                    assertEquals(registered, SqliteNodeRepository(state).findByPath(registered.path))
                }
            }
        }

    /** close 之后可以再次打开同一文件；文件也能被删除，说明没有留下写句柄（A04）。 */
    @Test
    fun `a closed database can be opened again and releases its file`() =
        runBlocking {
            withTempFile { file ->
                val first = VfsStateDatabase.file(file)
                SqliteNodeRepository(first).register(testRecord("/notes/first.txt"))
                first.close()

                VfsStateDatabase.file(file).use { state ->
                    assertEquals(1L, state.activeNodeCount())
                }

                Files.deleteIfExists(file)
                assertTrue(!Files.exists(file))
            }
        }

    /** 直连读一个标量（`PRAGMA user_version`、`count(*)`），绕开 VFS 打开逻辑检查文件本身。 */
    private fun rawLong(
        file: Path,
        sql: String,
    ): Long =
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { cursor ->
                    check(cursor.next()) { "查询无结果：$sql" }
                    cursor.getLong(1)
                }
            }
        }

    private fun rawStatement(
        file: Path,
        sql: String,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
