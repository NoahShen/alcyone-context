package com.github.noahshen.alcyone.context.vfs.storage.opendal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * A02：验证 OpenDAL native classifier 平台的选择与未知平台明确拒绝。
 * ponytail: 纯逻辑分支测试，不加载 native 库，支持平台扩充时同步更新白名单映射。
 */
class OpendalClassifierSelectionTest {
    // 镜像 build.gradle.kts 中的 opendalNativeClassifier 分支逻辑
    private fun resolveClassifier(
        osName: String,
        osArch: String,
    ): String {
        val isMac = osName.startsWith("Mac")
        val isLinux = osName.startsWith("Linux")
        val isArm = osArch in setOf("aarch64", "arm64")
        val isX64 = osArch in setOf("x86_64", "amd64")

        return when {
            isMac && isArm -> "osx-aarch_64"
            isMac && isX64 -> "osx-x86_64"
            isLinux && isArm -> "linux-aarch_64"
            isLinux && isX64 -> "linux-x86_64"
            else -> error("Unsupported OS/CPU for OpenDAL native: os.name='$osName', os.arch='$osArch'")
        }
    }

    @Test
    fun `supported platforms map to the exact opendal classifiers`() {
        assertEquals("osx-aarch_64", resolveClassifier("Mac OS X", "aarch64"))
        assertEquals("osx-aarch_64", resolveClassifier("Mac OS X", "arm64"))
        assertEquals("osx-x86_64", resolveClassifier("Mac OS X", "x86_64"))
        assertEquals("linux-aarch_64", resolveClassifier("Linux", "aarch64"))
        assertEquals("linux-aarch_64", resolveClassifier("Linux", "arm64"))
        assertEquals("linux-x86_64", resolveClassifier("Linux", "x86_64"))
        assertEquals("linux-x86_64", resolveClassifier("Linux", "amd64"))
    }

    @Test
    fun `unsupported OS or CPU architecture fails explicitly without silent fallback`() {
        val unsupported =
            listOf(
                "Windows 11" to "amd64",
                "Windows 10" to "x86_64",
                "SunOS" to "sparc",
                "FreeBSD" to "x86_64",
                "Linux" to "mips64",
                "Linux" to "riscv64",
                "Mac OS X" to "riscv64",
            )
        for ((os, arch) in unsupported) {
            val ex = assertFailsWith<IllegalStateException> { resolveClassifier(os, arch) }
            assertEquals(
                "Unsupported OS/CPU for OpenDAL native: os.name='$os', os.arch='$arch'",
                ex.message,
            )
        }
    }
}
