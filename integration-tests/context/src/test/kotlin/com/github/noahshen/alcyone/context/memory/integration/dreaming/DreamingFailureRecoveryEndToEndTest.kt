package com.github.noahshen.alcyone.context.memory.integration.dreaming

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import com.github.noahshen.alcyone.context.memory.api.DreamingRunStage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.integration.sqliteLong
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** CT26 B1：真实动态预算分批、失败批原子性与 SQLite 重开恢复。 */
class DreamingFailureRecoveryEndToEndTest {
    @Test
    fun `failed batch preserves prior commit and restart resumes exact checkpoint`() =
        runBlocking {
            val directory = Files.createTempDirectory("ct26-dreaming-recovery-")
            val database = directory.resolve("data/context.db")
            DreamingHttpStub(database).use { stub ->
                val config = config(directory, stub)
                AlcyoneContextFactory.initializeDatabase(config)
                AlcyoneContextFactory.create(config, config.vfsConfig).use { context ->
                    context.start()
                    // 固定开销与 Evidence 引用使预算偏向小批；按实际提交水位断言，不假设输入数等于产出数。
                    ingest(context, "first", "Kotlin重试".repeat(6_000), 1)
                    ingest(context, "second", "Kotlin重试".repeat(5_000), 2)
                    ingest(context, "third", "Kotlin重试".repeat(5_000), 3)
                    stub.failReflectionAtTurnOnce(3)
                    val beforeFailureRequests = stub.requests().size

                    val result = runCatching { context.runDreaming() }
                    val failureSummary =
                        "stages=${stub.requests().drop(
                            beforeFailureRequests,
                        ).map {
                            "${it.routingSummary()}; turns=${if (it.stage == "DREAMING_TOPIC") {
                                topicSourceTurns(
                                    stub,
                                    it,
                                )
                            } else {
                                emptyList<Long>()
                            }}"
                        }}; " +
                            "reflectionRemaining=${stub.remainingReflectionFailures()}; " +
                            "stubFailures=${stub.failures()}; error=${result.exceptionOrNull()}"
                    val failure = result.exceptionOrNull() as? DreamingRunException ?: fail(failureSummary)
                    assertEquals(DreamingRunStage.BATCH_PROCESSING, failure.stage, failureSummary)
                    assertIs<LlmException>(failure.cause)
                    assertEquals(0L, failure.partialResult.startCheckpoint)
                    assertEquals(2L, failure.partialResult.endCheckpoint, failureSummary)
                    assertEquals(3, failure.partialResult.scannedTurns, failureSummary)
                    assertEquals(2, failure.partialResult.generatedMemories)
                    assertEquals(2L, checkpoint(database), failureSummary)
                    assertEquals(2L, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"))
                    assertEquals(2L, sqliteLong(database, "SELECT COUNT(*) FROM organization_pending"))
                    assertEquals(3, stub.requests().count { it.stage == "DREAMING_REFLECTION" }, failureSummary)
                    assertEquals(2, stub.requests().count { it.stage == "DREAMING_RAW" }, failureSummary)
                    assertEquals(
                        listOf(listOf(1L), listOf(2L), listOf(3L)),
                        stub
                            .requests()
                            .filter {
                                it.stage == "DREAMING_TOPIC"
                            }.map { topicSourceTurns(stub, it) },
                        failureSummary,
                    )

                    val resumed = context.runDreaming()
                    assertEquals(2L, resumed.startCheckpoint)
                    assertEquals(3L, resumed.endCheckpoint)
                    assertEquals(1, resumed.scannedTurns)
                    assertEquals(1, resumed.generatedMemories, failureSummary)
                    assertEquals(3L, checkpoint(database), failureSummary)
                    assertEquals(3L, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"))
                    assertEquals(0L, sqliteLong(database, ORPHANED_RAW_QUERY))

                    val beforeIdle = stub.requests().size
                    val idle = context.runDreaming()
                    assertEquals(3L, idle.startCheckpoint)
                    assertEquals(0, idle.scannedTurns)
                    assertEquals(beforeIdle, stub.requests().size)
                }

                val beforeRestart = stub.requests().size
                val beforeRestartReflections = stub.requests().count { it.stage == "DREAMING_REFLECTION" }
                val beforeRestartRawCount = sqliteLong(database, "SELECT COUNT(*) FROM raw_memory")
                assertEquals(3L, beforeRestartRawCount)
                var restartSummary = "not run"
                AlcyoneContextFactory.create(config, config.vfsConfig).use { restarted ->
                    restarted.start()
                    ingest(restarted, "fourth", "fourth resumed durable Kotlin memory", 4)
                    val result = restarted.runDreaming()
                    assertEquals(3L, result.startCheckpoint)
                    assertEquals(4L, result.endCheckpoint)
                    assertEquals(1, result.scannedTurns)
                    assertEquals(1, result.generatedMemories)
                    restartSummary = result.toString()
                }
                assertEquals(
                    4L,
                    checkpoint(database),
                    stub
                        .requests()
                        .drop(beforeRestart)
                        .joinToString("\n") { request ->
                            request.routingSummary() + "; prompt=" + stub.decodedPrompt(request).take(150)
                        },
                )
                assertEquals(beforeRestartRawCount + 1, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"), restartSummary)
                assertEquals(0L, sqliteLong(database, ORPHANED_RAW_QUERY))
                assertEquals(beforeRestartReflections + 1, stub.requests().count { it.stage == "DREAMING_REFLECTION" })
                assertTrue(stub.requests().size >= beforeRestart)
                assertTrue(
                    stub.requests().all {
                        it.authorization in
                            setOf("Bearer topic-key", "Bearer search-key", "Bearer evidence-key", "Bearer reflection-key")
                    },
                )
            }
        }

    private suspend fun ingest(
        context: AlcyoneContext,
        id: String,
        text: String,
        timestampSeconds: Long,
    ) {
        context.ingestConversation(
            ConversationTurn(
                runtimeId = "ct26-recovery",
                agentId = "agent",
                sessionId = "session-$id",
                sessionTurnId = id,
                timestamp = Instant.parse("2026-11-10T04:00:00Z").plusSeconds(timestampSeconds),
                messages = listOf(ChatMessage(ChatRole.USER, text)),
            ),
        )
    }

    private fun checkpoint(database: Path): Long =
        sqliteLong(database, "SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id = 1")

    private fun topicSourceTurns(
        stub: DreamingHttpStub,
        request: DreamingHttpStub.RecordedRequest,
    ): List<Long> = Regex("""<turn id="([0-9]+)"""").findAll(stub.decodedPrompt(request)).map { it.groupValues[1].toLong() }.toList()

    private fun config(
        directory: Path,
        stub: DreamingHttpStub,
    ): ContextConfig {
        val yaml =
            """
            context:
              database: { path: ./data/context.db }
              dreaming: {}
              llm:
                profiles:
                  topic: { baseUrl: '${stub.endpoint()}', apiKey: topic-key, model: topic-model, contextWindowTokens: 76000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                  search: { baseUrl: '${stub.endpoint()}', apiKey: search-key, model: search-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                  evidence: { baseUrl: '${stub.endpoint()}', apiKey: evidence-key, model: evidence-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                  reflection: { baseUrl: '${stub.endpoint()}', apiKey: reflection-key, model: reflection-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                tasks:
                  DREAMING_TOPIC: { profile: topic, maxInputTokens: 70000, maxOutputTokens: 4096 }
                  MEMORY_SEARCH: { profile: search, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  DREAMING_EVIDENCE: { profile: evidence, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  DREAMING_REFLECTION: { profile: reflection, maxInputTokens: 100000, maxOutputTokens: 4096 }
            vfs:
              database: { path: ./data/vfs.db }
            """.trimIndent()
        val file = directory.resolve("recovery.yaml")
        Files.writeString(file, yaml)
        return ContextConfigLoader.load(file)
    }

    private companion object {
        const val ORPHANED_RAW_QUERY =
            "SELECT COUNT(*) FROM raw_memory raw " +
                "LEFT JOIN organization_pending pending ON pending.raw_memory_id = raw.id " +
                "LEFT JOIN organized_memory_source source ON source.raw_memory_id = raw.id " +
                "WHERE pending.raw_memory_id IS NULL AND source.raw_memory_id IS NULL"
    }
}
