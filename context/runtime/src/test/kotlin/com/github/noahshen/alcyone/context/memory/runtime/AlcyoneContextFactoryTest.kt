package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * CT06-B2 工厂：create 零副作用与隔离校验、显式初始化、普通 start 的拒绝保护。
 */
class AlcyoneContextFactoryTest {
    @Test
    fun `create has no filesystem side effects and reports CREATED without probing`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())

            assertEquals(before, fixture.snapshot(), "create 不得创建文件/目录/锁")
            val status = context.status()
            assertEquals(ContextLifecycleState.CREATED, status.lifecycle)
            assertFalse(status.storage.databaseInitialized, "create 后未验证数据库，不得声称已初始化")
            assertTrue(status.workers.isEmpty())
            assertEquals(fixture.contextPath, Path.of(status.storage.databasePath))

            // 状态查询本身也不得隐式取得资源。
            context.status()
            assertEquals(before, fixture.snapshot())
            assertFalse(Files.exists(fixture.contextPath))
            assertFalse(Files.exists(fixture.vfsPath))
        }

    @Test
    fun `create rejects the same context and vfs database file`() =
        ContextFixture().use { fixture ->
            val vfsConfig = fixture.vfsConfig(stateDatabase = fixture.contextPath)
            val failure =
                assertFailsWith<ContextConfigException> {
                    AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("database.path"))
            assertFalse(Files.exists(fixture.contextPath))
        }

    @Test
    fun `create rejects a database inside a local mount root`() =
        ContextFixture().use { fixture ->
            Files.createDirectories(fixture.directory.resolve("data"))
            val mount = fixture.localMount(root = fixture.directory.resolve("data"))
            val vfsConfig = fixture.vfsConfig(mounts = listOf(mount))
            val failure =
                assertFailsWith<ContextConfigException> {
                    AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("context.database.path"))

            // vfs.db 落在另一块挂载根内同样拒绝（context.db 不在该根内）。
            val vfsStorage = Files.createDirectories(fixture.directory.resolve("vfs-storage"))
            val vfsMount = fixture.localMount(vfsPath = "/resources", storageKey = "resources", root = vfsStorage)
            val insideVfs =
                fixture.vfsConfig(stateDatabase = vfsStorage.resolve("vfs.db"), mounts = listOf(vfsMount))
            val vfsFailure =
                assertFailsWith<ContextConfigException> {
                    AlcyoneContextFactory.create(fixture.config(vfsConfig = insideVfs), insideVfs)
                }
            assertTrue(vfsFailure.message.orEmpty().contains("vfs.database.path"), "实际: ${vfsFailure.message}")
        }

    @Test
    fun `create rejects case-insensitive aliases of a local mount root`() =
        ContextFixture().use { fixture ->
            Files.createDirectory(fixture.directory.resolve("CaseRoot"))
            val caseRootReal = runCatching { fixture.directory.resolve("CaseRoot").toRealPath() }.getOrNull()
            val caseAliasReal = runCatching { fixture.directory.resolve("caseroot").toRealPath() }.getOrNull()
            val caseInsensitive =
                Files.exists(fixture.directory.resolve("caseroot")) && caseRootReal != null && caseRootReal == caseAliasReal

            val mount = fixture.localMount(root = fixture.directory.resolve("CaseRoot"))
            val vfsConfig = fixture.vfsConfig(mounts = listOf(mount))
            val config = ContextConfig(fixture.directory.resolve("caseroot/context.db"), 30.seconds, vfsConfig = vfsConfig)
            if (caseInsensitive) {
                val failure = assertFailsWith<ContextConfigException> { AlcyoneContextFactory.create(config, vfsConfig) }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            } else {
                // 大小写敏感文件系统：这是另一个（不存在的）目录，属于合法配置，create 不创建它。
                val context = AlcyoneContextFactory.create(config, vfsConfig)
                assertEquals(ContextLifecycleState.CREATED, context.status().lifecycle)
                assertFalse(Files.exists(fixture.directory.resolve("caseroot")))
            }
        }

    @Test
    fun `create rejects structurally invalid vfs configuration`() =
        ContextFixture().use { fixture ->
            val cases: List<Pair<String, com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig>> =
                listOf(
                    "vfs.mounts[0].vfsPath" to
                        fixture.vfsConfig(mounts = listOf(MountConfig(VfsPath.root, "root", fixture.mountRoot))),
                    "vfs.mounts[0].storageKey" to
                        fixture.vfsConfig(mounts = listOf(MountConfig(VfsPath.parse("/memory"), "  ", fixture.mountRoot))),
                    "vfs.streamTotalLimit" to fixture.vfsConfig(streamTotalLimit = -1),
                    "vfs.eventBufferCapacity" to fixture.vfsConfig(eventBufferCapacity = 0),
                    "vfs.closeGracePeriod" to fixture.vfsConfig(closeGracePeriod = (-1).milliseconds),
                )
            cases.forEach { (path, vfsConfig) ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode, "case: $path")
                assertTrue(failure.message.orEmpty().contains(path), "期望路径 $path，实际: ${failure.message}")
            }
        }

    @Test
    fun `initializeDatabase creates the file and a second call refuses to overwrite`() =
        ContextFixture().use { fixture ->
            runBlocking { AlcyoneContextFactory.initializeDatabase(fixture.config()) }
            assertTrue(Files.exists(fixture.contextPath))

            val failure =
                assertFailsWith<ContextPersistenceException> {
                    runBlocking { AlcyoneContextFactory.initializeDatabase(fixture.config()) }
                }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        }

    @Test
    fun `start without initialization is rejected and creates no files`() =
        ContextFixture().use { fixture ->
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val failure = assertFailsWith<ContextPersistenceException> { runBlocking { context.start() } }
            assertEquals(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, failure.errorCode)
            assertFalse(Files.exists(fixture.contextPath), "start 不得自动建库")
            assertFalse(Files.exists(fixture.vfsPath), "Context start 失败时不得启动 VFS")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `start rejects empty wrong-version and corrupt databases without changing bytes`() =
        ContextFixture().use { fixture ->
            Files.createDirectories(fixture.contextPath.parent)
            Files.createFile(fixture.contextPath)
            val emptyContext = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val emptyFailure = assertFailsWith<ContextPersistenceException> { runBlocking { emptyContext.start() } }
            assertEquals(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, emptyFailure.errorCode)
            assertEquals(0L, Files.size(fixture.contextPath))

            Files.delete(fixture.contextPath)
            fixture.initialize()
            fixture.setUserVersion(99)
            val wrongVersionBytes = fixture.bytesOf(fixture.contextPath)
            val wrongContext = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val versionFailure = assertFailsWith<ContextPersistenceException> { runBlocking { wrongContext.start() } }
            assertEquals(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, versionFailure.errorCode)
            assertContentEquals(wrongVersionBytes, fixture.bytesOf(fixture.contextPath), "错版库不得被改动")

            Files.delete(fixture.contextPath)
            Files.write(fixture.contextPath, "definitely not a sqlite database".toByteArray())
            val corruptBytes = fixture.bytesOf(fixture.contextPath)
            val corruptContext = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val corruptFailure = assertFailsWith<ContextPersistenceException> { runBlocking { corruptContext.start() } }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, corruptFailure.errorCode)
            assertContentEquals(corruptBytes, fixture.bytesOf(fixture.contextPath), "损坏库不得被改动")
        }

    @Test
    fun `start succeeds after explicit initialization and reports verified storage`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking { context.start() }

            val status = context.status()
            assertEquals(ContextLifecycleState.RUNNING, status.lifecycle)
            assertTrue(status.storage.databaseInitialized)
            assertEquals(fixture.contextPath.toAbsolutePath().normalize(), Path.of(status.storage.databasePath))
            assertTrue(status.workers.isEmpty())
            assertTrue(Files.exists(fixture.vfsPath), "VFS 状态库应由 VFS 自己创建")

            context.close()
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `start after close is rejected with CLOSED`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            context.close()
            val failure = assertFailsWith<ContextLifecycleException> { runBlocking { context.start() } }
            assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
        }
}
