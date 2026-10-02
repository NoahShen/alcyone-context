package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.runBlocking
import org.apache.opendal.OpenDAL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * A01：正式模块加载 OpenDAL native、访问真实文件；关闭可重复，关闭后拒绝新调用。
 *
 * 证据强度说明：
 *
 * - native 已加载：[OpenDAL.enabledServices] 是 native 调用，native 没加载会直接抛 UnsatisfiedLinkError；
 *   断言它包含 `fs`，说明跑的不是纯 Java 实现。
 * - 资源确实关闭：断言 `operator.isDisposed`。**不能**用「文件还能删」当作 Operator 已关闭的证据，
 *   POSIX 上文件句柄与目录项无关，删掉文件说明不了 Operator 释放了。
 */
class LocalFsStorageLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `the opendal native library is loaded and provides the fs service`() {
        val services = OpenDAL.enabledServices()

        assertTrue(services.contains("fs"), "native 未加载或缺少 fs 服务，实际：$services")
    }

    @Test
    fun `write through one instance is readable by a later instance on the same real file`() =
        runBlocking {
            LocalFsStorage.create(tempDir).use { storage ->
                // write 不隐式补父目录，所以先显式建目录（T12 §2.3）。
                storage.createDirectory(StoragePath.parse("reports"))
                storage.write(StoragePath.parse("reports/a.txt"), "hello 本地文件".toByteArray(), StorageWriteMode.CREATE_NEW)
            }

            // 证据一：物理文件真的在宿主文件系统上，内容由后端写出。
            val physical = tempDir.resolve("reports/a.txt")
            assertTrue(Files.isRegularFile(physical), "写入后物理文件不存在：$physical")
            assertEquals("hello 本地文件", Files.readString(physical))

            LocalFsStorage.create(tempDir).use { reopened ->
                val content = reopened.read(StoragePath.parse("reports/a.txt"), 1024)

                assertEquals("hello 本地文件", String(content.bytes))
            }
        }

    @Test
    fun `close is idempotent and disposes the native handle`() =
        runBlocking {
            val storage = LocalFsStorage.create(tempDir)

            storage.close()
            storage.close() // 幂等：重复关闭不抛异常

            assertTrue(storage.isClosed())
            assertTrue(storage.nativeHandleDisposed(), "close 之后 native 句柄必须已释放")
        }

    @Test
    fun `every new call after close fails with CLOSED`() =
        runBlocking {
            val storage = LocalFsStorage.create(tempDir)
            storage.createDirectory(StoragePath.parse("dir"))
            storage.write(StoragePath.parse("dir/a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
            storage.close()

            val failures =
                listOf(
                    assertFailsWith<VfsException> { storage.stat(StoragePath.parse("dir/a.txt")) },
                    assertFailsWith<VfsException> { storage.read(StoragePath.parse("dir/a.txt"), 16) },
                    assertFailsWith<VfsException> { storage.readStream(StoragePath.parse("dir/a.txt")) },
                    assertFailsWith<VfsException> { storage.list(StoragePath.parse("dir")) },
                    assertFailsWith<VfsException> { storage.createDirectory(StoragePath.parse("dir2")) },
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("dir/b.txt"), "y".toByteArray(), StorageWriteMode.UPSERT)
                    },
                    assertFailsWith<VfsException> { storage.move(StoragePath.parse("dir/a.txt"), StoragePath.parse("dir/c.txt")) },
                    assertFailsWith<VfsException> { storage.delete(StoragePath.parse("dir"), recursive = true) },
                )

            failures.forEach { assertEquals(VfsErrorCode.CLOSED, it.code) }
            assertEquals(
                VfsErrorCode.CLOSED,
                assertFailsWith<VfsException> { storage.createDirectory(StoragePath.parse("dir3")) }.code,
            )
            assertFalse(Files.exists(tempDir.resolve("dir2")), "关闭后的调用不能产生任何副作用")
        }

    @Test
    fun `a stream that outlives the adapter reports CLOSED instead of touching the native handle`() =
        runBlocking {
            val storage = LocalFsStorage.create(tempDir)
            storage.write(StoragePath.parse("a.txt"), "0123456789".toByteArray(), StorageWriteMode.UPSERT)
            val stream = storage.readStream(StoragePath.parse("a.txt"))
            val opened = stream.openStream()

            storage.close()

            // 实测：Operator 关闭后再读 native 流会让 JVM 崩溃，所以这里必须先被守卫拦成 VfsException。
            val failure = assertFailsWith<VfsException> { opened.read() }
            assertEquals(VfsErrorCode.CLOSED, failure.code)
            stream.close() // 关闭不再触碰 native 句柄
        }

    @Test
    fun `a root that does not exist is rejected with INVALID_ARGUMENT`() =
        runBlocking {
            val failure =
                assertFailsWith<VfsException> {
                    LocalFsStorage.create(tempDir.resolve("missing"))
                }

            assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
            // 公开消息不得拼接物理路径。
            assertFalse(failure.message!!.contains(tempDir.toString()))
        }

    @Test
    fun `a root that is a regular file is rejected with INVALID_ARGUMENT`() =
        runBlocking {
            val file = Files.writeString(tempDir.resolve("not-a-dir.txt"), "x")

            val failure = assertFailsWith<VfsException> { LocalFsStorage.create(file) }

            assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
        }

    @Test
    fun `a symlinked root is resolved to its real directory`() =
        runBlocking {
            val real = Files.createDirectory(tempDir.resolve("real"))
            val link = tempDir.resolve("link").also { Files.createSymbolicLink(it, real) }

            LocalFsStorage.create(link).use { storage ->
                storage.write(StoragePath.parse("a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
                assertEquals("x", Files.readString(real.resolve("a.txt")))
            }
        }
}
