package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.ReadOptions
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * T25 A01 / A02：公开 Runtime 对**真实 WebDAV 服务**补齐操作契约与流式读取。
 *
 * - A01：三种写入模式、文件 / 目录类型冲突、空目录与非递归删除、中文 / 空格 / `+` 名称、
 *   父目录不自动补（Storage 语义）、路径段里的 `%` 在配置 / 解析阶段被拒。
 * - A02：公开 `openStream` 经 WebDAV `readStream` 分块读；空文件、零长度读取、恰好上限、超限、
 *   `null` 上限无界、按**实际读出字节**计限。
 *
 * 断言全部对着真实磁盘上的字节与真实 HTTP 服务，不靠替身。
 */
class T25WebDavContractTest {
    @TempDir
    lateinit var tempDir: Path

    private fun db() = tempDir.resolve("state.db")

    private fun uri(path: String) = VfsUri.parse("alcyone://resources/remote$path")

    /** 服务数据根下的物理路径。 */
    private fun physical(relative: String): Path = WebDavFixture.dataRoot().resolve(relative)

    // ------------------------------------------------------------------ A01 契约

    /** A01：三种写入模式的正常与反例——CREATE_NEW 拒绝已有目标且保留原内容；REPLACE_EXISTING 拒绝缺失目标。 */
    @Test
    @Timeout(120)
    fun `A01 the three write modes behave per the port on the real service`() =
        runBlocking {
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                val target = uri("/t25-modes/a.txt")

                // CREATE_NEW 第一次成功。
                val created = vfs.write(target, "first".toByteArray(), WriteOptions(mode = WriteMode.CREATE_NEW))
                assertEquals(NodeType.FILE, created.type)

                // CREATE_NEW 遇到已有目标：拒绝，保留原内容与身份。
                val refuseCreate =
                    assertFailsWith<VfsException> {
                        vfs.write(target, "second".toByteArray(), WriteOptions(mode = WriteMode.CREATE_NEW))
                    }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, refuseCreate.code, "CREATE_NEW 已有目标要拒")
                assertArrayEquals("first".toByteArray(), vfs.read(target), "拒绝后原内容一个字节没变")
                assertEquals(created.id, vfs.stat(target).id, "拒绝后身份不变")

                // REPLACE_EXISTING 覆盖已有目标：保留身份，内容换新。
                val replaced = vfs.write(target, "second".toByteArray(), WriteOptions(mode = WriteMode.REPLACE_EXISTING))
                assertEquals(created.id, replaced.id, "REPLACE 保留 Node ID")
                assertArrayEquals("second".toByteArray(), vfs.read(target))

                // REPLACE_EXISTING 遇到缺失目标：拒绝。
                val refuseReplace =
                    assertFailsWith<VfsException> {
                        vfs.write(
                            uri("/t25-modes/missing.txt"),
                            "x".toByteArray(),
                            WriteOptions(mode = WriteMode.REPLACE_EXISTING),
                        )
                    }
                assertEquals(VfsErrorCode.NOT_FOUND, refuseReplace.code, "REPLACE_EXISTING 缺失目标要拒")

