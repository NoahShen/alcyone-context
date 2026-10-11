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
                val liveReadersField = WebDavStorage::class.java.getDeclaredField("liveReaders").apply { isAccessible = true }
                val liveReaders = liveReadersField.get(remote) as Set<*>

                assertEquals(1, liveReaders.size, "readStream 打开后 liveReaders 包含 1 个句柄")

                // 直接关闭 adapter，不调用 stream.close()
                remote.close()

                assertTrue(liveReaders.isEmpty(), "关闭 adapter 后 liveReaders 被彻底排空，底层已被释放")

                // 重复调用 stream.close() 为 no-op，不抛异常
                stream.close()
                stream.close()
            } finally {
                deleteRemoteQuietly("r4-stream")
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
        try {
            local = LocalFsStorage.create(localRoot)
            remote = WebDavStorage.create(endpoint, "/", WebDavFixture.user, WebDavFixture.password)
            val stack = Stack(database, state, local, remote)
            return try {
                block(stack)
            } finally {
                stack.closeNotifiers()
            }
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
                val first = failures.first()
                for (other in failures.drop(1)) first.addSuppressed(other)
                throw first
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

        fun closeNotifiers() {
            for (notifier in notifiers) {
                runCatching { notifier.close() }
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
 * 故障服务：绑 127.0.0.1。
 * MKCOL 正常响应 201 Created，并在内存中创建目录记录；
 * PUT 完整读取请求体字节并写入磁盘临时文件，记录 putCount++，然后直接关闭连接，不回 HTTP 响应；
 * 请求路径解析保留 '+'，对 '/../' 返回 400；
 * close 顺序停 accept、主动关闭所有连接、等 worker 线程。
 */
private class DroppedWriteServer(
    private val dataRoot: Path,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = CopyOnWriteArrayList<Thread>()
    private val openSockets = CopyOnWriteArrayList<Socket>()
    private val acceptThread: Thread

    val putCount = AtomicInteger(0)

    @Volatile
    private var running = true

    val endpoint: String get() = "http://127.0.0.1:${serverSocket.localPort}"

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

                // R3 检查：路径逃逸拒绝
                if (rawPath.contains("/../") || rawPath.endsWith("/..")) {
                    respond(socket, 400, "Bad Request", ByteArray(0))
                    return
                }

                val decodedPath = decodePreservingPlus(rawPath)

                when (method) {
                    "MKCOL" -> {
                        val relative = decodedPath.trimStart('/').trimEnd('/')
                        if (relative.isNotEmpty()) {
                            Files.createDirectories(dataRoot.resolve(relative))
                        }
                        respond(socket, 201, "Created", ByteArray(0))
                    }
                    "PROPFIND" -> respondPropfind(socket, decodedPath, headers["depth"] ?: "0")
                    "PUT" -> {
                        val length = headers["content-length"]?.toLongOrNull() ?: 0L
                        val relative = decodedPath.trimStart('/')
                        val targetFile = dataRoot.resolve(relative)
                        if (targetFile.parent != null) {
                            Files.createDirectories(targetFile.parent)
                        }

                        // 按字节流精确读取 Content-Length
                        val bytes = ByteArray(length.toInt())
                        var readTotal = 0
                        while (readTotal < length) {
                            val r = input.read(bytes, readTotal, (length - readTotal).toInt())
                            if (r < 0) break
                            readTotal += r
                        }
                        Files.write(targetFile, bytes)
                        putCount.incrementAndGet()
                        // 读完写盘后，直接由 socket.use 关闭连接，不回任何 HTTP 响应
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

    private fun respondPropfind(
        socket: Socket,
        path: String,
        depth: String,
    ) {
        val relative = path.trimStart('/').trimEnd('/')
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
            val output: OutputStream = socket.getOutputStream()
            output.write(
                "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(),
            )
            output.write(body)
            output.flush()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        for (sock in openSockets) {
            runCatching { sock.close() }
        }
        acceptThread.join(6_000)
        check(!acceptThread.isAlive) { "dropped write accept thread did not stop" }
        workers.toList().forEach { worker ->
            worker.join(6_000)
            check(!worker.isAlive) { "dropped write worker did not stop: ${worker.name}" }
        }
    }
}
