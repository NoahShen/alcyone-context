package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.DefaultVfs
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import com.github.noahshen.alcyone.context.vfs.storage.opendal.WebDavStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * T25 A06：失败事实——远端写入部分完成后失败、目标确认失败、删源失败都不误提交；UNKNOWN 不降级，已知变化不报 NONE。
 * 以及 R4：Adapter 级直接调用失败与流回收证据。
 */
class T25WebDavFailureReportTest {
    @TempDir
    lateinit var tempDir: Path

    private fun physical(relative: String): Path = WebDavFixture.dataRoot().resolve(relative)

    private fun localUri(name: String) = VfsUri.parse("alcyone://resources/local/$name")

    private fun remoteUri(
        case: String,
        name: String,
    ) = VfsUri.parse("alcyone://resources/remote/$case/$name")

    /**
     * A06：**远端写入部分完成后连接断开**（真实 HTTP 结果丢失）——源保持完整、不调用源删除、
     * 原路径映射不迁移、无成功移动事件；后端 code / cause 保留、effect 不报 NONE。
     */
    @Test
    @Timeout(120)
    fun `A06 a real dropped write response keeps the source and records no move`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            Files.writeString(localRoot.resolve("a06-source.txt"), "source-kept")
            val faultDataRoot = Files.createDirectories(tempDir.resolve("fault-data"))

