package com.github.noahshen.alcyone.context.memory.runtime.llm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CT08-B2 A08（core 无 Ktor 证据）：新 Adapter 只落在 runtime。
 *
 * - `context/core/build.gradle.kts` 文本不含 ktor；
 * - `context/core/src/main/kotlin` 无 `io.ktor` import。
 *
 * 依赖解析角度的第二证据（`:context:core:dependencies` 无 ktor）由 Lead 独立记录。
 */
class KtorBoundaryTest {
    @Test
    fun `core module declares no ktor dependency or import`() {
        val root = repositoryRoot()
        val buildFile = File(root, "context/core/build.gradle.kts")
        assertTrue(buildFile.isFile, "缺少 ${buildFile.path}")
        assertTrue(
            !buildFile.readText().contains("ktor", ignoreCase = true),
            "context/core/build.gradle.kts 不得声明 ktor",
        )

        val mainSources = File(root, "context/core/src/main/kotlin")
        assertTrue(mainSources.isDirectory, "缺少 ${mainSources.path}")
        val ktorImports =
            mainSources
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> file.readLines().map { line -> file to line.trim() } }
                .filter { (_, line) -> line.startsWith("import io.ktor") }
                .toList()
        assertTrue(ktorImports.isEmpty(), "core 主源码不得 import Ktor: $ktorImports")
    }

    private fun repositoryRoot(): File {
        val start = File(System.getProperty("user.dir"))
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("无法从 ${start.path} 向上定位仓库根（settings.gradle.kts）")
    }
}
