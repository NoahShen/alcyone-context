package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * CT06-B2 §5 R1：create 必须先取得归实例所有的配置快照，start 只消费已校验快照。
 *
 * 在 create 之后修改调用者原 mounts/namespaces，start 仍必须挂载原 safe 目录：
 * 能看到 safe marker、看不到 private marker、也看不到 private 目录里的 Context 状态库文件。
 */
class AlcyoneContextConfigOwnershipTest {
    @Test
    fun `create snapshots a directly constructed mutable vfs config`() =
        ContextFixture().use { fixture ->
            val safe = Files.createDirectories(fixture.directory.resolve("safe"))
            val private = Files.createDirectories(fixture.directory.resolve("private"))
            Files.writeString(safe.resolve("safe-marker.txt"), "safe")
            Files.writeString(private.resolve("private-marker.txt"), "private")

            val contextPath = private.resolve("context.db")
            val mounts = mutableListOf(MountConfig(VfsPath.parse("/memory"), "local", safe))
            val namespaces = mutableSetOf("memory")
            val vfsConfig =
                fixture.vfsConfig(
                    stateDatabase = fixture.directory.resolve("state/vfs.db"),
                    namespaces = namespaces,
                    mounts = mounts,
                )
            val contextConfig = ContextConfig(contextPath, 30.seconds, vfsConfig = vfsConfig)
            runBlocking { AlcyoneContextFactory.initializeDatabase(contextConfig) }

            val context = AlcyoneContextFactory.create(contextConfig, vfsConfig)
            // 调用者在 create 之后修改原集合：不能影响已校验快照。
            mounts[0] = MountConfig(VfsPath.parse("/memory"), "local", private)
            namespaces.clear()
            namespaces.add("mutated")

            runBlocking { context.start() }
            val names = listMemory(context)
            assertSnapshotIsSafe(names)
            context.close()
        }

    @Test
    fun `create snapshots a loader produced vfs config`() =
        ContextFixture().use { fixture ->
            val safe = Files.createDirectories(fixture.directory.resolve("safe"))
            val other = Files.createDirectories(fixture.directory.resolve("other-safe"))
            val private = Files.createDirectories(fixture.directory.resolve("private"))
            Files.writeString(safe.resolve("safe-marker.txt"), "safe")
            Files.writeString(private.resolve("private-marker.txt"), "private")

            // 两个 namespace / 两个 mount：Loader 的 toList()/toSet() 才返回可变集合，
            // 从而能真实模拟“Java 调用者事后修改原集合”。
            val yaml =
                """
                context:
                  database:
                    path: ./private/context.db
                vfs:
                  database:
                    path: ./state/vfs.db
                  namespaces: [memory, resources]
                  mounts:
                    - vfsPath: /memory
                      storageKey: local
                      backend: { type: local, root: ./safe }
                    - vfsPath: /resources
                      storageKey: other
                      backend: { type: local, root: ./other-safe }
                """.trimIndent()
            val configFile = fixture.directory.resolve("config.yaml")
            Files.writeString(configFile, yaml)
            val config = ContextConfigLoader.load(configFile)
            runBlocking { AlcyoneContextFactory.initializeDatabase(config) }

            val context = AlcyoneContextFactory.create(config, config.vfsConfig)

            @Suppress("UNCHECKED_CAST")
            val callerMounts = config.vfsConfig.mounts as MutableList<MountConfig>
            callerMounts[0] = MountConfig(VfsPath.parse("/memory"), "local", private)
            @Suppress("UNCHECKED_CAST")
            val callerNamespaces = config.vfsConfig.namespaces as MutableSet<String>
            callerNamespaces.clear()
            callerNamespaces.add("mutated")

            runBlocking { context.start() }
            assertSnapshotIsSafe(listMemory(context))
            context.close()
        }

    private fun listMemory(context: AlcyoneContext): List<String> {
        val vfs = (context as AlcyoneContextImpl).vfsForTesting()
        checkNotNull(vfs) { "context must be RUNNING before listing" }
        return runBlocking { vfs.list(VfsUri.parse("alcyone://memory/")) }.map { it.uri.toString() }
    }

    private fun assertSnapshotIsSafe(names: List<String>) {
        assertTrue(names.any { it.endsWith("/safe-marker.txt") }, "必须挂载原 safe 目录，实际: $names")
        assertFalse(names.any { it.contains("private-marker") }, "修改后的 private 目录不得成为挂载根，实际: $names")
        assertFalse(names.any { it.contains("context.db") }, "Context 状态库不得出现在实际挂载内，实际: $names")
    }
}
