package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.time.Duration.Companion.seconds

/** CT07-B2 测试夹具：真实 Context SQLite + VFS LocalFs，Server 只经公共 Runtime API 组装。 */
internal class ServerFixture : AutoCloseable {
    val directory: Path = Files.createTempDirectory("ct07-b2-")
    val contextPath: Path = directory.resolve("data/context.db")
    val vfsPath: Path = directory.resolve("data/vfs.db")
    val mountRoot: Path = Files.createDirectories(directory.resolve("storage"))

    fun vfsConfig(): VfsRuntimeConfig =
        VfsRuntimeConfig(
            stateDatabase = vfsPath,
            namespaces = setOf("memory"),
            mounts = listOf(MountConfig(VfsPath.parse("/memory"), "local", mountRoot)),
        )

    fun config(ingestionLimits: IngestionLimits = IngestionLimits.DEFAULTS): ContextConfig =
        ContextConfig(
            databasePath = contextPath,
            lifecycleShutdownTimeout = 30.seconds,
            ingestionLimits = ingestionLimits,
            vfsConfig = vfsConfig(),
        )

    fun initialize() {
        runBlocking { AlcyoneContextFactory.initializeDatabase(config()) }
    }

    fun newContext(ingestionLimits: IngestionLimits = IngestionLimits.DEFAULTS): AlcyoneContext =
        AlcyoneContextFactory.create(config(ingestionLimits), vfsConfig())

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

internal object HttpTurns {
    val KEY_RUNTIME_1: String = "ct07-b2-key-runtime-1"
    val KEY_RUNTIME_2: String = "ct07-b2-key-runtime-2"

    fun registry(): IngestionClientRegistry =
        IngestionClientRegistry.of(
            listOf(
                IngestionClientConfig("runtime-1", "pi", KEY_RUNTIME_1),
                IngestionClientConfig("runtime-2", "hermes", KEY_RUNTIME_2),
            ),
        )

    fun json(
        runtimeId: String = "runtime-1",
        agentId: String = "agent-1",
        sessionId: String = "session-1",
        sessionTurnId: String = "turn-1",
        timestamp: String = "2026-10-29T08:00:00+08:00",
        messages: List<Pair<String, String>> = listOf("user" to "hello"),
    ): String {
        val messagesJson =
            messages.joinToString(",") { (role, content) ->
                """{"role":"$role","content":"${escape(content)}"}"""
            }
        return """{"runtime_id":"$runtimeId","agent_id":"$agentId","session_id":"$sessionId",""" +
            """"session_turn_id":"$sessionTurnId","timestamp":"$timestamp","messages":[$messagesJson]}"""
    }

    private fun escape(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
