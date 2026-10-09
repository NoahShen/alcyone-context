package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * T24 A03～A06：公开 Runtime 直接对**真实的本机 WebDAV 服务**跑一遍最小闭环。
 *
 * 服务由 `./scripts/webdav-check` 起（WsgiDAV + Cheroot，只听 127.0.0.1、测试凭据、一次性临时根）。
 * **没有服务就直接失败**——这批用例不提供「跳过也算通过」的口子，也不会被 Fake 顶替：
 * 断言全部对着真实磁盘上的字节、真实 HTTP 响应和真实状态库。
 */
class WebDavRealServerTest {
    @TempDir
    lateinit var tempDir: Path

    /** 一条挂载：把服务根挂到 `/resources/remote`；`root` / 凭据 / endpoint 可按用例换。 */
    private fun config(
        endpoint: String = Companion.endpoint,
        password: String = Companion.password,
        root: String = "/",
    ) = VfsRuntimeConfig(
        stateDatabase = tempDir.resolve("state.db"),
        namespaces = setOf("resources"),
        mounts =
            listOf(
                MountConfig(
                    VfsPath.parse("/resources/remote"),
                    "remote",
                    MountBackend.WebDav(endpoint, root, Companion.user, password),
                ),
            ),
    )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources/remote$path")

    /** 开实例 → 跑一段 → 关实例：[AlcyoneVfs.close] 是挂起的，所以不用 `use`。 */
    private suspend fun <T> withVfs(
        config: VfsRuntimeConfig,
        block: suspend (AlcyoneVfs) -> T,
    ): T {
        val vfs = AlcyoneVfs.create(config)
        try {
            return block(vfs)
        } finally {
            vfs.close()
        }
    }

