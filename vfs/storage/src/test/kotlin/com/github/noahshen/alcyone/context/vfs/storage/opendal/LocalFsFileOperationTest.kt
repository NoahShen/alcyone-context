package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * A03：三种写入模式、文件移动、创建目录、非递归 / 递归删除，以及类型与缺失契约（T12 §2.3）。
 *
 * 后端会覆盖写、会自动补父目录、会覆盖式 rename、会静默删除不存在的路径，
 * 所以这些用例同时证明适配层的预检在**没有副作用**的前提下先失败。
 */
class LocalFsFileOperationTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path

    private suspend fun openStorage() = LocalFsStorage.create(root)

    private fun setUpRoot() {
        root = Files.createDirectory(tempDir.resolve("root"))
    }

    // ---------- 三种写入模式 ----------

    @Test
    fun `CREATE_NEW creates a missing file and refuses an existing one without changing it`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                val created = storage.write(StoragePath.parse("a.txt"), "first".toByteArray(), StorageWriteMode.CREATE_NEW)
                assertEquals("first".length.toLong(), created.sizeBytes)

                val failure =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("a.txt"), "second".toByteArray(), StorageWriteMode.CREATE_NEW)
                    }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, failure.code)
                assertEquals(VfsEffect.NONE, failure.effect, "预检失败必须确认没有副作用")
                assertEquals("first", Files.readString(root.resolve("a.txt")))
            }
        }

    @Test
    fun `REPLACE_EXISTING replaces an existing file and refuses a missing one without creating it`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "first".toByteArray(), StorageWriteMode.UPSERT)

                val replaced = storage.write(StoragePath.parse("a.txt"), "second".toByteArray(), StorageWriteMode.REPLACE_EXISTING)
                assertEquals("second".length.toLong(), replaced.sizeBytes)
                assertEquals("second", Files.readString(root.resolve("a.txt")))

                val failure =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("b.txt"), "x".toByteArray(), StorageWriteMode.REPLACE_EXISTING)
                    }
                assertEquals(VfsErrorCode.NOT_FOUND, failure.code)
                assertEquals(VfsEffect.NONE, failure.effect)
                assertFalse(Files.exists(root.resolve("b.txt")), "REPLACE_EXISTING 不能创建缺失文件")
            }
        }

    @Test
    fun `UPSERT both creates and replaces`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "first".toByteArray(), StorageWriteMode.UPSERT)
                storage.write(StoragePath.parse("a.txt"), "second".toByteArray(), StorageWriteMode.UPSERT)

                assertEquals("second", Files.readString(root.resolve("a.txt")))
            }
        }

    @Test
    fun `write does not create missing parent directories`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                val failure =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("missing/a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
                    }

                assertEquals(VfsErrorCode.NOT_FOUND, failure.code)
                assertFalse(Files.exists(root.resolve("missing")), "不能隐式补父目录")
            }
        }

    @Test
    fun `write under a file parent and onto a directory are type mismatches`() =
        runBlocking {
            setUpRoot()
            Files.writeString(root.resolve("a.txt"), "a")
            Files.createDirectory(root.resolve("dir"))
            openStorage().use { storage ->
                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("a.txt/child.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
                    }.code,
                )
                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("dir"), "x".toByteArray(), StorageWriteMode.UPSERT)
                    }.code,
                )
                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> { storage.write(StoragePath.root, "x".toByteArray(), StorageWriteMode.UPSERT) }.code,
                )
            }
        }

    // ---------- move ----------

    @Test
    fun `a file move relocates real bytes and reports the target attributes`() =
        runBlocking {
            setUpRoot()
            Files.createDirectory(root.resolve("dir"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("dir/a.txt"), "payload".toByteArray(), StorageWriteMode.UPSERT)

                val moved = storage.move(StoragePath.parse("dir/a.txt"), StoragePath.parse("dir/b.txt"))

                assertEquals(NodeType.FILE, moved.type)
                assertEquals(7L, moved.sizeBytes)
                assertFalse(Files.exists(root.resolve("dir/a.txt")))
                assertEquals("payload", Files.readString(root.resolve("dir/b.txt")))
            }
        }

    @Test
    fun `a move never overwrites an existing target`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "a".toByteArray(), StorageWriteMode.UPSERT)
                storage.write(StoragePath.parse("b.txt"), "b".toByteArray(), StorageWriteMode.UPSERT)

                val failure =
                    assertFailsWith<VfsException> {
                        storage.move(StoragePath.parse("a.txt"), StoragePath.parse("b.txt"))
                    }

                assertEquals(VfsErrorCode.ALREADY_EXISTS, failure.code)
                assertEquals("b", Files.readString(root.resolve("b.txt")), "目标内容不能被覆盖")
                assertEquals("a", Files.readString(root.resolve("a.txt")), "源必须还在")
            }
        }

    @Test
    fun `a move reports a missing source, a missing target parent and a directory source`() =
        runBlocking {
            setUpRoot()
            Files.createDirectory(root.resolve("dir"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("dir/a.txt"), "a".toByteArray(), StorageWriteMode.UPSERT)

                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> {
                        storage.move(StoragePath.parse("dir/missing.txt"), StoragePath.parse("dir/b.txt"))
                    }.code,
                )
                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> {
                        storage.move(StoragePath.parse("dir/a.txt"), StoragePath.parse("nope/b.txt"))
                    }.code,
                )
                assertFalse(Files.exists(root.resolve("nope")), "移动不能隐式创建目标父目录")
                // T04 实测：目录 rename 返回 IsADirectory，本 Adapter 直接判为不支持。
                assertEquals(
                    VfsErrorCode.UNSUPPORTED_OPERATION,
                    assertFailsWith<VfsException> { storage.move(StoragePath.parse("dir"), StoragePath.parse("dir2")) }.code,
                )
                assertTrue(Files.isDirectory(root.resolve("dir")))
                assertEquals(
                    VfsErrorCode.UNSUPPORTED_OPERATION,
                    assertFailsWith<VfsException> {
                        storage.move(StoragePath.root, StoragePath.parse("dir2"))
                    }.code,
                )
            }
        }

    // ---------- createDirectory ----------

    @Test
    fun `createDirectory creates nested directories and is idempotent`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("a/b/c"))
                storage.createDirectory(StoragePath.parse("a/b/c")) // 已存在视为成功
                storage.createDirectory(StoragePath.root) // 挂载根无需创建

                assertTrue(Files.isDirectory(root.resolve("a/b/c")))
                assertEquals(NodeType.DIRECTORY, storage.stat(StoragePath.parse("a/b")).type)
            }
        }

    @Test
    fun `createDirectory refuses an existing file`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "a".toByteArray(), StorageWriteMode.UPSERT)

                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> { storage.createDirectory(StoragePath.parse("a.txt")) }.code,
                )
            }
        }

    // ---------- delete ----------

    @Test
    fun `delete removes files, refuses missing paths and refuses a non-recursive non-empty directory`() =
        runBlocking {
            setUpRoot()
            Files.createDirectories(root.resolve("dir/child"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "a".toByteArray(), StorageWriteMode.UPSERT)
                storage.delete(StoragePath.parse("a.txt"), recursive = false)
                assertFalse(Files.exists(root.resolve("a.txt")))

                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> { storage.delete(StoragePath.parse("a.txt"), recursive = false) }.code,
                )

                val notEmpty = assertFailsWith<VfsException> { storage.delete(StoragePath.parse("dir"), recursive = false) }
                assertEquals(VfsErrorCode.DIRECTORY_NOT_EMPTY, notEmpty.code)
                assertEquals(VfsEffect.NONE, notEmpty.effect)
                assertTrue(Files.isDirectory(root.resolve("dir")), "非空拒绝不能删掉任何东西")

                storage.delete(StoragePath.parse("dir/child"), recursive = false)
                storage.delete(StoragePath.parse("dir"), recursive = false)
                assertFalse(Files.exists(root.resolve("dir")))

                assertEquals(
                    VfsErrorCode.UNSUPPORTED_OPERATION,
                    assertFailsWith<VfsException> { storage.delete(StoragePath.root, recursive = true) }.code,
                )
            }
        }

    @Test
    fun `recursive delete removes a whole subtree bottom-up`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("dir/sub/deep"))
                storage.write(StoragePath.parse("dir/one.txt"), "1".toByteArray(), StorageWriteMode.UPSERT)
                storage.write(StoragePath.parse("dir/sub/two.txt"), "2".toByteArray(), StorageWriteMode.UPSERT)
                storage.write(StoragePath.parse("dir/sub/deep/three.txt"), "3".toByteArray(), StorageWriteMode.UPSERT)

                storage.delete(StoragePath.parse("dir"), recursive = true)

                assertFalse(Files.exists(root.resolve("dir")))
                assertEquals(emptyList<String>(), storage.list(StoragePath.root).map { it.name })
            }
        }

    @Test
    fun `recursive delete of a plain file works the same way`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "a".toByteArray(), StorageWriteMode.UPSERT)

                storage.delete(StoragePath.parse("a.txt"), recursive = true)

                assertFalse(Files.exists(root.resolve("a.txt")))
            }
        }

    // ---------- read / stat ----------

    @Test
    fun `read and stat report real bytes and types`() =
        runBlocking {
            setUpRoot()
            Files.createDirectory(root.resolve("dir"))
            openStorage().use { storage ->
                val payload = ByteArray(300) { (it % 251).toByte() }
                storage.write(StoragePath.parse("a.txt"), payload, StorageWriteMode.UPSERT)

                val content = storage.read(StoragePath.parse("a.txt"), 1024)
                assertTrue(content.bytes.contentEquals(payload))
                assertEquals(NodeType.FILE, content.attributes.type)
                assertEquals(300L, content.attributes.sizeBytes)

                val stat = storage.stat(StoragePath.parse("a.txt"))
                assertEquals(NodeType.FILE, stat.type)
                assertEquals(300L, stat.sizeBytes)
                assertEquals(NodeType.DIRECTORY, storage.stat(StoragePath.parse("dir")).type)
                assertEquals(null, storage.stat(StoragePath.parse("dir")).sizeBytes)
                assertEquals(NodeType.DIRECTORY, storage.stat(StoragePath.root).type)
            }
        }

    @Test
    fun `read and stat report missing targets and directories`() =
        runBlocking {
            setUpRoot()
            Files.createDirectory(root.resolve("dir"))
            openStorage().use { storage ->
                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> { storage.stat(StoragePath.parse("missing.txt")) }.code,
                )
                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> { storage.read(StoragePath.parse("missing.txt"), 16) }.code,
                )
                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> { storage.read(StoragePath.parse("dir"), 16) }.code,
                )
                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> { storage.read(StoragePath.root, 16) }.code,
                )
                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> { storage.readStream(StoragePath.parse("missing.txt")) }.code,
                )
                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> { storage.readStream(StoragePath.parse("dir")) }.code,
                )
            }
        }

    @Test
    fun `a negative read limit is rejected as an argument error`() =
        runBlocking {
            setUpRoot()
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "a".toByteArray(), StorageWriteMode.UPSERT)

                assertEquals(
                    VfsErrorCode.INVALID_ARGUMENT,
                    assertFailsWith<VfsException> { storage.read(StoragePath.parse("a.txt"), -1) }.code,
                )
                assertEquals(
                    VfsErrorCode.INVALID_ARGUMENT,
                    assertFailsWith<VfsException> { storage.readStream(StoragePath.parse("a.txt"), -1) }.code,
                )
            }
        }
}
