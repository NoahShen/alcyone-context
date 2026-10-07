package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.registry.makeDirectory
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.streams.toList
import kotlin.test.assertFailsWith

/**
 * T15 A07：阶段边界——未交付的 `move` 明确拒绝且零副作用，Core 也只认 Port。
 *
 * 依赖方向本身由 `vfs/core/build.gradle.kts` 决定（只有 `:vfs:api`），这里用源码扫描与反射做静态佐证。
 * 测试的工作目录是模块目录，所以 `src/main/kotlin` 是相对的。
 */
class DefaultVfsStageBoundaryTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    @Test
    fun `A07 the operations still to be delivered are refused with no side effect`() =
        runBlocking {
            val disk = StorageFakeImpl()
            disk.withFile("a.txt", "hello")
            disk.makeDirectory("dir")
            disk.withFile("dir/inner.txt", "inner")
            disk.calls.clear() // 下面的断言只看这几个方法自己造成的调用
            val harness = VfsHarness(listOf(VfsHarness.Mounted("/resources", disk)), notifier = notifiers.create())
            val dir = VfsUri.parse("alcyone://resources/dir")
            val dir2 = VfsUri.parse("alcyone://resources/dir2")

            // 同 Mount 文件移动已在 T20 交付，这里改钉仍属阶段拒绝的两类：目录移动（T22）与复制回退（T21）。
            val directory = assertFailsWith<VfsException>("move directory") { harness.vfs.move(dir, dir2) }
            assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, directory.code, "目录移动还没交付，明确拒绝，不静默成功")
            assertEquals(VfsEffect.NONE, directory.effect, "拒绝时零副作用")
            assertTrue(disk.calls.none { it.startsWith("move:") }, "不许再动后端：${disk.calls}")
            assertTrue(harness.committedNodes().isEmpty(), "不写状态")
            assertTrue(harness.committedEvents().isEmpty(), "不发事件")
            assertEquals("inner", disk.readText("dir/inner.txt"), "目录还在")
        }

    @Test
    fun `A07 the file operations live in core and reach into neither a storage engine nor the database`() {
        val sources =
            Files
                .walk(Path.of("src/main/kotlin/com/github/noahshen/alcyone/context/vfs/core"))
                .use { stream: Stream<Path> -> stream.filter { it.toString().endsWith(".kt") }.toList() }
        val forbidden = listOf("org.apache.opendal", "app.cash.sqldelight", "java.sql", "vfs.persistence", "vfs.storage")

        val offenders = sources.filter { file -> forbidden.any { it in Files.readString(file) } }

        assertTrue(offenders.isEmpty(), "Core 不得直接依赖具体后端或数据库：$offenders")
        assertTrue(sources.any { it.toString().endsWith("DefaultVfs.kt") }, "DefaultVfs 在 core 里")
    }

    @Test
    fun `A07 no public member of DefaultVfs exposes an infrastructure type`() {
        val signatures: List<Class<*>> =
            listOf(DefaultVfs::class.java, VfsLimits::class.java)
                .flatMap { type: Class<*> ->
                    val fromMethods: List<List<Class<*>>> = type.methods.map { m -> listOf(m.returnType) + m.parameterTypes.toList() }
                    val fromConstructors: List<List<Class<*>>> = type.constructors.map { c -> c.parameterTypes.toList() }
                    fromMethods + fromConstructors
                }.flatten()

        val leaking =
            signatures.filter {
                it.name.startsWith("org.apache.opendal") ||
                    it.name.startsWith("app.cash.sqldelight") ||
                    it.name.startsWith("com.github.noahshen.alcyone.context.vfs.persistence") ||
                    it.name.startsWith("com.github.noahshen.alcyone.context.vfs.storage")
            }

        assertTrue(leaking.isEmpty(), "公开签名只能出现 Port 和 API 类型：$leaking")
    }
}
