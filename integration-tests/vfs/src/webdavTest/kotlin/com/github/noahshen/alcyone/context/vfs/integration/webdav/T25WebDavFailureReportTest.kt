package com.github.noahshen.alcyone.context.vfs.integration.webdav

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/**
 * T25 A06：失败事实——远端写入部分完成后失败、目标确认失败、删源失败都不误提交；UNKNOWN 不降级，已知变化不报 NONE。
 *
 * 组装方式：真 [DefaultVfs] + 真 SQLite + 真 [LocalFsStorage]（源）+ 真 [WebDavStorage]（远端目标），
 * 用包装 Storage 在**精确失败点**注入（真服务不会自己停在「写了一半」或「确认那一步」）。
 * 物理事实对着真实服务磁盘与服务端响应断言；逻辑事实对着事件表、Node 路径与异常 code / effect 断言。
 *
 * 另含**一条真实 HTTP 结果丢失证据**：一个只监听 loopback 的故障服务，对 PUT 请求读入请求体后不回包直接断开，
 * 让目标盘的写入在「服务端可能已改了一点」处失败；断言源保持完整、不调用源删除、无成功移动事件。
 *
 * 每个用例用独立的远端子目录，跑完清理，避免用例间互相污染。
 */
class T25WebDavFailureReportTest {
    @TempDir
    lateinit var tempDir: Path

    private fun physical(relative: String): Path = WebDavFixture.dataRoot().resolve(relative)

    private fun localUri(name: String) = VfsUri.parse("alcyone://resources/local/$name")

    /** 每个用例的 `case` 是一个独立远端子目录，源与目标都落在它里面。 */
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
            val dataRoot = WebDavFixture.dataRoot()

            // 故障服务把自己的根当远端共享：PROPFIND 返回真实属性，PUT 后不回包直接断开。
            DroppedWriteServer(dataRoot).use { fault ->
                withStack(localRoot, endpoint = fault.endpoint) { stack ->
                    val before = stack.eventRows()
                    val failure =
                        assertFailsWith<VfsException> {
                            stack.vfs.move(localUri("a06-source.txt"), remoteUri("dropped", "target.txt"))
                        }

                    assertTrue(failure.cause != null, "后端原始异常挂在 cause 上：${failure.cause}")
                    assertEquals(
                        VfsEffect.UNKNOWN,
                        failure.effect,
                        "写入边界结果丢失直接钉住 UNKNOWN，不降级为 NONE / PARTIAL：${failure.effect}",
                    )
                    assertFalse(
                        stack.eventRows().any { it[0] == "FILE_MOVED" },
                        "写入失败不得提交成功移动事件",
                    )
                    assertEquals(before, stack.eventRows(), "事件表逐条不变")

                    // 源物理与逻辑都保留。
                    assertTrue(Files.exists(localRoot.resolve("a06-source.txt")), "源物理文件保留")
                    assertEquals("source-kept", Files.readString(localRoot.resolve("a06-source.txt")))
                    assertArrayEquals("source-kept".toByteArray(), stack.vfs.read(localUri("a06-source.txt")), "源逻辑内容可读")
                }
            }
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
                    // 打开一个「写入成功、但随后对指定目标 stat 失败」的远端包装。
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
                }
            } finally {
                deleteRemoteQuietly("confirm")
            }
        }

    /**
     * A06：**删源失败**——目标已确认但删源那一步失败：目标留着、源也留着、不提交、effect 至少 PARTIAL。
     */
    @Test
    @Timeout(120)
    fun `A06 a source delete failure leaves both sides and keeps the effect partial`() =
        runBlocking {
            val localRoot = Files.createDirectories(tempDir.resolve("local"))
            Files.writeString(localRoot.resolve("a06-source.txt"), "delete-source")

            try {
                withStack(localRoot) { stack ->
                    val failingLocal = FailingDeleteStorage(stack.rawLocal)
                    val before = stack.eventRows()

                    val failure =
                        assertFailsWith<VfsException> {
                            stack.vfsWith(local = failingLocal).move(localUri("a06-source.txt"), remoteUri("delsrc", "target.txt"))
                        }

                    assertTrue(failure.effect != VfsEffect.NONE, "删源失败不能报 NONE：${failure.effect}")

                    assertTrue(Files.exists(physical("delsrc/target.txt")), "目标在服务端")
                    assertTrue(Files.exists(localRoot.resolve("a06-source.txt")), "删源失败源还在")
                    assertEquals(before, stack.eventRows(), "删源失败不提交任何事件")
                }
            } finally {
                deleteRemoteQuietly("delsrc")
            }
        }

    /** A06：成功反例——一次正常跨后端移动只提交一条 FILE_MOVED，源删、目标在（对照上面三条失败）。 */
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

    // ------------------------------------------------------------------ 组装与包装

    /** 直接删掉服务端一个测试子目录（不走 Runtime，纯物理收尾）。 */
    private fun deleteRemoteQuietly(relative: String) {
        val target = physical(relative)
        if (!Files.exists(target)) return
        Files.walk(target).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * 一次真实组装：Core 接线 + 真 SQLite + 真 LocalFsStorage + 真 WebDavStorage；
     * [remote] / [local] 参数用于替换成故障包装。用完按逆序关。
     */
    private suspend fun <T> withStack(
        localRoot: Path,
        endpoint: String = WebDavFixture.endpoint,
        block: suspend (Stack) -> T,
    ): T {
        val database = tempDir.resolve("state.db")
        val state = VfsStateDatabase.file(database)
        val local = LocalFsStorage.create(localRoot)
        val remote = WebDavStorage.create(endpoint, "/", WebDavFixture.user, WebDavFixture.password)
        try {
            return block(Stack(database, state, local, remote))
        } finally {
            remote.close()
            local.close()
            state.close()
        }
    }

    /** 一次组装的三个部分：状态库文件、真身两块盘、以及用它们即时拼出的 DefaultVfs。 */
    private class Stack(
        val database: Path,
        private val state: VfsStateDatabase,
        val rawLocal: Storage,
        val rawRemote: Storage,
    ) {
        val vfs: DefaultVfs get() = vfsWith(rawLocal, rawRemote)

        fun vfsWith(
            local: Storage = rawLocal,
            remote: Storage = rawRemote,
        ): DefaultVfs {
            val boundary = StateBoundary()
            val notifier = AsyncEventNotifier()
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
    }
}

/**
 * 转发一切，但指定相对路径的目标在**写入之后**的 [stat] 抛 STORAGE_ERROR 的包装
 * （模拟「写入成功、目标确认失败」）。写之前的确认（目标应不存在）正常转发。
 */
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

/** 转发一切，但 [delete] 一律抛 STORAGE_ERROR 的包装（模拟「删源失败」）。 */
private class FailingDeleteStorage(
    private val delegate: Storage,
) : Storage by delegate {
    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ): Unit = throw VfsException(VfsErrorCode.STORAGE_ERROR, "injected source delete failure")
}