            DroppedWriteServer(faultDataRoot).use { fault ->
                withStack(localRoot, endpoint = fault.endpoint) { stack ->
                    // 1. 先进行 stat 登记并设置 metadata
                    val statInfo = stack.vfs.stat(localUri("a06-source.txt"))
                    val nodeId = statInfo.id
                    val meta = NodeMetadata(tags = setOf("test-tag"), description = "pre-move meta")
                    stack.vfs.setMetadata(nodeId, meta)

                    val beforeEvents = stack.eventRows()

                    val failure =
                        assertFailsWith<VfsException> {
                            stack.vfs.move(localUri("a06-source.txt"), remoteUri("dropped", "target.txt"))
                        }

                    // 断言服务端确已收到并处理了 PUT 请求（写入已发生）
                    assertTrue(fault.putCount.get() >= 1, "PUT 请求确实已由故障服务接收并落盘: ${fault.putCount.get()}")

                    // 目标物理临时文件在服务端落盘并包含写入内容
                    val droppedTarget = faultDataRoot.resolve("dropped/target.txt")
                    assertTrue(Files.exists(droppedTarget), "服务端临时目录中目标物理文件已落盘: $droppedTarget")
                    assertEquals("source-kept", Files.readString(droppedTarget), "服务端落盘内容与源一致")

                    // 异常类型与 effect 断言
                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "写响应断开保留 STORAGE_ERROR")
                    assertEquals(
                        VfsEffect.UNKNOWN,
                        failure.effect,
                        "写入边界结果丢失直接钉住 UNKNOWN，不降级为 NONE / PARTIAL",
                    )
                    assertNotNull(failure.cause, "后端原始异常挂在 cause 上")

                    // 数据库状态验证：node 路径未变，metadata 未变，没有 FILE_MOVED 事件
                    val nodeRows = stack.queryRows("SELECT id, vfs_path FROM node WHERE id = '$nodeId'")
                    assertEquals(1, nodeRows.size)
                    assertEquals("/resources/local/a06-source.txt", nodeRows[0][1], "Node vfs_path 保持源路径未迁移")

                    val metaRows = stack.queryRows("SELECT payload FROM metadata WHERE node_id = '$nodeId'")
                    assertEquals(1, metaRows.size)
                    assertTrue(metaRows[0][0].contains("pre-move meta"), "Metadata 保持未变")

                    assertFalse(
                        stack.eventRows().any { it[0] == "FILE_MOVED" },
                        "写入失败不得提交成功移动事件",
                    )
                    assertEquals(beforeEvents, stack.eventRows(), "事件表逐条不变")

                    // 源物理文件保持完整
                    assertTrue(Files.exists(localRoot.resolve("a06-source.txt")), "源物理文件保留")
                    assertEquals("source-kept", Files.readString(localRoot.resolve("a06-source.txt")))
                    assertArrayEquals("source-kept".toByteArray(), stack.vfs.read(localUri("a06-source.txt")), "源逻辑内容可读")
                }
            }
        }

    /**
     * R3：向故障服务直接发起带 '/../' 的路径逃逸请求，断言收到 400 且临时根外未受影响。
     */
    @Test
    @Timeout(60)
    fun `R3 dropped write server rejects path traversal with 400`() {
        val faultDataRoot = Files.createDirectories(tempDir.resolve("fault-data-traversal"))
        DroppedWriteServer(faultDataRoot).use { fault ->
            val uri = URI(fault.endpoint)
            Socket(uri.host, uri.port).use { socket ->
                val out = socket.getOutputStream()
                val request = "PROPFIND /../escape.txt HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n"
                out.write(request.toByteArray())
                out.flush()

                val reader = socket.getInputStream().bufferedReader()
                val statusLine = reader.readLine()
                assertNotNull(statusLine, "应当收到响应行")
                assertTrue(statusLine.contains("400"), "带 '/../' 的请求必须返回 400 Bad Request: $statusLine")
            }
            assertFalse(Files.exists(tempDir.resolve("escape.txt")), "根外文件不得被创建或影响")
        }
    }

    /**
     * R3 补证：编码点段 `/%2e%2e/` 也被拒绝（400），且根外无创建。
     *
     * 用 MKCOL：复核复现的正是这一条（PUT / MKCOL 写入后能写到根外），
     * 所以只钉住“响应被拒”和“根外没有目录”两个事实。
     */
    @Test
    @Timeout(60)
    fun `R3 dropped write server rejects encoded path traversal outside the root`() {
        val faultDataRoot = Files.createDirectories(tempDir.resolve("fault-data-encoded-traversal"))
        DroppedWriteServer(faultDataRoot).use { fault ->
            val uri = URI(fault.endpoint)
            Socket(uri.host, uri.port).use { socket ->
                val out = socket.getOutputStream()
                val request = "MKCOL /%2e%2e/outside HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n"
                out.write(request.toByteArray())
                out.flush()

                val reader = socket.getInputStream().bufferedReader()
                val statusLine = reader.readLine()
                assertNotNull(statusLine, "应当收到响应行")
                assertTrue(
                    statusLine.contains("400") || statusLine.contains("403"),
                    "编码点段请求必须被拒（400/403）: $statusLine",
                )
            }
            assertFalse(Files.exists(tempDir.resolve("outside")), "根外不得被创建任何内容")
            assertFalse(Files.exists(faultDataRoot.resolve("../outside")), "根外不得被创建任何内容（另一种路径写法）")
        }
    }

    /**
     * R3 补证：普通含 '+' 的路径正常（解码保留 '+'，不是空路径也不被判成目录）。
     */
    @Test
    @Timeout(60)
    fun `R3 dropped write server accepts a normal path containing plus`() {
        val faultDataRoot = Files.createDirectories(tempDir.resolve("fault-data-plus"))
        Files.writeString(faultDataRoot.resolve("file+name.txt"), "plus-ok")
        DroppedWriteServer(faultDataRoot).use { fault ->
            val uri = URI(fault.endpoint)
            Socket(uri.host, uri.port).use { socket ->
                val out = socket.getOutputStream()
                // '+' 按本服务器的解码规则保留为字面量 '+'
                val request = "PROPFIND /file+name.txt HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n"
                out.write(request.toByteArray())
                out.flush()

                val reader = socket.getInputStream().bufferedReader()
                val statusLine = reader.readLine()
                assertNotNull(statusLine, "应当收到响应行")
                assertTrue(statusLine.contains("207"), "含 '+' 的正常路径照常列举（207 Multi-Status）: $statusLine")
                assertTrue(reader.readText().contains("<D:getcontentlength>7"), "列举结果里是那个文件的真实长度")
            }
        }
    }

    /**
     * R3 补证：在途连接代表。
     *
     * 客户端连上但不发完整请求（停在 header 中间），server 保持在 worker 里等读；
     * 这时 close() 必须先把接纳线程停干净，再关闭已登记连接，worker 才能退。
     * 断言 close 返回后服务确实已死：端口不再接受新连接。
     */
    @Test
    @Timeout(30)
    fun `R3 dropping the server closes an in-flight connection and stops accepting`() {
        val faultDataRoot = Files.createDirectories(tempDir.resolve("fault-data-inflight"))
        val endpoint: String
        val port: Int
        DroppedWriteServer(faultDataRoot).use { fault ->
            val uri = URI(fault.endpoint)
            endpoint = fault.endpoint
            port = uri.port
            // 开一条连接，只发半截 header，然后停手：这条连接属于“在途”。
            val stalled = Socket(uri.host, uri.port)
            stalled.getOutputStream().write("PROPFIND /in-flight HTTP/1.1\r\nHost: x".toByteArray())
            stalled.getOutputStream().flush()
            // 等 worker 把它登记进 openSockets。
            val deadline = System.nanoTime() + 5_000_000_000L
            while (fault.openSocketCount == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(fault.openSocketCount >= 1, "在途连接已被服务登记")

            // 服务关闭，必须主动断开这条在途连接，不能靠读超时。
            fault.close()
            // 客户端应该能察觉到对端关闭：读到 EOF（-1）或抛重置都算主动断开。
            stalled.soTimeout = 2_000
            val closed =
                try {
                    stalled.getInputStream().read() == -1
                } catch (_: Exception) {
                    true
                } finally {
                    runCatching { stalled.close() }
                }
            assertTrue(closed, "close 主动断开了在途连接，客户端读到 EOF / 重置")
        }

        // 服务已经彻底停：端口不再接受新连接。
        val refused =
            try {
                Socket().use {
                    it.connect(java.net.InetSocketAddress(URI(endpoint).host, port), 1_000)
                }
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(refused, "close 后端口不再接受新连接 ($endpoint)")
    }

    /**
     * A06：**目标确认失败**——写入已成功但确认那一步抛错：源不删、不提交、effect 至少 PARTIAL（已知目标已写入）。
     */
    @Test
    @Timeout(120)
    fun `A06 a target confirmation failure refuses to delete the source and keeps the effect partial`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            Files.writeString(localRoot.resolve("a06-source.txt"), "confirm-me")
            val target = "a06-target.txt"

            try {
                withStack(localRoot) { stack ->
                    val statInfo = stack.vfs.stat(localUri("a06-source.txt"))
                    val nodeId = statInfo.id

                    val failingRemote = FailingStatStorage(stack.rawRemote, failOnStatOf = "confirm/$target")
                    val before = stack.eventRows()

                    val failure =
                        assertFailsWith<VfsException> {
                            stack.vfsWith(remote = failingRemote).move(localUri("a06-source.txt"), remoteUri("confirm", target))
                        }

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "确认失败的 code 保留为 STORAGE_ERROR")
                    assertEquals(VfsEffect.PARTIAL, failure.effect, "已知目标已写入，effect 至少 PARTIAL，不是 NONE")

                    assertTrue(Files.exists(physical("confirm/$target")), "目标已写入服务端")
                    assertTrue(Files.exists(localRoot.resolve("a06-source.txt")), "确认失败不删源")
                    assertEquals(before, stack.eventRows(), "确认失败不提交任何事件")

                    val nodeRows = stack.queryRows("SELECT vfs_path FROM node WHERE id = '$nodeId'")
                    assertEquals("/resources/local/a06-source.txt", nodeRows[0][0], "Node 路径未变")
                }
            } finally {
                deleteRemoteQuietly("confirm")
            }
        }

    /**
     * A06：**删源失败**——目标已确认但删源那一步失败：目标留着、源也留着、不提交、effect 收紧为 PARTIAL。
     */
    @Test
    @Timeout(120)
    fun `A06 a source delete failure leaves both sides and keeps the effect partial`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            Files.writeString(localRoot.resolve("a06-source.txt"), "delete-source")

            try {
                withStack(localRoot) { stack ->
                    val statInfo = stack.vfs.stat(localUri("a06-source.txt"))
                    val nodeId = statInfo.id

                    val failingLocal = FailingDeleteStorage(stack.rawLocal)
                    val before = stack.eventRows()

                    val failure =
                        assertFailsWith<VfsException> {
                            stack.vfsWith(local = failingLocal).move(localUri("a06-source.txt"), remoteUri("delsrc", "target.txt"))
                        }

                    assertEquals(VfsEffect.PARTIAL, failure.effect, "删源失败收紧断言为 PARTIAL")

                    assertTrue(Files.exists(physical("delsrc/target.txt")), "目标在服务端")
                    assertTrue(Files.exists(localRoot.resolve("a06-source.txt")), "删源失败源还在")
                    assertEquals(before, stack.eventRows(), "删源失败不提交任何事件")

                    val nodeRows = stack.queryRows("SELECT vfs_path FROM node WHERE id = '$nodeId'")
                    assertEquals("/resources/local/a06-source.txt", nodeRows[0][0], "Node 路径未变")
                }
            } finally {
                deleteRemoteQuietly("delsrc")
            }
        }

    /** A06：成功反例——一次正常跨后端移动只提交一条 FILE_MOVED，源删、目标在。 */
    @Test
    @Timeout(120)
    fun `A06 a successful cross backend move commits one FILE_MOVED and removes the source`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            Files.writeString(localRoot.resolve("a06-source.txt"), "happy-path")

            try {
                withStack(localRoot) { stack ->
                    stack.vfs.move(localUri("a06-source.txt"), remoteUri("happy", "target.txt"))

                    assertFalse(Files.exists(localRoot.resolve("a06-source.txt")), "成功后源消失")
                    assertEquals("happy-path", Files.readString(physical("happy/target.txt")))
                    val moves = stack.eventRows().filter { it[0] == "FILE_MOVED" }
                    assertEquals(1, moves.size, "成功移动恰好一条 FILE_MOVED：${stack.eventRows()}")
                }
            } finally {
                deleteRemoteQuietly("happy")
            }
        }

    // ------------------------------------------------------------------ R4 Adapter move 直接测试

    @Test
    @Timeout(60)
    fun `R4 direct WebDavStorage move validates preconditions`() =
        runBlocking {
            val remote = WebDavStorage.create(WebDavFixture.endpoint, "/", WebDavFixture.user, WebDavFixture.password)
            try {
                remote.createDirectory(StoragePath.parse("r4-test"))
                remote.write(
                    StoragePath.parse("r4-test/src.txt"),
                    "data".toByteArray(),
                    com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode.CREATE_NEW,
                )

                // 1. 源缺失 -> NOT_FOUND
                val nf =
                    assertFailsWith<VfsException> {
                        remote.move(StoragePath.parse("r4-test/missing.txt"), StoragePath.parse("r4-test/dst.txt"))
                    }
                assertEquals(VfsErrorCode.NOT_FOUND, nf.code)
                assertEquals(VfsEffect.NONE, nf.effect)

                // 2. 目标已存在 -> ALREADY_EXISTS
                remote.write(
                    StoragePath.parse("r4-test/existing.txt"),
                    "exist".toByteArray(),
                    com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode.CREATE_NEW,
                )
                val ae =
                    assertFailsWith<VfsException> {
                        remote.move(StoragePath.parse("r4-test/src.txt"), StoragePath.parse("r4-test/existing.txt"))
                    }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, ae.code)
                assertEquals(VfsEffect.NONE, ae.effect)

                // 3. 父目录缺失 -> NOT_FOUND
                val pnf =
                    assertFailsWith<VfsException> {
                        remote.move(StoragePath.parse("r4-test/src.txt"), StoragePath.parse("r4-test/no-dir/dst.txt"))
                    }
                assertEquals(VfsErrorCode.NOT_FOUND, pnf.code)
                assertEquals(VfsEffect.NONE, pnf.effect)

                // 4. 目录 move -> UNSUPPORTED_OPERATION
                remote.createDirectory(StoragePath.parse("r4-test/sub-dir"))
                val dirMove =
                    assertFailsWith<VfsException> {
                        remote.move(StoragePath.parse("r4-test/sub-dir"), StoragePath.parse("r4-test/dst-dir"))
                    }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, dirMove.code)
                assertEquals(VfsEffect.NONE, dirMove.effect)
            } finally {
                runCatching { remote.delete(StoragePath.parse("r4-test"), recursive = true) }
                remote.close()
            }
        }

    // ------------------------------------------------------------------ R4 遗忘流回收直接证据

    @Test
    @Timeout(60)
    fun `R4 forgotten stream is reclaimed directly on adapter close without relying on stream close`() =
        runBlocking {
            val remote = WebDavStorage.create(WebDavFixture.endpoint, "/", WebDavFixture.user, WebDavFixture.password)
            try {
                remote.createDirectory(StoragePath.parse("r4-stream"))
                remote.write(
                    StoragePath.parse("r4-stream/file.txt"),
                    "stream-bytes".toByteArray(),
                    com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode.CREATE_NEW,
                )

                val stream = remote.readStream(StoragePath.parse("r4-stream/file.txt"))
                // 直接抓具体 handle（liveReaders 是私有字段，测试只能反射取），
                // 并用包装过的底层流计数 close，证明 release 真的执行过，
                // 而不是“集合被 clear 了但底层根本没关”。
                val liveReadersField =
                    WebDavStorage::class.java.getDeclaredField("liveReaders").apply { isAccessible = true }

                @Suppress("UNCHECKED_CAST")
                val liveReaders = liveReadersField.get(remote) as MutableSet<Any>
                val handle = liveReaders.single()
                val releasedField = handle.javaClass.getDeclaredField("released").apply { isAccessible = true }
                val nativeField = handle.javaClass.getDeclaredField("native").apply { isAccessible = true }
                val originalNative = nativeField.get(handle) as InputStream
                val closeCount = AtomicInteger(0)
                nativeField.set(handle, CountingCloseInputStream(originalNative, closeCount))

                assertFalse(releasedField.getBoolean(handle), "readStream 拿到时句柄未释放")
                assertEquals(0, closeCount.get(), "readStream 拿到时底层流未关闭")
                assertEquals(1, liveReaders.size, "readStream 打开后 liveReaders 包含 1 个句柄")

                // 直接关闭 adapter，不调用 stream.close()
                remote.close()

                // 释放证据看具体 handle：已 released、底层真实关闭过一次、集合已移除。
                assertTrue(releasedField.getBoolean(handle), "关闭 adapter 后句柄确实被 release（不是只被集合清空）")
                assertEquals(1, closeCount.get(), "关闭 adapter 真正关闭了底层流一次（不是只 clear 集合）")
                assertTrue(liveReaders.isEmpty(), "释放回调把 handle 从 liveReaders 摘掉")
                assertTrue(remote.isClosed(), "adapter 已关闭")

                // 重复调用 stream.close() 为 no-op：不再增底层关闭次数。
                stream.close()
                stream.close()
                assertEquals(1, closeCount.get(), "重复关闭不会再次关闭底层流")
            } finally {
                // R3/R4 修复：finally 里兜底关闭 remote。
                // 放在所有断言之后，前置断言失败时不会掩盖证据。
                runCatching { remote.close() }
                deleteRemoteQuietly("r4-stream")
            }
        }

    // ------------------------------------------------------------------ R4 原生 move 两阶段直接证据

    /**
     * R4：直接调 WebDavStorage.move，让**rename 阶段**真正失败（服务端 500），
     * 断言 UNKNOWN（结果不明），并核对磁盘物理事实：源仍在、目标未出现。
     */
    @Test
    @Timeout(60)
    fun `R4 direct WebDavStorage move reports UNKNOWN when the rename itself fails`() =
        runBlocking {
            val faultRoot = Files.createDirectories(tempDir.resolve("move-fault-rename"))
            DroppedWriteServer(faultRoot, moveFault = MoveFault.REFUSE, acknowledgePut = true).use { fault ->
                val remote = WebDavStorage.create(fault.endpoint, "/", "", "")
                try {
                    remote.createDirectory(StoragePath.parse("mv-rename"))
                    remote.write(
                        StoragePath.parse("mv-rename/src.txt"),
                        "rename-source".toByteArray(),
                        com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode.CREATE_NEW,
                    )

                    val failure =
                        assertFailsWith<VfsException> {
                            remote.move(StoragePath.parse("mv-rename/src.txt"), StoragePath.parse("mv-rename/dst.txt"))
                        }

                    // 已知物理事实：rename 没真的发生
                    assertTrue(Files.exists(faultRoot.resolve("mv-rename/src.txt")), "rename 失败后源仍在服务端")
                    assertFalse(Files.exists(faultRoot.resolve("mv-rename/dst.txt")), "rename 失败后目标不存在")

                    assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code, "后端 500 转成 STORAGE_ERROR")
                    assertEquals(VfsEffect.UNKNOWN, failure.effect, "rename 阶段失败只能报 UNKNOWN")
                    assertNotNull(failure.cause, "后端原始异常挂在 cause 上")
                } finally {
                    runCatching { remote.close() }
                }
            }
        }

    /**
     * R4：直接调 WebDavStorage.move，让 rename **成功后**对目标的确认失败，
     * 断言 PARTIAL（已知改了东西），并核对磁盘物理事实：源已消失、目标已出现。
     */
    @Test
    @Timeout(60)
    fun `R4 direct WebDavStorage move reports PARTIAL when the target confirmation fails`() =
        runBlocking {
            val faultRoot = Files.createDirectories(tempDir.resolve("move-fault-confirm"))
            DroppedWriteServer(faultRoot, moveFault = MoveFault.FAIL_TARGET_STAT, acknowledgePut = true).use { fault ->
                val remote = WebDavStorage.create(fault.endpoint, "/", "", "")
                try {
                    remote.createDirectory(StoragePath.parse("mv-confirm"))
                    remote.write(
                        StoragePath.parse("mv-confirm/src.txt"),
                        "confirm-source".toByteArray(),
                        com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode.CREATE_NEW,
                    )

                    val failure =
                        assertFailsWith<VfsException> {
                            remote.move(StoragePath.parse("mv-confirm/src.txt"), StoragePath.parse("mv-confirm/dst.txt"))
                        }

                    // 已知物理事实：rename 已真的发生
                    assertFalse(Files.exists(faultRoot.resolve("mv-confirm/src.txt")), "确认失败前源已被改名，原路径消失")
                    assertTrue(Files.exists(faultRoot.resolve("mv-confirm/dst.txt")), "确认失败前目标已经出现")

                    assertEquals(VfsEffect.PARTIAL, failure.effect, "rename 成功后的确认失败至少 PARTIAL")
                    assertNotNull(failure.cause, "后端原始异常挂在 cause 上")
                } finally {
                    runCatching { remote.close() }
                }
            }
        }

    // ------------------------------------------------------------------ 组装与包装

    private fun deleteRemoteQuietly(relative: String) {
        val target = physical(relative)
        if (!Files.exists(target)) return
        Files.walk(target).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private suspend fun <T> withStack(
        localRoot: Path,
        endpoint: String = WebDavFixture.endpoint,
        block: suspend (Stack) -> T,
    ): T {
        val database = tempDir.resolve("state.db")
        val state = VfsStateDatabase.file(database)
        var local: LocalFsStorage? = null
        var remote: WebDavStorage? = null
        var primary: Throwable? = null
        try {
            local = LocalFsStorage.create(localRoot)
            remote = WebDavStorage.create(endpoint, "/", WebDavFixture.user, WebDavFixture.password)
            val stack = Stack(database, state, local, remote)
            try {
                return block(stack)
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                // R3 修复：保留主异常，notifier 清理错误挂 suppressed，不覆盖原测试异常。
                val cleanup = mutableListOf<Throwable>()
                stack.closeNotifiers(cleanup)
                if (primary != null) {
                    cleanup.forEach(primary::addSuppressed)
                } else if (cleanup.isNotEmpty()) {
                    val first = cleanup.first()
                    cleanup.drop(1).forEach(first::addSuppressed)
                    throw first
                }
            }
        } catch (t: Throwable) {
            // 主异常已经记在 primary 上；这里不再重复记录，但保留字段以防 block 在别处抛。
            if (primary == null) primary = t
            throw t
        } finally {
            val failures = mutableListOf<Throwable>()
            try {
                remote?.close()
            } catch (t: Throwable) {
                failures += t
            }
            try {
                local?.close()
            } catch (t: Throwable) {
                failures += t
            }
            try {
                state.close()
            } catch (t: Throwable) {
                failures += t
            }
            if (failures.isNotEmpty()) {
                // 已有主异常时，清理错误挂 suppressed，不盖掉原测试异常。
                val pending = primary
                if (pending != null) {
                    failures.forEach(pending::addSuppressed)
                } else {
                    val first = failures.first()
                    for (other in failures.drop(1)) first.addSuppressed(other)
                    throw first
                }
            }
        }
    }

    private class Stack(
        val database: Path,
        private val state: VfsStateDatabase,
        val rawLocal: Storage,
        val rawRemote: Storage,
    ) {
        private val notifiers = mutableListOf<AsyncEventNotifier>()

        val vfs: DefaultVfs get() = vfsWith(rawLocal, rawRemote)

        fun vfsWith(
            local: Storage = rawLocal,
            remote: Storage = rawRemote,
        ): DefaultVfs {
            val boundary = StateBoundary()
            val notifier = AsyncEventNotifier()
            notifiers += notifier
            val router =
                MountRouter.of(
                    setOf("resources"),
                    listOf(
                        MountRecord(VfsPath.parse("/resources/local"), "local"),
                        MountRecord(VfsPath.parse("/resources/remote"), "remote"),
                    ),
                )
            val nodes = SqliteNodeRepository(state)
            val byKey = mapOf("local" to local, "remote" to remote)
            return DefaultVfs(
                router = router,
                capabilities = CapabilitySnapshot.of(byKey.mapValues { (_, storage) -> storage.capabilities() }),
                registry = NodeRegistry(router, nodes, { key -> byKey[key] }, boundary),
                nodes = nodes,
                metadata = SqliteMetadataRepository(state),
                storages = { key -> byKey[key] },
                boundary = boundary,
                pipeline = EventPipeline(boundary, SqliteUnitOfWork(state), notifier),
            )
        }

        fun closeNotifiers(failures: MutableList<Throwable>) {
            for (notifier in notifiers) {
                try {
                    notifier.close()
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }

        fun eventRows(): List<List<String>> =
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT event_type, node_id, uri, source_uri, target_uri FROM event ORDER BY rowid")
                        .use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add((1..5).map { rows.getString(it) ?: "" })
                                }
                            }
                        }
                }
            }

        fun queryRows(sql: String): List<List<String>> =
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        val colCount = rows.metaData.columnCount
                        buildList {
                            while (rows.next()) {
                                add((1..colCount).map { rows.getString(it) ?: "" })
                            }
                        }
                    }
                }
            }
    }
}

