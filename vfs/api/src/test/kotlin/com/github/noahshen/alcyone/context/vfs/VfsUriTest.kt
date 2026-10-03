package com.github.noahshen.alcyone.context.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T02 第 2.3 节全部例子与第 2.1、2.2 节接受空间、规范化规则。 */
class VfsUriTest {
    // 2.1 接受的逻辑空间
    @Test
    fun `root uri maps to logical root`() {
        assertEquals(VfsPath.root, VfsUri.parse("alcyone://").path)
        assertEquals("/", VfsUri.parse("alcyone://").path.toString())
    }

    @Test
    fun `single segment paths keep the first segment`() {
        assertEquals("/memory", VfsUri.parse("alcyone://memory/").path.toString())
        assertEquals("/resources", VfsUri.parse("alcyone://resources").path.toString())
        assertTrue(VfsUri.parse("alcyone://memory/").path.isNamespaceRoot)
        assertTrue(VfsUri.parse("alcyone://resources").path.isNamespaceRoot)
    }

    @Test
    fun `ordinary path keeps case and structure`() {
        assertEquals("/resources/a/b.txt", VfsUri.parse("alcyone://resources/a/b.txt").path.toString())
    }

    // 2.3 例子，逐行对应文档表格
    @Test
    fun `example 1 scheme is case insensitive and trailing slash is dropped`() {
        val uri = VfsUri.parse("ALCYONE://resources/a/")
        assertEquals("alcyone://resources/a", uri.toString())
        assertEquals("/resources/a", uri.path.toString())
        assertEquals(uri, VfsUri.parse("alcyone://resources/a"))
    }

    @Test
    fun `example 2 percent encoded letter equals plain letter`() {
        assertEquals(VfsUri.parse("alcyone://resources/a.txt"), VfsUri.parse("alcyone://resources/%61.txt"))
    }

    @Test
    fun `example 3 unicode segment is accepted and re-encoded`() {
        val uri = VfsUri.parse("alcyone://resources/报告.md")
        assertEquals("/resources/报告.md", uri.path.toString())
        assertEquals("alcyone://resources/%E6%8A%A5%E5%91%8A.md", uri.toString())
    }

    @Test
    fun `example 4 percent encoded space decodes to a single space`() {
        assertEquals("/resources/a b.txt", VfsUri.parse("alcyone://resources/a%20b.txt").path.toString())
    }

    @Test
    fun `example 5 repeated separator is rejected`() {
        assertInvalidUri("alcyone://resources/a//b")
    }

    @Test
    fun `example 6 dot dot segment is rejected`() {
        assertInvalidUri("alcyone://resources/../memory/a")
    }

    @Test
    fun `example 7 encoded dot segment is rejected`() {
        assertInvalidUri("alcyone://resources/%2e%2e/a")
    }

    @Test
    fun `example 8 encoded separator is rejected`() {
        assertInvalidUri("alcyone://resources/a%2Fb")
    }

    @Test
    fun `example 9 double encoding is rejected`() {
        assertInvalidUri("alcyone://resources/%252e%252e")
    }

    @Test
    fun `example 9b encoded percent in a file name is rejected`() {
        assertInvalidUri("alcyone://resources/a%25b")
        assertInvalidUri("alcyone://resources/%61%25") // 解码后段为 "a%"
    }

    @Test
    fun `example 10 query is rejected`() {
        assertInvalidUri("alcyone://resources/a?x=1")
    }

    @Test
    fun `example 11 port is rejected`() {
        assertInvalidUri("alcyone://resources:80/a")
    }

    // R5：命名空间由 Runtime 配置提供，API 不检查第一段名称
    @Test
    fun `arbitrary top level directories are accepted`() {
        assertEquals("/notes/a", VfsUri.parse("alcyone://notes/a").path.toString())
        assertEquals("/Resources/a", VfsUri.parse("alcyone://Resources/a").path.toString())
        assertEquals("/MEMORY", VfsUri.parse("alcyone://MEMORY").path.toString())
        assertEquals("alcyone://notes/", VfsUri.parse("alcyone://notes").toString())
        assertNotEquals(VfsUri.parse("alcyone://Resources/a"), VfsUri.parse("alcyone://resources/a"))
    }

    @Test
    fun `userinfo fragment and raw backslash are rejected`() {
        assertInvalidUri("alcyone://user@resources/a")
        assertInvalidUri("alcyone://resources/a#frag")
        assertInvalidUri("alcyone://resources\\a")
        assertInvalidUri("alcyone://resources/a%5Cb")
    }

    @Test
    fun `control characters and NUL are rejected`() {
        assertInvalidUri("alcyone://resources/a%00b")
        assertInvalidUri("alcyone://resources/a%09b")
        assertInvalidUri("alcyone://resources/a%7Fb")
    }

    @Test
    fun `C1 control characters are rejected`() {
        assertInvalidUri("alcyone://resources/a%C2%85b") // U+0085 NEXT LINE
        assertInvalidUri("alcyone://resources/a%C2%9Fb") // U+009F
        assertInvalidUri("alcyone://resources/a%C2%80b") // U+0080
        assertInvalidUri("alcyone://resources/a\u0085b")
    }

    @Test
    fun `unpaired surrogates are rejected in raw and encoded text`() {
        assertInvalidUri("alcyone://resources/\uD800")
        assertInvalidUri("alcyone://resources/\uDC00")
        assertInvalidUri("alcyone://resources/a%20\uD800")
        assertInvalidUri("alcyone://resources/%ED%A0%80") // UTF-8 编码的孤立代理项
    }

