package com.github.noahshen.alcyone.context.vfs.core.router

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T09 A01～A06：路由是纯逻辑视图，不接触 Storage / Repository。
 * 每个测试方法名标注对应的验收编号。
 */
class MountRouterTest {
    // A01 配置校验
    @Test
    fun `A01 duplicate mount positions are rejected`() {
        val ex =
            assertThrows(VfsException::class.java) {
                MountRouter.of(setOf("notes"), listOf(mount("/notes/a", "a"), mount("/notes/a/", "b")))
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `A01 mount at the logical root is rejected`() {
        val ex = assertThrows(VfsException::class.java) { MountRouter.of(setOf("notes"), listOf(mount("/", "root"))) }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `A01 mount outside the configured namespaces is rejected`() {
        val ex =
            assertThrows(VfsException::class.java) {
                MountRouter.of(setOf("notes"), listOf(mount("/other/a", "a")))
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `A01 namespace names follow single segment rules`() {
        for (bad in listOf("", "a/b", "a\\b", "a b", "a%b", "a\u0000b", "a\u0085b")) {
            val ex =
                assertThrows(VfsException::class.java, { MountRouter.of(setOf(bad), emptyList()) }, "rejection for: $bad")
            assertEquals(VfsErrorCode.INVALID_URI, ex.code)
        }
    }

    @Test
    fun `A01 namespaces are not hardcoded and keep their case`() {
        val router = MountRouter.of(setOf("notes", "Resources"), listOf(mount("/notes/a", "a"), mount("/Resources/b", "b")))
        assertEquals("a", router.route(VfsPath.parse("/notes/a/x.txt"))!!.mount.storageKey)
        assertEquals("b", router.route(VfsPath.parse("/Resources/b"))!!.mount.storageKey)
        assertNull(router.route(VfsPath.parse("/resources/b"))) // 大小写敏感
        assertTrue(router.isConfiguredDirectory(VfsPath.parse("/Resources")))
    }

    @Test
    fun `A01 an empty mount set is usable`() {
        val router = MountRouter.of(setOf("notes"), emptyList())
        assertTrue(router.isConfiguredDirectory(VfsPath.root))
        assertTrue(router.isConfiguredDirectory(VfsPath.parse("/notes")))
        assertNull(router.route(VfsPath.parse("/notes/a.txt")))
    }

    @Test
    fun `A01 mounting a namespace root itself is allowed`() {
        // memory 整个命名空间挂本地盘是正常用法；挂到逻辑根 '/' 才被拒绝。
        val router = MountRouter.of(setOf("memory"), listOf(mount("/memory", "local-disk")))

        val file = router.route(VfsPath.parse("/memory/notes/a.txt"))!!
        assertEquals("local-disk", file.mount.storageKey)
        assertEquals("notes/a.txt", file.relativePath.toRelativeString())

        val mountRoot = router.route(VfsPath.parse("/memory"))!!
        assertEquals(StoragePath.root, mountRoot.relativePath)
        assertTrue(mountRoot.relativePath.isRoot)

        val rejected = assertThrows(VfsException::class.java) { MountRouter.of(setOf("memory"), listOf(mount("/", "local-disk"))) }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, rejected.code)
    }

    // A02 最长完整段匹配
    @Test
    fun `A02 the deepest mount wins`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes", "parent"), mount("/notes/a", "child")))
        assertEquals("child", router.route(VfsPath.parse("/notes/a/x"))!!.mount.storageKey)
        assertEquals("parent", router.route(VfsPath.parse("/notes/b"))!!.mount.storageKey)
        assertEquals("parent", router.route(VfsPath.parse("/notes"))!!.mount.storageKey)
    }

    @Test
    fun `A02 matching respects segment boundaries`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes/a", "a")))
        assertNotNull(router.route(VfsPath.parse("/notes/a/report.txt")))
        assertNull(router.route(VfsPath.parse("/notes/abc")))
        assertNull(router.route(VfsPath.parse("/notes")))
    }

    @Test
    fun `A02 mount order does not change the result`() {
        val forwards = MountRouter.of(setOf("notes"), listOf(mount("/notes", "a"), mount("/notes/a", "b"), mount("/notes/a/x", "c")))
        val backwards = MountRouter.of(setOf("notes"), listOf(mount("/notes/a/x", "c"), mount("/notes/a", "b"), mount("/notes", "a")))
        for (path in listOf("/notes/a/x/y.txt", "/notes/a/z.txt", "/notes/q", "/notes/a/x")) {
            val left = forwards.route(VfsPath.parse(path))
            val right = backwards.route(VfsPath.parse(path))
            assertEquals(left, right, "routing must not depend on input order: $path")
        }
    }

    // A03 相对路径
    @Test
    fun `A03 the mount root itself maps to the storage root`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes/team", "a")))
        val match = router.route(VfsPath.parse("/notes/team"))!!
        assertEquals("a", match.mount.storageKey)
        assertEquals(StoragePath.root, match.relativePath)
        assertTrue(match.relativePath.isRoot)
        assertEquals(".", match.relativePath.toRelativeString())
    }

    @Test
    fun `A03 the relative path drops the logical mount prefix`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes/team/docs", "a")))
        val match = router.route(VfsPath.parse("/notes/team/docs/a/b.txt"))!!
        assertEquals(listOf("a", "b.txt"), match.relativePath.segments)
        assertEquals("a/b.txt", match.relativePath.toRelativeString())
        assertFalse(match.relativePath.segments.containsAll(listOf("notes", "team", "docs")))
    }

