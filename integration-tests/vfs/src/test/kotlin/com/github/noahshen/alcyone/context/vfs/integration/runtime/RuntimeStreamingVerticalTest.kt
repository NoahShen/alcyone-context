package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsStreamResult
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/**
 * T19 A05：公开入口上的**大文件流式纵向闭环**。用的是真 SQLite + 真本机目录，
 * 文件在工作目录里直接落到磁盘上（绕开 `read` 的 16 MiB 写限额），只通过公开的
 * [AlcyoneVfs.openStream] 分块读取，全程**不把整个文件读进内存**。
 *
 * 覆盖：`read` 默认 `LIMIT_EXCEEDED`、分块摘要与原文件一致（证明逐块读、内容无损）、
 * 调用级上限收紧、遗忘的流被 Runtime 回收、关闭后读失败、同库可重开。
 *
 * 交接取消与 native 内部细目是 T18 / T12 的专项证据，这里不复制它们的反射替身用例。
 */
class RuntimeStreamingVerticalTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("docs"))
    }

    private fun config(streamTotalLimit: Long? = null) =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", disk)),
            streamTotalLimit = streamTotalLimit,
        )

    private fun uri(name: String) = VfsUri.parse("alcyone://resources/docs/$name")

    /** 直接往磁盘上放一个确定性字节序列；不经过 VFS，也不整份进内存（分块填充）。 */
    private fun putBigFile(
        name: String,
        sizeBytes: Long,
    ) {
        val target = disk.resolve(name)
        Files.newOutputStream(target).use { out ->
            val chunk = ByteArray(64 * 1024)
            var written = 0L
            while (written < sizeBytes) {
                val remain = (sizeBytes - written).coerceAtMost(chunk.size.toLong()).toInt()
                for (i in 0 until remain) {
                    chunk[i] = ((written + i) % 251).toByte()
                }
                out.write(chunk, 0, remain)
                written += remain
            }
        }
    }

    /** 分块算一个流的 SHA-256，返回字节数与摘要——大文件走这条，不 `readAllBytes()`。 */
    private fun digestOf(stream: InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            total += read
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return total to hex
    }

    /** 原文件的 SHA-256：同样分块，整份不进内存。 */
    private fun fileDigest(path: Path): Pair<Long, String> = Files.newInputStream(path).use { digestOf(it) }

    /**
     * 17 MiB 的文件：`read` 报 `LIMIT_EXCEEDED`；`openStream` 分块读到的字节数与摘要都与原文件一致。
     */
    @Test
    @Timeout(60)
    fun `a file over the byte array default streams in chunks and matches the original digest`() =
        runBlocking {
            prepare()
            val size = 17L * 1024 * 1024
            putBigFile("big.bin", size)
            val (originalBytes, originalDigest) = fileDigest(disk.resolve("big.bin"))
            assertEquals(size, originalBytes, "夹具：原文件长度就是 17 MiB")

            val vfs = AlcyoneVfs.create(config())
            try {
                val refused = assertFailsWith<VfsException> { vfs.read(uri("big.bin")) }
                assertEquals(VfsErrorCode.LIMIT_EXCEEDED, refused.code, "read 的 16 MiB 默认限额仍然生效")

                vfs.openStream(uri("big.bin")).use { result ->
                    assertEquals(size, result.sizeBytes, "结果带上后端报告的文件长度")
                    val (streamedBytes, streamedDigest) = digestOf(result.stream)
                    assertEquals(size, streamedBytes, "分块读完，总字节数一致")
                    assertEquals(originalDigest, streamedDigest, "分块读到的内容与原文件逐字节一致")
                }

                // 流式读取是只读的：不登记 Node、不产生事件。
                assertEquals(0, countActiveNodes(), "读大文件不登记 Node")
                assertEquals(0, eventCount(), "读大文件不产生变更事件")
            } finally {
                vfs.close()
            }
        }

    /**
     * 调用级上限只能收紧配置上限：收紧时停在上限处，放宽也不会越界。
     */
    @Test
    @Timeout(60)
    fun `a call level limit tightens the configured total limit`() =
        runBlocking {
            prepare()
            putBigFile("mid.bin", 3L * 1024 * 1024)
            val configured = 2L * 1024 * 1024

            val vfs = AlcyoneVfs.create(config(streamTotalLimit = configured))
            try {
                vfs.openStream(uri("mid.bin"), VfsStreamOptions(maxTotalBytes = 1024 * 1024)).use { result ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    val failure =
                        runCatching {
                            while (true) {
                                val read = result.stream.read(buffer)
                                if (read <= 0) break
                                total += read
                            }
                        }.exceptionOrNull()
                    assertNotNull(failure, "超过调用级上限要报错")
                    assertTrue(failure is VfsException)
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, (failure as VfsException).code)
                    assertEquals(1024 * 1024L, total, "正好停在上限，超出的字节不交出去")
                }

                // 放宽不会把配置上限顶开：还是 2 MiB 处报错。
                vfs.openStream(uri("mid.bin"), VfsStreamOptions(maxTotalBytes = 8L * 1024 * 1024)).use { result ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    val failure =
                        runCatching {
                            while (true) {
                                val read = result.stream.read(buffer)
                                if (read <= 0) break
                                total += read
                            }
                        }.exceptionOrNull()
                    assertTrue(failure is VfsException)
                    assertEquals(VfsErrorCode.LIMIT_EXCEEDED, (failure as VfsException).code)
                    assertEquals(configured, total, "调用级更宽也还是配置上限说了算")
                }
            } finally {
                vfs.close()
            }
        }

    /**
     * 调用方忘了关流：`vfs.close()` 正常返回并替它收回流（底层句柄放掉、关闭后读失败），
     * 同一个状态库还能再开一个实例。
     */
    @Test
    @Timeout(60)
    fun `an unclosed stream is reclaimed by close and the same database can be reopened`() =
        runBlocking {
            prepare()
            putBigFile("forgotten.bin", 4096)

            val vfs = AlcyoneVfs.create(config())
            val result: VfsStreamResult = vfs.openStream(uri("forgotten.bin"))
            assertEquals("alcyone://resources/docs/forgotten.bin", result.uri.toString())

            vfs.close()

            assertTrue(result.isClosed, "Runtime 替调用方把流关了")
            val thrown = assertFailsWith<Exception> { result.stream.read() }
            assertTrue(
                thrown is IOException || thrown is VfsException,
                "收走之后读取必须失败（CLOSED 或 IOException），实际是 $thrown",
            )

            // 锁也放掉了：同一状态库还能再开，且能正常用。
            val reopened = AlcyoneVfs.create(config())
            try {
                reopened.openStream(uri("forgotten.bin")).use { again ->
                    assertEquals(4096L, digestOf(again.stream).first, "重开后还能分块读完")
                }
            } finally {
                reopened.close()
            }
        }

    private fun query(sql: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    private fun countActiveNodes(): Int = query("SELECT COUNT(*) FROM node WHERE deleted_at IS NULL").single().toInt()

    private fun eventCount(): Int = query("SELECT COUNT(*) FROM event").single().toInt()
}
