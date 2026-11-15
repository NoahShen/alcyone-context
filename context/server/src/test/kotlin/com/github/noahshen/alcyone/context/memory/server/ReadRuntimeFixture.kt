package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/** Real Runtime/SQLite/Local FS, with a controlled HTTP model response for search only. */
internal class ReadRuntimeFixture : AutoCloseable {
    val files = ServerFixture()
    val modelCalls = AtomicInteger()
    private val model =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.use { request ->
                    val noMatch = request.readBytes().decodeToString().contains("no-match")
                    val call = modelCalls.getAndIncrement()
                    val payload =
                        if (call % 2 == 0) {
                            if (noMatch) """{"candidate_ids":[]}""" else """{"candidate_ids":["O-1"]}"""
                        } else {
                            if (noMatch) """{"memory_ids":[]}""" else """{"memory_ids":["O-1"]}"""
                        }
                    val response =
                        """{"choices":[{"message":{"content":${JsonPrimitive(payload)}},"finish_reason":"stop"}]}"""
                            .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            start()
        }

    suspend fun start(): AlcyoneContext {
        files.initialize()
        // Seed before Runtime owns the database; all values obey the published schema.
        DriverManager.getConnection("jdbc:sqlite:${files.contextPath}").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("PRAGMA foreign_keys=ON")
                sql.execute("INSERT INTO raw_memory VALUES ('R-1','raw content','EXTRACTION','[]','2026-01-01T08:00:00.000+08:00')")
                sql.execute(
                    "INSERT INTO organized_memory VALUES ('O-1','organized content','[\"domain\"]','2026-01-01T08:00:00.000+08:00','2026-01-01T08:00:01.000+08:00')",
                )
                sql.execute("INSERT INTO organized_memory_source VALUES ('O-1','R-1')")
            }
        }
        val vfs =
            VfsRuntimeConfig(
                stateDatabase = files.vfsPath,
                namespaces = setOf("resources"),
                mounts = listOf(MountConfig(VfsPath.parse("/resources"), "resources", files.mountRoot)),
            )
        val profile =
            LlmProfileConfig(
                name = "search",
                baseUrl = "http://127.0.0.1:${model.address.port}",
                apiKey = "model-secret-marker",
                model = "controlled",
                contextWindowTokens = 64000,
                jsonMode = LlmResponseFormat.JSON_OBJECT,
                maxAttempts = 1,
            )
        val config =
            files.config().copy(
                vfsConfig = vfs,
                extraction = ExtractionConfig(idleEnabled = false),
                llm =
                    LlmConfig(
                        profiles = mapOf("search" to profile),
                        tasks = mapOf(LlmTask.MEMORY_SEARCH to LlmTaskConfig("search", 32000, 4000)),
                        safetyMarginPercent = 0,
                    ),
            )
        val context = AlcyoneContextFactory.create(config, vfs)
        try {
            context.start()
            return context
        } catch (failure: Throwable) {
            try {
                context.closeAsync()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun turnCount(): Int =
        DriverManager.getConnection("jdbc:sqlite:${files.contextPath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM conversation_turn").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

    override fun close() {
        model.stop(0)
        files.close()
    }
}
