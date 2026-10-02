package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.runBlocking
import org.apache.opendal.Metadata
import org.apache.opendal.OpenDALException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * T12 R5：effect 必须能区分**失败发生在哪个阶段**，而不是「这个方法整体是什么 effect」。
 *
 * 错误注入全部用 [LocalFsStorage.open] 的 `statFactory` 缝隙，不碰权限、不赌 native 崩溃：
 * 替身只在被指定的第 N 次调用上抛，其余调用照常走真实后端，因此每个用例都是确定的。
 *
 * 不能用 `onNativeCall` 制造这里的失败：它在 `mapStorageErrors` 之前抛，异常不会被映射成
 * 带 effect 的 `VfsException`，也就测不出阶段差别。
 */
class LocalFsEffectPhaseTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path

    private fun setUpRoot() {
        root = Files.createDirectory(tempDir.resolve("root"))
    }

    private fun backendFailure(): OpenDALException = OpenDALException(OpenDALException.Code.Unexpected, "injected")

    /**
     * 替身：第 [failAt] 次调用（1 起）抛后端异常，其余照常走真实 `stat`。
     *
     * @return 该次调用的计数，供用例断言「确实走到了那一步」。
     */
    private fun statFailingAt(
        operator: org.apache.opendal.Operator,
        failAt: Int,
        calls: AtomicInteger = AtomicInteger(0),
    ): (String) -> Metadata =
        { path ->
            if (calls.incrementAndGet() == failAt) throw backendFailure()
            operator.stat(path)
        }

    // ------------------------------------------------- 阶段一：预检 → NONE

    @Test
    fun `a precheck stat failure during write reports NONE`() =
        runBlocking {
            setUpRoot()
            Files.writeString(root.resolve("a.txt"), "original")
            val calls = AtomicInteger(0)

            LocalFsStorage
                .open(root, LocalFsOptions(), statFactory = { o -> statFailingAt(o, 2, calls) })
                .use { storage ->
                    // 第 1 次 stat = 父目录检查，通过；第 2 次 stat = 目标元数据，失败 → 预检阶段。
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.write(
                                StoragePath.parse("a.txt"),
                                "new".toByteArray(),
                                StorageWriteMode.UPSERT,
                            )
                        }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.NONE, failure.effect, "预检失败，一个字节都没写")
                    assertEquals("original", Files.readString(root.resolve("a.txt")), "原文件不能被改动")
                }
        }

    @Test
    fun `a precheck stat failure during move reports NONE`() =
        runBlocking {
            setUpRoot()
            Files.writeString(root.resolve("a.txt"), "original")
            val calls = AtomicInteger(0)

            LocalFsStorage
                .open(root, LocalFsOptions(), statFactory = { o -> statFailingAt(o, 2, calls) })
                .use { storage ->
                    // 调用序 [a.txt, b.txt, ., b.txt]：第 2 次 = 目标是否已存在 → 仍在预检阶段。
                    val failure = assertFailsWith<VfsException> { storage.move(StoragePath.parse("a.txt"), StoragePath.parse("b.txt")) }

                    assertEquals(VfsEffect.NONE, failure.effect, "预检失败，源与目标都没动")
                    assertTrue(Files.exists(root.resolve("a.txt")))
                    assertFalse(Files.exists(root.resolve("b.txt")))
                }
        }

    // ---------------------------------- 阶段三：变更成功后读属性 → 不是 NONE

    /**
     * R5-T 核心：`write` 变更成功后再读属性失败，**不得**是 `NONE`。
     *
     * 预检用 2 次 stat（父目录 + 目标），第 3 次是写入完成后的回读，所以第 3 次失败正好落在阶段三。
     * 把实现改回默认 `NONE` 时这条会失败。
     */
    @Test
    fun `a write that succeeded but fails to read its attributes back is not NONE`() =
        runBlocking {
            setUpRoot()
            val calls = AtomicInteger(0)

            LocalFsStorage
                .open(root, LocalFsOptions(), statFactory = { o -> statFailingAt(o, 3, calls) })
                .use { storage ->
                    val failure =
                        assertFailsWith<VfsException> {
                            storage.write(
                                StoragePath.parse("a.txt"),
                                "new".toByteArray(),
                                StorageWriteMode.UPSERT,
                            )
                        }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertNotEquals(VfsEffect.NONE, failure.effect, "写入已经发生，不得报「没有副作用」")
                    assertEquals(VfsEffect.PARTIAL, failure.effect, "变更确实发生了")
                    assertEquals(3, calls.get())
                    assertEquals("new", Files.readString(root.resolve("a.txt")), "写入本身是成功的")
                }
        }

    /** `move` 的阶段三：改名成功、回读属性失败 → `PARTIAL`，源已不在、目标已在。 */
    @Test
    fun `a move that succeeded but fails to read its attributes back is not NONE`() =
        runBlocking {
            setUpRoot()
            Files.writeString(root.resolve("a.txt"), "original")
            val calls = AtomicInteger(0)

            // 实测调用序（探针）：[a.txt, b.txt, ., b.txt] = 源属性、目标是否已存在、目标父目录、改名后回读。
            LocalFsStorage
                .open(root, LocalFsOptions(), statFactory = { o -> statFailingAt(o, 4, calls) })
                .use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.move(StoragePath.parse("a.txt"), StoragePath.parse("b.txt")) }

                    assertNotEquals(VfsEffect.NONE, failure.effect, "改名已经发生")
                    assertEquals(VfsEffect.PARTIAL, failure.effect)
                    assertFalse(Files.exists(root.resolve("a.txt")), "源确实已移走")
                    assertEquals("original", Files.readString(root.resolve("b.txt")), "目标确实已写入")
                }
        }

    // --------------------------------- 阶段二：进入后端后失败 → UNKNOWN

    /** `createDirectory` 预检 stat 失败 → `NONE`。 */
    @Test
    fun `a precheck stat failure during createDirectory reports NONE`() =
        runBlocking {
            setUpRoot()
            Files.createDirectory(root.resolve("dir"))
            val calls = AtomicInteger(0)

            LocalFsStorage
                .open(root, LocalFsOptions(), statFactory = { o -> statFailingAt(o, 1, calls) })
                .use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.createDirectory(StoragePath.parse("new-dir")) }

                    assertEquals(VfsEffect.NONE, failure.effect, "预检失败，什么都没建")
                    assertFalse(Files.exists(root.resolve("new-dir")))
                }
        }

    /**
     * `createDirectory` 进入后端阶段后失败 → 不得是 `NONE`。
     *
     * 替身让预检的 `stat` 说「目标不存在」，于是流程真的进入 `operator.createDir`；那一步对着一个
     * 真实存在的**文件**创建目录，必然失败。这条完全确定性：不需要权限，也不赌 native 行为。
     *
     * 后端逐级补目录，失败时无法断定建到了哪一层，所以是 `UNKNOWN` 而不是 `NONE`。
     */
    @Test
    fun `a createDirectory that fails after reaching the backend is not NONE`() =
        runBlocking {
            setUpRoot()
            Files.writeString(root.resolve("a.txt"), "x")

            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    statFactory = { o ->
                        { path ->
                            if (path == "a.txt") throw OpenDALException(OpenDALException.Code.NotFound, path) else o.stat(path)
                        }
                    },
                ).use { storage ->
                    val failure = assertFailsWith<VfsException> { storage.createDirectory(StoragePath.parse("a.txt")) }

                    assertNotEquals(VfsEffect.NONE, failure.effect, "已经进入后端阶段，不得报「什么都没做」")
                    assertEquals(VfsEffect.UNKNOWN, failure.effect, "无法断定后端建到了哪一层")
                    assertEquals("x", Files.readString(root.resolve("a.txt")), "原文件不受影响")
                }
        }
}