/**
 * 包装一个底层输入流，只计数 [close] 被调了几次——用来证明 ReaderHandle.release 真的执行了。
 * 读操作全部透传。
 */
private class CountingCloseInputStream(
    private val delegate: InputStream,
    private val closeCount: AtomicInteger,
) : InputStream() {
    override fun read(): Int = delegate.read()

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = delegate.read(b, off, len)

    override fun available(): Int = delegate.available()

    override fun close() {
        closeCount.incrementAndGet()
        delegate.close()
    }
}

private class FailingStatStorage(
    private val delegate: Storage,
    private val failOnStatOf: String,
) : Storage by delegate {
    private var written = false

    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode,
    ): StorageAttributes {
        val attributes = delegate.write(path, content, mode)
        if (path.toRelativeString() == failOnStatOf) written = true
        return attributes
    }

    override suspend fun stat(path: StoragePath): StorageAttributes {
        if (written && path.toRelativeString() == failOnStatOf) {
            throw VfsException(VfsErrorCode.STORAGE_ERROR, "injected target confirmation failure")
        }
        return delegate.stat(path)
    }
}

private class FailingDeleteStorage(
    private val delegate: Storage,
) : Storage by delegate {
    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ): Unit = throw VfsException(VfsErrorCode.STORAGE_ERROR, "injected source delete failure")
}

