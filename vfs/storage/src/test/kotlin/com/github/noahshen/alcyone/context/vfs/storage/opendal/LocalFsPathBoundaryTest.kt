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
 * A02：挂载根与子路径定位、Unicode / 空格 / 下划线、不重复解码、符号链接一律拒绝。
 *
 * 每个拒绝用例都检查**根外哨兵文件内容不变**，证明没有跟随链接出去，也没有修改链接目标。
 */
class LocalFsPathBoundaryTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path
    private lateinit var outside: Path

    private suspend fun openStorage() = LocalFsStorage.create(root)

    private fun setUpRoots() {
        root = Files.createDirectory(tempDir.resolve("root"))
        outside = Files.createDirectory(tempDir.resolve("outside"))
    }

    private fun sentinel(): String = Files.readString(outside.resolve("secret.txt"))

    @Test
    fun `the mount root itself resolves to the physical root`() =
        runBlocking {
            setUpRoots()
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("dir"))
                storage.write(StoragePath.parse("dir/a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)

                val rootStat = storage.stat(StoragePath.root)

                assertTrue(Files.isDirectory(root))
                assertEquals(NodeType.DIRECTORY, rootStat.type)
                assertEquals(1, storage.list(StoragePath.root).size)
            }
        }

    @Test
    fun `unicode, spaces and underscores round-trip through real files`() =
        runBlocking {
            setUpRoots()
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("中文 目录"))
                val content = "报告内容 with spaces_and_underscores".toByteArray()
                storage.write(StoragePath.parse("中文 目录/报告 v1_2.txt"), content, StorageWriteMode.CREATE_NEW)

                assertArrayEqualsOnDisk(root.resolve("中文 目录/报告 v1_2.txt"), content)
                assertArrayEqualsInStorage(storage.read(StoragePath.parse("中文 目录/报告 v1_2.txt"), 1024).bytes, content)
                assertEquals(
                    listOf("报告 v1_2.txt"),
                    storage.list(StoragePath.parse("中文 目录")).map { it.name },
                )
            }
        }

    @Test
    fun `segment text is never decoded again`() =
        runBlocking {
            setUpRoots()
            openStorage().use { storage ->
                // 输入已解码：段内保留百分号形式的名字应当被 StoragePath 当成非法段拒绝，
                // 而不是被适配层再解码一次变成另一个路径。
                val rejected = assertFailsWith<VfsException> { StoragePath.parse("reports/a%20b.txt") }
                assertEquals(VfsErrorCode.INVALID_URI, rejected.code)

                // 后端层面的原样保留：文件名里的 '+' 和 '&' 不会被解释成转义。
                storage.createDirectory(StoragePath.parse("dir"))
                storage.write(StoragePath.parse("dir/a+b&c.txt"), "raw".toByteArray(), StorageWriteMode.CREATE_NEW)

                assertTrue(Files.isRegularFile(root.resolve("dir/a+b&c.txt")))
                assertEquals("raw", String(storage.read(StoragePath.parse("dir/a+b&c.txt"), 16).bytes))
            }
        }

    @Test
    fun `a symlinked file is refused and the target outside the root is untouched`() =
        runBlocking {
            setUpRoots()
            Files.writeString(outside.resolve("secret.txt"), "SECRET")
            Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"))
            openStorage().use { storage ->
                assertRefused { storage.stat(StoragePath.parse("link.txt")) }
                assertRefused { storage.read(StoragePath.parse("link.txt"), 64) }
                assertRefused { storage.write(StoragePath.parse("link.txt"), "new".toByteArray(), StorageWriteMode.UPSERT) }
                assertRefused { storage.delete(StoragePath.parse("link.txt"), recursive = false) }

                assertEquals("SECRET", sentinel())
                assertEquals("SECRET", Files.readString(outside.resolve("secret.txt")))
            }
        }

    @Test
    fun `a symlinked intermediate directory is refused and never traversed`() =
        runBlocking {
            setUpRoots()
            Files.writeString(outside.resolve("secret.txt"), "SECRET")
            Files.createSymbolicLink(root.resolve("dirlink"), outside)
            openStorage().use { storage ->
                assertRefused { storage.read(StoragePath.parse("dirlink/secret.txt"), 64) }
                assertRefused { storage.stat(StoragePath.parse("dirlink/secret.txt")) }
                assertRefused { storage.list(StoragePath.parse("dirlink")) }
                assertRefused { storage.createDirectory(StoragePath.parse("dirlink/new")) }
                assertRefused { storage.write(StoragePath.parse("dirlink/new.txt"), "x".toByteArray(), StorageWriteMode.UPSERT) }

                assertEquals("SECRET", sentinel())
            }
        }

    @Test
    fun `a symlink listed as an entry makes list fail without reading the target`() =
        runBlocking {
            setUpRoots()
            Files.writeString(outside.resolve("secret.txt"), "SECRET")
            Files.createDirectory(root.resolve("d"))
            Files.writeString(root.resolve("d/inner.txt"), "inner")
            Files.createSymbolicLink(root.resolve("d/link.txt"), outside.resolve("secret.txt"))
            openStorage().use { storage ->
                assertRefused { storage.list(StoragePath.parse("d")) }

                assertEquals("SECRET", sentinel())
                assertEquals("inner", Files.readString(root.resolve("d/inner.txt")))
            }
        }

    @Test
    fun `both ends of a move are checked for symlinks`() =
        runBlocking {
            setUpRoots()
            Files.writeString(outside.resolve("secret.txt"), "SECRET")
            Files.createDirectory(root.resolve("dir"))
            Files.writeString(root.resolve("dir/a.txt"), "a")
            Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"))
            openStorage().use { storage ->
                assertRefused { storage.move(StoragePath.parse("link.txt"), StoragePath.parse("dir/b.txt")) }
                assertRefused { storage.move(StoragePath.parse("dir/a.txt"), StoragePath.parse("link.txt")) }

                assertEquals("SECRET", sentinel())
                assertTrue(Files.isRegularFile(root.resolve("dir/a.txt")), "被拒绝的移动不能移动源")
            }
        }

    @Test
    fun `recursive delete refuses a symlink and deletes nothing at all`() =
        runBlocking {
            setUpRoots()
            Files.writeString(outside.resolve("secret.txt"), "SECRET")
            Files.createDirectory(root.resolve("dir"))
            Files.writeString(root.resolve("dir/keep.txt"), "keep")
            Files.createSymbolicLink(root.resolve("dir/link.txt"), outside.resolve("secret.txt"))
            openStorage().use { storage ->
                val failure = assertFailsWith<VfsException> { storage.delete(StoragePath.parse("dir"), recursive = true) }

                assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
                // 先规划后删除：遇到链接时副作用为零，已存在的普通文件必须还在。
                assertEquals(VfsEffect.NONE, failure.effect)
                assertTrue(Files.isRegularFile(root.resolve("dir/keep.txt")), "规划阶段失败不应删除任何条目")
                assertEquals("SECRET", sentinel())
            }
        }

    @Test
    fun `non-recursive delete of a directory holding a symlink still reports DIRECTORY_NOT_EMPTY`() {
        runBlocking {
            setUpRoots()
            Files.writeString(outside.resolve("secret.txt"), "SECRET")
            Files.createDirectory(root.resolve("dir"))
            Files.createSymbolicLink(root.resolve("dir/link.txt"), outside.resolve("secret.txt"))
            openStorage().use { storage ->
                // 非递归删除只需要知道「是否为空」，不进入子项，所以这里报 DIRECTORY_NOT_EMPTY 而不是拒绝链接。
                val failure = assertFailsWith<VfsException> { storage.delete(StoragePath.parse("dir"), recursive = false) }

                assertEquals(VfsErrorCode.DIRECTORY_NOT_EMPTY, failure.code)
                assertTrue(Files.isDirectory(root.resolve("dir")))
                assertEquals("SECRET", sentinel())
            }
        }
    }

    @Test
    fun `a dangling symlink entry is silently dropped by the backend listing`() {
        runBlocking {
            setUpRoots()
            Files.createDirectory(root.resolve("dir"))
            Files.createSymbolicLink(root.resolve("dir/dangling.txt"), root.resolve("never-created.txt"))
            openStorage().use { storage ->
                // 记录后端事实：fs 列举会 stat 每个条目，悬空符号链接 stat 失败就被**静默跳过**，
                // 调用方看不到这个条目。对可见的符号链接条目 Adapter 仍然报 STORAGE_ACCESS_DENIED。
                assertEquals(emptyList<String>(), storage.list(StoragePath.parse("dir")).map { it.name })
                assertTrue(Files.isDirectory(root.resolve("dir")))
            }
        }
    }

    @Test
    fun `an intermediate component that is a file is a type mismatch`() =
        runBlocking {
            setUpRoots()
            Files.writeString(root.resolve("a.txt"), "a")
            openStorage().use { storage ->
                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> { storage.read(StoragePath.parse("a.txt/child.txt"), 16) }.code,
                )
            }
        }

    @Test
    fun `a dangling symlink is still a symlink`() =
        runBlocking {
            setUpRoots()
            Files.createSymbolicLink(root.resolve("dangling.txt"), root.resolve("never-created.txt"))
            openStorage().use { storage ->
                assertRefused { storage.stat(StoragePath.parse("dangling.txt")) }
                assertFalse(Files.exists(root.resolve("never-created.txt")))
            }
        }
}

/** 复用的小断言，避免每个用例重复写断言体。 */
private suspend fun assertRefused(block: suspend () -> Unit) {
    val failure = assertFailsWith<VfsException> { block() }
    assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
}

private fun assertArrayEqualsOnDisk(
    path: Path,
    expected: ByteArray,
) {
    assertTrue(Files.isRegularFile(path), "物理文件不存在：$path")
    assertTrue(Files.readAllBytes(path).contentEquals(expected), "物理文件内容与写入不一致")
}

private fun assertArrayEqualsInStorage(
    actual: ByteArray,
    expected: ByteArray,
) {
    assertTrue(actual.contentEquals(expected), "读回内容与写入不一致")
}
