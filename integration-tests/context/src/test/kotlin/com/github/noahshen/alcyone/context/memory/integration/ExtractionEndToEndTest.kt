package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CT12-B1：真实 Runtime（公共 `AlcyoneContextFactory.create`）+ 真实 SQLite + Fake LLM HTTP 的端到端提取。
 *
 * 夹具说明：`context.db` 由既有 `B3Fixtures.TestDatabase` 初始化；`ContextConfig` 通过**公共**
 * `ContextConfigLoader.load(...)` 从临时 YAML 读取（`vfs.runtime` 不在本测试模块的编译类路径，
 * 且本批不允许改构建/依赖），`vfs.db` 为同目录下独立临时文件。
 *
 * 覆盖：失败后 Trigger 继续、窗口外 Pending 不变、零记忆全部完成；断言基于**真实 SQLite 只读查询**
 * 并通过有界轮询等待（不使用固定 sleep 猜结果）。
 */
class ExtractionEndToEndTest {
    private val session = SessionKey("runtime-1", "agent-1", "session-1")
    private val base: Instant = Instant.parse("2026-10-31T02:00:00Z")

    @Test
    fun `a failed extraction does not stop later triggers`() =
        runBlocking {
            val stub =
                ExtractionHttpStub(
                    listOf(
                        ExtractionHttpStub.chatCompletionEnvelope("this is not valid json at all"),
                        ExtractionHttpStub.chatCompletionEnvelope(
                            """{"memories":[{"content":"memory-after-retry","source_turn_ids":[1,2]}]}""",
                        ),
                    ),
                )
            val harness = harness(stub)
            try {
                harness.context.start()
                repeat(5) { index -> ingest(harness.context, index) }

                // 第一次提取已发起且失败（非法响应不会写库，读取稳定）。
                awaitUntil { stub.requestCount() == 1 }
                assertEquals(0L, harness.count("raw_memory"), "非法响应不得写 Raw")
                assertEquals(0L, harness.count("organization_pending"), "非法响应不得写 Pending")
                assertEquals(List(5) { "pending" }, harness.statuses().values.toList(), "Target 必须保持 pending")
                assertEquals(ContextLifecycleState.RUNNING, harness.lifecycle(), "普通失败不得使 Context 停下")

                // 第 6 条再次达到阈值 → 第二次触发成功。
                ingest(harness.context, 5)
                awaitUntil { harness.count("raw_memory") == 1L }

                assertEquals(2, stub.requestCount(), "失败后必须继续接受新的触发")
                assertEquals(1L, harness.count("organization_pending"))
                val raw = harness.rawMemories().single()
                assertEquals("memory-after-retry", raw.content)
                assertTrue(raw.sourcesJson.contains("turn-0") && raw.sourcesJson.contains("turn-1"), raw.sourcesJson)
                assertEquals(List(6) { "complete" }, harness.statuses().values.toList(), "本批 Target 全部完成")
                assertEquals(ContextLifecycleState.RUNNING, harness.lifecycle())
                assertEquals(
                    listOf("Bearer $KEY_MARKER", "Bearer $KEY_MARKER"),
                    stub.authorizations(),
                    "凭据只出现在逐请求 Header",
                )
                harness.assertNoCredentialInPersistedState(KEY_MARKER)
            } finally {
                harness.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, harness.lifecycle())
        }

    @Test
    fun `pending turns outside the initial window stay pending`() =
        runBlocking {
            val stub =
                ExtractionHttpStub(
                    listOf(
                        ExtractionHttpStub.chatCompletionEnvelope(
                            """{"memories":[{"content":"memory-window","source_turn_ids":[6]}]}""",
                        ),
                    ),
                )
            val harness =
                harness(
                    stub,
                    ExtractionConfig(countThreshold = 5, idleEnabled = false, initialWindowTurns = 4),
                )
            try {
                harness.context.start()
                repeat(6) { index -> ingest(harness.context, index) }

                awaitUntil { harness.count("raw_memory") == 1L }

                val statuses = harness.statuses()
                assertEquals("pending", statuses.getValue("turn-0"), "窗口外较早 Turn 不得被完成")
                assertEquals("pending", statuses.getValue("turn-1"), "窗口外较早 Turn 不得被完成")
                listOf("turn-2", "turn-3", "turn-4", "turn-5").forEach { turnId ->
                    assertEquals("complete", statuses.getValue(turnId), "窗口内 Turn $turnId 必须完成")
                }
                val raw = harness.rawMemories().single()
                assertEquals("memory-window", raw.content)
                assertTrue(raw.sourcesJson.contains("turn-5"), raw.sourcesJson)
                assertFalse(raw.sourcesJson.contains("turn-0"), "窗口外 Turn 不得成为来源: ${raw.sourcesJson}")
                assertEquals(1L, harness.count("organization_pending"))
                assertEquals(ContextLifecycleState.RUNNING, harness.lifecycle())
            } finally {
                harness.close()
            }
        }

