package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Comparator
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * CT06-B2 测试夹具：真实临时目录、真实 Context SQLite 与 VFS LocalFs 配置。
 *
 * `context.db` 由 [ContextDatabaseInitializer] 显式建库；`vfs.db` 由 VFS 自己的 create 打开。
 */
internal class ContextFixture : AutoCloseable {
    val directory: Path = Files.createTempDirectory("ct06-b2-")
    val contextPath: Path = directory.resolve("data/context.db")
    val vfsPath: Path = directory.resolve("data/vfs.db")
    val mountRoot: Path = Files.createDirectories(directory.resolve("storage"))

    /** 显式初始化 Context 状态库（生产入口是 AlcyoneContextFactory.initializeDatabase）。 */
    fun initialize() {
        ContextDatabaseInitializer.initialize(contextPath)
    }

    /** 打开已初始化的 Context 状态库（测试直接使用持久化层）。 */
    fun open(): ContextDatabaseExecutor = ContextDatabaseFactory.open(contextPath)

    fun config(
        shutdownTimeout: Duration = 30.seconds,
        vfsConfig: VfsRuntimeConfig = vfsConfig(),
    ): ContextConfig = ContextConfig(databasePath = contextPath, lifecycleShutdownTimeout = shutdownTimeout, vfsConfig = vfsConfig)

    fun vfsConfig(
        stateDatabase: Path = vfsPath,
        namespaces: Set<String> = emptySet(),
        mounts: List<MountConfig> = emptyList(),
        streamTotalLimit: Long? = null,
        eventBufferCapacity: Int = 256,
        closeGracePeriod: Duration = 30.seconds,
    ): VfsRuntimeConfig =
        VfsRuntimeConfig(
            stateDatabase = stateDatabase,
            namespaces = namespaces,
            mounts = mounts,
            streamTotalLimit = streamTotalLimit,
            eventBufferCapacity = eventBufferCapacity,
            closeGracePeriod = closeGracePeriod,
        )

    fun localMount(
        vfsPath: String = "/memory",
        storageKey: String = "local-memory",
        root: Path = mountRoot,
    ): MountConfig = MountConfig(VfsPath.parse(vfsPath), storageKey, root)

    /** 目录下所有条目（相对路径、排序），用于断言 create 没有文件系统副作用。 */
    fun snapshot(): List<String> =
        Files.walk(directory).use { paths ->
            paths
                .filter { it != directory }
                .map { directory.relativize(it).toString() }
                .sorted()
                .toList()
        }

    fun setUserVersion(version: Long) {
        DriverManager.getConnection("jdbc:sqlite:${contextPath.toUri()}", Properties()).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
        }
    }

    fun userVersion(): Long =
        DriverManager.getConnection("jdbc:sqlite:${contextPath.toUri()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getLong(1) else -1L }
            }
        }

    fun bytesOf(path: Path): ByteArray = Files.readAllBytes(path)

    override fun close() {
        if (!Files.exists(directory)) return
        val failures = mutableListOf<Throwable>()
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (failure: Throwable) {
                    failures += IllegalStateException("failed to delete test artifact $path", failure)
                }
            }
        }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }
}

/** `getStatus` 是挂起签名；测试用这个 helper 统一读取，保持缓存事实语义不变。 */
internal fun AlcyoneContext.status(): ContextStatus = runBlocking { getStatus() }
