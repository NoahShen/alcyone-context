package com.github.noahshen.alcyone.context.vfs.core.storage

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StorageFakeImplTest {
    private val storage = StorageFakeImpl()

    // 1. 常规 read / write / stat / list / move / delete / createDirectory 流程
    @Test
    fun `regular storage operations workflow`() =
        runBlocking {
            val dir = StoragePath.parse("docs/notes")
            storage.createDirectory(dir)

            val statDir = storage.stat(dir)
            assertEquals(NodeType.DIRECTORY, statDir.type)

            val filePath = StoragePath.parse("docs/notes/hello.txt")
            val content = "Hello VFS".toByteArray(Charsets.UTF_8)
            val writeAttrs = storage.write(filePath, content, StorageWriteMode.CREATE_NEW)
            assertEquals(NodeType.FILE, writeAttrs.type)
            assertEquals(content.size.toLong(), writeAttrs.sizeBytes)

            val readContent = storage.read(filePath, maxBytes = 1024)
            assertArrayEquals(content, readContent.bytes)
            assertEquals(content.size.toLong(), readContent.attributes.sizeBytes)

            val listEntries = storage.list(dir)
            assertEquals(1, listEntries.size)
            assertEquals("hello.txt", listEntries[0].name)
            assertEquals(NodeType.FILE, listEntries[0].type)

            val targetPath = StoragePath.parse("docs/notes/moved.txt")
            val moveAttrs = storage.move(filePath, targetPath)
            assertEquals(NodeType.FILE, moveAttrs.type)

            val notFoundEx =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.stat(filePath) }
                }
            assertEquals(VfsErrorCode.NOT_FOUND, notFoundEx.code)

            val movedStat = storage.stat(targetPath)
            assertEquals(NodeType.FILE, movedStat.type)

            storage.delete(targetPath, recursive = false)
            val afterDeleteEx =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.stat(targetPath) }
                }
            assertEquals(VfsErrorCode.NOT_FOUND, afterDeleteEx.code)
        }

    // 2. 有界读取超限抛 LIMIT_EXCEEDED
    @Test
    fun `bounded read throws LIMIT_EXCEEDED when file exceeds limit`() =
        runBlocking {
            val path = StoragePath.parse("big_file.bin")
            val data = ByteArray(100) { 1 }
            storage.write(path, data, StorageWriteMode.CREATE_NEW)

            val ex =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.read(path, maxBytes = 50) }
                }
            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, ex.code)
        }

    // 3. 流式读取功能与有界截断
    @Test
    fun `readStream supports consumption and bounded limit check`() =
        runBlocking {
            val path = StoragePath.parse("stream_test.txt")
            val data = "Streamable content data".toByteArray()
            storage.write(path, data, StorageWriteMode.CREATE_NEW)

            // 正常流式读取
            storage.readStream(path, maxBytes = 100).use { stream ->
                val consumed = stream.openStream().readBytes()
                assertArrayEquals(data, consumed)
                assertEquals(data.size.toLong(), stream.attributes.sizeBytes)
            }

            // 超限中止
            val ex =
                assertThrows(VfsException::class.java) {
                    runBlocking {
                        storage.readStream(path, maxBytes = 10).use { stream ->
                            stream.openStream().readBytes()
                        }
                    }
                }
            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, ex.code)
        }

    // 4. 写入模式冲突分支 (CREATE_NEW / REPLACE_EXISTING / UPSERT)
    @Test
    fun `write modes trigger expected errors`() =
        runBlocking {
            val path = StoragePath.parse("mode_test.txt")
            val data1 = "1".toByteArray()
            val data2 = "2".toByteArray()

            // REPLACE_EXISTING 目标不存在 -> NOT_FOUND
            val exNotFound =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.write(path, data1, StorageWriteMode.REPLACE_EXISTING) }
                }
            assertEquals(VfsErrorCode.NOT_FOUND, exNotFound.code)

            // CREATE_NEW 成功写入
            storage.write(path, data1, StorageWriteMode.CREATE_NEW)

            // CREATE_NEW 目标已存在 -> ALREADY_EXISTS
            val exAlreadyExists =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.write(path, data2, StorageWriteMode.CREATE_NEW) }
                }
            assertEquals(VfsErrorCode.ALREADY_EXISTS, exAlreadyExists.code)

            // UPSERT 覆盖成功
            storage.write(path, data2, StorageWriteMode.UPSERT)
            assertArrayEquals(data2, storage.read(path, maxBytes = 10).bytes)
        }

    // 5. 非空目录非递归删除抛 DIRECTORY_NOT_EMPTY
    @Test
    fun `delete non-empty directory non-recursively throws DIRECTORY_NOT_EMPTY`() =
        runBlocking {
            val dir = StoragePath.parse("parent")
            val subFile = StoragePath.parse("parent/child.txt")
            storage.createDirectory(dir)
            storage.write(subFile, "content".toByteArray(), StorageWriteMode.CREATE_NEW)

            val ex =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.delete(dir, recursive = false) }
                }
            assertEquals(VfsErrorCode.DIRECTORY_NOT_EMPTY, ex.code)

            // recursive = true 能够成功删除
            storage.delete(dir, recursive = true)
            val statEx =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.stat(dir) }
                }
            assertEquals(VfsErrorCode.NOT_FOUND, statEx.code)
        }

    // 6. 目标为目录时读内容直接报 TYPE_MISMATCH (G6)
    @Test
    fun `reading content from directory directly throws TYPE_MISMATCH`() =
        runBlocking {
            val dir = StoragePath.parse("some_folder")
            storage.createDirectory(dir)

            val exRead =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.read(dir, maxBytes = 1024) }
                }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, exRead.code)

            val exStream =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.readStream(dir) }
                }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, exStream.code)
        }

    // 7. 父路径为文件时补目录 / 写入报错（G3 条件式）
    // 替身能确认父路径就是那个文件，属于「可确认的文件/目录类型冲突」，故用 TYPE_MISMATCH；
    // 无法识别原因的真实 I/O 失败才是 STORAGE_ERROR。替身行为不是 T12 的最终实现结论。
    @Test
    fun `creating directory or writing child when parent is file reports a type conflict`() =
        runBlocking {
            val filePath = StoragePath.parse("file_as_parent")
            storage.write(filePath, "data".toByteArray(), StorageWriteMode.CREATE_NEW)

            val invalidSubDir = StoragePath.parse("file_as_parent/subdir")
            val exDir =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.createDirectory(invalidSubDir) }
                }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, exDir.code)

            val invalidSubFile = StoragePath.parse("file_as_parent/child.txt")
            val exWrite =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.write(invalidSubFile, "data".toByteArray(), StorageWriteMode.CREATE_NEW) }
                }
            assertEquals(VfsErrorCode.TYPE_MISMATCH, exWrite.code)
        }

    // 8. 能力不足时抛 UNSUPPORTED_OPERATION
    @Test
    fun `unsupported capability throws UNSUPPORTED_OPERATION`() =
        runBlocking {
            storage.setCapabilities(StorageCapabilities(nativeFileMove = false, nativeDirectoryMove = false))
            val src = StoragePath.parse("src.txt")
            val dst = StoragePath.parse("dst.txt")
            storage.write(src, "content".toByteArray(), StorageWriteMode.CREATE_NEW)

            val ex =
                assertThrows(VfsException::class.java) {
                    runBlocking { storage.move(src, dst) }
                }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, ex.code)
        }

    // 9. 反射检查：Storage 接口方法及参数类型绝对不包含第三方基础设施类型
    @Test
    fun `storage interface does not expose opendal sqldelight or jdbc types`() {
        val methods = Storage::class.java.declaredMethods
        for (m in methods) {
            val allTypes = listOf(m.returnType) + m.parameterTypes
            for (type in allTypes) {
                val typeName = type.name
                val forbidden = listOf("opendal", "sqldelight", "sqlite", "jdbc")
                for (keyword in forbidden) {
                    val containsForbidden = typeName.lowercase().contains(keyword)
                    if (containsForbidden) {
                        throw AssertionError("Storage interface leaks infrastructure type: $typeName")
                    }
                }
            }
        }
    }
}