    @Test
    fun `an empty memory batch completes every target without raw rows`() =
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

                assertEquals(0L, harness.count("raw_memory"), "零记忆不得写 Raw")
                assertEquals(0L, harness.count("organization_pending"), "零记忆不得写 Pending")
                assertEquals(List(5) { "complete" }, harness.statuses().values.toList())
                assertEquals(1, stub.requestCount())
                assertEquals(ContextLifecycleState.RUNNING, harness.lifecycle())
                harness.assertNoCredentialInPersistedState(KEY_MARKER)
            } finally {
                harness.close()
            }
        }

    // ------------------------------------------------------------------ 工具

    private fun harness(
        stub: ExtractionHttpStub,
        extraction: ExtractionConfig = ExtractionConfig(countThreshold = 5, idleEnabled = false),
    ): Harness {
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
              llm:
                profiles:
                  extraction:
                    baseUrl: ${stub.baseUrl}
                    apiKey: $KEY_MARKER
                    model: fast-model
                    contextWindowTokens: 64000
                    jsonMode: json_object
                    maxAttempts: 1
                tasks:
                  EXTRACTION:
                    profile: extraction
                    maxInputTokens: 12000
                    maxOutputTokens: 3000
            vfs:
              database:
                path: ./vfs.db
            """.trimIndent()
        val configFile = directory.resolve("config.yaml")
        Files.writeString(configFile, yaml)
        val loaded = ContextConfigLoader.load(configFile)
        val config: ContextConfig = loaded.copy(extraction = extraction)
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

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(20_000L) {
            while (!condition()) delay(20)
        }
    }

    private class RawRow(
        val content: String,
        val sourcesJson: String,
    )

    private class Harness(
        val context: AlcyoneContext,
        private val database: TestDatabase,
    ) : AutoCloseable {
        fun count(table: String): Long = database.count(table)

        fun lifecycle(): ContextLifecycleState = runBlocking { context.getStatus() }.lifecycle

        fun statuses(): Map<String, String> =
            database.readOnly("SELECT session_turn_id, extraction_status FROM conversation_turn ORDER BY id") { rs ->
                val result = LinkedHashMap<String, String>()
                while (rs.next()) result[rs.getString(1)] = rs.getString(2)
                result
            }

        fun rawMemories(): List<RawRow> =
            database.readOnly("SELECT content, sources_json FROM raw_memory ORDER BY created_at, id") { rs ->
                val rows = mutableListOf<RawRow>()
                while (rs.next()) rows += RawRow(content = rs.getString(1), sourcesJson = rs.getString(2))
                rows
            }

        /** 已落库内容不得包含凭据（Key 只允许出现在当次请求 Header）。 */
        fun assertNoCredentialInPersistedState(marker: String) {
            val texts = mutableListOf<String>()
            database.readOnly("SELECT content FROM raw_memory") { rs ->
                while (rs.next()) texts += rs.getString(1)
            }
            database.readOnly("SELECT sources_json FROM raw_memory") { rs ->
                while (rs.next()) texts += rs.getString(1)
            }
            database.readOnly("SELECT messages_json FROM conversation_turn") { rs ->
                while (rs.next()) texts += rs.getString(1)
            }
            texts.forEach { text -> assertFalse(text.contains(marker), "已落库内容不得包含凭据") }
        }

        override fun close() {
            context.close()
            database.close()
        }
    }

    private companion object {
        const val KEY_MARKER = "ct12-b1-unique-key-marker-4e18"
    }
}
