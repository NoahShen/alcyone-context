package com.github.noahshen.alcyone.context.vfs.core.operation

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T10 A01～A05：A01～A03 为 S1 的结构与参数判定，A04～A05 为 S2 的只读与能力组合。
 * 预检是纯逻辑，不接触 Storage / Repository。每个测试方法名标注对应的验收编号。
 */
class OperationGuardTest {
    // A01 结构性变更保护
    @Test
    fun `A01 write rejects the logical root, a namespace root, a mount root and a derived directory`() {
        for (target in PROTECTED) {
            val ex = assertThrows(VfsException::class.java) { guard(OperationIntent.write(path(target))) }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code, "write must reject '$target'")
            assertTrue(ex.message!!.contains(target), "message must name the logical path: ${ex.message}")
        }
    }

    @Test
    fun `A01 delete rejects the logical root, a namespace root, a mount root and a derived directory`() {
        for (source in PROTECTED) {
            val ex =
                assertThrows(VfsException::class.java) { guard(OperationIntent.delete(path(source), NodeType.DIRECTORY, recursive = true)) }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code, "delete must reject '$source'")
        }
    }

    @Test
    fun `A01 move rejects a protected source and a protected target`() {
        // 目标用 /other/x.txt：它不在任何受保护源的子树内，否则会先命中参数冲突。
        // 逻辑根 '/' 不作为源出现在这里：它是所有路径的祖先，同样先命中"目标入源子树"。
        for (source in listOf("/memory", "/notes", "/notes/team", "/notes/team/docs")) {
            val ex =
                assertThrows(VfsException::class.java) {
                    guard(OperationIntent.move(path(source), path("/other/x.txt"), NodeType.DIRECTORY))
                }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code, "move must reject source '$source'")
        }
        for (target in PROTECTED) {
            val ex =
                assertThrows(VfsException::class.java) {
                    guard(OperationIntent.move(path("/notes/team/docs/a.txt"), path(target), NodeType.FILE))
                }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code, "move must reject target '$target'")
        }
    }

    @Test
    fun `A01 ordinary entries inside a mount pass the structure check`() {
        val result = guard(OperationIntent.write(path("/notes/team/docs/a.txt")))
        assertEquals("docs-disk", result.target!!.mount.storageKey)
        assertEquals("a.txt", result.target!!.relativePath.toRelativeString())
        assertNull(result.source)
        assertEquals(
            path("/notes/team/docs"),
            guard(OperationIntent.delete(path("/notes/team/docs/a.txt"), NodeType.FILE)).source!!.mount.path,
        )
    }

    @Test
    fun `A01 the intent factories keep the operation shape`() {
        val write = OperationIntent.write(path("/memory/a.txt"), WriteMode.CREATE_NEW)
        assertEquals(OperationType.WRITE, write.type)
        assertNull(write.source)
        assertEquals(path("/memory/a.txt"), write.target)
        assertEquals(NodeType.FILE, write.entryType)
        assertEquals(WriteMode.CREATE_NEW, write.writeMode)

        val move = OperationIntent.move(path("/notes/a.txt"), path("/notes/b.txt"), NodeType.DIRECTORY)
        assertEquals(OperationType.MOVE, move.type)
        assertEquals(path("/notes/a.txt"), move.source)
        assertEquals(path("/notes/b.txt"), move.target)
        assertEquals(NodeType.DIRECTORY, move.entryType)
        assertNull(move.writeMode)

        val delete = OperationIntent.delete(path("/notes/a.txt"), NodeType.FILE, recursive = true)
        assertEquals(OperationType.DELETE, delete.type)
        assertTrue(delete.recursive)
        assertNull(delete.target)
        assertNull(delete.writeMode)
    }

    // A02 嵌套挂载
    @Test
    fun `A02 deleting a directory with a descendant mount is rejected even when recursive`() {
        for (source in listOf("/notes/team", "/notes")) {
            for (recursive in listOf(false, true)) {
                val ex =
                    assertThrows(VfsException::class.java) {
                        guard(OperationIntent.delete(path(source), NodeType.DIRECTORY, recursive = recursive))
                    }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code, "delete must reject '$source' (recursive=$recursive)")
            }
        }
    }

    @Test
    fun `A02 moving a directory with a descendant mount is rejected`() {
        val ex =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/notes/team"), path("/memory/team"), NodeType.DIRECTORY))
            }
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code)
        assertTrue(ex.message!!.contains("/notes/team"))
    }

    @Test
    fun `A02 a move target that is a mount point or a mount ancestor is rejected`() {
        // 目标包含已配置挂载时，整个 move 在物理变更前拒绝（T02 §7.2、§8.2）。
        for (target in listOf("/notes", "/notes/team", "/notes/team/docs")) {
            val ex =
                assertThrows(VfsException::class.java) {
                    guard(OperationIntent.move(path("/memory/a.txt"), path(target), NodeType.FILE))
                }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code, "move target must reject '$target'")
        }
    }

    @Test
    fun `A02 plain files inside the nested mount are unaffected`() {
        val deleted = guard(OperationIntent.delete(path("/notes/team/docs/a.txt"), NodeType.FILE))
        assertEquals("docs-disk", deleted.source!!.mount.storageKey)
        assertEquals(StoragePath.parse("a.txt"), deleted.source!!.relativePath)

        val moved = guard(OperationIntent.move(path("/notes/team/docs/a.txt"), path("/notes/team/docs/b.txt"), NodeType.FILE))
        assertEquals("docs-disk", moved.source!!.mount.storageKey)
        assertEquals("docs-disk", moved.target!!.mount.storageKey)
    }

    // A03 参数冲突与路由判定
    @Test
    fun `A03 move to the same path is rejected`() {
        val ex =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/notes/team/docs/a.txt"), path("/notes/team/docs/a.txt/"), NodeType.FILE))
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `A03 move into the source subtree is rejected`() {
        val ex =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/notes/a"), path("/notes/a/b"), NodeType.DIRECTORY))
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(ex.message!!.contains("/notes/a/b"))
    }

    @Test
    fun `A03 parameter conflicts are reported before structural protection`() {
        // 源是挂载根、目标在其子树内：结构检查会拒绝，但参数冲突更早。
        val ex =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/notes/team/docs"), path("/notes/team/docs/b"), NodeType.DIRECTORY))
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, ex.code)

        // 目标是逻辑根 '/'：它不是源的子路径，落到结构保护被拒。
        val rootTarget =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/memory/a.txt"), path("/"), NodeType.FILE))
            }
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, rootTarget.code)
    }

    @Test
    fun `A03 an unmapped write target reports MOUNT_NOT_FOUND`() {
        val ex = assertThrows(VfsException::class.java) { guard(OperationIntent.write(path("/other/x"))) }
        assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, ex.code)
        assertTrue(ex.message!!.contains("/other/x"))
        assertFalse(ex.message!!.contains("disk")) // 不泄露 storageKey
    }

    @Test
    fun `A03 an unmapped delete source and move path report MOUNT_NOT_FOUND`() {
        val deleted = assertThrows(VfsException::class.java) { guard(OperationIntent.delete(path("/other/a.txt"), NodeType.FILE)) }
        assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, deleted.code)

        val moved =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/other/a.txt"), path("/memory/a.txt"), NodeType.FILE))
            }
        assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, moved.code)
    }

    @Test
    fun `A03 structural protection is reported before routing`() {
        // /notes 与 /notes/team 都没有自己的挂载（route == null），却是 /notes/team/docs 的祖先目录：先报结构冲突。
        val ex = assertThrows(VfsException::class.java) { guard(OperationIntent.write(path("/notes"))) }
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code)

        // 受保护目录之外的未挂载路径才报 MOUNT_NOT_FOUND。
        val unmapped =
            assertThrows(VfsException::class.java) { guard(OperationIntent.write(path("/notes/team/x.txt"))) }
        assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, unmapped.code)

        val deleted =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.delete(path("/notes/team"), NodeType.DIRECTORY, recursive = true))
            }
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, deleted.code)
    }

    @Test
    fun `A03 the allowed result carries the matched mounts and relative storage paths`() {
        val same = guard(OperationIntent.move(path("/notes/team/docs/a.txt"), path("/notes/team/docs/sub/b.txt"), NodeType.FILE))
        assertEquals(path("/notes/team/docs"), same.source!!.mount.path)
        assertEquals("a.txt", same.source!!.relativePath.toRelativeString())
        assertEquals(same.source!!.mount, same.target!!.mount)
        assertEquals("sub/b.txt", same.target!!.relativePath.toRelativeString())

        val cross = guard(OperationIntent.move(path("/notes/team/docs/a.txt"), path("/memory/a.txt"), NodeType.FILE))
        assertEquals("docs-disk", cross.source!!.mount.storageKey)
        assertEquals("a.txt", cross.source!!.relativePath.toRelativeString())
        assertEquals("local-disk", cross.target!!.mount.storageKey)
        assertEquals("a.txt", cross.target!!.relativePath.toRelativeString())
    }

    // 基础行为：快照不可变、空挂载集合
    @Test
    fun `A06 the capability snapshot is immutable after construction`() {
        val capabilities = mutableMapOf("docs-disk" to StorageCapabilities(nativeFileMove = false))
        val snapshot = CapabilitySnapshot.of(capabilities)

        capabilities["local-disk"] = StorageCapabilities()
        capabilities["docs-disk"] = StorageCapabilities(nativeFileMove = true)

        assertFalse(snapshot.capabilitiesOf("docs-disk")!!.nativeFileMove)
        assertNull(snapshot.capabilitiesOf("local-disk"))
        assertNull(snapshot.capabilitiesOf("unknown-disk"))
    }

    @Test
    fun `A06 without mounts every change is rejected`() {
        val empty = MountRouter.of(setOf("notes"), emptyList())
        assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, rejected { guard(OperationIntent.write(path("/notes/a.txt")), empty) })
        assertEquals(VfsErrorCode.MOUNT_NOT_FOUND, rejected { guard(OperationIntent.delete(path("/notes/a.txt"), NodeType.FILE), empty) })
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, rejected { guard(OperationIntent.write(path("/notes")), empty) })
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, rejected { guard(OperationIntent.write(path("/")), empty) })
    }

    @Test
    fun `A06 a namespace root mounted on its own keeps its ordinary entries operable`() {
        val router = MountRouter.of(setOf("memory"), listOf(mount("/memory", "local-disk")))
        val result = guard(OperationIntent.write(path("/memory/notes.md")), router)
        assertEquals("local-disk", result.target!!.mount.storageKey)
        assertEquals("notes.md", result.target!!.relativePath.toRelativeString())
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, rejected { guard(OperationIntent.write(path("/memory")), router) })
    }

    // A04 只读预检
    @Test
    fun `A04 write and delete on a read-only mount are rejected`() {
        val readOnlyDocs = snapshot(docs = StorageCapabilities(readOnly = true))

        val write =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.write(path("/notes/team/docs/a.txt")), NESTED, readOnlyDocs)
            }
        assertEquals(VfsErrorCode.READ_ONLY, write.code)
        assertTrue(write.message!!.contains("/notes/team/docs/a.txt"))
        assertTrue(write.message!!.contains("target"))
        assertFalse(write.message!!.contains("docs-disk")) // 不泄露 storageKey

        val deleted =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.delete(path("/notes/team/docs/a.txt"), NodeType.FILE), NESTED, readOnlyDocs)
            }
        assertEquals(VfsErrorCode.READ_ONLY, deleted.code)
        assertTrue(deleted.message!!.contains("source"))
    }

    @Test
    fun `A04 move is rejected when either side is read-only`() {
        val intent = OperationIntent.move(path("/notes/team/docs/a.txt"), path("/memory/a.txt"), NodeType.FILE)
        val readOnly = StorageCapabilities(readOnly = true)
        for (caps in listOf(snapshot(docs = readOnly), snapshot(local = readOnly), snapshot(docs = readOnly, local = readOnly))) {
            assertEquals(VfsErrorCode.READ_ONLY, rejected { guard(intent, NESTED, caps) })
        }
    }

    @Test
    fun `A04 readOnly defaults to false`() {
        assertFalse(StorageCapabilities().readOnly)
        assertFalse(StorageCapabilities(nativeFileMove = false).readOnly)
        // 默认快照全部可写，既有行为不受影响。
        assertEquals(ExecutionStrategy.DIRECT_WRITE, guard(OperationIntent.write(path("/notes/team/docs/a.txt"))).strategy)
    }

    @Test
    fun `A04 an incomplete capability snapshot is rejected`() {
        // write 的目标落在 docs-disk，快照只有 local-disk 即为不完整。
        val missingDocs = CapabilitySnapshot.of(mapOf("local-disk" to StorageCapabilities()))
        val write =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.write(path("/notes/team/docs/a.txt")), NESTED, missingDocs)
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, write.code)
        assertTrue(write.message!!.contains("incomplete"))
        assertTrue(write.message!!.contains("/notes/team/docs/a.txt"))

        // move 的目标落在 local-disk，快照只有 docs-disk 即为不完整。
        val missingLocal = CapabilitySnapshot.of(mapOf("docs-disk" to StorageCapabilities()))
        val moved =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/notes/team/docs/a.txt"), path("/memory/a.txt"), NodeType.FILE), NESTED, missingLocal)
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, moved.code)
        assertTrue(moved.message!!.contains("target"))
    }

    // A05 能力组合与执行策略
    @Test
    fun `A05 a same-mount file move prefers the native strategy`() {
        val result =
            guard(
                OperationIntent.move(path("/notes/team/docs/a.txt"), path("/notes/team/docs/b.txt"), NodeType.FILE),
                NESTED,
                snapshot(docs = StorageCapabilities(nativeFileMove = true)),
            )
        assertEquals(ExecutionStrategy.NATIVE_MOVE, result.strategy)
        assertEquals("docs-disk", result.source!!.mount.storageKey)
        assertEquals("b.txt", result.target!!.relativePath.toRelativeString())
    }

    @Test
    fun `A05 a same-mount file move falls back to copy without the native capability`() {
        // 文件复制回退总是可行，不要求 createDirectory。
        val caps = snapshot(docs = StorageCapabilities(nativeFileMove = false, createDirectory = false))
        val result =
            guard(
                OperationIntent.move(path("/notes/team/docs/a.txt"), path("/notes/team/docs/b.txt"), NodeType.FILE),
                NESTED,
                caps,
            )
        assertEquals(ExecutionStrategy.COPY_FALLBACK_MOVE, result.strategy)
    }

    @Test
    fun `A05 a same-mount directory move prefers the native strategy`() {
        val caps = snapshot(docs = StorageCapabilities(nativeDirectoryMove = true, createDirectory = false))
        val result =
            guard(
                OperationIntent.move(path("/notes/team/docs/sub"), path("/notes/team/docs/other"), NodeType.DIRECTORY),
                NESTED,
                caps,
            )
        assertEquals(ExecutionStrategy.NATIVE_MOVE, result.strategy)
    }

    @Test
    fun `A05 a same-mount directory move falls back when the native capability is missing`() {
        val caps = snapshot(docs = StorageCapabilities(nativeDirectoryMove = false, createDirectory = true))
        val result =
            guard(
                OperationIntent.move(path("/notes/team/docs/sub"), path("/notes/team/docs/other"), NodeType.DIRECTORY),
                NESTED,
                caps,
            )
        assertEquals(ExecutionStrategy.COPY_FALLBACK_MOVE, result.strategy)
    }

    @Test
    fun `A05 a same-mount directory fallback requires createDirectory`() {
        val caps = snapshot(docs = StorageCapabilities(nativeDirectoryMove = false, createDirectory = false))
        val ex =
            assertThrows(VfsException::class.java) {
                guard(OperationIntent.move(path("/notes/team/docs/sub"), path("/notes/team/docs/other"), NodeType.DIRECTORY), NESTED, caps)
            }
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code)
        assertTrue(ex.message!!.contains("/notes/team/docs/other"))
    }

    @Test
    fun `A05 a cross-mount move always uses copy semantics`() {
        // 两侧原生能力都齐全，跨挂载点仍按复制语义执行与检查（T10 §2.4 取舍）。
        val caps =
            snapshot(
                docs = StorageCapabilities(nativeFileMove = true, nativeDirectoryMove = true, createDirectory = false),
                local = StorageCapabilities(nativeFileMove = true, nativeDirectoryMove = true),
            )
        val result = guard(OperationIntent.move(path("/notes/team/docs/a.txt"), path("/memory/a.txt"), NodeType.FILE), NESTED, caps)
        assertEquals(ExecutionStrategy.CROSS_MOUNT_COPY_MOVE, result.strategy)
        assertEquals("docs-disk", result.source!!.mount.storageKey)
        assertEquals("local-disk", result.target!!.mount.storageKey)
    }

    @Test
    fun `A05 a cross-mount directory move requires createDirectory on the target`() {
        val intent = OperationIntent.move(path("/notes/team/docs/sub"), path("/memory/sub"), NodeType.DIRECTORY)
        val missing =
            assertThrows(VfsException::class.java) {
                guard(intent, NESTED, snapshot(docs = StorageCapabilities(), local = StorageCapabilities(createDirectory = false)))
            }
        assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, missing.code)
        assertTrue(missing.message!!.contains("target"))

        val allowed = guard(intent, NESTED, snapshot(local = StorageCapabilities(createDirectory = true)))
        assertEquals(ExecutionStrategy.CROSS_MOUNT_COPY_MOVE, allowed.strategy)
    }

    @Test
    fun `A05 two mounts sharing one storage still use cross-mount copy semantics`() {
        val router =
            MountRouter.of(
                setOf("notes"),
                listOf(mount("/notes/team/docs", "docs-disk"), mount("/notes/team/wiki", "docs-disk")),
            )
        val intent = OperationIntent.move(path("/notes/team/docs/a.txt"), path("/notes/team/wiki/a.txt"), NodeType.FILE)
        assertEquals(ExecutionStrategy.CROSS_MOUNT_COPY_MOVE, guard(intent, router, snapshot()).strategy)

        val directory = OperationIntent.move(path("/notes/team/docs/sub"), path("/notes/team/wiki/sub"), NodeType.DIRECTORY)
        assertEquals(
            VfsErrorCode.UNSUPPORTED_OPERATION,
            rejected { guard(directory, router, snapshot(docs = StorageCapabilities(createDirectory = false))) },
        )
    }

    @Test
    fun `A05 write and delete report direct strategies`() {
        val write = guard(OperationIntent.write(path("/notes/team/docs/a.txt"), WriteMode.CREATE_NEW))
        assertEquals(ExecutionStrategy.DIRECT_WRITE, write.strategy)
        assertNull(write.source)
        assertEquals("a.txt", write.target!!.relativePath.toRelativeString())

        val deleted = guard(OperationIntent.delete(path("/memory/a.txt"), NodeType.FILE))
        assertEquals(ExecutionStrategy.DIRECT_DELETE, deleted.strategy)
        assertEquals("local-disk", deleted.source!!.mount.storageKey)
        assertEquals("a.txt", deleted.source!!.relativePath.toRelativeString())
        assertNull(deleted.target)
    }

    @Test
    fun `A05 read-only is reported before missing capabilities`() {
        // 目标既只读又缺 createDirectory：只读检查先于能力组合。
        val intent = OperationIntent.move(path("/notes/team/docs/sub"), path("/memory/sub"), NodeType.DIRECTORY)
        val caps = snapshot(docs = StorageCapabilities(readOnly = true), local = StorageCapabilities(createDirectory = false))
        val ex = assertThrows(VfsException::class.java) { guard(intent, NESTED, caps) }
        assertEquals(VfsErrorCode.READ_ONLY, ex.code)
        assertTrue(ex.message!!.contains("source"))
    }

    private fun rejected(block: () -> Unit): VfsErrorCode = assertThrows(VfsException::class.java, block).code

    private fun guard(
        intent: OperationIntent,
        router: MountRouter = NESTED,
        capabilities: CapabilitySnapshot = CAPABILITIES,
    ): PreconditionResult = OperationGuard.check(intent, router, capabilities)

    private fun path(text: String): VfsPath = VfsPath.parse(text)

    private fun mount(
        text: String,
        key: String,
    ): MountRecord = MountRecord(path(text), key)

    private companion object {
        /** 逻辑根、命名空间根 / Mount 根、挂载祖先：全部是 T02 §8.2 禁止变更的路径。 */
        val PROTECTED = listOf("/", "/memory", "/notes", "/notes/team", "/notes/team/docs")

        /** 挂载 `/notes/team/docs` 使其祖先成为配置目录；`/memory` 自身是挂载的命名空间根。 */
        val NESTED =
            MountRouter.of(
                setOf("notes", "memory"),
                listOf(MountRecord(VfsPath.parse("/notes/team/docs"), "docs-disk"), MountRecord(VfsPath.parse("/memory"), "local-disk")),
            )

        val CAPABILITIES = snapshot()

        /** 两个存储的能力快照；缺省表示可写、支持原生 move 与创建目录。 */
        fun snapshot(
            docs: StorageCapabilities = StorageCapabilities(),
            local: StorageCapabilities = StorageCapabilities(),
        ): CapabilitySnapshot = CapabilitySnapshot.of(mapOf("docs-disk" to docs, "local-disk" to local))
    }
}
