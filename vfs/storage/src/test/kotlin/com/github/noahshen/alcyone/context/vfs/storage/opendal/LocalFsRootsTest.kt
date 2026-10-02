package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * A02：物理根集合的重叠校验（T12 §2.2），供 T18 组装阶段复用。
 *
 * 比较按完整路径段，所以 `data/a` 不会因为字符串前缀而误判包含 `data/ab`。
 */
class LocalFsRootsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `normalize resolves the root to a real path`() {
        val nested = Files.createDirectories(tempDir.resolve("a/b"))
        val link = tempDir.resolve("a/link").also { Files.createSymbolicLink(it, nested) }

        assertEquals(nested.toRealPath(), LocalFsRoots.normalize(link))
    }

    @Test
    fun `identical roots are rejected`() {
        val root = Files.createDirectories(tempDir.resolve("data"))
        val sameAgain = tempDir.resolve("data/.").also { Files.createDirectories(it) }

        val failure = assertFailsWith<VfsException> { LocalFsRoots.requireNonOverlapping(listOf(root, sameAgain)) }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun `a nested root is rejected`() {
        val outer = Files.createDirectories(tempDir.resolve("data/a"))
        val inner = Files.createDirectories(tempDir.resolve("data/a/sub"))

        val failure = assertFailsWith<VfsException> { LocalFsRoots.requireNonOverlapping(listOf(outer, inner)) }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun `sibling roots with a shared string prefix are allowed`() {
        val a = Files.createDirectories(tempDir.resolve("data/a"))
        val ab = Files.createDirectories(tempDir.resolve("data/ab"))

        assertEquals(
            listOf(a.toRealPath(), ab.toRealPath()),
            LocalFsRoots.requireNonOverlapping(listOf(a, ab)),
        )
    }

    @Test
    fun `a root that is not an existing directory is rejected without leaking the path`() {
        val missing = tempDir.resolve("missing")
        val failure = assertFailsWith<VfsException> { LocalFsRoots.normalize(missing) }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
        assertEquals(false, failure.message!!.contains(tempDir.toString()))
    }
}
