package com.github.noahshen.alcyone.context.memory.integration.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.integration.queryReadOnly
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CT26：真实 YAML/Factory/HTTP/存储正链，以及历史 Organized 证据进入后续 Reflection。 */
class DreamingPublicEntryEndToEndTest {
    @Test
    fun `yaml public run persists raw then uses organized evidence in the next reflection`() =
        runBlocking {
            val directory = Files.createTempDirectory("ct26-public-dreaming-")
            val database = directory.resolve("data/context.db")
            DreamingHttpStub(database).use { stub ->
                val config = config(directory, stub)
                AlcyoneContextFactory.initializeDatabase(config)
                AlcyoneContextFactory.create(config, config.vfsConfig).use { context ->
                    context.start()
                    ingest(context, "public-first", "Prefer bounded retry for Kotlin calls.")
                    val first = context.runDreaming()
                    assertEquals(emptyList(), stub.failures())
                    assertEquals(1L, first.endCheckpoint)
                    assertEquals(1, first.generatedMemories, stub.requests().map { it.stage }.toString())
                    assertEquals(1L, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"))
                    assertEquals(1L, sqliteLong(database, "SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id = 1"))
                    awaitReflectionCount(stub, 1)
                    val firstDreamingRequests = stub.requests().count { it.stage.startsWith("DREAMING_") }

                    awaitOrganization(database, stub)
                    ingest(context, "public-second", "The durable Kotlin retry boundary is confirmed.")
                    val second = context.runDreaming()
                    awaitReflectionCount(stub, 2)
                    assertEquals(2L, second.endCheckpoint)
                    val secondRequests =
                        stub
                            .requests()
                            .filter { it.stage.startsWith("DREAMING_") }
                            .drop(firstDreamingRequests)
                            .groupingBy { it.stage }
                            .eachCount()
                    assertEquals(1, second.generatedMemories, secondRequests.toString())
                    assertEquals(1, secondRequests.getOrDefault("DREAMING_EVIDENCE", 0), secondRequests.toString())
                    assertEquals(1, secondRequests.getOrDefault("DREAMING_RAW", 0), secondRequests.toString())
                    assertEquals(1, secondRequests.getOrDefault("DREAMING_CONVERSATION", 0), secondRequests.toString())
                    assertEquals(emptyList(), stub.failures())
                    awaitOrganization(database, stub, expectedSources = 2)
                    val rawRows =
                        queryReadOnly(database, "SELECT id, content, created_at, generated_by FROM raw_memory ORDER BY id") { result ->
                            buildList {
                                while (result.next()) {
                                    add(listOf(result.getString(1), result.getString(2), result.getString(3), result.getString(4)))
                                }
                            }
                        }
                    assertEquals(2L, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"), rawRows.toString())
                    assertEquals(1L, sqliteLong(database, "SELECT COUNT(*) FROM organized_memory"), secondRequests.toString())
                    assertEquals(2L, sqliteLong(database, "SELECT COUNT(*) FROM organized_memory_source"), secondRequests.toString())
                    val memory = assertNotNull(context.getMemory(firstOrganizedId(database)))
                    val requests = stub.requests()
                    val reflection = requests.last { it.stage == "DREAMING_REFLECTION" }
                    val requestSummary =
                        requests.groupingBy { it.stage }.eachCount().toString() +
                            "\nreflection=${stub.decodedPrompt(reflection)}"
                    val reflectionPrompt = stub.decodedPrompt(reflection)
                    val rawId = firstRawId(database)
                    val rawContent = rawRows.single { row -> row[0] == rawId }[1]
                    assertEquals(1L, first.generatedMemories.toLong(), requestSummary)
                    assertTrue(reflection.body.contains(memory.id), requestSummary)
                    // MERGE 会更新 Organized 正文；验证实际持久化的当前正文，而不是 Stub 的旧版本。
                    assertTrue(reflection.body.contains(memory.content), requestSummary)
                    assertTrue(reflectionPrompt.contains(rawContent), requestSummary)
                    assertTrue(reflectionPrompt.contains("<raw_candidates count=\"1\">"), requestSummary)
                    assertTrue(reflectionPrompt.contains("<raw_candidate id=\"$rawId\""), requestSummary)
                    assertTrue(reflectionPrompt.contains("sessionTurnId=\"public-first\""), requestSummary)
                    // MEMORY_SEARCH 也服务 Topic Consolidation；不能把其计数当作 Organized 命中。
                    assertTrue(reflectionPrompt.contains("<current_organized count=\"1\">"), requestSummary)
                    assertTrue(reflectionPrompt.contains(memory.content), requestSummary)
                    assertTrue(reflectionPrompt.contains("<current_organized"), requestSummary)
                }
                assertProfileRouting(config, stub.requests())
                // 第二次已有 Organized 证据，经 Novelty 后仍执行 Raw 补充扫描。
                assertEquals(1, stub.requests().count { it.stage == "DREAMING_RAW" })
                assertEquals(1, stub.requests().count { it.stage == "DREAMING_CONVERSATION" })
            }
        }

    @Test
    fun `public run retries reflection 503 then a 429 failure resumes from the same checkpoint`() =
        runBlocking {
            val directory = Files.createTempDirectory("ct26-public-http-failure-")
            val database = directory.resolve("data/context.db")
            DreamingHttpStub(database).use { stub ->
                val config = config(directory, stub, retryAttempts = 2)
                AlcyoneContextFactory.initializeDatabase(config)
                AlcyoneContextFactory.create(config, config.vfsConfig).use { context ->
                    context.start()
                    ingest(context, "http-retry", "Retry an unavailable reflection without duplicate evidence.")
                    stub.reflection503Once()
                    val recovered = context.runDreaming()
                    assertEquals(1L, recovered.endCheckpoint)
                    assertEquals(2, stub.requests().count { it.stage == "DREAMING_REFLECTION" })
                    assertEquals(1L, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"))

                    ingest(context, "http-failed", "A rate limited reflection leaves its batch uncommitted.")
                    stub.reflection429Once()
                    assertEquals(2, stub.requests().count { it.stage == "DREAMING_REFLECTION" })
                    runCatching { context.runDreaming() }
                    awaitReflectionCount(stub, 4)
                    val resumedReflection = stub.decodedPrompt(stub.requests().last { it.stage == "DREAMING_REFLECTION" })
                    assertRecoveredFromSameTurn(resumedReflection)
                    assertEquals(1L, sqliteLong(database, "SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id = 1"))
                    assertEquals(1L, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"))
                    val resumed = context.runDreaming()
                    awaitReflectionCount(stub, 5)
                    val recoveryReflection = stub.decodedPrompt(stub.requests().last { it.stage == "DREAMING_REFLECTION" })
                    assertRecoveredFromSameTurn(recoveryReflection)
                    assertEquals(1L, resumed.startCheckpoint)
                    assertEquals(2L, resumed.endCheckpoint)
                    assertEquals(1, resumed.scannedTurns)
                    assertEquals(1, resumed.generatedMemories)
                    assertEquals(2L, sqliteLong(database, "SELECT COUNT(*) FROM raw_memory"))
                    assertEquals(emptyList(), stub.failures())
                }
            }
        }

    private fun assertProfileRouting(
        config: ContextConfig,
        requests: List<DreamingHttpStub.RecordedRequest>,
    ) {
        val llm = requireNotNull(config.llm)
        val tasks =
            mapOf(
                LlmTask.DREAMING_TOPIC to ("topic-model" to "topic-secret"),
                LlmTask.MEMORY_SEARCH to ("search-model" to "search-secret"),
                LlmTask.DREAMING_EVIDENCE to ("evidence-model" to "evidence-secret"),
                LlmTask.DREAMING_REFLECTION to ("reflection-model" to "reflection-secret"),
            )
        tasks.forEach { (task, expected) ->
            val taskConfig = requireNotNull(llm.tasks[task])
            val profile = requireNotNull(llm.profiles[taskConfig.profile])
            assertEquals(expected.first, profile.model)
            assertEquals(expected.second, profile.apiKey)
            assertEquals(LlmResponseFormat.JSON_SCHEMA, profile.jsonMode)
            requests.filter { it.stage == task.name }.forEach { request ->
                assertEquals(expected.first, request.model)
                assertEquals("Bearer ${expected.second}", request.authorization)
                assertEquals("json_schema", request.responseFormat)
            }
        }
    }

    private fun awaitOrganization(
        database: Path,
        stub: DreamingHttpStub,
        expectedSources: Long = 1,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (sqliteLong(database, "SELECT COUNT(*) FROM organized_memory") == 1L &&
                sqliteLong(database, "SELECT COUNT(*) FROM organized_memory_source") == expectedSources &&
                sqliteLong(database, "SELECT COUNT(*) FROM organization_pending") == 0L
            ) {
                return
            }
            Thread.sleep(25)
        }
        error(
            "Organization did not commit; pending=${sqliteLong(
                database,
                "SELECT COUNT(*) FROM organization_pending",
            )}; sources=${sqliteLong(
                database,
                "SELECT COUNT(*) FROM organized_memory_source",
            )}; stages=${stub.requests().groupingBy { it.stage }.eachCount()}; httpFailures=${stub.failures()}",
        )
    }

    private fun awaitReflectionCount(
        stub: DreamingHttpStub,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (stub.requests().count { it.stage == "DREAMING_REFLECTION" } >= expected) return
            Thread.sleep(25)
        }
        error("Expected $expected Reflection calls; ${stub.requests().map { it.routingSummary() }}; ${stub.failures()}")
    }

