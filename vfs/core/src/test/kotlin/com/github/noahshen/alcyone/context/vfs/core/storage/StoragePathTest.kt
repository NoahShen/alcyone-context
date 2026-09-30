package com.github.noahshen.alcyone.context.vfs.core.storage

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoragePathTest {
    // 1. 挂载根的表示与往返
    @Test
    fun `root representation and round trip`() {
        val root = StoragePath.root
        assertTrue(root.isRoot)
        assertEquals(emptyList<String>(), root.segments)
        assertEquals("", root.name)
        assertEquals(root, root.parent)
        assertEquals(".", root.toRelativeString())
        assertEquals(".", root.toString())

        assertEquals(root, StoragePath.parse(""))
        assertEquals(root, StoragePath.parse("."))
        assertEquals(root, StoragePath.of(emptyList()))
        assertEquals(".", StoragePath.parse("").toRelativeString())
        assertEquals(".", StoragePath.parse(".").toRelativeString())
    }

    // 2. 合法中文与空格文件名
    @Test
    fun `valid chinese characters and spaces`() {
        val path = StoragePath.parse("工作 目录/测试 报告.txt")
        assertFalse(path.isRoot)
        assertEquals(listOf("工作 目录", "测试 报告.txt"), path.segments)
        assertEquals("测试 报告.txt", path.name)
        assertEquals("工作 目录", path.parent.toRelativeString())
        assertEquals("工作 目录/测试 报告.txt", path.toRelativeString())

        val resolved = path.parent.resolve("子 文件夹")
        assertEquals(listOf("工作 目录", "子 文件夹"), resolved.segments)
        assertEquals("工作 目录/子 文件夹", resolved.toRelativeString())

        val resolvedAll = path.parent.resolveAll(listOf("一级", "二级.dcm"))
        assertEquals(listOf("工作 目录", "一级", "二级.dcm"), resolvedAll.segments)
    }

    // 3. 每条拒绝规则各一个用例
    @Test
    fun `reject absolute path`() {
        val ex = assertThrows(VfsException::class.java) { StoragePath.parse("/a") }
        assertEquals(VfsErrorCode.INVALID_URI, ex.code)
        assertTrue(ex.message!!.contains("must not be absolute"))
    }

    @Test
    fun `reject empty middle segment`() {
        val ex = assertThrows(VfsException::class.java) { StoragePath.parse("a//b") }
        assertEquals(VfsErrorCode.INVALID_URI, ex.code)
        assertTrue(ex.message!!.contains("empty path segment"))
    }

    @Test
    fun `reject dot segment`() {
        val ex = assertThrows(VfsException::class.java) { StoragePath.parse("a/./b") }
        assertEquals(VfsErrorCode.INVALID_URI, ex.code)
        assertTrue(ex.message!!.contains("relative path segments are not allowed"))
    }

    @Test
    fun `reject dot-dot segment`() {
        val ex = assertThrows(VfsException::class.java) { StoragePath.parse("a/../b") }
        assertEquals(VfsErrorCode.INVALID_URI, ex.code)
        assertTrue(ex.message!!.contains("relative path segments are not allowed"))
    }

    @Test
    fun `reject segment containing slash`() {
        // split 会按 / 切割，但直接通过 of(listOf(...)) 传入包含 / 的段必须被拒绝
        val ex = assertThrows(VfsException::class.java) { StoragePath.of(listOf("a/b")) }
        assertEquals(VfsErrorCode.INVALID_URI, ex.code)
        assertTrue(ex.message!!.contains("contains '/', '\\', NUL or a control character"))
    }

    @Test
    fun `reject segment containing backslash`() {
        val ex = assertThrows(VfsException::class.java) { StoragePath.parse("a\\b") }
        assertEquals(VfsErrorCode.INVALID_URI, ex.code)
        assertTrue(ex.message!!.contains("contains '/', '\\', NUL or a control character"))
    }

    @Test
    fun `reject nul and control characters including c1 range`() {
        // NUL (0x00)
        val exNul = assertThrows(VfsException::class.java) { StoragePath.parse("a\u0000b") }
        assertEquals(VfsErrorCode.INVALID_URI, exNul.code)
        assertTrue(exNul.message!!.contains("contains '/', '\\', NUL or a control character"))

        // C0 control character (e.g. 0x07 BEL, 0x1F US)
        val exC0 = assertThrows(VfsException::class.java) { StoragePath.parse("a\u001fb") }
        assertEquals(VfsErrorCode.INVALID_URI, exC0.code)
        assertTrue(exC0.message!!.contains("contains '/', '\\', NUL or a control character"))

        // DEL (0x7F)
        val exDel = assertThrows(VfsException::class.java) { StoragePath.parse("a\u007fb") }
        assertEquals(VfsErrorCode.INVALID_URI, exDel.code)
        assertTrue(exDel.message!!.contains("contains '/', '\\', NUL or a control character"))

        // C1 control character (0x80..0x9F, e.g. 0x85 NEL, 0x9F APC)
        val exC1 = assertThrows(VfsException::class.java) { StoragePath.parse("a\u0085b") }
        assertEquals(VfsErrorCode.INVALID_URI, exC1.code)
        assertTrue(exC1.message!!.contains("contains '/', '\\', NUL or a control character"))
    }

    @Test
    fun `reject unpaired surrogate`() {
        // 孤立高代理字符 \uD83D (无低代理)
        val exHigh = assertThrows(VfsException::class.java) { StoragePath.parse("foo\uD83Dbar") }
        assertEquals(VfsErrorCode.INVALID_URI, exHigh.code)
        assertTrue(exHigh.message!!.contains("unpaired surrogate"))

        // 孤立低代理字符 \uDE00 (无高代理)
        val exLow = assertThrows(VfsException::class.java) { StoragePath.parse("foo\uDE00bar") }
        assertEquals(VfsErrorCode.INVALID_URI, exLow.code)
        assertTrue(exLow.message!!.contains("unpaired surrogate"))

        // 合法成对代理字符 (Emoji: 😊 \uD83D\uDE0A) 应当允许
        val validEmoji = StoragePath.parse("smile_\uD83D\uDE0A.txt")
        assertEquals("smile_\uD83D\uDE0A.txt", validEmoji.name)
    }

    // 4. 字面 %2e%2e 当普通文件名（不二次解码）
    @Test
    fun `literal percent encoded sequences are treated as plain text`() {
        val path = StoragePath.parse("%2e%2e/test%20file.txt")
        assertEquals(listOf("%2e%2e", "test%20file.txt"), path.segments)
        assertEquals("test%20file.txt", path.name)
        assertEquals("%2e%2e", path.parent.toRelativeString())
        assertEquals("%2e%2e/test%20file.txt", path.toRelativeString())
    }

    // 5. 不可变性：改构造入参列表、改对外暴露的集合都不影响对象，equals / hashCode 稳定
    @Test
    fun `immutability and equals hashcode stability`() {
        val mutableInput = mutableListOf("a", "b")
        val path = StoragePath.of(mutableInput)

        // 修改输入入参不影响内部状态
        mutableInput.add("c")
        assertEquals(listOf("a", "b"), path.segments)

        // 对外暴露的 segments 不可修改
        assertThrows(UnsupportedOperationException::class.java) {
            (path.segments as MutableList<String>).add("c")
        }

        // equals 与 hashCode 稳定
        val samePath = StoragePath.parse("a/b")
        val samePathTrailingSlash = StoragePath.parse("a/b/")
        assertEquals(path, samePath)
        assertEquals(path, samePathTrailingSlash)
        assertEquals(path.hashCode(), samePath.hashCode())

        val otherPath = StoragePath.parse("a/c")
        assertNotEquals(path, otherPath)
        assertNotEquals(path.hashCode(), otherPath.hashCode())
    }

    // 6. 非法构造抛 VfsException 且 code 为 INVALID_URI
    @Test
    fun `illegal construction throws VfsException with INVALID_URI`() {
        val ex1 = assertThrows(VfsException::class.java) { StoragePath.of(listOf("")) }
        assertEquals(VfsErrorCode.INVALID_URI, ex1.code)

        val ex2 = assertThrows(VfsException::class.java) { StoragePath.of(listOf("..")) }
        assertEquals(VfsErrorCode.INVALID_URI, ex2.code)
    }
}
