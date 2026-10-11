package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import org.apache.opendal.OpenDALException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertFailsWith

/**
 * R2：共享读流组件必须带上正确的后端名。
 *
 * [StorageInputStream] / [ReaderHandle] 由本地盘与 WebDAV 共用。后端名漏传时，
 * WebDAV 专用的「HTTP 401 → `STORAGE_ACCESS_DENIED`」规则失效、错误码与关闭消息都会说成 `local storage`。
 * 这里不碰网络，只对**已编译的共享组件**做受控注入，直接断言转换结果与消息里的后端名。
 *
 * Local FS 的原行为必须不变：默认后端名仍是 `local storage`。
 */
class StorageStreamBackendLabelTest {
    /** 带 `webdav storage` 标签的生命周期，模拟 WebDAV 适配器传给共享流的那一个。 */
    private fun webdavLifetime() = NativeLifetime(label = "webdav storage") { }

    private fun webdavHandle(native: InputStream) = ReaderHandle(native, label = "webdav storage")

    /** 读时抛指定异常的替身流。 */
    private fun throwingStream(error: () -> Throwable) =
        object : InputStream() {
            override fun read(): Int = throw error()
        }

    @Test
    fun `a webdav 401 while reading maps to STORAGE_ACCESS_DENIED and names the webdav backend`() {
        val bounded =
            StorageInputStream(
                webdavHandle(throwingStream { OpenDALException(OpenDALException.Code.Unexpected, "status: 401") }),
                limitBytes = null,
                lifetime = webdavLifetime(),
            )

        val failure = assertFailsWith<VfsException> { bounded.read() }

        assertEquals(
            VfsErrorCode.STORAGE_ACCESS_DENIED,
            failure.code,
            "WebDAV 专用 401 规则只在后端名为 webdav storage 时生效，漏传会退回本地盘：${failure.code}",
        )
        assertTrue(
            "webdav storage" in (failure.message ?: ""),
            "消息要说清是哪块盘读流失败，不能说成 local storage：${failure.message}",
        )
        assertTrue(failure.cause is OpenDALException, "后端原始异常要挂在 cause 上：${failure.cause}")
    }

    @Test
    fun `a plain io failure while reading stays STORAGE_ERROR with the webdav backend name`() {
        val cause = IOException("backend says no")
        val bounded = StorageInputStream(webdavHandle(throwingStream { cause }), limitBytes = null, lifetime = webdavLifetime())

        val failure = assertFailsWith<VfsException> { bounded.read() }

        assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
        assertTrue("webdav storage" in (failure.message ?: ""), "普通 I/O 失败也要带上正确的后端名：${failure.message}")
        assertSame(cause, failure.cause)
        assertFalse(failure.message!!.contains("backend says no"), "公开消息不拼接后端原始响应")
    }

    @Test
    fun `a webdav 401 while closing maps to STORAGE_ACCESS_DENIED and names the webdav backend`() {
        val delegate =
            object : InputStream() {
                override fun read(): Int = -1

                override fun close(): Unit = throw OpenDALException(OpenDALException.Code.Unexpected, "status: 401")
            }
        val bounded = StorageInputStream(webdavHandle(delegate), limitBytes = null, lifetime = webdavLifetime())

        val failure = assertFailsWith<VfsException> { bounded.close() }

        assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code, "关流转换同样要走 webdav 后端规则：${failure.code}")
        assertTrue("webdav storage" in (failure.message ?: ""), "关流失败消息也要带正确的后端名：${failure.message}")
    }

    @Test
    fun `reading a webdav stream after the handle was released reports CLOSED naming the webdav backend`() {
        val handle = webdavHandle(InputStream.nullInputStream())
        val bounded = StorageInputStream(handle, limitBytes = null, lifetime = webdavLifetime())

        // 直接释放底层 handle（不经过包装流的 close）：状态仍是 ACTIVE，读取会走到 ReaderHandle 的守卫。
        handle.release()

        val failure = assertFailsWith<VfsException> { bounded.read() }

        assertEquals(VfsErrorCode.CLOSED, failure.code)
        assertEquals(
            "webdav storage read stream is closed",
            failure.message,
            "ReaderHandle 的关闭消息不能硬编码成 local storage：${failure.message}",
        )
    }

    @Test
    fun `reading a webdav stream after the wrapper was closed reports CLOSED naming the webdav backend`() {
        val bounded = StorageInputStream(webdavHandle(InputStream.nullInputStream()), limitBytes = null, lifetime = webdavLifetime())

        bounded.close()

        val failure = assertFailsWith<VfsException> { bounded.read() }

        assertEquals(VfsErrorCode.CLOSED, failure.code)
        assertTrue(
            "webdav storage" in (failure.message ?: ""),
            "外层先关后读取的 CLOSED 消息也要带正确的后端名：${failure.message}",
        )
    }

    @Test
    fun `the local backend keeps its original local storage messages`() {
        val localLifetime = NativeLifetime { }
        val handle = ReaderHandle(InputStream.nullInputStream())
        val bounded = StorageInputStream(handle, limitBytes = null, lifetime = localLifetime)

        handle.release()
        assertEquals("local storage read stream is closed", assertFailsWith<VfsException> { bounded.read() }.message)

        val readFailure =
            assertFailsWith<VfsException> {
                StorageInputStream(ReaderHandle(throwingStream { IOException("boom") }), null, localLifetime).read()
            }
        assertTrue("local storage" in (readFailure.message ?: ""), "本地盘消息保持原样：${readFailure.message}")
    }
}
