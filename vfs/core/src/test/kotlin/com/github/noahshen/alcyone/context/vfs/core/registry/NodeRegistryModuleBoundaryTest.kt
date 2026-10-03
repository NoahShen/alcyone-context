package com.github.noahshen.alcyone.context.vfs.core.registry

import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.streams.toList

/**
 * T13 A07 模块边界：Registry 与共享边界都在 `vfs/core`，只依赖 Core 的 Port 和 API 的值类型。
 *
 * 依赖方向本身由 `vfs/core/build.gradle.kts` 决定（只有 `:vfs:api`），这里用源码扫描与反射做静态佐证。
 * 测试的工作目录是模块目录，所以 `src/main/kotlin` 是相对的。
 */
class NodeRegistryModuleBoundaryTest {
    private fun sourcesIn(relativeDir: String): List<Path> =
        Files
            .walk(Path.of(relativeDir))
            .use { stream: Stream<Path> -> stream.filter { it.toString().endsWith(".kt") }.toList() }

    private val registrySources: List<Path> =
        sourcesIn("src/main/kotlin/com/github/noahshen/alcyone/context/vfs/core/registry")

    private val stateSources: List<Path> = sourcesIn("src/main/kotlin/com/github/noahshen/alcyone/context/vfs/core/state")

    @Test
    fun `the new sources exist where the task expects them`() {
        assertTrue(registrySources.isNotEmpty(), "core/registry 应该有实现代码")
        assertTrue(stateSources.isNotEmpty(), "core/state 应该有共享边界")
    }

    @Test
    fun `the registry never reaches into a storage engine or the state database`() {
        val forbidden = listOf("org.apache.opendal", "app.cash.sqldelight", "java.sql", "vfs.persistence", "vfs.storage")

        val offenders = (registrySources + stateSources).filter { file -> forbidden.any { it in Files.readString(file) } }

        assertTrue(offenders.isEmpty(), "Core 不得直接依赖具体后端或数据库：$offenders")
    }

    @Test
    fun `no public member exposes an infrastructure type`() {
        val leaking =
            listOf(NodeRegistry::class.java, StateBoundary::class.java)
                .flatMap { type ->
                    type.methods
                        .filter {
                            java.lang.reflect.Modifier
                                .isPublic(it.modifiers)
                        }.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
                }.filter { it.name.startsWith("org.apache.opendal") || it.name.startsWith("app.cash.sqldelight") }

        assertTrue(leaking.isEmpty(), "公开签名不得暴露后端或数据库类型：$leaking")
    }
}
