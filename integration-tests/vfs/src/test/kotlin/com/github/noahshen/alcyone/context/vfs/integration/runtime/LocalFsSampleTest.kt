package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * T19 A07：**可运行的最小样例**。这是文档里给出的「运行入口」——一条 `./scripts/dev gradle` 命令就能跑，
 * 不新建样例模块、不做 CLI、不发布。
 *
 * 它演示宿主视角的完整用法：**只提交配置**给 [AlcyoneVfs.create]，然后
 * write → read → stat → setMetadata/getMetadata → list → subscribe（等到事件送达）→ openStream（分块读）
 * → 在 `finally` 里 `close()`。所有资源由 Runtime 持有，宿主不管 Operator / Driver / Repository。
 *
 * 运行：
 * ```
 * ./scripts/dev gradle :integration-tests:vfs:test --tests "*LocalFsSampleTest*" --console=plain
 * ```
 *
 * 预期：`BUILD SUCCESSFUL`；用例在 `stdout` 打印每一步的观测值（见使用说明的「实际输出」）。
 * 状态库放在挂载物理根**之外**的临时目录里，测试结束随 `@TempDir` 一起清理。
 */
class LocalFsSampleTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `a host submits only configuration and walks a real local file lifecycle`() =
        runBlocking {
            // 1) 只准备两样东西：一块真的本机目录，和一个放状态库的位置（在挂载根之外）。
            val docs = Files.createDirectory(tempDir.resolve("docs"))
            val database = tempDir.resolve("state.db")
            Files.writeString(docs.resolve("external.txt"), "placed outside the VFS") // 未登记的外部文件

            // 2) 把配置交给 Runtime。命名空间与挂载都来自配置，不是写死的白名单。
            val config =
                VfsRuntimeConfig(
                    stateDatabase = database,
                    namespaces = setOf("resources"),
                    mounts = listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", docs)),
                )

            val vfs = AlcyoneVfs.create(config)
            try {
                // 3) 订阅已提交的变更。订阅先于操作，按目标 Node ID / 类型等待（不靠固定 sleep）。
                val updated = CompletableDeferred<VfsEvent>()
                vfs.subscribe { event ->
                    if (event.type == com.github.noahshen.alcyone.context.vfs.VfsEventType.METADATA_UPDATED) {
                        updated.complete(event)
                    }
                }

                // 4) 写文件 + 整体替换 Metadata。
                val info = vfs.write(VfsUri.parse("alcyone://resources/docs/note.txt"), "hello alcyone".toByteArray())
                println("[sample] wrote ${info.uri} nodeId=${info.id} size=${info.storage?.sizeBytes}")
                vfs.setMetadata(info.id, NodeMetadata(setOf("ct", "影像"), "胸部 CT 报告"))
                val event = withTimeout(30_000) { updated.await() }
                println("[sample] event ${event.type} nodeId=${event.nodeId} uri=${event.uri}")

                // 5) 读回内容，并用 ID 查逻辑状态与 Metadata。
                val content = vfs.read(VfsUri.parse("alcyone://resources/docs/note.txt")).toString(Charsets.UTF_8)
                assertEquals("hello alcyone", content)
                val stat = vfs.stat(VfsUri.parse("alcyone://resources/docs/note.txt"))
                assertEquals(info.id, stat.id)
                println("[sample] read bytes=${content.length} stat.id=${stat.id} tags=${vfs.getMetadata(info.id).tags}")

                // 6) 列出目录：按路径比较，不假设顺序；未登记项 nodeId 为空。
                val listing =
                    vfs
                        .list(VfsUri.parse("alcyone://resources/docs"))
                        .map { it.uri.path.toString() to it.nodeId }
                        .sortedBy { it.first }
                println("[sample] list=$listing")

                // 7) 流式读取：一块一块读，不把整份读进内存；一块没登记的大文件也能读。
                val big = Files.newOutputStream(docs.resolve("big.bin"))
                big.use { out -> repeat(64) { out.write(ByteArray(1024) { i -> (i % 251).toByte() }) } } // 64 KiB
                val digest = MessageDigest.getInstance("SHA-256")
                var streamed = 0L
                vfs.openStream(VfsUri.parse("alcyone://resources/docs/big.bin")).use { result ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = result.stream.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        streamed += read
                    }
                }
                println("[sample] streamed bytes=$streamed sha256=${digest.digest().joinToString("") { "%02x".format(it) }.take(16)}…")

                assertEquals(64L * 1024, streamed)
            } finally {
                // 8) 关闭 Runtime：挂起函数，所以用 try/finally。流、通知器、各块盘、状态库、独占锁逐个释放。
                vfs.close()
                println("[sample] closed; state database can be reopened")
            }
        }
}
