package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * A04：单层列举必须完整（T12 §2.3）。
 *
 * 后端不加尾斜杠时只返回目录自身、并且会把目录自身放进结果，这两点都由适配层纠正；
 * 条目多于一千个用来暴露漏页或截断。
 */
class LocalFsListTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path

    private suspend fun openStorage() = LocalFsStorage.create(root)

    @Test
    fun `an empty directory lists nothing`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("empty"))

                assertEquals(emptyList<String>(), storage.list(StoragePath.parse("empty")).map { it.name })
            }
        }

    @Test
    fun `mixed files and directories list with reliable types`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("dir/sub"))
                storage.write(StoragePath.parse("dir/a.txt"), "12345".toByteArray(), StorageWriteMode.UPSERT)
                storage.write(StoragePath.parse("dir/b.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)

                val entries = storage.list(StoragePath.parse("dir")).associate { it.name to it.type }

                assertEquals(
                    mapOf("a.txt" to NodeType.FILE, "b.txt" to NodeType.FILE, "sub" to NodeType.DIRECTORY),
                    entries,
                )
                val file = storage.list(StoragePath.parse("dir")).first { it.name == "a.txt" }
                assertEquals(5L, file.attributes?.sizeBytes)
            }
        }

    @Test
    fun `listing does not include the directory itself or any grandchild`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("a/b/c"))
                storage.write(StoragePath.parse("a/b/c/deep.txt"), "d".toByteArray(), StorageWriteMode.UPSERT)
                storage.write(StoragePath.parse("a/b/mid.txt"), "m".toByteArray(), StorageWriteMode.UPSERT)

                assertEquals(listOf("b"), storage.list(StoragePath.parse("a")).map { it.name })
                assertEquals(listOf("c", "mid.txt"), storage.list(StoragePath.parse("a/b")).map { it.name }.sorted())
                assertEquals(listOf("a"), storage.list(StoragePath.root).map { it.name })
            }
        }

    @Test
    fun `entry names are relative single segments`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("dir"))
                storage.write(StoragePath.parse("dir/a b_中文.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)

                val entry = storage.list(StoragePath.parse("dir")).single()

                assertEquals("a b_中文.txt", entry.name)
                assertTrue('/' !in entry.name)
            }
        }

    @Test
    fun `a large directory lists every entry without truncation`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            val expected = (0 until 1200).map { "file-%04d.txt".format(it) }
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("big"))
                expected.forEach { name ->
                    storage.write(StoragePath.parse("big/$name"), "x".toByteArray(), StorageWriteMode.UPSERT)
                }

                val listed = storage.list(StoragePath.parse("big")).map { it.name }

                assertEquals(expected.size, listed.size)
                assertEquals(expected.toSet(), listed.toSet())
            }
        }

    @Test
    fun `listing a file or a missing path is an error`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.write(StoragePath.parse("a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)

                assertEquals(
                    VfsErrorCode.TYPE_MISMATCH,
                    assertFailsWith<VfsException> { storage.list(StoragePath.parse("a.txt")) }.code,
                )
                assertEquals(
                    VfsErrorCode.NOT_FOUND,
                    assertFailsWith<VfsException> { storage.list(StoragePath.parse("missing")) }.code,
                )
            }
        }
}
