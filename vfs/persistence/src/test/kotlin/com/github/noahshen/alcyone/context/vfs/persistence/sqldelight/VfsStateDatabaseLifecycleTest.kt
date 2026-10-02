package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

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

    /** 基线 schema 就是版本 1（A05）。 */
    @Test
    fun `the baseline schema is version one`() {
        assertEquals(1L, VfsDatabase.Schema.version)
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
}