    @Test
    fun `emoji surrogate pairs survive a round trip`() {
        val uri = VfsUri.parse("alcyone://resources/%F0%9F%98%80.txt")
        assertEquals("\uD83D\uDE00.txt", uri.path.segments.last())
        assertEquals(uri, VfsUri.parse(uri.toString()))
        assertEquals("alcyone://resources/%F0%9F%98%80.txt", uri.toString())
    }

    @Test
    fun `invalid escapes and invalid utf8 are rejected`() {
        assertInvalidUri("alcyone://resources/a%zz")
        assertInvalidUri("alcyone://resources/a%2")
        assertInvalidUri("alcyone://resources/%FF")
        assertInvalidUri("alcyone://resources/%C3")
        assertInvalidUri("alcyone://resources/%C0%AF") // 过长编码的 '/'
        assertInvalidUri("alcyone://resources/%F4%90%80%80") // 超过 U+10FFFF
    }

    @Test
    fun `raw space and malformed scheme are rejected`() {
        assertInvalidUri("alcyone://resources/a b")
        assertInvalidUri("alcyone:/resources/a")
        assertInvalidUri("resources/a")
        assertInvalidUri("")
        assertInvalidUri("alcyoneX://resources/a")
    }

    @Test
    fun `repeated leading and trailing separators are rejected`() {
        assertInvalidUri("alcyone:///resources")
        assertInvalidUri("alcyone://resources//")
    }

    // 序列化与幂等
    @Test
    fun `serialization keeps unreserved characters and uses uppercase hex`() {
        assertEquals("alcyone://resources/a-._~0AZaz", VfsUri.parse("alcyone://resources/a-._~0AZaz").toString())
        assertEquals("alcyone://resources/a%3Fb%23c%3Ad%2Be%3D", VfsUri.parse("alcyone://resources/a%3Fb%23c%3Ad%2Be%3D").toString())
        assertInvalidUri("alcyone://resources/a%3Fb%23c%3Ad%2Be%3D%25") // 解码后段尾为 '%'
    }

    @Test
    fun `single segment paths serialize with a trailing slash`() {
        assertEquals("alcyone://memory/", VfsUri.parse("alcyone://memory").toString())
        assertEquals("alcyone://resources/", VfsUri.parse("alcyone://resources/").toString())
        assertEquals("alcyone://", VfsUri.parse("alcyone://").toString())
    }

    @Test
    fun `uri round trip is idempotent`() {
        val inputs =
            listOf(
                "alcyone://",
                "alcyone://resources",
                "alcyone://resources/a",
                "alcyone://resources/a%20b.txt",
                "alcyone://resources/%E6%8A%A5%E5%91%8A.md",
                "alcyone://resources/a%20b%2Bc.txt",
                "alcyone://memory/notes/2026/a%2Bb",
            )
        for (input in inputs) {
            val once = VfsUri.parse(input).toString()
            val twice = VfsUri.parse(once)
            assertEquals(once, twice.toString(), "round trip for $input")
            assertEquals(VfsUri.parse(input), twice)
        }
    }

    @Test
    fun `path and uri agree on the same logical location`() {
        val fromUri = VfsUri.parse("alcyone://resources/a/").path
        assertEquals(VfsPath.parse("/resources/a"), fromUri)
        assertEquals(VfsUri.parse("alcyone://resources/a"), VfsUri.parse("alcyone://resources/a/"))
    }

    @Test
    fun `serialization never changes the logical location`() {
        for (input in listOf("alcyone://notes/a", "alcyone://notes", "alcyone://resources/a%20b", "alcyone://resources/报告.md")) {
            val uri = VfsUri.parse(input)
            val reparsed = VfsUri.parse(uri.toString())
            assertEquals(uri, reparsed, "round trip must keep the location for $input")
            assertEquals(uri.path, reparsed.path)
            assertEquals(uri.toString(), reparsed.toString())
        }
    }

    @Test
    fun `different logical locations stay different`() {
        val uris =
            listOf(
                "alcyone://resources/a",
                "alcyone://resources/A",
                "alcyone://resources/a/b",
                "alcyone://memory/a",
            ).map { VfsUri.parse(it) }
        assertEquals(uris.size, uris.toSet().size)
    }

    /**
     * T13 用到的 path → uri：Core 组装 `NodeInfo` 时直接把逻辑路径变成 URI。
     * 路径里允许有空格（文件名就是这样），URI 里必须编码成 `%20`，再 parse 回来还是同一个路径。
     */
    @Test
    fun `a logical path can be turned into a uri without losing anything`() {
        assertEquals("alcyone://resources/my%20notes.txt", VfsUri.of(VfsPath.parse("/resources/my notes.txt")).toString())
        assertEquals(VfsPath.parse("/resources/my notes.txt"), VfsUri.of(VfsPath.parse("/resources/my notes.txt")).path)
        assertEquals("alcyone://", VfsUri.of(VfsPath.root).toString())
        assertEquals("alcyone://resources/", VfsUri.of(VfsPath.parse("/resources")).toString())
        assertEquals(
            VfsPath.parse("/resources/中文/a.txt"),
            VfsUri.parse(VfsUri.of(VfsPath.parse("/resources/中文/a.txt")).toString()).path,
        )
    }

    private fun assertInvalidUri(text: String) {
        val error = assertFailsWith<VfsException>("expected INVALID_URI for: $text") { VfsUri.parse(text) }
        assertEquals(VfsErrorCode.INVALID_URI, error.code)
    }
}