    /** A03：建目录 → 写 → 读 → stat → list → 删除；字节一致，含中文与带空格的文件名。 */
    @Test
    @Timeout(120)
    fun `A03 the public runtime does the whole loop on the real service including chinese and spaced names`() =
        runBlocking {
            val chinese = "你好，WebDAV — T24".toByteArray(Charsets.UTF_8)
            val spaced = "note with space".toByteArray()

            withVfs(config()) { vfs ->
                // 写进两层新目录：目录是写入链顺带建出来的（VFS 没有单独的 mkdir 入口）。
                val info = vfs.write(uri("/t24-loop/报告/hello.txt"), chinese)
                val spacedInfo = vfs.write(uri("/t24-loop/dir%20with%20space/note%20with%20space.md"), spaced)

                // 字节一致：读回来的和写进去的是同一份。
                assertArrayEquals(chinese, withTimeout(30_000) { vfs.read(uri("/t24-loop/报告/hello.txt")) })
                assertArrayEquals(spaced, withTimeout(30_000) { vfs.read(uri("/t24-loop/dir%20with%20space/note%20with%20space.md")) })

                // stat 说的是真实后端属性。
                val stat = vfs.stat(uri("/t24-loop/报告/hello.txt"), StatOptions(includeStorage = true))
                assertEquals(info.id, stat.id, "同一个路径始终同一个身份")
                assertEquals(chinese.size.toLong(), stat.storage?.sizeBytes, "sizeBytes 来自服务器，不是本地缓存")

                // list 看到的是服务器上真实存在的条目，中文与空格目录名原样回来。
                val parent = vfs.list(uri("/t24-loop/报告"))
                assertEquals(listOf("hello.txt"), parent.map { it.uri.path.lastSegment() })
                val root = vfs.list(uri("/t24-loop"))
                assertEquals(
                    setOf("报告", "dir with space"),
                    root.map { it.uri.path.lastSegment() }.toSet(),
                )

                // 删除之后物理与逻辑都不在了。
                vfs.delete(uri("/t24-loop/报告/hello.txt"))
                val goneRead = assertFailsWith<VfsException> { vfs.read(uri("/t24-loop/报告/hello.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, goneRead.code, "删除后读回来是 NOT_FOUND，不是空内容")
                val goneStat = assertFailsWith<VfsException> { vfs.stat(uri("/t24-loop/报告/hello.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, goneStat.code)
                vfs.delete(uri("/t24-loop"), DeleteOptions(recursive = true))

                assertNotEquals("", spacedInfo.id.value, "第二个文件也拿到了身份")
            }
        }

    /** A04：同一个状态库按同一份配置重开，已登记的 Node ID 与路径原样保留。 */
    @Test
    @Timeout(120)
    fun `A04 reopening with the same configuration keeps the registered identity`() =
        runBlocking {
            val target = uri("/t24-identity/a.txt")
            val id: NodeId = withVfs(config()) { vfs -> vfs.write(target, "identity".toByteArray()).id }

            // 关掉重开：新连接、新 Adapter、新事务，只有真落库的那份状态还在。
            withVfs(config()) { vfs ->
                val reopened = vfs.getNode(id)
                assertEquals(id, reopened.id, "重开之后还是同一个 Node ID")
                assertEquals(target.path, reopened.uri.path, "路径也没变")
                assertArrayEquals("identity".toByteArray(), withTimeout(30_000) { vfs.read(target) })
            }
        }

    /** A05：认证失败、缺失文件、服务不可达都变成 VfsException，而且消息 / cause 里不出现凭据。 */
    @Test
    @Timeout(120)
    fun `A05 auth failure missing files and an unreachable service all become vfs exceptions without leaking credentials`() =
        runBlocking {
            // 1) 密码错了：按观察到的后端行为，HTTP 401 → STORAGE_ACCESS_DENIED。
            val refused =
                assertFailsWith<VfsException> {
                    withVfs(config(password = "wrong-password-on-purpose")) { vfs ->
                        withTimeout(30_000) { vfs.read(uri("/t24-auth/hello.txt")) }
                    }
                }
            assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, refused.code, "认证被拒要能按权限错误分支处理")

            // 2) 凭据不许出现在异常链的任何一层。
            val chain = describeChain(refused)
            assertFalse(chain.contains(password), "密码出现在了异常链里：$chain")
            assertFalse(chain.contains("wrong-password-on-purpose"), "测试用的错误密码也不该回显")

            // 3) 缺失文件：NOT_FOUND，同样不带凭据。
            withVfs(config()) { vfs ->
                val missing = assertFailsWith<VfsException> { vfs.read(uri("/t24-auth/definitely-missing.txt")) }
                assertEquals(VfsErrorCode.NOT_FOUND, missing.code)
                assertFalse(describeChain(missing).contains(password))
            }

            // 4) 服务不可达：在约定的等待上限内报成 VfsException，而不是挂住或漏出原生异常。
            val startedAt = System.nanoTime()
            val unreachable =
                assertFailsWith<VfsException> {
                    withVfs(config(endpoint = deadEndpoint())) { vfs ->
                        withTimeout(30_000) { vfs.stat(uri("/t24-auth/hello.txt")) }
                    }
                }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMillis < 30_000, "网络失败必须在等待上限内返回，实测 ${elapsedMillis}ms")
            assertFalse(describeChain(unreachable).contains(password), "不可达的错误里也不许出现凭据")
        }

    /** A06：正常关闭之后同一个状态库能再开，读写的事实还在（释放顺序沿用 T18）。 */
    @Test
    @Timeout(120)
    fun `A06 a normal close releases the adapter and the same library reopens against the live service`() =
        runBlocking {
            withVfs(config()) { vfs ->
                vfs.write(uri("/t24-lifecycle/a.txt"), "first".toByteArray())
            }

            withVfs(config()) { vfs ->
                val stored = withTimeout(30_000) { vfs.read(uri("/t24-lifecycle/a.txt")) }
                assertArrayEquals("first".toByteArray(), stored)
                val info = vfs.write(uri("/t24-lifecycle/b.txt"), "second".toByteArray())
                assertEquals(
                    1,
                    vfs.list(uri("/t24-lifecycle")).count { it.uri.path.lastSegment() == "b.txt" },
                )
                assertEquals(info.id, vfs.stat(uri("/t24-lifecycle/b.txt")).id)
            }
        }

    /** 异常链的全部消息拼在一起：检查凭据泄漏时用它，而不是只看最外层那一行。 */
    private fun describeChain(failure: Throwable): String =
        generateSequence(failure) { it.cause }
            .flatMap { sequenceOf(it.message ?: "", it.toString()) }
            .joinToString("\n")

    /** 分配一个空闲端口然后立刻放手：那上面没有服务，连接一定失败。 */
    private fun deadEndpoint(): String = ServerSocket(0).use { socket -> "http://127.0.0.1:${socket.localPort}" }

    private companion object {
        private lateinit var endpoint: String
        private lateinit var user: String
        private lateinit var password: String

        @JvmStatic
        @BeforeAll
        fun requireFixture() {
            endpoint = requireVariable("T24_WEBDAV_ENDPOINT")
            user = requireVariable("T24_WEBDAV_USER")
            password = requireVariable("T24_WEBDAV_PASSWORD")
        }

        private fun requireVariable(name: String): String =
            System.getenv(name)
                ?: error(
                    "$name is not set: run these tests through ./scripts/webdav-check. " +
                        "A missing service is a failure here, never a skip.",
                )
    }
}

/** 路径的最后一个段：`VfsPath` 没有直接暴露，按已解码的段列表取。 */
private fun VfsPath.lastSegment(): String = segments.last()
