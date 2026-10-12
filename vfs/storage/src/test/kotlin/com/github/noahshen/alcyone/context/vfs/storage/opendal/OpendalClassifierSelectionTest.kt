package com.github.noahshen.alcyone.context.vfs.storage.opendal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A02 / R1：验证 OpenDAL native classifier 平台的选择与未知平台明确拒绝。
 * 直接调用真实构建命令执行 vfs/storage/build.gradle.kts 中的 resolveOpendalClassifier 任务，
 * 保证测试真正执行被测的实际构建逻辑；当修改 build.gradle.kts 逻辑时测试即时反馈。
 */
class OpendalClassifierSelectionTest {
    private fun resolveClassifierViaBuild(
        osName: String,
        osArch: String,
    ): Result<String> {
        val rootDir =
            generateSequence(File(".").canonicalFile) { it.parentFile }
                .firstOrNull { File(it, "gradlew").isFile }
                ?: error("Could not find project root directory containing gradlew")

        val devScript = File(rootDir, "scripts/dev").canonicalPath
        val process =
            ProcessBuilder(
                devScript,
                "gradle",
                "-q",
                ":vfs:storage:resolveOpendalClassifier",
                "-PtargetOs=$osName",
                "-PtargetArch=$osArch",
            ).directory(rootDir)
                .redirectErrorStream(true)
                .start()

        val output =
            try {
                val reader = process.inputStream.bufferedReader()
                val text = reader.readText()
                val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    error("Gradle classifier resolution timed out for os='$osName', arch='$osArch'")
                }
                text
            } catch (t: Throwable) {
                process.destroyForcibly()
                throw t
            }
        val exitCode = process.exitValue()

        return if (exitCode == 0) {
            val classifier =
                output
                    .lineSequence()
                    .firstOrNull { it.startsWith("classifier=") }
                    ?.substringAfter("classifier=")
                    ?.trim()
                    ?: error("classifier output not found in:\n$output")
            Result.success(classifier)
        } else {
            Result.failure(IllegalStateException(output))
        }
    }

    @Test
    fun `supported platforms map to the exact opendal classifiers via actual build logic`() {
        assertEquals("osx-aarch_64", resolveClassifierViaBuild("Mac OS X", "aarch64").getOrThrow())
        assertEquals("osx-aarch_64", resolveClassifierViaBuild("Mac OS X", "arm64").getOrThrow())
        assertEquals("linux-aarch_64", resolveClassifierViaBuild("Linux", "aarch64").getOrThrow())
        assertEquals("linux-aarch_64", resolveClassifierViaBuild("Linux", "arm64").getOrThrow())
        assertEquals("linux-x86_64", resolveClassifierViaBuild("Linux", "x86_64").getOrThrow())
        assertEquals("linux-x86_64", resolveClassifierViaBuild("Linux", "amd64").getOrThrow())
    }

    @Test
    fun `unsupported OS or CPU architecture fails explicitly without silent fallback via actual build logic`() {
        val unsupported =
            listOf(
                "Mac OS X" to "x86_64",
                "Mac OS X" to "amd64",
                "Windows 11" to "amd64",
                "Windows 10" to "x86_64",
                "SunOS" to "sparc",
                "FreeBSD" to "x86_64",
                "Linux" to "mips64",
                "Linux" to "riscv64",
                "Mac OS X" to "riscv64",
            )
        for ((os, arch) in unsupported) {
            val result = resolveClassifierViaBuild(os, arch)
            assertTrue(result.isFailure, "Expected failure for os='$os', arch='$arch'")
            val message = result.exceptionOrNull()?.message ?: ""
            assertTrue(
                message.contains("Unsupported OS/CPU for OpenDAL native: os.name='$os', os.arch='$arch'"),
                "Output should contain expected error message, but was:\n$message",
            )
        }
    }
}
