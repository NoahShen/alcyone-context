package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * T18 A04：初始化到一半失败或被取消，已取得的资源要全部释放、原始异常要留着、修好之后还能再建一次。
 *
 * 故障是**注入的**（真文件系统不会自己停在某个组装步骤上）：用 [AlcyoneVfs.AssemblyProbe] 在
 * 「Adapter 与状态库都建好了、实例还没交出去」的那一刻抛错或挂起。
 * 除此之外都是真的：真的 OpenDAL Adapter、真的 SQLite 连接、真的文件锁。
 */
class InitializationFailureTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("disk"))
    }

    private fun config() =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk)),
        )

    @Test
    @Timeout(60)
    fun `a late failure releases every acquired resource and keeps the original error`() =
        runBlocking {
            prepare()
            val injected = VfsException(VfsErrorCode.STORAGE_ERROR, "injected failure after assembly")
            var captured: AlcyoneVfs.Assembly? = null

            val thrown =
                assertFailsWith<VfsException> {
                    AlcyoneVfs.create(
                        config(),
                        AlcyoneVfs.AssemblyProbe { assembly ->
                            captured = assembly
                            throw injected
                        },
                    )
                }

            assertSame(injected, thrown, "原始异常原样抛出来，不被清理或包装顶掉")
            val assembly = requireNotNull(captured)
            assembly.storages.forEach { storage ->
                val closed = assertFailsWith<VfsException> { storage.stat(StoragePath.root) }
                assertEquals(VfsErrorCode.CLOSED, closed.code, "已经打开的 Adapter 必须被关掉")
            }
            assertTrue(assembly.notifier.isClosed, "通知器必须被关掉")
            assertTrue(assembly.state.isClosed, "状态库连接必须被关掉")

            // 用完还能再建一次：没有残留的锁、没有没关的连接。
            withVfs(config()) { vfs ->
                assertEquals(
                    "/resources",
                    vfs
                        .stat(VfsUri.parse("alcyone://resources"))
                        .uri.path
                        .toString(),
                )
            }
        }

    /**
     * 取消落在「资源都拿到了、还没交出去」这一刻：清理照做，取消原样传播，之后还能再建一次。
     *
     * 被取消的协程自己 `await()` 必然会报取消，证明不了 `create` 内部没有把取消咽下去。
     * 所以在协程内部把它真正抛出来的东西接进 [escaped]，取消之后再拿它做断言。
     */
    @Test
    @Timeout(60)
    fun `a cancellation after acquiring resources releases them and propagates`() =
        runBlocking {
            prepare()
            val arrived = CompletableDeferred<AlcyoneVfs.Assembly>()
            val escaped = CompletableDeferred<Throwable>()
            val injected = CancellationException("cancel injected by the test")
            val job =
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        AlcyoneVfs.create(
                            config(),
                            AlcyoneVfs.AssemblyProbe { assembly ->
                                arrived.complete(assembly)
                                CompletableDeferred<Unit>().await() // 挂在这里等取消
                            },
                        )
                        // 正常返回时不去 complete，让下面 withTimeout 超时，这样问题会显形。
                    } catch (failure: Throwable) {
                        // complete 不是挂起函数，已经取消也执行得到。
                        escaped.complete(failure)
                        throw failure
                    }
                }

            val assembly = withTimeout(30_000) { arrived.await() }
            job.cancel(injected)
            val thrown = withTimeout(30_000) { escaped.await() }

            assertTrue(thrown is CancellationException, "取消原样传播，不被包装成业务异常：$thrown")
            assertTrue(thrown === injected || thrown.cause === injected, "抛出来的是我们注入的那一个取消，不是别的：$thrown")
            assembly.storages.forEach { storage ->
                assertEquals(VfsErrorCode.CLOSED, assertFailsWith<VfsException> { storage.stat(StoragePath.root) }.code)
            }
            assertTrue(assembly.notifier.isClosed)
            assertTrue(assembly.state.isClosed)

            withVfs(config()) { }
        }

    /** create → 用 → close 的三步。`close()` 是挂起函数，所以用 `try/finally` 而不是 `use`。 */
    private suspend fun <T> withVfs(
        config: VfsRuntimeConfig,
        block: suspend (AlcyoneVfs) -> T,
    ): T {
        val vfs = AlcyoneVfs.create(config)
        return try {
            block(vfs)
        } finally {
            vfs.close()
        }
    }
}
