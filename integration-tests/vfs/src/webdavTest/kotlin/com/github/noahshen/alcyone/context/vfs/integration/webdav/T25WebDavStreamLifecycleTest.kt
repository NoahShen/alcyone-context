package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.storage.opendal.WebDavStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * T25 A07：新增的流路径正常、失败与遗忘回收都清理；关闭不取消宿主协程、不提前关库。
 *
 * 复用 T12 / T18 的所有权机制（谁先关都行、只关一次、超限后仍能关），这一轮只补 **WebDAV 接线**：
 * - 正常：开流 → 用 → 关流 → 同一状态库重开仍能用；
 * - 失败：对缺失目标 / 目录开流失败后，Adapter 与状态库照常可用；
 * - 遗忘：调用方不关流，Runtime `close()` 时把它收回，之后同一状态库能重开；
 * - 不提前关库：在一条流被手动关闭的同时读另一个文件仍然成功，close 不打断在途的读。
 *
 * 这一轮不声称能强制中断 native 调用（方案④边界），只验证公开流的所有权与回收接线。
 */
class T25WebDavStreamLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private fun db(name: String = "state.db") = tempDir.resolve(name)

    private fun remote(path: String) = VfsUri.parse("alcyone://resources/remote$path")

    /** A07：正常关流之后同一状态库能重开，读写照常。 */
    @Test
    @Timeout(120)
    fun `A07 closing a stream normally keeps the adapter and the same library usable`() =
        runBlocking {
            val database = db()
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.write(remote("/a07/normal.txt"), "normal-close".toByteArray())
                vfs.openStream(remote("/a07/normal.txt")).use { result ->
                    assertEquals("normal-close", result.stream.readBytes().decodeToString())
                }
            }

            // 重开同一状态库：之前的关流没有留下未释放的句柄，一切照常。
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.openStream(remote("/a07/normal.txt")).use { result ->
                    assertEquals("normal-close", result.stream.readBytes().decodeToString())
                }
                vfs.write(remote("/a07/normal2.txt"), "again".toByteArray())
                Unit
            }
        }

    /** A07：对缺失目标 / 目录开流失败后，Adapter 与状态库照常可用（失败路径也清理）。 */
    @Test
    @Timeout(120)
    fun `A07 a failed open leaves the adapter usable`() =
        runBlocking {
            val database = db()
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                val missing = assertFailsWith<VfsException> { vfs.openStream(remote("/a07/missing.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code, "缺失目标开流报 NOT_FOUND")

                vfs.write(remote("/a07/dir/inner.txt"), "x".toByteArray())
                val directory = assertFailsWith<VfsException> { vfs.openStream(remote("/a07/dir")) }
                assertEquals(VfsErrorCode.TYPE_MISMATCH, directory.code, "目录不能开读流")

                // 失败之后存储照常可用：还能写、还能开流读。
                vfs.write(remote("/a07/after.txt"), "after".toByteArray())
                vfs.openStream(remote("/a07/after.txt")).use { result ->
                    assertEquals("after", result.stream.readBytes().decodeToString())
                }

                vfs.delete(remote("/a07"), DeleteOptions(recursive = true))
            }
        }

    /**
     * A07：调用方**不关流**（遗忘）——Runtime `close()` 时把它收回，之后同一状态库能重开；
     * 这不依赖调用方记性，是所有权机制的一部分。
     */
    @Test
    @Timeout(120)
    fun `A07 a forgotten stream is reclaimed by the runtime on close and the library reopens`() =
        runBlocking {
            val database = db()
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.write(remote("/a07/forgotten.bin"), "reclaim-me".toByteArray())
                // 故意不 close：让 Runtime 在 close() 时收回来。
                val leaked = vfs.openStream(remote("/a07/forgotten.bin"))
                assertNotNull(leaked)
                val first = leaked.stream.read()
                assertTrue(first >= 0, "流能读")
            }

            // close() 把遗忘的流收回了；同一库重开后照常。
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.openStream(remote("/a07/forgotten.bin")).use { result ->
                    assertEquals("reclaim-me", result.stream.readBytes().decodeToString())
                }
            }
        }

    /** A07：重复关闭是空操作，不会报错也不会重复关底层句柄。 */
    @Test
    @Timeout(120)
    fun `A07 closing a stream twice is a no-op`() =
        runBlocking {
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                vfs.write(remote("/a07/twice.txt"), "twice".toByteArray())
                val result = vfs.openStream(remote("/a07/twice.txt"))
                assertEquals("twice", result.stream.readBytes().decodeToString())
                result.close()
                result.close() // 再关一次不抛错
                assertTrue(result.isClosed, "重复关闭之后仍是已关闭")

                // 关流之后同一实例照常可用。
                vfs.write(remote("/a07/twice-2.txt"), "still".toByteArray())
                Unit
            }
        }

    /** A07：一条流超限失败之后仍能关闭，Adapter 照常可用（不因超限留下坏状态）。 */
    @Test
    @Timeout(120)
    fun `A07 a stream that exceeded its limit can still be closed and the adapter stays usable`() =
        runBlocking {
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                vfs.write(remote("/a07/limited.bin"), "0123456789".toByteArray())
                vfs.openStream(remote("/a07/limited.bin"), VfsStreamOptions(maxTotalBytes = 4)).use { result ->
                    val buffer = ByteArray(4)
                    val failure =
                        runCatching {
                            while (true) {
                                val read = result.stream.read(buffer)
                                if (read <= 0) break
                            }
                        }.exceptionOrNull()
                    assertNotNull(failure, "超限要报错")
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, (failure as VfsException).code)
                }
                // 超限失败之后存储照常可用。
                vfs.write(remote("/a07/limited-2.bin"), "ok".toByteArray())
                vfs.openStream(remote("/a07/limited-2.bin")).use { result ->
                    assertEquals("ok", result.stream.readBytes().decodeToString())
                }
            }
        }

    /**
     * A07：关流与在途读互不干扰——手动把关掉的流不会取消宿主，也不会提前关库；
     * 同时另一条流的读仍然成功。这里不声称能强制中断 native。
     */
    @Test
    @Timeout(120)
    fun `A07 closing one stream does not cancel the host or close the database early`() =
        runBlocking {
            val database = db()
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.write(remote("/a07/a.bin"), "AAAA".toByteArray())
                vfs.write(remote("/a07/b.bin"), "BBBBBBBB".toByteArray())

                val a = vfs.openStream(remote("/a07/a.bin"))
                a.close() // 关掉一条流

                // 另一条流照常读完整；宿主协程没有被这次关闭取消。
                vfs.openStream(remote("/a07/b.bin")).use { b ->
                    val bytes: InputStream = b.stream
                    assertEquals("BBBBBBBB", bytes.readBytes().decodeToString())
                }

                // 库还没关：还能继续写。
                vfs.write(remote("/a07/c.bin"), "C".toByteArray())
                assertNotNull(vfs.stat(remote("/a07/c.bin")))
            }

            // 正常 close 之后能重开。
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.openStream(remote("/a07/b.bin")).use { b ->
                    assertEquals("BBBBBBBB", b.stream.readBytes().decodeToString())
                }
            }
        }

    /** A07：Adapter 层——一条打开后不关的流在 Adapter 关闭时被收回，重复关流是空操作。 */
    @Test
    @Timeout(120)
    fun `A07 closing the adapter reclaims an opened reader and later stream close is a no-op`() =
        runBlocking {
            val storage = WebDavStorage.create(WebDavFixture.endpoint, "/", WebDavFixture.user, WebDavFixture.password)
            try {
                val path =
                    com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
                        .parse("a07-adapter.bin")
                storage.write(
                    path,
                    "adapter-stream".toByteArray(),
                    com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode.CREATE_NEW,
                )
                // 打开流但不关：让 Adapter 关闭时收回去。
                val stream = storage.readStream(path, null)
                assertFalse(storage.isClosed(), "关之前没关")
                storage.close()
                assertTrue(storage.isClosed(), "Adapter 已关闭")
                // Adapter 已经关了，之后再关流是空操作，不报错。
                stream.close()
            } finally {
                runCatching { storage.close() }
            }
        }
}