    private fun assertRecoveredFromSameTurn(prompt: String) {
        assertTrue("<sourceTurnIds>2</sourceTurnIds>" in prompt, prompt)
        val newConversation = prompt.substringAfter("<new_conversation").substringBefore("</new_conversation>")
        assertTrue("sessionTurnId=\"http-failed\"" in newConversation, prompt)
        assertTrue("sessionTurnId=\"http-retry\"" !in newConversation, prompt)
    }

    private fun firstOrganizedId(database: Path): String =
        queryReadOnly(database, "SELECT id FROM organized_memory ORDER BY created_at, id LIMIT 1") {
            check(it.next())
            it.getString(1)
        }

    private fun firstRawId(database: Path): String =
        queryReadOnly(database, "SELECT id FROM raw_memory ORDER BY created_at, id LIMIT 1") {
            check(it.next())
            it.getString(1)
        }

    private suspend fun ingest(
        context: com.github.noahshen.alcyone.context.memory.api.AlcyoneContext,
        id: String,
        text: String,
    ) {
        context.ingestConversation(
            ConversationTurn(
                runtimeId = "ct26",
                agentId = "agent",
                sessionId = "public",
                sessionTurnId = id,
                timestamp = Instant.parse("2026-11-10T04:00:00Z").plusSeconds(id.length.toLong()),
                messages = listOf(ChatMessage(ChatRole.USER, text)),
            ),
        )
    }

