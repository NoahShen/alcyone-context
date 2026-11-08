package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CT16 B2 A07：公共 Factory + 真实 SQLite + Fake HTTP 的 Extraction→Organization 闭环。 */
class OrganizationEndToEndTest {
    private val session = SessionKey("ct16-runtime", "ct16-agent", "ct16-session")
    private val base = Instant.parse("2026-11-08T02:00:00Z")
    private val memoryContent = "ct16-e2e-organized-memory"
    private val domain = "ct16-e2e-domain"

    @Test
    fun `conversation extraction and organization complete with source and watermark`() =
        runBlocking {
            val stub =
                ExtractionHttpStub(
                    listOf(
                        ExtractionHttpStub.chatCompletionEnvelope(
                            """{"memories":[{"content":"$memoryContent","source_turn_ids":[5]}]}""",
                        ),
                        ExtractionHttpStub.chatCompletionEnvelope(
                            """{"action":"NEW","targetMemoryId":null,"content":"$memoryContent","domains":["$domain"]}""",
                        ),
                    ),
                )
            val harness = harness(stub)
            try {
                harness.context.start()
                repeat(5) { index -> ingest(harness.context, index) }

                awaitUntil {
                    harness.count("raw_memory") == 1L &&
                        harness.count("organization_pending") == 0L &&
                        harness.count("organized_memory") == 1L &&
                        harness.count("organized_memory_source") == 1L
                }
                awaitUntil { harness.workerState() == WorkerState.IDLE }

                assertEquals(2, stub.requestCount(), "Extraction 与 Governance 各一次；空候选 Scanner 零请求")
                val raw = harness.rawRow()
                val organized = harness.organizedRow()
                assertEquals(memoryContent, raw.content)
                assertEquals(memoryContent, organized.content)
                assertTrue(organized.domainsJson.contains(domain), organized.domainsJson)
                assertEquals(raw.id, harness.sourceRawId())
                assertEquals(organized.updatedAt, harness.maxUpdatedAt())
                assertTrue(CANONICAL_SHANGHAI_MILLIS.matches(organized.createdAt), organized.createdAt)
                assertTrue(CANONICAL_SHANGHAI_MILLIS.matches(organized.updatedAt), organized.updatedAt)
                assertTrue(organized.createdAt <= organized.updatedAt)
                assertEquals(ContextLifecycleState.RUNNING, harness.lifecycle())
                assertEquals(WorkerState.IDLE, harness.workerState())
            } finally {
                harness.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, harness.lifecycle())
            assertEquals(WorkerState.STOPPED, harness.workerState())
        }

    @Test
    fun `empty extraction memories never wake organization`() =
        runBlocking {
            val stub =
                ExtractionHttpStub(
                    listOf(ExtractionHttpStub.chatCompletionEnvelope("""{"memories":[]}""")),
                )
            val harness = harness(stub)
            try {
                harness.context.start()
                repeat(5) { index -> ingest(harness.context, index) }

                awaitUntil { harness.statuses().values.all { status -> status == "complete" } }
                awaitUntil { harness.workerState() == WorkerState.IDLE }
                assertEquals(1, stub.requestCount())
                assertEquals(0L, harness.count("raw_memory"))
                assertEquals(0L, harness.count("organization_pending"))
                assertEquals(0L, harness.count("organized_memory"))
                assertEquals(WorkerState.IDLE, harness.workerState())
            } finally {
                harness.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, harness.lifecycle())
            assertEquals(WorkerState.STOPPED, harness.workerState())
        }

    private fun harness(stub: ExtractionHttpStub): Harness {
        val database = TestDatabase()
        val directory = database.path.parent
        val yaml =
            """
            context:
              database:
                path: ./context.db
              extraction:
                triggers:
                  count:
                    threshold: 5
                  idle:
                    enabled: false
              organization:
                triggers:
                  immediate:
                    enabled: true
                  periodic:
                    enabled: false
              llm:
                profiles:
                  all:
                    baseUrl: ${stub.baseUrl}
                    apiKey: $KEY_MARKER
                    model: fake-model
                    contextWindowTokens: 64000
                    jsonMode: json_object
                    maxAttempts: 1
                tasks:
                  EXTRACTION:
                    profile: all
                    maxInputTokens: 12000
                    maxOutputTokens: 3000
                  CANDIDATE_SCANNER:
                    profile: all
                    maxInputTokens: 12000
                    maxOutputTokens: 3000
                  GOVERNANCE:
                    profile: all
                    maxInputTokens: 12000
                    maxOutputTokens: 3000
            vfs:
              database:
                path: ./vfs.db
            """.trimIndent()
        val configFile = directory.resolve("config.yaml")
        Files.writeString(configFile, yaml)
        val config = ContextConfigLoader.load(configFile)
        val context = AlcyoneContextFactory.create(config, config.vfsConfig)
        return Harness(context, database)
    }

    private suspend fun ingest(
        context: AlcyoneContext,
        index: Int,
    ) {
        val result =
            context.ingestConversation(
                ConversationTurn(
                    runtimeId = session.runtimeId,
                    agentId = session.agentId,
                    sessionId = session.sessionId,
                    sessionTurnId = "turn-$index",
                    timestamp = base.plusSeconds(index.toLong()),
                    messages = listOf(ChatMessage(ChatRole.USER, "hello-$index")),
                ),
            )
        assertEquals(IngestStatus.CREATED, result.status)
    }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) {
        withTimeout(20_000L) {
            while (!condition()) delay(20)
        }
    }

    private data class RawRow(
        val id: String,
        val content: String,
    )

    private data class OrganizedRow(
        val content: String,
        val domainsJson: String,
        val createdAt: String,
        val updatedAt: String,
    )

    private class Harness(
        val context: AlcyoneContext,
        private val database: TestDatabase,
    ) : AutoCloseable {
        fun count(table: String): Long = database.count(table)

        fun lifecycle(): ContextLifecycleState = runBlocking { context.getStatus() }.lifecycle

        fun workerState(): WorkerState = runBlocking { context.getStatus() }.workers.getValue("organization").state

        fun statuses(): Map<String, String> =
            database.readOnly("SELECT session_turn_id, extraction_status FROM conversation_turn ORDER BY id") { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(1), rs.getString(2))
                }
            }

        fun rawRow(): RawRow =
            database.readOnly("SELECT id, content FROM raw_memory") { rs ->
                assertTrue(rs.next())
                RawRow(rs.getString(1), rs.getString(2))
            }

        fun organizedRow(): OrganizedRow =
            database.readOnly("SELECT content, domains_json, created_at, updated_at FROM organized_memory") { rs ->
                assertTrue(rs.next())
                OrganizedRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
            }

        fun sourceRawId(): String =
            database.readOnly("SELECT raw_memory_id FROM organized_memory_source") { rs ->
                assertTrue(rs.next())
                rs.getString(1)
            }

        fun maxUpdatedAt(): String =
            database.readOnly("SELECT MAX(updated_at) FROM organized_memory") { rs ->
                assertTrue(rs.next())
                rs.getString(1)
            }

        override fun close() {
            context.close()
            database.close()
        }
    }

    private companion object {
        const val KEY_MARKER = "ct16-b2-e2e-key-marker"
        val CANONICAL_SHANGHAI_MILLIS =
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+08:00""")
    }
}
