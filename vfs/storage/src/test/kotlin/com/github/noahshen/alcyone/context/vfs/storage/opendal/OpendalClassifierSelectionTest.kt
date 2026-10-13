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
    private fun runProcessWithTimeout(
        command: List<String>,
        directory: File,
        timeoutSeconds: Long,
    ): Result<String> {
        val tempFile = File.createTempFile("opendal-proc", ".log")
        return try {
            val process =
                ProcessBuilder(command)
                    .directory(directory)
                    .redirectOutput(tempFile)
                    .redirectErrorStream(true)
                    .start()

            val finished =
                try {
                    process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    process.destroyForcibly()
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    throw t
                }

            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                error("Process timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
            }

            val output = tempFile.readText()
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                Result.success(output)
            } else {
                Result.failure(IllegalStateException(output))
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun resolveClassifierViaBuild(
        osName: String,
        osArch: String,
    ): Result<String> {
        val rootDir =
            generateSequence(File(".").canonicalFile) { it.parentFile }
                .firstOrNull { File(it, "gradlew").isFile }
                ?: error("Could not find project root directory containing gradlew")

        val devScript = File(rootDir, "scripts/dev").canonicalPath
        val command =
            listOf(
                devScript,
                "gradle",
                "-q",
                ":vfs:storage:resolveOpendalClassifier",
                "-PtargetOs=$osName",
                "-PtargetArch=$osArch",
            )

        val runResult = runProcessWithTimeout(command, rootDir, 30)
        return runResult.mapCatching { output ->
            output
                .lineSequence()
                .firstOrNull { it.startsWith("classifier=") }
                ?.substringAfter("classifier=")
                ?.trim()
                ?: error("classifier output not found in:\n$output")
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

    @Test
    fun `subprocess timeout forcibly terminates hanging child process and reports timeout failure`() {
        val rootDir = File(".").canonicalFile
        val hungCommand = listOf("sh", "-c", "echo hanging-process-started; sleep 10")
        val err =
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                runProcessWithTimeout(hungCommand, rootDir, timeoutSeconds = 1).getOrThrow()
            }
        assertTrue(
            err.message?.contains("Process timed out after 1s") == true,
            "Error message must indicate timeout: ${err.message}",
        )
    }
}
