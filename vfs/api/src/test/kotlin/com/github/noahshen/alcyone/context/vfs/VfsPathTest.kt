package com.github.noahshen.alcyone.context.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T02 第 2.2 节规则 3、5、6 与第 2.3 节末段：VfsPath 入口执行同样的段边界检查，不解码，但段内不允许字面 `%`。 */
class VfsPathTest {
    @Test
    fun `root and single segment paths are represented`() {
        assertTrue(VfsPath.root.isRoot)
        assertEquals("/", VfsPath.root.toString())
        assertEquals(emptyList(), VfsPath.root.segments)
        assertEquals("/resources", VfsPath.parse("/resources/").toString())
        assertTrue(VfsPath.parse("/memory").isNamespaceRoot)
        assertTrue(VfsPath.parse("/notes").isNamespaceRoot)
        assertTrue(!VfsPath.parse("/resources/a").isNamespaceRoot)
    }

    @Test
    fun `one optional trailing separator only`() {
        assertEquals(VfsPath.parse("/resources/a"), VfsPath.parse("/resources/a/"))
        assertInvalidPath("/resources/a//")
    }

    @Test
    fun `segments are taken literally without percent decoding`() {
        val path = VfsPath.parse("/resources/报告 md/a+b.txt")
        assertEquals(listOf("resources", "报告 md", "a+b.txt"), path.segments)
        assertEquals("/resources/报告 md/a+b.txt", path.toString())
    }

    @Test
    fun `literal percent signs in segments are rejected`() {
        assertInvalidPath("/resources/a%25b")
        assertInvalidPath("/resources/%2e%2e")
        assertFailsWith<VfsException> { VfsPath.of(listOf("resources", "a%b")) }
        assertInvalidPath("/resources/a%2Fb") // 规则与 URI 入口一致
    }

    @Test
    fun `unicode and case are preserved`() {
        assertEquals(listOf("resources", "报告.md", "A", "a"), VfsPath.parse("/resources/报告.md/A/a").segments)
        assertNotEquals(VfsPath.parse("/resources/A"), VfsPath.parse("/resources/a"))
    }

    @Test
    fun `segment boundary rules match the uri entry point`() {
        for (bad in listOf("/resources/../a", "/resources/./a", "/resources/a//b", "//resources", "resources/a", "")) {
            assertInvalidPath(bad)
        }
    }

    @Test
    fun `path construction from segments applies the same checks`() {
        assertEquals(VfsPath.parse("/resources/a"), VfsPath.of(listOf("resources", "a")))
        assertEquals(VfsPath.root, VfsPath.of(emptyList()))
        assertFailsWith<VfsException> { VfsPath.of(listOf("resources", "a/b")) }
        assertFailsWith<VfsException> { VfsPath.of(listOf("resources", "..")) }
        assertFailsWith<VfsException> { VfsPath.of(listOf("resources", "a\u0000b")) }
        assertFailsWith<VfsException> { VfsPath.of(listOf("resources", "a\u0085b")) }
    }

    @Test
    fun `equality is by normalized segments`() {
        assertEquals(VfsPath.parse("/resources/a"), VfsPath.of(listOf("resources", "a")))
        assertEquals(VfsPath.parse("/resources/a").hashCode(), VfsPath.parse("/resources/a/").hashCode())
    }

    // R5：命名空间由 Runtime 配置提供，API 不检查第一段名称
    @Test
    fun `any first segment is accepted`() {
        assertEquals("/notes/a", VfsPath.parse("/notes/a").toString())
        assertEquals(listOf("notes", "a"), VfsPath.of(listOf("notes", "a")).segments)
        assertEquals(VfsPath.parse("/notes/a"), VfsPath.of(listOf("notes", "a")))
        assertNotEquals(VfsPath.parse("/notes/a"), VfsPath.parse("/resources/a"))
    }

    @Test
    fun `first segment keeps its case`() {
        assertEquals("/Resources", VfsPath.parse("/Resources").toString())
        assertEquals("/MEMORY", VfsPath.parse("/MEMORY").toString())
        assertNotEquals(VfsPath.parse("/Resources"), VfsPath.parse("/resources"))
    }

    // R1：值对象不可被集合回写
    @Test
    fun `segments cannot be modified through the exposed collection`() {
        val path = VfsPath.parse("/resources/safe/file")
        val hashBefore = path.hashCode()
        val asMutable = path.segments as MutableList<String>
        assertFailsWith<UnsupportedOperationException> { asMutable[1] = ".." }
        assertFailsWith<UnsupportedOperationException> { asMutable.add("extra") }
        assertFailsWith<UnsupportedOperationException> { asMutable.clear() }
        assertEquals(listOf("resources", "safe", "file"), path.segments)
        assertEquals(hashBefore, path.hashCode())
        assertEquals(VfsPath.parse("/resources/safe/file"), path)
        assertEquals("alcyone://resources/safe/file", VfsUri.parse("alcyone://resources/safe/file").toString())
    }

    @Test
    fun `a uri derived from a path keeps its hash when the exposed list is modified`() {
        val uri = VfsUri.parse("alcyone://resources/safe/file")
        val hashBefore = uri.hashCode()
        val asMutable = uri.path.segments as MutableList<String>
        assertFailsWith<UnsupportedOperationException> { asMutable[1] = ".." }
        assertEquals(hashBefore, uri.hashCode())
        assertEquals("alcyone://resources/safe/file", uri.toString())
        assertEquals(uri, VfsUri.parse("alcyone://resources/safe/file"))
    }

    @Test
    fun `constructor input list cannot change the path afterwards`() {
        val input = mutableListOf("resources", "safe")
        val path = VfsPath.of(input)
        val hashBefore = path.hashCode()
        input[1] = ".."
        input.add("late")
        assertEquals(listOf("resources", "safe"), path.segments)
        assertEquals("/resources/safe", path.toString())
        assertEquals(hashBefore, path.hashCode())
    }

    // R3：未配对代理字符与 C1 控制字符
    @Test
    fun `unpaired surrogates are rejected at every entry point`() {
        assertInvalidPath("/resources/\uD800")
        assertInvalidPath("/resources/a\uDC00b")
        assertFailsWith<VfsException> { VfsPath.of(listOf("resources", "\uD800")) }
        assertFailsWith<VfsException> { VfsUri.parse("alcyone://resources/\uD800") }
        assertFailsWith<VfsException> { VfsUri.parse("alcyone://resources/a%20\uD800") }
    }

    @Test
    fun `control characters including C1 are rejected at every entry point`() {
        assertInvalidPath("/resources/a\u0085b")
        assertInvalidPath("/resources/a\u009Fb")
        assertFailsWith<VfsException> { VfsPath.of(listOf("resources", "a\u009Fb")) }
        assertFailsWith<VfsException> { VfsUri.parse("alcyone://resources/a%C2%85b") }
    }

    @Test
    fun `valid surrogate pairs are preserved and round trip`() {
        val emoji = "\uD83D\uDE00"
        val path = VfsPath.parse("/resources/$emoji.txt")
        assertEquals(listOf("resources", "$emoji.txt"), path.segments)
        val uri = VfsUri.parse("alcyone://resources/%F0%9F%98%80.txt")
        assertEquals(path, uri.path)
        assertEquals(uri, VfsUri.parse(uri.toString()))
        assertEquals("alcyone://resources/%F0%9F%98%80.txt", uri.toString())
    }

    private fun assertInvalidPath(text: String) {
        val error = assertFailsWith<VfsException>("expected INVALID_URI for: $text") { VfsPath.parse(text) }
        assertEquals(VfsErrorCode.INVALID_URI, error.code)
    }
}