    @Test
    fun `A03 unicode and spaces are preserved without re-decoding`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes", "a")))
        val match = router.route(VfsPath.parse("/notes/报告 md/a+b.txt"))!!
        assertEquals(listOf("报告 md", "a+b.txt"), match.relativePath.segments)
    }

    @Test
    fun `A03 double encoded paths never reach the router`() {
        // T02 §2.2 第 3 条：解析层已拒绝，路由输入不可能含字面 '%'。
        assertThrows(VfsException::class.java) { VfsUri.parse("alcyone://notes/%252e%252e/a") }
        assertThrows(VfsException::class.java) { VfsPath.parse("/notes/a%25b") }
    }

    // A04 配置目录导航
    @Test
    fun `A04 the root lists configured namespaces even without mounts`() {
        val router = MountRouter.of(setOf("notes", "Resources"), emptyList())
        assertEquals(setOf("notes", "Resources"), router.listConfiguredChildren(VfsPath.root))
        assertEquals(emptySet<String>(), router.listConfiguredChildren(VfsPath.parse("/notes")))
        assertEquals(emptySet<String>(), router.listConfiguredChildren(VfsPath.parse("/other")))
    }

    @Test
    fun `A04 deep mounts contribute their missing ancestors`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes/team/docs", "a")))
        assertTrue(router.isConfiguredDirectory(VfsPath.parse("/notes/team")))
        assertTrue(router.isConfiguredDirectory(VfsPath.parse("/notes/team/docs")))
        assertEquals(setOf("team"), router.listConfiguredChildren(VfsPath.parse("/notes")))
        assertEquals(setOf("docs"), router.listConfiguredChildren(VfsPath.parse("/notes/team")))
    }

    @Test
    fun `A04 a shared ancestor is listed once`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes/team/docs", "a"), mount("/notes/team/wiki", "b")))
        assertEquals(setOf("team"), router.listConfiguredChildren(VfsPath.parse("/notes")))
        assertEquals(setOf("docs", "wiki"), router.listConfiguredChildren(VfsPath.parse("/notes/team")))
    }

    @Test
    fun `A04 a parseable but unconfigured path is not a virtual directory`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes/team/docs", "a")))
        assertFalse(router.isConfiguredDirectory(VfsPath.parse("/other")))
        assertFalse(router.isConfiguredDirectory(VfsPath.parse("/notes/teammate")))
        assertFalse(router.isConfiguredDirectory(VfsPath.parse("/notes/team/docs/a.txt")))
    }

    // A05 结构与无匹配
    @Test
    fun `A05 descendant mounts are detected by segment`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes", "a"), mount("/notes/team/docs", "b")))
        assertTrue(router.hasDescendantMounts(VfsPath.parse("/notes/team")))
        assertFalse(router.hasDescendantMounts(VfsPath.parse("/notes/teammate")))
        assertTrue(router.hasDescendantMounts(VfsPath.root))
        assertFalse(router.hasDescendantMounts(VfsPath.parse("/notes/team/docs")))
    }

    @Test
    fun `A05 mount points are matched exactly`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes", "a"), mount("/notes/team/docs", "b")))
        assertTrue(router.isMountPoint(VfsPath.parse("/notes")))
        assertTrue(router.isMountPoint(VfsPath.parse("/notes/team/docs")))
        assertFalse(router.isMountPoint(VfsPath.parse("/notes/team")))
    }

    @Test
    fun `A05 unmapped ordinary paths have no route`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes/team", "a")))
        assertNull(router.route(VfsPath.parse("/notes/other/a.txt")))
        assertNull(router.route(VfsPath.parse("/other/a.txt")))
        assertNull(router.route(VfsPath.root))
    }

    @Test
    fun `A05 a virtual ancestor keeps its parent mapping`() {
        // /notes/team 没有自己的挂载，但既是 /notes 下的相对路径，又是 /notes/team/docs 的祖先目录。
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes", "a"), mount("/notes/team/docs", "b")))
        val match = router.route(VfsPath.parse("/notes/team"))!!
        assertEquals("a", match.mount.storageKey)
        assertEquals(listOf("team"), match.relativePath.segments)
        assertTrue(router.isConfiguredDirectory(VfsPath.parse("/notes/team")))
        assertEquals(setOf("docs"), router.listConfiguredChildren(VfsPath.parse("/notes/team")))
    }

    // A06 纯逻辑与不可变性
    @Test
    fun `A06 the input collections are copied defensively`() {
        val namespaces = mutableSetOf("notes")
        val mounts = mutableListOf(mount("/notes/a", "a"))
        val router = MountRouter.of(namespaces, mounts)

        namespaces.add("other")
        mounts.add(mount("/other/b", "b"))
        mounts.clear()

        assertEquals("a", router.route(VfsPath.parse("/notes/a/x.txt"))!!.mount.storageKey)
        assertNull(router.route(VfsPath.parse("/other/b")))
        assertFalse(router.isConfiguredDirectory(VfsPath.parse("/other")))
    }

    @Test
    fun `A06 routing is repeatable and only reads configuration`() {
        val router = MountRouter.of(setOf("notes"), listOf(mount("/notes", "a"), mount("/notes/a", "b")))
        val first = router.route(VfsPath.parse("/notes/a/x.txt"))
        repeat(3) { assertEquals(first, router.route(VfsPath.parse("/notes/a/x.txt"))) }
        assertEquals("b", first!!.mount.storageKey)
        assertEquals(listOf("x.txt"), first.relativePath.segments)
    }

    private fun mount(
        path: String,
        key: String,
    ): MountRecord = MountRecord(VfsPath.parse(path), key)
}
