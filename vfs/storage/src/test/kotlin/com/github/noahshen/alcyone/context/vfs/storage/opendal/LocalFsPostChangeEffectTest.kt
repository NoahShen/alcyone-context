package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.runBlocking
import org.apache.opendal.Metadata
import org.apache.opendal.OpenDALException
import org.apache.opendal.Operator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * 改动已经成功、之后才失败的时候，报错要带上「已经改过东西」。
 *
 * [mapStorageErrors] 对已经是 `VfsException` 的异常是原样抛出的，所以这些「自己构造的异常」
 * 必须自己带上 effect，否则改动的事实就丢了。
 *
 * [Timeout]：这些用例会在存储操作内部注入失败。万一哪次注入踩到锁（拿着读锁去等写锁会挂死），
 * 超时把它变成一次失败，而不是把整个测试套件一起拖住。
 */
@Timeout(60)
class LocalFsPostChangeEffectTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path

    private fun setUpRoot() {
        root = Files.createDirectory(tempDir.resolve("root"))
    }

    /** 让**第 [failAt] 次**查文件信息失败，其余照常问后端。 */
    private fun statReturning(
        operator: Operator,
        failAt: Int,
        failure: () -> OpenDALException,
        calls: AtomicInteger = AtomicInteger(0),
    ): (String) -> Metadata =
        { path ->
            if (calls.incrementAndGet() == failAt) throw failure() else operator.stat(path)
        }

    @Test
    fun `a write that succeeded but whose attributes vanished is not NONE`() =
        runBlocking {
            setUpRoot()
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    statFactory = { o ->
                        statReturning(o, 3, failure = { OpenDALException(OpenDALException.Code.NotFound, "a.txt") })
                    },
                ).use { storage ->
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.write(StoragePath.parse("a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
                        }

                    assertEquals(VfsErrorCode.NOT_FOUND, failure.code)
                    assertNotEquals(VfsEffect.NONE, failure.effect, "文件已经写进去了")
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("x", Files.readString(root.resolve("a.txt")))
                }
        }

    @Test
    fun `a move that succeeded but whose attributes vanished is not NONE`() =
        runBlocking {
            setUpRoot()
            Files.writeString(root.resolve("a.txt"), "original")
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    statFactory = { o ->
                        statReturning(o, 4, failure = { OpenDALException(OpenDALException.Code.NotFound, "b.txt") })
                    },
                ).use { storage ->
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.move(StoragePath.parse("a.txt"), StoragePath.parse("b.txt"))
                        }

                    assertNotEquals(VfsEffect.NONE, failure.effect, "改名已经发生了")
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("original", Files.readString(root.resolve("b.txt")))
                }
        }

    /** 后端回一个「认不出是什么类型」，同样是在改动之后发生的。 */
    @Test
    fun `a write whose attributes come back with an unknown type is not NONE`() =
        runBlocking {
            setUpRoot()
            val calls = AtomicInteger(0)
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    statFactory = { o ->
                        { path ->
                            // 第一次是检查阶段的父目录查询，第二次是检查目标的查询，
                            // 第三次就是写入之后的回读，这里让后端认不出类型。
                            if (calls.incrementAndGet() == 3) unknownMode() else o.stat(path)
                        }
                    },
                ).use { storage ->
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.write(StoragePath.parse("a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
                        }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertNotEquals(VfsEffect.NONE, failure.effect, "文件已经写进去了")
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertEquals("x", Files.readString(root.resolve("a.txt")))
                }
        }

    /**
     * 改动之后存储已经关掉，收尾步骤失败时同样不能说「什么都没做」。
     *
     * 这里**不真的去调 `close()`**：此刻调用线程正拿着 `NativeLifetime` 的读锁，
     * `close()` 要写锁，读锁升级写锁是不允许的，会挂死（`NativeLifetime` 的类注释也警告过）。
     * 所以直接抛 `CLOSED`——那正是存储已关闭时守卫会给调用方的错误码，语义相同且不会死锁。
     * 真的关闭存储由 [LocalFsStorageLifetimeTest] 用线程编排验证。
     */
    @Test
    fun `a write whose attribute read hits a closed storage is not NONE`() =
        runBlocking {
            setUpRoot()
            val calls = AtomicInteger(0)
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    statFactory = { o ->
                        { path ->
                            // 前两次是写入之前的检查；第三次是写入之后的回读，
                            // 这时候存储已经关掉了，守卫会回 CLOSED。
                            if (calls.incrementAndGet() == 3) {
                                throw VfsException(VfsErrorCode.CLOSED, "local storage is closed")
                            }
                            o.stat(path)
                        }
                    },
                ).use { storage ->
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.write(StoragePath.parse("a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
                        }

                    assertEquals(VfsErrorCode.CLOSED, failure.code)
                    assertEquals(VfsEffect.PARTIAL, failure.effect, "文件已经写进去了，关掉存储也不能抹掉这件事")
                    assertEquals("x", Files.readString(root.resolve("a.txt")))
                }
        }

    /**
     * 一个「认不出类型」的后端返回。
     *
     * [Metadata] 只有一个 9 个参数的构造器（mode、contentLength、contentDisposition、contentMd5、
     * contentType、cacheControl、etag、lastModified、version），这里只需要 mode 和时间戳，其余留空。
     */
    private fun unknownMode(): Metadata =
        Metadata(
            Metadata.EntryMode.UNKNOWN.ordinal,
            0L,
            null,
            null,
            null,
            null,
            null,
            Instant.EPOCH,
            null,
        )
}