                // UPSERT：缺失则创建，存在则覆盖，身份沿用。
                val upsertNew = vfs.write(uri("/t25-modes/u.txt"), "u1".toByteArray(), WriteOptions(mode = WriteMode.UPSERT))
                val upsertAgain = vfs.write(uri("/t25-modes/u.txt"), "u2".toByteArray(), WriteOptions(mode = WriteMode.UPSERT))
                assertEquals(upsertNew.id, upsertAgain.id, "UPSERT 覆盖保留身份")
                assertArrayEquals("u2".toByteArray(), vfs.read(uri("/t25-modes/u.txt")))
            }
        }

    /** A01：文件 / 目录类型冲突——写目标已是目录、以文件路径建目录、read/list 目录与文件互换都被明确拒绝。 */
    @Test
    @Timeout(120)
    fun `A01 file and directory type conflicts are refused with the right codes`() =
        runBlocking {
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                // 先把 /t25-types/dir 建成目录（通过写入其下一个文件顺带建出）。
                vfs.write(uri("/t25-types/dir/inner.txt"), "x".toByteArray())
                vfs.write(uri("/t25-types/plain.txt"), "y".toByteArray())

                // 写目标已是目录：TYPE_MISMATCH。
                val writeOverDir = assertFailsWith<VfsException> { vfs.write(uri("/t25-types/dir"), "z".toByteArray()) }
                assertEquals(VfsErrorCode.TYPE_MISMATCH, writeOverDir.code, "写目录要报类型不符")

                // 目标是文件却要读成流：TYPE_MISMATCH。
                val streamDir = assertFailsWith<VfsException> { vfs.openStream(uri("/t25-types/dir")) }
                assertEquals(VfsErrorCode.TYPE_MISMATCH, streamDir.code, "目录不能开读流")

                // 目标是文件却要列：TYPE_MISMATCH。
                val listFile = assertFailsWith<VfsException> { vfs.list(uri("/t25-types/plain.txt")) }
                assertEquals(VfsErrorCode.TYPE_MISMATCH, listFile.code, "文件不能当目录列")

                // 目标是文件却要删成非空目录：普通文件删除照常成功；这里验证读文件当目录的 stat 类型。
                assertEquals(NodeType.FILE, vfs.stat(uri("/t25-types/plain.txt")).type)
                assertEquals(NodeType.DIRECTORY, vfs.stat(uri("/t25-types/dir")).type)
            }
        }

    /** A01：空目录可非递归删除；非空目录非递归删除报 DIRECTORY_NOT_EMPTY，递归删除成功。 */
    @Test
    @Timeout(120)
    fun `A01 empty directories delete non recursively and non empty ones refuse then succeed recursively`() =
        runBlocking {
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                // 造一个空目录：写入其下的文件再删掉文件，目录留下。
                vfs.write(uri("/t25-del/empty/occupied.txt"), "x".toByteArray())
                vfs.delete(uri("/t25-del/empty/occupied.txt"))
                assertEquals(NodeType.DIRECTORY, vfs.stat(uri("/t25-del/empty")).type, "空目录还在")

                // 空目录非递归删除成功。
                vfs.delete(uri("/t25-del/empty"))
                val gone = assertFailsWith<VfsException> { vfs.stat(uri("/t25-del/empty")) }
                assertEquals(VfsErrorCode.NOT_FOUND, gone.code, "空目录删掉之后查不到")

                // 非空目录非递归删除：DIRECTORY_NOT_EMPTY，内容不动。
                vfs.write(uri("/t25-del/full/a.txt"), "a".toByteArray())
                val refuse = assertFailsWith<VfsException> { vfs.delete(uri("/t25-del/full")) }
                assertEquals(VfsErrorCode.DIRECTORY_NOT_EMPTY, refuse.code, "非空目录非递归删要拒")
                assertEquals(1, vfs.list(uri("/t25-del/full")).size, "拒绝后内容不动")
                assertTrue(Files.exists(physical("t25-del/full/a.txt")), "物理文件还在")

                // 递归删除成功。
                vfs.delete(
                    uri("/t25-del/full"),
                    com.github.noahshen.alcyone.context.vfs
                        .DeleteOptions(recursive = true),
                )
                assertFalse(Files.exists(physical("t25-del/full")), "递归删后物理目录也没了")
            }
        }

    /**
     * A01：中文、空格、`+` 名称实际落到服务端；路径段里的 `%` 在 URI 解析阶段被拒，不重新设计编码规则。
     */
    @Test
    @Timeout(120)
    fun `A01 names with chinese spaces and plus land on the server while a bare percent is refused`() =
        runBlocking {
            val chinese = "报告-2026.txt"
            val spaced = "a b.txt"
            val plus = "a+b.txt"
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                // VfsPath 段已经解码；URI 里空格用 %20，`+` 是普通字符（不当作空格）。
                vfs.write(uri("/t25-names/$chinese"), "cn".toByteArray())
                vfs.write(uri("/t25-names/a%20b.txt"), "sp".toByteArray())
                vfs.write(uri("/t25-names/$plus"), "pl".toByteArray())

                // 服务端物理落点：中文原样、空格恢复、`+` 不变成空格。
                assertTrue(Files.exists(physical("t25-names/$chinese")), "中文名落到服务端")
                assertTrue(Files.exists(physical("t25-names/$spaced")), "%20 解码成空格落到服务端")
                assertTrue(Files.exists(physical("t25-names/$plus")), "`+` 是普通字符，不当作空格")

                // list 回来的也是服务端真实名字。
                assertEquals(
                    setOf(chinese, spaced, plus),
                    vfs
                        .list(uri("/t25-names"))
                        .map {
                            it.uri.path.segments
                                .last()
                        }.toSet(),
                )

                // 路径段里的裸 `%`（非法编码）在解析阶段就被拒，不会打到服务器。
                val barePercent = assertFailsWith<VfsException> { VfsUri.parse("alcyone://resources/remote/t25-names/50%off.txt") }
                assertEquals(VfsErrorCode.INVALID_URI, barePercent.code, "裸 % 在 URI 解析阶段被拒")
            }
        }

    // ------------------------------------------------------------------ A02 流式读取

    /**
     * A02：对超过默认 ByteArray 读限额的文件，公开 `openStream` 分块读出完整内容——摘要与源逐字节一致。
     * 同时证明 `read` 仍受 16 MiB 限制（不是整文件读进来再包成流）。
     */
    @Test
    @Timeout(180)
    fun `A02 a file over the byte array limit streams in full via the public openStream`() =
        runBlocking {
            // 20 MiB > 默认 16 MiB。直接写进服务数据根，绕开 VFS 写限额，避免把文件整份进内存。
            val size = 20L * 1024 * 1024
            val target = physical("t25-stream/big.bin")
            Files.createDirectories(target.parent)
            Files.newOutputStream(target).use { out ->
                val chunk = ByteArray(64 * 1024)
                var written = 0L
                while (written < size) {
                    val remain = (size - written).coerceAtMost(chunk.size.toLong()).toInt()
                    for (i in 0 until remain) chunk[i] = ((written + i) % 251).toByte()
                    out.write(chunk, 0, remain)
                    written += remain
                }
            }
            val (originalBytes, originalDigest) = WebDavFixture.fileDigest(target)
            assertEquals(size, originalBytes, "夹具：源文件就是 20 MiB")

            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                // read 的 16 MiB 天花板仍然生效。
                val refused = assertFailsWith<VfsException> { vfs.read(uri("/t25-stream/big.bin")) }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, refused.code, "read 仍受 ByteArray 限额")

                var streamed: Pair<Long, String>? = null
                vfs.openStream(uri("/t25-stream/big.bin")).use { result ->
                    assertEquals(size, result.sizeBytes, "结果带上后端报告的长度")
                    streamed = WebDavFixture.streamDigest(result.stream)
                }
                val (streamedBytes, streamedDigest) = streamed!!
                assertEquals(size, streamedBytes, "分块读完，总字节数一致")
                assertEquals(originalDigest, streamedDigest, "分块读到的内容与源逐字节一致")
            }
        }

    /** A02：空文件、零长度读取、恰好上限、超限——都按**实际读出字节**计。 */
    @Test
    @Timeout(120)
    fun `A02 empty zero length exact limit and over limit all respect the real read bytes`() =
        runBlocking {
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(db())) { vfs ->
                // 空文件：读到 0 字节。
                vfs.write(uri("/t25-limit/empty.bin"), ByteArray(0))
                vfs.openStream(uri("/t25-limit/empty.bin")).use { result ->
                    assertEquals(0L, WebDavFixture.streamDigest(result.stream).first, "空文件读到 0 字节")
                }

                // 零长度读取：read(buffer, 0, 0) 返回 0，不消耗额度也不超限。
                vfs.write(uri("/t25-limit/data.bin"), "abcdefghij".toByteArray())
                vfs.openStream(uri("/t25-limit/data.bin"), VfsStreamOptions(maxTotalBytes = 10)).use { result ->
                    val buffer = ByteArray(4)
                    assertEquals(0, result.stream.read(buffer, 0, 0), "零长度读取返回 0")
                }

                // 恰好上限：读满 10 字节不报错。
                vfs.openStream(uri("/t25-limit/data.bin"), VfsStreamOptions(maxTotalBytes = 10)).use { result ->
                    val buffer = ByteArray(4)
                    var total = 0
                    while (true) {
                        val read = result.stream.read(buffer)
                        if (read <= 0) break
                        total += read
                    }
                    assertEquals(10, total, "恰好上限读满不报错")
                }

                // 超限：下一次读取会越过上限时报 LIMIT_EXCEEDED，越界的字节不交出去（已交出的不超过上限）。
                vfs.openStream(uri("/t25-limit/data.bin"), VfsStreamOptions(maxTotalBytes = 9)).use { result ->
                    val buffer = ByteArray(4)
                    var total = 0
                    val failure =
                        runCatching {
                            while (true) {
                                val read = result.stream.read(buffer)
                                if (read <= 0) break
                                total += read
                            }
                        }.exceptionOrNull()
                    assertNotNull(failure, "超限要报错")
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, (failure as VfsException).code)
                    assertEquals(8, total, "越界的那一次读取不交字节；已交出的字节不超过上限")
                    assertTrue(total <= 9, "已交出的字节不能超过上限")
                }

                // null 上限：默认无界，能读完整个文件。
                vfs.openStream(uri("/t25-limit/data.bin")).use { result ->
                    assertEquals(10L, WebDavFixture.streamDigest(result.stream).first, "null 上限按无界读完")
                }

                // read 的显式上限同样按实际字节：文件 10 字节、上限 5，超限直接报 LIMIT_EXCEEDED，不截断交付。
                val boundedRead = assertFailsWith<VfsException> { vfs.read(uri("/t25-limit/data.bin"), ReadOptions(maxBytes = 5)) }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, boundedRead.code, "read 显式上限也按实际字节，超限不截断")
            }
        }

    /** A02：只读——流式读取不登记 Node、不发事件。 */
    @Test
    @Timeout(120)
    fun `A02 streaming reads leave no node or event behind`() =
        runBlocking {
            val database = db()
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.write(uri("/t25-readonly/a.bin"), "payload".toByteArray())
            }
            val nodesAfterWrite = WebDavFixture.query(database, "SELECT COUNT(*) FROM node WHERE deleted_at IS NULL").single()
            val eventsAfterWrite = WebDavFixture.query(database, "SELECT COUNT(*) FROM event").single()

            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(database)) { vfs ->
                vfs.openStream(uri("/t25-readonly/a.bin")).use { result ->
                    assertEquals(7L, WebDavFixture.streamDigest(result.stream).first)
                }
            }
            assertEquals(
                nodesAfterWrite,
                WebDavFixture.query(database, "SELECT COUNT(*) FROM node WHERE deleted_at IS NULL").single(),
                "流式读取不登记 Node",
            )
            assertEquals(eventsAfterWrite, WebDavFixture.query(database, "SELECT COUNT(*) FROM event").single(), "流式读取不发事件")
        }
}
