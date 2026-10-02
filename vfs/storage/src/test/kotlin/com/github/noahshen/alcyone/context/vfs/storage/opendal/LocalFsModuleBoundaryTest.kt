package com.github.noahshen.alcyone.context.vfs.storage.opendal

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.streams.toList

/**
 * A07：模块边界检查。`vfs/storage` 只能依赖 `vfs/core`，公共签名不出现 OpenDAL / JNI 类型。
 *
 * 依赖方向本身由 `vfs/storage/build.gradle.kts` 决定（只有 `:vfs:core` 与 opendal），这里用源码扫描与反射做静态佐证。
 * 测试的工作目录是模块目录，所以 `src/main/kotlin` 是相对的。
 */
class LocalFsModuleBoundaryTest {
    private val mainSources: List<Path> =
        Files
            .walk(Path.of("src/main/kotlin"))
            .use { stream: Stream<Path> -> stream.filter { it.toString().endsWith(".kt") }.toList() }

    @Test
    fun `main sources never reach into other vfs modules`() {
        val forbidden = listOf("vfs.persistence", "core.router", "core.operation", "core.repository", "core.event", "vfs.runtime")

        val offenders = mainSources.filter { file -> forbidden.any { it in Files.readString(file) } }

        assertTrue(offenders.isEmpty(), "vfs/storage 不得反向依赖其他模块：$offenders")
    }

    @Test
    fun `main sources never touch node identity, events or the state store`() {
        val forbidden = listOf("VfsEvent", "VfsEventId", "NodeId", "UUID", "Repository")

        val offenders =
            mainSources.filter { file ->
                val text = Files.readString(file)
                forbidden.any { Regex("""\b$it\b""").containsMatchIn(text) }
            }

        assertTrue(offenders.isEmpty(), "Adapter 不生成 Node ID、不写 Event、不访问 SQLite：$offenders")
    }

    @Test
    fun `no public member exposes an OpenDAL or JNI type`() {
        val leaking =
            listOf(LocalFsStorage::class.java, LocalFsOptions::class.java, LocalFsRoots::class.java)
                .flatMap { type ->
                    type.methods
                        .filter {
                            java.lang.reflect.Modifier
                                .isPublic(it.modifiers)
                        }.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
                }.filter { it.name.startsWith("org.apache.opendal") || it.name.contains("Panama") || it.name.contains("jni") }

        assertTrue(leaking.isEmpty(), "公共签名不得暴露 OpenDAL / JNI 类型：$leaking")
    }
}
