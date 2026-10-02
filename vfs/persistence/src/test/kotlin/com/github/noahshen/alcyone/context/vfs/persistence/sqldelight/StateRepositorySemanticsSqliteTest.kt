package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** A02：R4～R11 在真实 SQLite 上的查询与变更语义。 */
class StateRepositorySemanticsSqliteTest {
    private fun path(text: String): VfsPath = VfsPath.parse(text)

    // A02-1：改路径后 Node ID 不变，旧路径查不到
    @Test
    fun `updatePath moves the node and keeps the same id`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val original = nodes.register(testRecord("/notes/a.txt"))
                val moved = Instant.parse("2026-10-02T11:00:00Z")

                nodes.updatePath(original.id, path("/notes/b.txt"), moved)

                val reloaded = nodes.findByPath(path("/notes/b.txt"))
                assertEquals(original.id, reloaded?.id)
                assertEquals(moved, reloaded?.updatedAt)
                assertEquals(TEST_NOW, reloaded?.registeredAt)
                assertNull(nodes.findByPath(original.path))
                assertEquals(original.copy(path = path("/notes/b.txt"), updatedAt = moved), nodes.findById(original.id))
            }
        }

    // A02-1：新路径已被占用时报 STATE_ERROR，且不改动任何状态
    @Test
    fun `updatePath to an occupied path fails with STATE_ERROR`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val first = nodes.register(testRecord("/notes/a.txt"))
                val second = nodes.register(testRecord("/notes/b.txt"))

                val failure =
                    assertThrows(VfsException::class.java) {
                        runBlocking { nodes.updatePath(first.id, second.path, TEST_NOW) }
                    }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(first, nodes.findByPath(first.path))
                assertEquals(second, nodes.findByPath(second.path))
                assertEquals(2L, state.activeNodeCount())
            }
        }

    // A02-1：已删除节点不参与后续变更
    @Test
    fun `updatePath and touch ignore deleted nodes`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val original = nodes.register(testRecord("/notes/a.txt"))
                nodes.markDeleted(listOf(original.id), TEST_NOW)

                nodes.updatePath(original.id, path("/notes/b.txt"), TEST_NOW)
                nodes.touch(original.id, TEST_NOW)

                assertNull(nodes.findById(original.id))
                assertNull(nodes.findByPath(path("/notes/b.txt")))
            }
        }

    // A02-2：touch 只动 updatedAt
    @Test
    fun `touch updates only the updated timestamp`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val original = nodes.register(testRecord("/notes/a.txt"))
                val touched = Instant.parse("2026-10-03T09:30:00Z")

                nodes.touch(original.id, touched)

                assertEquals(original.copy(updatedAt = touched), nodes.findById(original.id))
            }
        }

    // A02-3：标记删除后所有正常查询都不再返回该节点
    @Test
    fun `deleted nodes disappear from every lookup`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val kept = nodes.register(testRecord("/notes/keep.txt"))
                val removed = nodes.register(testRecord("/notes/gone.txt"))
                nodes.markDeleted(listOf(removed.id), TEST_NOW)

                assertNull(nodes.findByPath(removed.path))
                assertNull(nodes.findById(removed.id))
                assertNull(nodes.findSubtree(path("/notes")).firstOrNull { it.id == removed.id })
                assertEquals(
                    listOf(kept),
                    nodes.findByPaths(listOf(kept.path, removed.path)),
                )
            }
        }

    // A02-4：子树按完整段边界匹配，同名前缀兄弟不算后代
    @Test
    fun `findSubtree returns self and descendants by segment boundary`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val self = nodes.register(testRecord("/notes/a"))
                val child = nodes.register(testRecord("/notes/a/b"))
                val grandChild = nodes.register(testRecord("/notes/a/b/c.txt"))
                nodes.register(testRecord("/notes/abc")) // 同名前缀兄弟，不是后代
                nodes.register(testRecord("/notes/a-b"))
                nodes.register(testRecord("/notes"))

                assertEquals(
                    listOf(self, child, grandChild).map { it.path },
                    nodes.findSubtree(path("/notes/a")).map { it.path },
                )
                // ORDER BY vfs_path 用 BINARY 排序：'-'(0x2D) < '/'(0x2F)，所以 /notes/a-b 排在 /notes/a/b 之前。
                assertEquals(
                    listOf("/notes", "/notes/a", "/notes/a-b", "/notes/a/b", "/notes/a/b/c.txt", "/notes/abc").map(::path),
                    nodes.findSubtree(path("/notes")).map { it.path },
                )
            }
        }

    // A02-4：子树比较大小写敏感
    @Test
    fun `findSubtree is case sensitive`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val lower = nodes.register(testRecord("/notes/a"))
                val upper = nodes.register(testRecord("/Notes/A/b"))

                assertEquals(listOf(lower.path), nodes.findSubtree(path("/notes/a")).map { it.path })
                assertEquals(listOf(path("/Notes/A/b")), nodes.findSubtree(path("/Notes/A")).map { it.path })
            }
        }

    // A02-4：逻辑根的子树是全部有效节点
    @Test
    fun `findSubtree of the logical root returns every active node`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                nodes.register(testRecord("/notes/a.txt"))
                val other = nodes.register(testRecord("/resources/b.txt"))

                assertEquals(2, nodes.findSubtree(VfsPath.root).size)
                assertEquals(
                    other.path,
                    nodes.findSubtree(VfsPath.root).first { it.id == other.id }.path,
                )
            }
        }

    // A02-5：批量查询只返回当前有效且存在的路径
    @Test
    fun `findByPaths returns only active matches and tolerates an empty list`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val nodes = SqliteNodeRepository(state)
                val a = nodes.register(testRecord("/notes/a.txt"))
                val b = nodes.register(testRecord("/notes/b.txt"))
                nodes.markDeleted(listOf(b.id), TEST_NOW)

                val found = nodes.findByPaths(listOf(a.path, b.path, path("/notes/missing.txt")))

                assertEquals(listOf(a.id), found.map { it.id })
                assertEquals(emptyList<Any>(), nodes.findByPaths(emptyList()))
            }
        }

    // A02-6：元数据 JSON 往返，空 tags / null description / 空 extensions 都要保持等值
    @Test
    fun `metadata survives a JSON round trip`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val metadata = SqliteMetadataRepository(state)
                val id = testRecord("/notes/a.txt").id
                val extensions = JsonObject(mapOf("reviewer" to JsonPrimitive("kim"), "score" to JsonPrimitive(3)))
                val cases =
                    listOf(
                        NodeMetadata(setOf("a", "b"), "描述", extensions),
                        NodeMetadata(setOf("a", "b"), "描述", JsonObject(emptyMap())),
                        NodeMetadata(setOf("ct"), null, extensions),
                        NodeMetadata(setOf("ct"), null, JsonObject(mapOf("x" to JsonPrimitive(1)))),
                    )

                cases.forEach { input ->
                    metadata.put(id, input)
                    assertEquals(input, metadata.get(id))
                }
                assertEquals(1L, state.metadataCount())
            }
        }

    // A02-6：整体替换，不与旧值合并
    @Test
    fun `put replaces metadata as a whole`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val metadata = SqliteMetadataRepository(state)
                val id = testRecord("/notes/a.txt").id
                metadata.put(id, NodeMetadata(setOf("old", "stale"), "旧描述", JsonObject(mapOf("k" to JsonPrimitive(1)))))
                val replacement = NodeMetadata(setOf("new"), null, JsonObject(emptyMap()))

                metadata.put(id, replacement)

                assertEquals(replacement, metadata.get(id))
                assertEquals(1L, state.metadataCount())
            }
        }

    // A02-6：空元数据代表清空（与 Core 内存实现一致：不留空行，get 返回 null）
    @Test
    fun `empty metadata clears the node`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val metadata = SqliteMetadataRepository(state)
                val id = testRecord("/notes/a.txt").id
                metadata.put(id, NodeMetadata(setOf("tag"), "描述", JsonObject(emptyMap())))
                assertEquals(NodeMetadata(setOf("tag"), "描述", JsonObject(emptyMap())), metadata.get(id))

                metadata.put(id, NodeMetadata())

                assertNull(metadata.get(id))
                assertEquals(0L, state.metadataCount())
            }
        }

    // A02-6：delete 清理元数据
    @Test
    fun `delete removes metadata`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val metadata = SqliteMetadataRepository(state)
                val id = testRecord("/notes/a.txt").id
                metadata.put(id, NodeMetadata(setOf("tag"), null, JsonObject(emptyMap())))

                metadata.delete(id)

                assertNull(metadata.get(id))
                metadata.delete(id) // 幂等
            }
        }

    // A02-7：事件全字段落库，可空列存 null
    @Test
    fun `append persists every event field including nulls`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val events = SqliteEventRepository(state)
                val nodeId = testRecord("/notes/a.txt").id
                val minimal = testEvent(nodeId, "alcyone://notes/a.txt")
                val full =
                    testEvent(
                        nodeId = null,
                        uri = "alcyone://notes/a.txt",
                        type = VfsEventType.FILE_MOVED,
                        occurredAt = Instant.parse("2026-10-04T08:00:00Z"),
                        operationId = "op-1",
                        sourceUri = "alcyone://notes/old.txt",
                        targetUri = "alcyone://notes/a.txt",
                    )

                events.append(minimal)
                events.append(full)

                val rows = state.eventRows()
                assertEquals(2, rows.size)
                val minimalRow = rows.first { it.eventId == minimal.id.value }
                assertEquals(VfsEventType.FILE_CREATED.name, minimalRow.eventType)
                assertEquals(nodeId.value, minimalRow.nodeId)
                assertEquals(TEST_NOW.toEpochMilli(), minimalRow.occurredAt)
                assertEquals(minimal.uri.toString(), minimalRow.uri)
                assertNull(minimalRow.operationId)
                assertNull(minimalRow.sourceUri)
                assertNull(minimalRow.targetUri)

                val fullRow = rows.first { it.eventId == full.id.value }
                assertNull(fullRow.nodeId)
                assertEquals("op-1", fullRow.operationId)
                assertEquals(full.sourceUri.toString(), fullRow.sourceUri)
                assertEquals(full.targetUri.toString(), fullRow.targetUri)
            }
        }

    // A02-7：事件主键重复映射 STATE_ERROR，不静默忽略
    @Test
    fun `duplicate event id fails with STATE_ERROR`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                val events = SqliteEventRepository(state)
                val event = testEvent(null, "alcyone://notes/a.txt")
                events.append(event)

                val failure = assertThrows(VfsException::class.java) { runBlocking { events.append(event) } }

                assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
                assertEquals(1L, state.eventCount())
            }
        }

    // A02-8：挂载点只读列出全部记录
    @Test
    fun `mount list returns every stored mapping`() =
        runBlocking {
            VfsStateDatabase.inMemory().use { state ->
                state.insertMount("/resources/", "local-1")
                state.insertMount("/memory/", "local-2")

                val mounts = SqliteMountRepository(state).list()

                // 尾部分隔符由 VfsPath 归一化，不入库。
                assertEquals(
                    listOf("/memory" to "local-2", "/resources" to "local-1"),
                    mounts.map { it.path.toString() to it.storageKey },
                )
                assertEquals(VfsPath.parse("/memory"), mounts.first().path)
            }
        }
}
