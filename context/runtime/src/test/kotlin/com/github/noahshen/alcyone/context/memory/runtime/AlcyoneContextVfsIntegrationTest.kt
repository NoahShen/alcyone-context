package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CT06-B2 集成：真实 Context SQLite + 真实 VFS LocalFs 挂载的 start/close 与重开。
 */
class AlcyoneContextVfsIntegrationTest {
    @Test
    fun `start assembles a real LocalFs mount and close allows reopening`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val mount = fixture.localMount()
            val vfsConfig = fixture.vfsConfig(namespaces = setOf("memory"), mounts = listOf(mount))
            val config = fixture.config(vfsConfig = vfsConfig)

            val first = AlcyoneContextFactory.create(config, vfsConfig)
            runBlocking { first.start() }
            assertEquals(ContextLifecycleState.RUNNING, first.status().lifecycle)
            assertTrue(first.status().storage.databaseInitialized)
            assertEquals(fixture.contextPath, Path.of(first.status().storage.databasePath))
            assertTrue(Files.exists(fixture.vfsPath), "VFS 状态库应随 start 创建")
            assertTrue(Files.isDirectory(fixture.mountRoot), "挂载根必须保持存在")

            first.close()
            assertEquals(ContextLifecycleState.CLOSED, first.status().lifecycle)

            // 正常关闭后重开同一配置：Context 库不重建、VFS 库可复用、独占锁已释放。
            val second = AlcyoneContextFactory.create(config, vfsConfig)
            runBlocking { second.start() }
            assertEquals(ContextLifecycleState.RUNNING, second.status().lifecycle)
            assertTrue(second.status().storage.databaseInitialized)
            second.close()
            assertEquals(ContextLifecycleState.CLOSED, second.status().lifecycle)
        }
}