/**
 * MOVE 的受控故障模式（R4 用：原生 move 的两个失败阶段）。
 */
private enum class MoveFault {
    /** 正常：真的改名并回 201。 */
    NONE,

    /** MOVE 直接回 500，磁盘上不动——对应“rename 阶段失败”。 */
    REFUSE,

    /** MOVE 真改名并回 201，随后对目标的 stat 回 500——对应“改名后确认失败”。 */
    FAIL_TARGET_STAT,
}

/**
 * 故障服务：绑 127.0.0.1。
 * MKCOL 正常响应 201 Created，并在磁盘上创建目录；
 * PUT 完整读取请求体字节并写入磁盘文件，记录 putCount++，然后直接关闭连接，不回 HTTP 响应；
 * MOVE 按 [moveFault] 决定是真改名还是直接失败；
 * 请求路径**先解码**（保留 '+'），再按 Path 段检查是否仍在数据根内，逃逸一律 400；
 * close 先停接纳并等它结束、再关全部已登记连接、最后等全部 worker。
 */
private class DroppedWriteServer(
    private val dataRoot: Path,
    private val moveFault: MoveFault = MoveFault.NONE,
    /**
     * PUT 是否回 201。默认 false：完全写入后直接断连、不回响应（A06 装置）；
     * MOVE 用例里置 true，让建源文件正常成功，故障只留在 MOVE / 确认阶段。
     */
    private val acknowledgePut: Boolean = false,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = CopyOnWriteArrayList<Thread>()
    private val openSockets = CopyOnWriteArrayList<Socket>()

    /** [MoveFault.FAIL_TARGET_STAT] 下要报错的相对路径（已真的改名，但查不到）。 */
    private val statFailurePaths = CopyOnWriteArrayList<String>()
    private val acceptThread: Thread

    val putCount = AtomicInteger(0)

    @Volatile
    private var running = true

    val endpoint: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    /** 当前已登记、还没结束的连接数：close 之后必须是 0，否则说明有在途连接被漏掉。 */
    val openSocketCount: Int get() = openSockets.size

    init {
        acceptThread =
            Thread { acceptLoop() }.apply {
                isDaemon = true
                name = "dropped-write-accept"
                start()
            }
    }

    private fun acceptLoop() {
        while (running) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    break
                }
            if (!running) {
                runCatching { socket.close() }
                break
            }
            openSockets += socket
            val worker =
                Thread {
                    try {
                        handle(socket)
                    } finally {
                        openSockets.remove(socket)
                    }
                }.apply {
                    isDaemon = true
                    name = "dropped-write-handle"
                }
            workers += worker
            worker.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            socket.use {
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
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

                // R3 修复：先解码（保留 '+'），再按 Path 段检查仍在根内
                val decodedPath = decodePreservingPlus(rawPath)
                val relative = decodedPath.trimStart('/').trimEnd('/')
                val targetPath = resolveWithinRoot(relative)
                if (targetPath == null) {
                    respond(socket, 400, "Bad Request", ByteArray(0))
                    return
                }

                when (method) {
                    "MKCOL" -> {
                        if (relative.isNotEmpty()) {
                            Files.createDirectories(targetPath)
                        }
                        respond(socket, 201, "Created", ByteArray(0))
                    }
                    "PROPFIND" -> respondPropfind(socket, decodedPath, relative, targetPath, headers["depth"] ?: "0")
                    "MOVE" -> handleMove(socket, relative, targetPath, headers["destination"])
                    "PUT" -> {
                        val length = headers["content-length"]?.toLongOrNull() ?: 0L
                        if (targetPath.parent != null) {
                            Files.createDirectories(targetPath.parent)
                        }

                        // 按字节流精确读取 Content-Length
                        val bytes = ByteArray(length.toInt())
                        var readTotal = 0
                        while (readTotal < length) {
                            val r = input.read(bytes, readTotal, (length - readTotal).toInt())
                            if (r < 0) break
                            readTotal += r
                        }
                        Files.write(targetPath, bytes)
                        putCount.incrementAndGet()
                        if (acknowledgePut) {
                            respond(socket, 201, "Created", ByteArray(0))
                        }
                        // 非 acknowledgePut：读完写盘后直接由 socket.use 关闭连接，不回任何 HTTP 响应
                    }
                    else -> respond(socket, 404, "Not Found", ByteArray(0))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun decodePreservingPlus(rawPath: String): String {
        val safe = rawPath.replace("+", "%2B")
        return java.net.URLDecoder.decode(safe, Charsets.UTF_8)
    }

    /**
     * R3 修复：统一路径安全检查。
     * 先解码（保留 '+'），再把候选路径相对规范根 resolve + normalize，按 Path 段检查仍在根内。
     * 所有方法共用这一判断，不各写字符串过滤。
     * 返回 null 表示路径逃逸，调用方应拒绝。
     */
    private fun resolveWithinRoot(relative: String): Path? {
        if (relative.isEmpty()) return dataRoot
        val normalizedRoot = dataRoot.toAbsolutePath().normalize()
        val candidate = normalizedRoot.resolve(relative).normalize()
        // 按 Path 段检查：candidate 必须仍在 normalizedRoot 内
        if (!candidate.startsWith(normalizedRoot)) return null
        return candidate
    }

    /**
     * MOVE：按 [moveFault] 三种模式处理。
     * Destination 是绝对 URL，取它的路径部分当相对路径。
     */
    private fun handleMove(
        socket: Socket,
        relative: String,
        targetPath: Path,
        destination: String?,
    ) {
        if (destination == null) {
            respond(socket, 400, "Bad Request", ByteArray(0))
            return
        }
        val destinationPath = URI(destination).path ?: ""
        val destinationRelative = destinationPath.trimStart('/').trimEnd('/')
        val destinationFile = resolveWithinRoot(destinationRelative)
        if (destinationFile == null) {
            respond(socket, 400, "Bad Request", ByteArray(0))
            return
        }
        if (moveFault == MoveFault.REFUSE) {
            // 磁盘上不动，直接回 500：这就是“rename 阶段失败”。
            respond(socket, 500, "Internal Server Error", ByteArray(0))
            return
        }
        try {
            Files.move(targetPath, destinationFile)
        } catch (_: Exception) {
            respond(socket, 500, "Internal Server Error", ByteArray(0))
            return
        }
        if (moveFault == MoveFault.FAIL_TARGET_STAT) {
            // 改名真的完成了，但随后查这个目标要报错：这就是“改名后确认失败”。
            statFailurePaths += destinationRelative
        }
        respond(socket, 201, "Created", ByteArray(0))
    }

    private fun respondPropfind(
        socket: Socket,
        path: String,
        relative: String,
        target: Path,
        depth: String,
    ) {
        if (statFailurePaths.contains(relative)) {
            respond(socket, 500, "Internal Server Error", ByteArray(0))
            return
        }
        if (!Files.exists(target)) {
            respond(socket, 404, "Not Found", ByteArray(0))
            return
        }
        val isDir = Files.isDirectory(target)
        val body =
            buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
                append("<D:multistatus xmlns:D=\"DAV:\">")

                fun entry(
                    href: String,
                    dir: Boolean,
                    size: Long?,
                ) {
                    val rt = if (dir) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>"
                    val sizeXml = if (!dir && size != null) "<D:getcontentlength>$size</D:getcontentlength>" else ""
                    append("<D:response><D:href>").append(href).append("</D:href><D:propstat><D:prop>")
                    append("<D:getlastmodified>Thu, 01 Jan 1970 00:00:00 GMT</D:getlastmodified>")
                    append(sizeXml)
                    append(rt)
                    append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                }
                entry(path, isDir, if (isDir) null else Files.size(target))
                if (depth == "1" && isDir) {
                    Files.list(target).use { stream ->
                        stream.sorted().forEach { child ->
                            val childDir = Files.isDirectory(child)
                            entry(path.trimEnd('/') + "/" + child.fileName, childDir, if (childDir) null else Files.size(child))
                        }
                    }
                }
                append("</D:multistatus>")
            }.toByteArray()
        respond(socket, 207, "Multi-Status", body, contentType = "application/xml")
    }

    private fun respond(
        socket: Socket,
        status: Int,
        reason: String,
        body: ByteArray,
        contentType: String = "text/plain",
    ) {
        try {
            val output: OutputStream = socket.getOutputStream()
            output.write(
                "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(),
            )
            output.write(body)
            output.flush()
        } catch (_: Exception) {
        }
    }

    /**
     * R3 修复：关闭顺序消除登记窗口。
     * 1. 先 running=false + 关 serverSocket + join acceptThread 并 check 存活（确认接纳结束、不再登记）
     * 2. 再关闭全部已登记 socket
     * 3. 最后等全部 worker；收集失败统一报告（首个抛出、其余挂 suppressed），不要首个失败就提前终止后续等待
     */
    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        acceptThread.join(6_000)
        check(!acceptThread.isAlive) { "dropped write accept thread did not stop" }

        // 接纳线程已结束，openSockets 不再增长，可以安全遍历
        for (sock in openSockets) {
            runCatching { sock.close() }
        }

        // 收集所有 worker 的等待结果，不提前终止
        val failures = mutableListOf<Throwable>()
        workers.toList().forEach { worker ->
            worker.join(6_000)
            if (worker.isAlive) {
                failures += IllegalStateException("dropped write worker did not stop: ${worker.name}")
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            for (other in failures.drop(1)) first.addSuppressed(other)
            throw first
        }
    }
}