    private fun config(
        directory: Path,
        stub: DreamingHttpStub,
        retryAttempts: Int = 1,
    ): ContextConfig {
        val yaml =
            """
            context:
              database: { path: ./data/context.db }
              organization:
                triggers:
                  immediate: { enabled: true }
                  periodic: { enabled: false }
              dreaming: {}
              llm:
                profiles:
                  topic: { baseUrl: '${stub.endpoint()}', apiKey: topic-secret, model: topic-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                  search: { baseUrl: '${stub.endpoint()}', apiKey: search-secret, model: search-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                  evidence: { baseUrl: '${stub.endpoint()}', apiKey: evidence-secret, model: evidence-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                  reflection: { baseUrl: '${stub.endpoint()}', apiKey: reflection-secret, model: reflection-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: $retryAttempts }
                  organization: { baseUrl: '${stub.endpoint()}', apiKey: organization-secret, model: organization-model, contextWindowTokens: 1000000, jsonMode: JSON_SCHEMA, maxAttempts: 1 }
                tasks:
                  DREAMING_TOPIC: { profile: topic, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  MEMORY_SEARCH: { profile: search, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  DREAMING_EVIDENCE: { profile: evidence, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  DREAMING_REFLECTION: { profile: reflection, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  CANDIDATE_SCANNER: { profile: organization, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  GOVERNANCE: { profile: organization, maxInputTokens: 100000, maxOutputTokens: 4096 }
                  MEMORY_MAP: { profile: organization, maxInputTokens: 100000, maxOutputTokens: 4096 }
            vfs:
              database: { path: ./data/vfs.db }
            """.trimIndent()
        val file = directory.resolve("alcyone.yaml")
        Files.writeString(file, yaml)
        return ContextConfigLoader.load(file)
    }
}
