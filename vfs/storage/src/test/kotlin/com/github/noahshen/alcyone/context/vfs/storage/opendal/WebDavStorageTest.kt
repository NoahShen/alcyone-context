package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertFailsWith

/**
 * T24 A01 / A07：WebDAV Adapter 的**声明与拒绝**，不碰网络。
 *
 * 打开 Adapter 不发请求（见 [WebDavStorage.create]），所以这批用例在没有服务的机器上也成立：
 * 它证明的是「能力表没有多报」「没验收的方法是明确拒绝而不是空实现 / 假成功」，
 * 以及 endpoint / root 的整理规则。真实读写闭环在 `./scripts/webdav-check` 跑的 webdavTest 里。
 */
class WebDavStorageTest {
    /** 端口没有服务在听：这里只求「打开不联网」，任何 I/O 都不会发生。 */
    private suspend fun open(): WebDavStorage = WebDavStorage.create("http://127.0.0.1:1", "/dav/team")

    @Test
    @Timeout(60)
    fun `capabilities do not claim moves this release never verified`() =
        runBlocking {
            val storage = open()
            try {
                val capabilities = storage.capabilities()
                assertFalse(capabilities.nativeFileMove, "rename 没验收过，不能报原生文件移动")
                assertFalse(capabilities.nativeDirectoryMove, "rename 没验收过，不能报原生目录移动")
                assertTrue(capabilities.createDirectory, "MKCOL 是这一轮验过的最小闭环之一")
                assertTrue(capabilities.boundedRead, "有界读是这一轮验过的最小闭环之一")
                assertFalse(capabilities.readOnly, "只读与否要后端说了算，打开时猜不出来")
            } finally {
                storage.close()
            }
        }

    @Test
    @Timeout(60)
    fun `unverified methods refuse explicitly instead of pretending to work`() =
        runBlocking {
            val storage = open()
            try {
                val move =
                    assertFailsWith<VfsException> { storage.move(StoragePath.parse("a.txt"), StoragePath.parse("b.txt")) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, move.code, "移动是阶段拒绝，不是空实现")
                assertEquals(VfsEffect.NONE, move.effect, "拒绝发生在任何副作用之前")

                val stream = assertFailsWith<VfsException> { storage.readStream(StoragePath.parse("a.txt"), null) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, stream.code, "流式读取同样阶段拒绝")
                assertEquals(VfsEffect.NONE, stream.effect)
            } finally {
                storage.close()
            }
        }

    @Test
    @Timeout(60)
    fun `a closed adapter says webdav in the message and still answers capability queries`() =
        runBlocking {
            val storage = open()
            storage.close()

            val closed = assertFailsWith<VfsException> { storage.stat(StoragePath.parse("a.txt")) }
            assertEquals(VfsErrorCode.CLOSED, closed.code)
            assertTrue(
                "webdav storage is closed" in (closed.message ?: ""),
                "消息要说清是哪块盘关了，不能说成 local storage：${closed.message}",
            )
            // 能力查询不碰后端，关了也照答——否则一次慢操作就能把能力查询一起堵住。
            assertFalse(storage.capabilities().nativeFileMove)
        }

    // ---- endpoint / root 的整理规则（WebDavRoots）----

    @Test
    fun `endpoints and roots are normalized once without touching credentials`() {
        assertEquals("http://127.0.0.1:8080", WebDavRoots.normalizeEndpoint("http://127.0.0.1:8080/"))
        assertEquals("http://127.0.0.1:8080/dav", WebDavRoots.normalizeEndpoint("  http://127.0.0.1:8080/dav/  "))
        assertEquals("https://Dav.example.com:8443/base", WebDavRoots.normalizeEndpoint("https://Dav.example.com:8443/base/"))
        assertEquals("/dav/team", WebDavRoots.normalizeRoot("/dav/team/"))
        assertEquals("/", WebDavRoots.normalizeRoot(""))
        assertEquals("/", WebDavRoots.normalizeRoot("/"))
        // 身份只由 endpoint + root 决定；凭据从签名上就进不来。
        assertEquals(
            "http://127.0.0.1:8080/dav/team",
            WebDavRoots.identity("http://127.0.0.1:8080/", "/dav/team/"),
        )
    }

    @Test
    fun `an endpoint that smuggles credentials or a query is rejected`() {
        listOf(
            "http://user:pass@127.0.0.1:8080",
            "127.0.0.1:8080",
            "ftp://127.0.0.1:8080",
            "http://127.0.0.1:8080/dav?x=1",
            "http://",
        ).forEach { endpoint ->
            val failure = assertFailsWith<VfsException>("$endpoint") { WebDavRoots.normalizeEndpoint(endpoint) }
            assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code, "$endpoint 应当在配置阶段就拒")
        }
        listOf("dav/team", "/a//b", "/a/../b", "/a/./b").forEach { root ->
            val failure = assertFailsWith<VfsException>("$root") { WebDavRoots.normalizeRoot(root) }
            assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code, "$root 应当在配置阶段就拒")
        }
    }

    @Test
    fun `roots on the same service are compared by whole path segments`() {
        // 平级：`/a` 和 `/a-old` 不是一家人，不能按字符串前缀误判成重叠。
        WebDavRoots.requireNonOverlapping(listOf("http://127.0.0.1:1" to "/a", "http://127.0.0.1:1" to "/a-old"))
        // 不同服务：各看各的，同一段路径互不相干。
        WebDavRoots.requireNonOverlapping(listOf("http://127.0.0.1:1" to "/a", "http://127.0.0.1:2" to "/a"))

        assertFailsWith<VfsException>("互相包含") {
            WebDavRoots.requireNonOverlapping(listOf("http://127.0.0.1:1" to "/a", "http://127.0.0.1:1" to "/a/b"))
        }
        assertFailsWith<VfsException>("同一个根挂两次") {
            WebDavRoots.requireNonOverlapping(listOf("http://127.0.0.1:1" to "/a", "http://127.0.0.1:1" to "/a/"))
        }
    }
}
