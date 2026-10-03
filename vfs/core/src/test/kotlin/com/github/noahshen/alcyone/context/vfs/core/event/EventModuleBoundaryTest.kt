package com.github.noahshen.alcyone.context.vfs.core.event

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.streams.toList

/**
 * T14 A07：事件接线全部在 `vfs/core`，只依赖 Core 的 Port 和 API 的值类型。
 *
 * 依赖方向本身由 `vfs/core/build.gradle.kts` 决定（只有 `:vfs:api`），这里用源码扫描与反射做静态佐证。
 * 测试的工作目录是模块目录，所以 `src/main/kotlin` 是相对的。
 */
class EventModuleBoundaryTest {
    private val eventSources: List<Path> =
        Files
            .walk(Path.of("src/main/kotlin/com/github/noahshen/alcyone/context/vfs/core/event"))
            .use { stream: Stream<Path> -> stream.filter { it.toString().endsWith(".kt") }.toList() }

    @Test
    fun `the event wiring lives in core and reaches into neither a storage engine nor the database`() {
        val forbidden = listOf("org.apache.opendal", "app.cash.sqldelight", "java.sql", "vfs.persistence", "vfs.storage")

        val offenders = eventSources.filter { file -> forbidden.any { it in Files.readString(file) } }

        assertTrue(offenders.isEmpty(), "Core 不得直接依赖具体后端或数据库：$offenders")
        assertTrue(eventSources.size >= 3, "事件生成、分发、提交接线都在 core/event")
    }

    @Test
    fun `no public member exposes an infrastructure type`() {
        val types = listOf(EventPipeline::class.java, AsyncEventNotifier::class.java, EventFactory::class.java)
        val signatures = types.flatMap { type -> type.methods.map { listOf(it.returnType) + it.parameterTypes }.flatten() }

        val leaking = signatures.filter { it.name.startsWith("org.apache.opendal") || it.name.startsWith("app.cash.sqldelight") }

        assertTrue(leaking.isEmpty(), "公开签名不得暴露后端或数据库类型：$leaking")
    }
}
