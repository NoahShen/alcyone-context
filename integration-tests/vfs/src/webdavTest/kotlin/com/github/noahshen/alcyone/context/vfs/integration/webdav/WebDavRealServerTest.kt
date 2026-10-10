package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
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

    /** 服务数据根：脚本通过 `T24_WEBDAV_DATA_ROOT` 传进来，用于核对物理文件。 */
    private fun dataRoot(): Path = Path.of(requireVariable("T24_WEBDAV_DATA_ROOT"))

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

    /** R1：非根 endpoint/root 的真实服务定位证据——文件落到 `/dav/team` 下而不是根 `/` 下。 */
    @Test
    @Timeout(120)
    fun `A03b a non-root endpoint root combination addresses the right place on the real service`() =
        runBlocking {
            val content = "non-root location".toByteArray()

            withVfs(config(root = "/dav/team")) { vfs ->
                val info = vfs.write(uri("/t24-locate/hello.txt"), content)
                assertArrayEquals(content, withTimeout(30_000) { vfs.read(uri("/t24-locate/hello.txt")) })

                // 物理文件必须落在 `/dav/team` 下，不是根 `/` 下。
                val teamTarget = dataRoot().resolve("dav/team/t24-locate/hello.txt")
                assertTrue(Files.exists(teamTarget), "文件应该落在 /dav/team 下：$teamTarget")
                assertArrayEquals(content, Files.readAllBytes(teamTarget))

                // 根 `/` 下不应该有这个文件。
                val rootTarget = dataRoot().resolve("t24-locate/hello.txt")
                assertFalse(Files.exists(rootTarget), "文件不应该落在根 / 下：$rootTarget")

                vfs.delete(uri("/t24-locate"), DeleteOptions(recursive = true))
                assertFalse(Files.exists(dataRoot().resolve("dav/team/t24-locate")), "删除后目录也不在了")
            }
        }

    /** R1：带路径的 endpoint 与 root 拆分写法，和只写长 root 的写法落在同一物理位置。 */
    @Test
    @Timeout(120)
    fun `A03b a pathed endpoint plus a short root addresses the same place as the long root`() =
        runBlocking {
            val content = "split endpoint location".toByteArray()

            // endpoint 带路径 `/dav` + root `/team`，完整路径仍是 `/dav/team`。
            withVfs(config(endpoint = "${Companion.endpoint}/dav", root = "/team")) { vfs ->
                val info = vfs.write(uri("/t24-locate-split/hello.txt"), content)
                assertArrayEquals(content, withTimeout(30_000) { vfs.read(uri("/t24-locate-split/hello.txt")) })

                // 物理文件必须落在 `/dav/team` 下——与 A03b 的长 root 写法同一位置。
                val teamTarget = dataRoot().resolve("dav/team/t24-locate-split/hello.txt")
                assertTrue(Files.exists(teamTarget), "endpoint /dav + root /team 应与 root /dav/team 落点一致：$teamTarget")
                assertArrayEquals(content, Files.readAllBytes(teamTarget))

                val rootTarget = dataRoot().resolve("t24-locate-split/hello.txt")
                assertFalse(Files.exists(rootTarget), "文件不应该落在根 / 下：$rootTarget")

                assertNotEquals("", info.id.value)
                vfs.delete(uri("/t24-locate-split"), DeleteOptions(recursive = true))
                assertFalse(Files.exists(dataRoot().resolve("dav/team/t24-locate-split")), "删除后目录也不在了")
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

    /** R2：文件删除失败（服务端实际删除后断开响应）不再误报 NONE。 */
    @Test
    @Timeout(60)
    fun `R2 deleting a file whose response is lost reports UNKNOWN and the file is physically gone`() =
        runBlocking {
            val dataRoot = dataRoot()
            val target = dataRoot.resolve("r2-file.txt")
            Files.write(target, "delete-me".toByteArray())

            SilentDeleteServer(dataRoot).use { silent ->
                val failure =
                    assertFailsWith<VfsException> {
                        withVfs(config(endpoint = silent.endpoint)) { vfs ->
                            vfs.delete(uri("/r2-file.txt"))
                        }
                    }

                assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "删除请求失败要报 STORAGE_ERROR")
                assertEquals(VfsEffect.UNKNOWN, failure.effect, "删除请求边界直接钉住 UNKNOWN")
                assertTrue(
                    failure.cause?.javaClass?.name == "org.apache.opendal.OpenDALException",
                    "后端原始异常原样挂在 cause 上：${failure.cause}",
                )
                assertFalse(Files.exists(target), "服务端实际删除了文件，只是回包丢了")
                assertFalse(describeChain(failure).contains(password), "错误里也不许出现凭据")
            }
        }

    /** R2：非递归空目录删除失败（服务端实际删除后断开响应）不再误报 NONE。 */
    @Test
    @Timeout(60)
    fun `R2 deleting an empty directory whose response is lost reports UNKNOWN and the directory is physically gone`() =
        runBlocking {
            val dataRoot = dataRoot()
            val target = dataRoot.resolve("r2-empty-dir")
            Files.createDirectories(target)

            SilentDeleteServer(dataRoot).use { silent ->
                val failure =
                    assertFailsWith<VfsException> {
                        withVfs(config(endpoint = silent.endpoint)) { vfs ->
                            vfs.delete(uri("/r2-empty-dir"))
                        }
                    }

                assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "删除请求失败要报 STORAGE_ERROR")
                assertEquals(VfsEffect.UNKNOWN, failure.effect, "删除请求边界直接钉住 UNKNOWN")
                assertTrue(
                    failure.cause?.javaClass?.name == "org.apache.opendal.OpenDALException",
                    "后端原始异常原样挂在 cause 上：${failure.cause}",
                )
                assertFalse(Files.exists(target), "服务端实际删除了目录，只是回包丢了")
            }
        }

    /** R2：Runtime 登记源并设置 Metadata 后删除失败，旧记录与事件不变。 */
    @Test
    @Timeout(60)
    fun `R2 a registered file with metadata keeps its records and events when the delete fails`() =
        runBlocking {
            val dataRoot = dataRoot()
            val target = dataRoot.resolve("r2-registered.txt")
            Files.write(target, "registered".toByteArray())

            SilentDeleteServer(dataRoot).use { silent ->
                withVfs(config(endpoint = silent.endpoint)) { vfs ->
                    val events = CopyOnWriteArrayList<VfsEvent>()
                    vfs.subscribe { event -> events += event }

                    // 通过 Runtime 登记源（stat 懒注册，不产生变更事件）并设置 Metadata。
                    val info = vfs.stat(uri("/r2-registered.txt"))
                    val tag = NodeMetadata(setOf("r2"), "删除失败也要保留")
                    vfs.setMetadata(info.id, tag)

                    // 等 Metadata 事件到齐（提交后通知，异步投递）。
                    withTimeout(10_000) { while (events.size < 1) delay(10) }
                    assertEquals(listOf(VfsEventType.METADATA_UPDATED), events.map { it.type })

                    val failure = assertFailsWith<VfsException> { vfs.delete(uri("/r2-registered.txt")) }
                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
                    assertEquals(VfsEffect.UNKNOWN, failure.effect, "删除请求边界直接钉住 UNKNOWN")
                    assertTrue(
                        failure.cause?.javaClass?.name == "org.apache.opendal.OpenDALException",
                        "后端原始异常原样挂在 cause 上：${failure.cause}",
                    )
                    assertFalse(describeChain(failure).contains(password), "错误里也不许出现凭据")

                    // 物理上文件已被服务端删除，但逻辑记录与事件都没被这次失败改写。
                    assertFalse(Files.exists(target), "服务端实际删除了文件，只是回包丢了")
                    val node = vfs.getNode(info.id)
                    assertEquals(info.id, node.id)
                    assertEquals(uri("/r2-registered.txt").path, node.uri.path)
                    assertEquals(tag, vfs.getMetadata(info.id))
                    assertEquals(
                        listOf(VfsEventType.METADATA_UPDATED),
                        events.map { it.type },
                        "失败没有产生新事件",
                    )
                }
            }
        }

    /** R5：静默删除装置只操作自己那个临时根——解码后的点段、绝对路径等越界请求被拒，根外文件不动。 */
    @Test
    @Timeout(60)
    fun `R5 the silent delete server refuses every path that escapes its own root`() =
        runBlocking {
            val ownRoot = Files.createTempDirectory("t24-silent-own-root")
            val outside = ownRoot.parent.resolve(ownRoot.fileName.toString() + "-outside.txt")
            Files.write(outside, "keep me".toByteArray())

            SilentDeleteServer(ownRoot).use { silent ->
                val rootDirName = ownRoot.fileName.toString()
                val outsideName = outside.fileName.toString()

                // 越界请求一律被拒（400），根外哨兵文件不动。
                assertEquals(400, rawRequest(silent.endpoint, "DELETE", "/$rootDirName/../$outsideName"))
                assertEquals(400, rawRequest(silent.endpoint, "DELETE", "/%2e%2e%2f$outsideName"))
                assertEquals(400, rawRequest(silent.endpoint, "DELETE", "//tmp/$outsideName"))
                assertEquals(400, rawRequest(silent.endpoint, "DELETE", "/$rootDirName/../../etc/hostname"))
                assertTrue(Files.exists(outside), "越界请求不能删到根外文件")
                assertArrayEquals("keep me".toByteArray(), Files.readAllBytes(outside))

                // 根内合法删除仍然有效：文件被删，只是回包丢失（客户端读到 EOF）。
                val victim = ownRoot.resolve("victim.txt")
                Files.write(victim, "delete me".toByteArray())
                rawRequest(silent.endpoint, "DELETE", "/victim.txt")
                assertFalse(Files.exists(victim), "根内删除仍然生效")
            }
        }

    /** 异常链的全部消息拼在一起：检查凭据泄漏时用它，而不是只看最外层那一行。 */
    private fun describeChain(failure: Throwable): String =
        generateSequence(failure) { it.cause }
            .flatMap { sequenceOf(it.message ?: "", it.toString()) }
            .joinToString("\n")

    /** 分配一个空闲端口然后立刻放手：那上面没有服务，连接一定失败。 */
    private fun deadEndpoint(): String = ServerSocket(0).use { socket -> "http://127.0.0.1:${socket.localPort}" }

    /** 发一条原始 HTTP 请求，返回状态码；读不到响应行返回 -1。 */
    private fun rawRequest(
        endpoint: String,
        method: String,
        path: String,
    ): Int {
        val uri = URI(endpoint)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("$method $path HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val statusLine = socket.getInputStream().bufferedReader().readLine() ?: return -1
            return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

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

/**
 * R2 测试装置：接受 PROPFIND 请求并返回真实属性，接受 DELETE 请求后删除文件但**不发响应**直接关闭连接。
 *
 * 模拟「服务端实际删除了但回包丢失」的场景：OpenDAL 会收到连接关闭错误，
 * 但物理上文件已经被删除。用于验证 delete 的 effect 是 UNKNOWN 而不是 NONE。
 *
 * 装置边界（R5 修复）：
 * - 只绑 127.0.0.1，不监听其他网卡；
 * - 请求路径先解码再规范化，只允许落在自己 [dataRoot] 内的单层路径——点段、多余前导斜杠一律拒；
 * - 连接读取有有限等待；[close] 关掉监听 socket 与所有已接纳连接，并等处理线程到点退出。
 */
private class SilentDeleteServer(
    private val dataRoot: Path,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))

    private val workers = CopyOnWriteArrayList<Thread>()
    private val openSockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var running = true

    val endpoint: String get() = "http://$LOOPBACK:${serverSocket.localPort}"

    private val acceptThread: Thread

    init {
        acceptThread = Thread { acceptLoop() }
        acceptThread
            .apply {
                isDaemon = true
                name = "silent-delete-accept"
            }.start()
    }

    private fun acceptLoop() {
        while (running) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
            openSockets += socket
            val worker = Thread { handle(socket) }
            workers += worker
            worker
                .apply {
                    isDaemon = true
                    name = "silent-delete-handle"
                }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            // 连接读取有有限等待：客户端不说话时线程不会一直挂住，close 的等待也才有界。
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.use {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8)
                val target = insideRoot(decoded)

                when {
                    target == null -> respond400(socket)
                    method == "PROPFIND" -> respondPropfind(socket, decoded, target, headers["depth"] ?: "0")
                    method == "DELETE" -> deleteAndClose(target)
                    else -> respond404(socket)
                }
            }
        } catch (e: Exception) {
            // 连接可能在处理中途被客户端或 close 关掉，忽略。
        } finally {
            openSockets -= socket
        }
    }

    /**
     * 把请求路径收敛成 [dataRoot] 内的目标；越界一律返回 null。
     *
     * 只接受单个前导 `/` 的路径：解码后的 `..` 段、多余前导斜杠（`//` 会让 `Path.resolve` 当成绝对路径）
     * 以及任何规范化后可能跑出临时根的写法，都不碰；仅允许单个结尾 `/`（WebDAV 目录请求自带）。
     */
    private fun insideRoot(decoded: String): Path? {
        if (!decoded.startsWith("/") || decoded.startsWith("//")) return null
        // 允许一个结尾 `/`（WebDAV 列目录 / 删目录的请求会带），其余空段一律拒。
        val path = decoded.removeSuffix("/")
        val segments = mutableListOf<String>()
        if (path.isNotEmpty()) {
            for (segment in path.split('/').drop(1)) {
                when (segment) {
                    ".." -> return null
                    else -> {
                        if (segment.isEmpty() || segment == ".") return null
                        segments += segment
                    }
                }
            }
        }
        val base = dataRoot.toAbsolutePath().normalize()
        // 段列表拼成**相对**路径再 resolve：拼上前导 `/` 会让 resolve 把整个 base 换成绝对路径。
        val target = if (segments.isEmpty()) base else base.resolve(segments.joinToString("/")).normalize()
        return target.takeIf { it.startsWith(base) }
    }

    private fun respondPropfind(
        socket: Socket,
        path: String,
        target: Path,
        depth: String,
    ) {
        if (!Files.exists(target)) {
            respond404(socket)
            return
        }
        val isDir = Files.isDirectory(target)
        val entries = mutableListOf(propEntry(path, target, isDir))
        if (depth == "1" && isDir) {
            Files.list(target).use { stream ->
                stream.sorted().forEach { child ->
                    val childPath = path.trimEnd('/') + "/" + child.fileName
                    entries += propEntry(childPath, child, Files.isDirectory(child))
                }
            }
        }
        val body =
            buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
                append("<D:multistatus xmlns:D=\"DAV:\">")
                entries.forEach { (href, dir, size) ->
                    val rt = if (dir) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>"
                    val sizeXml = if (!dir && size != null) "<D:getcontentlength>$size</D:getcontentlength>" else ""
                    append("<D:response><D:href>").append(href).append("</D:href><D:propstat><D:prop>")
                    append("<D:getlastmodified>Thu, 01 Jan 1970 00:00:00 GMT</D:getlastmodified>")
                    append(sizeXml)
                    append(rt)
                    append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                }
                append("</D:multistatus>")
            }.toByteArray()
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private fun propEntry(
        href: String,
        path: Path,
        isDir: Boolean,
    ): Triple<String, Boolean, Long?> = Triple(href, isDir, if (!isDir) Files.size(path) else null)

    /** 删除目标，然后不发响应直接关闭连接（socket.use 会关）。 */
    private fun deleteAndClose(target: Path) {
        if (Files.exists(target)) {
            if (Files.isDirectory(target)) {
                target.toFile().deleteRecursively()
            } else {
                Files.delete(target)
            }
        }
        // 不发响应，socket.use 在 handle 结束时关闭连接，客户端收到 EOF。
    }

    private fun respond400(socket: Socket) {
        try {
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun respond404(socket: Socket) {
        try {
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
        } catch (e: Exception) {
            // 忽略
        }
    }

    override fun close() {
        running = false
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // 忽略
        }
        // 关掉已接纳的连接：处理线程的有限读取会到点退出，join 也才有界。
        openSockets.forEach { socket -> runCatching { socket.close() } }
        workers.forEach { worker -> runCatching { worker.join(READ_TIMEOUT_MILLIS + 1_000L) } }
        runCatching { acceptThread.join(READ_TIMEOUT_MILLIS + 1_000L) }
    }

    private companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 50
        private const val READ_TIMEOUT_MILLIS = 5_000
    }
}

/** 路径的最后一个段：`VfsPath` 没有直接暴露，按已解码的段列表取。 */
private fun VfsPath.lastSegment(): String = segments.last()