/**
 * A06 真实 HTTP 结果丢失装置：只绑 127.0.0.1 的极简 WebDAV 子集。
 *
 * - `PROPFIND` 返回真实属性（源盘 stat / 目标存在性检查要用）；
 * - `PUT` 读掉请求体、**不发响应直接断开**，模拟「服务端可能收了一半就没了」；
 * - `DELETE` 立即断开；其它方法 404。
 *
 * 只监听 loopback，处理线程有有限读取等待；[close] 先停监听再等 worker，不吞超时。
 */
private class DroppedWriteServer(
    private val dataRoot: Path,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val acceptThread: Thread

    @Volatile
    private var running = true

    val endpoint: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    init {
        acceptThread =
            Thread { acceptLoop() }
                .apply {
                    isDaemon = true
                    name = "dropped-write-accept"
                }.also { it.start() }
    }

    private fun acceptLoop() {
        while (true) {
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
            val worker =
                Thread { handle(socket) }
                    .apply {
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
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]
                when (method) {
                    "PROPFIND" -> respondPropfind(socket, path, headers["depth"] ?: "0")
                    "PUT" -> {
                        // 读掉请求体（服务端「收了一半」），然后不回包直接关连接。
                        val length = headers["content-length"]?.toLongOrNull() ?: 0L
                        var remaining = length
                        val buffer = CharArray(4096)
                        while (remaining > 0) {
                            val read = reader.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            remaining -= read
                        }
                        // socket.use 关闭连接：客户端读到 EOF。
                    }
                    "MKCOL", "DELETE" -> Unit // 立即断开
                    else -> respond(socket, 404, "Not Found", ByteArray(0))
                }
            }
        } catch (e: Exception) {
            // 连接中途断掉是预期路径。
        }
    }

    private fun respondPropfind(
        socket: Socket,
        path: String,
        depth: String,
    ) {
        val decoded = java.net.URLDecoder.decode(path, Charsets.UTF_8)
        val relative = decoded.trimStart('/').trimEnd('/')
        val target = if (relative.isEmpty()) dataRoot else dataRoot.resolve(relative)
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
            val output = socket.getOutputStream()
            output.write(
                "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(),
            )
            output.write(body)
            output.flush()
        } catch (e: Exception) {
            // 忽略
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        acceptThread.join(6_000)
        check(!acceptThread.isAlive) { "dropped write accept thread did not stop" }
        workers.toList().forEach { worker ->
            worker.join(6_000)
            check(!worker.isAlive) { "dropped write worker did not stop: ${worker.name}" }
        }
    }
}
