package com.github.noahshen.alcyone.context.vfs.core.repository

import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RepositoryTest {
    private val nodeRepo = InMemoryNodeRepository()
    private val metaRepo = InMemoryMetadataRepository()
    private val eventRepo = InMemoryEventRepository()

    private fun nextNodeId(): NodeId = NodeId.parse(newUuidV7().toString())

    private fun nextEventId(): VfsEventId = VfsEventId.parse(newUuidV7().toString())

    // 1. R1~R6: Node 的注册、查询、竞争复用、路径更新、touch 与标记删除
    @Test
    fun `node lifecycle operations`() =
        runBlocking {
            val now = Instant.now()
            val path1 = VfsPath.parse("/notes/doc.txt")
            val node1 =
                NodeRecord(
                    id = nextNodeId(),
                    path = path1,
                    type = NodeType.FILE,
                    physical = true,
                    registeredAt = now,
                    updatedAt = now,
                )

            // R3: 注册
            val registered = nodeRepo.register(node1)
            assertEquals(node1, registered)

            // R1 / R2: 按 Path 和 ID 查询
            assertEquals(node1, nodeRepo.findByPath(path1))
            assertEquals(node1, nodeRepo.findById(node1.id))

            // R3: 竞争注册同一路径，返回已存在的记录
            val duplicateNode =
                NodeRecord(
                    id = nextNodeId(),
                    path = path1,
                    type = NodeType.FILE,
                    physical = true,
                    registeredAt = now,
                    updatedAt = now,
                )
            val reused = nodeRepo.register(duplicateNode)
            assertEquals(node1.id, reused.id)
            assertEquals(node1, reused)

            // R5: touch 更新时间
            val touchTime = now.plusSeconds(10)
            nodeRepo.touch(node1.id, touchTime)
            assertEquals(touchTime, nodeRepo.findById(node1.id)?.updatedAt)

            // R4: 路径更新（移动）
            val newPath = VfsPath.parse("/archive/doc.txt")
            val moveTime = now.plusSeconds(20)
            nodeRepo.updatePath(node1.id, newPath, moveTime)
            assertNull(nodeRepo.findByPath(path1))
            val movedNode = nodeRepo.findByPath(newPath)
            assertNotNull(movedNode)
            assertEquals(node1.id, movedNode!!.id)
            assertEquals(newPath, movedNode.path)
            assertEquals(moveTime, movedNode.updatedAt)

            // R6: 标记删除
            nodeRepo.markDeleted(listOf(node1.id), now.plusSeconds(30))
            assertNull(nodeRepo.findByPath(newPath))
            assertNull(nodeRepo.findById(node1.id))
        }

    // 2. 重点验证段边界子树查询（G10）：/a 匹配 /a, /a/b，绝不匹配 /ab
    @Test
    fun `subtree query strictly respects segment boundaries`() =
        runBlocking {
            val now = Instant.now()
            val pathA = VfsPath.parse("/notes/a")
            val pathAB = VfsPath.parse("/notes/ab")
            val pathAChild = VfsPath.parse("/notes/a/child.txt")
            val pathAGrandChild = VfsPath.parse("/notes/a/sub/grand.txt")
            val pathOther = VfsPath.parse("/other/a")

            listOf(pathA, pathAB, pathAChild, pathAGrandChild, pathOther).forEach { path ->
                nodeRepo.register(
                    NodeRecord(
                        id = nextNodeId(),
                        path = path,
                        type = NodeType.FILE,
                        physical = true,
                        registeredAt = now,
                        updatedAt = now,
                    ),
                )
            }

            // 查询 /notes/a 的子树
            val subtree = nodeRepo.findSubtree(pathA)
            val matchedPaths = subtree.map { it.path }.toSet()

            assertTrue(matchedPaths.contains(pathA), "Subtree must contain itself")
            assertTrue(matchedPaths.contains(pathAChild), "Subtree must contain direct child")
            assertTrue(matchedPaths.contains(pathAGrandChild), "Subtree must contain deep descendant")
            assertFalse(matchedPaths.contains(pathAB), "Subtree must NOT contain sibling path starting with same prefix: /notes/ab")
            assertFalse(matchedPaths.contains(pathOther), "Subtree must NOT contain unrelated path: /other/a")
            assertEquals(3, subtree.size)
        }

    // 3. 重点验证虚拟节点（G9）：physical = false 的节点正常注册与查询
    @Test
    fun `virtual directory nodes support physical false`() =
        runBlocking {
            val now = Instant.now()
            val virtualDir =
                NodeRecord(
                    id = nextNodeId(),
                    path = VfsPath.parse("/virtual/group"),
                    type = NodeType.DIRECTORY,
                    physical = false, // 纯虚拟目录，无底层物理文件/目录映射 (G9)
                    registeredAt = now,
                    updatedAt = now,
                )

            val physicalFile =
                NodeRecord(
                    id = nextNodeId(),
                    path = VfsPath.parse("/virtual/group/real.txt"),
                    type = NodeType.FILE,
                    physical = true,
                    registeredAt = now,
                    updatedAt = now,
                )

            nodeRepo.register(virtualDir)
            nodeRepo.register(physicalFile)

            val queriedVirtual = nodeRepo.findByPath(virtualDir.path)
            assertNotNull(queriedVirtual)
            assertFalse(queriedVirtual!!.physical, "Virtual node physical must be false")

            val queriedPhysical = nodeRepo.findByPath(physicalFile.path)
            assertNotNull(queriedPhysical)
            assertTrue(queriedPhysical!!.physical, "Physical node physical must be true")
        }

    // 4. R8: findByPaths 批量查询节点
    @Test
    fun `findByPaths batch lookup`() =
        runBlocking {
            val now = Instant.now()
            val p1 = VfsPath.parse("/batch/1.txt")
            val p2 = VfsPath.parse("/batch/2.txt")
            val p3 = VfsPath.parse("/batch/3.txt")

            nodeRepo.register(NodeRecord(nextNodeId(), p1, NodeType.FILE, true, now, now))
            nodeRepo.register(NodeRecord(nextNodeId(), p2, NodeType.FILE, true, now, now))

            val results = nodeRepo.findByPaths(listOf(p1, p3, p2))
            assertEquals(2, results.size)
            val resultPaths = results.map { it.path }
            assertTrue(resultPaths.contains(p1))
            assertTrue(resultPaths.contains(p2))
            assertFalse(resultPaths.contains(p3))
        }

    // 5. R9: MetadataRepository 增删改查
    @Test
    fun `metadata repository operations`() =
        runBlocking {
            val id = nextNodeId()
            assertNull(metaRepo.get(id))

            val meta = NodeMetadata(tags = setOf("important", "author"))
            metaRepo.put(id, meta)
            assertEquals(meta, metaRepo.get(id))

            // 覆盖替换
            val meta2 = NodeMetadata(tags = setOf("archived"), description = "archived document")
            metaRepo.put(id, meta2)
            assertEquals(meta2, metaRepo.get(id))

            // 空对象替换等同于清空
            metaRepo.put(id, NodeMetadata())
            assertNull(metaRepo.get(id))

            metaRepo.put(id, meta)
            metaRepo.delete(id)
            assertNull(metaRepo.get(id))
        }

    // 6. R10 / R11: Event 追加与 Mount 只读查询
    @Test
    fun `event append and mount list`() =
        runBlocking {
            val event =
                EventRecord(
                    id = nextEventId(),
                    type = VfsEventType.FILE_CREATED,
                    nodeId = nextNodeId(),
                    occurredAt = Instant.now(),
                    uri = VfsUri.parse("alcyone://resources/notes/doc.txt"),
                    operationId = "op-uuidv7-1234",
                )
            eventRepo.append(event)
            assertEquals(1, eventRepo.events.size)
            assertEquals(event, eventRepo.events[0])

            // 首版不提供可配置的只读挂载：MountRecord 只有路径与存储键两个字段（R4）
            val mounts =
                listOf(
                    MountRecord(VfsPath.parse("/resources/notes"), "storage-local-1"),
                    MountRecord(VfsPath.parse("/resources/shared"), "storage-webdav-2"),
                )
            val mountRepo = InMemoryMountRepository(mounts)
            val list = mountRepo.list()
            assertEquals(2, list.size)
            assertEquals("/resources/notes", list[0].path.toString())
            assertEquals("storage-local-1", list[0].storageKey)
            assertEquals("storage-webdav-2", list[1].storageKey)
        }

    // 7. 防泄漏反射测试：Repository 接口和实体绝对不暴露第三方数据库 / SQLDelight 类型
    @Test
    fun `repository interfaces do not leak database or sqldelight types`() {
        val repoClasses =
            listOf(
                NodeRepository::class.java,
                MetadataRepository::class.java,
                EventRepository::class.java,
                MountRepository::class.java,
                NodeRecord::class.java,
                MountRecord::class.java,
                EventRecord::class.java,
            )
        val forbidden = listOf("sqldelight", "sqlite", "jdbc", "opendal")

        for (cls in repoClasses) {
            for (m in cls.declaredMethods) {
                val types = listOf(m.returnType) + m.parameterTypes
                for (type in types) {
                    val name = type.name.lowercase()
                    for (keyword in forbidden) {
                        assertFalse(
                            name.contains(keyword),
                            "Repository class ${cls.simpleName} method ${m.name} leaks forbidden type: $name",
                        )
                    }
                }
            }
        }
    }
}
