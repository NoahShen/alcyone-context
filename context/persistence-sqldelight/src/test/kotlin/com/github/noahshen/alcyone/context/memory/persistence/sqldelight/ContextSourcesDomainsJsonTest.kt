package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CT05-B2 严格 JSON 类型（sources_json / domains_json）。
 *
 * 注入一律走受控 `updateRaw`，先断言行数 = 1 并回读库内原文，再断言读取失败，
 * 确保失败来自 codec 的严格类型判定而不是注入失败；异常链不得回显原文。
 */
class ContextSourcesDomainsJsonTest {
    @Test
    fun `sources elements must be known typed objects with string fields`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1"))
            val cases =
                listOf(
                    "[123]",
                    "[true]",
                    "[null]",
                    "[\"$RAW_MARKER\"]",
                    "[{\"type\":\"unknown\",\"memory_id\":\"$RAW_MARKER\"}]",
                    "[{\"memory_id\":\"$RAW_MARKER\"}]",
                    "[{\"type\":\"raw_memory\"}]",
                    "[{\"type\":\"raw_memory\",\"memory_id\":123}]",
                    "[{\"type\":\"raw_memory\",\"memory_id\":true}]",
                    "[{\"type\":\"raw_memory\",\"memory_id\":null}]",
                    "[{\"type\":\"raw_memory\",\"memory_id\":{\"id\":\"$RAW_MARKER\"}}]",
                    "[{\"type\":\"raw_memory\",\"memory_id\":[\"$RAW_MARKER\"]}]",
                    "[{\"type\":123,\"memory_id\":\"$RAW_MARKER\"}]",
                    "[{\"type\":\"conversation_turn\",\"runtime_id\":\"r\",\"agent_id\":\"a\",\"session_id\":\"s\"}]",
                    "[{\"type\":\"conversation_turn\",\"runtime_id\":\"r\",\"agent_id\":\"a\",\"session_id\":\"s\"," +
                        "\"session_turn_id\":true}]",
                    "[{\"type\":\"conversation_turn\",\"runtime_id\":\"r\",\"agent_id\":\"a\",\"session_id\":\"s\"," +
                        "\"session_turn_id\":\"$RAW_MARKER\"}," +
                        "{\"type\":\"conversation_turn\",\"runtime_id\":\"r\",\"agent_id\":\"a\",\"session_id\":\"s\"}]",
                )
            cases.forEach { json -> assertRawSourcesRejected(executor, "raw-1", json) }
        }

    @Test
    fun `domains elements must be JSON strings`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-1"))
            val cases =
                listOf(
                    "[123]",
                    "[true]",
                    "[null]",
                    "[{\"name\":\"$RAW_MARKER\"}]",
                    "[[\"$RAW_MARKER\"]]",
                    "[\"ok\",123]",
                    "[\"ok\",true]",
                    "[\"ok\",null]",
                )
            cases.forEach { json -> assertDomainsRejected(executor, "org-1", json) }
        }

    @Test
    fun `valid sources and domains round-trip with order preserved`() =
        ConversationFixtures.withDatabase { executor ->
            val conversation = MemorySourceRef.ConversationTurnRef("123", "agent-1", "session-1", "true")
            val rawRef = MemorySourceRef.RawMemoryRef("raw-0")
            B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1", sources = listOf(conversation, rawRef)))
            B2Fixtures.insertOrganized(
                executor,
                B2Fixtures.organized("org-1", domains = listOf("123", "true", "中文", "emoji 🚀")),
            )

            val storedRaw = assertNotNull(RawMemoryRepositoryImpl(executor).findById("raw-1"))
            assertEquals(listOf(conversation, rawRef), storedRaw.sources)
            val storedOrganized = assertNotNull(OrganizedMemoryRepositoryImpl(executor).findById("org-1"))
            assertEquals(listOf("123", "true", "中文", "emoji 🚀"), storedOrganized.domains)
        }

    @Test
    fun `empty sources and domains arrays are valid`() =
        ConversationFixtures.withDatabase { executor ->
            B2Fixtures.insertRaw(executor, B2Fixtures.raw("raw-1", sources = emptyList()))
            B2Fixtures.insertOrganized(executor, B2Fixtures.organized("org-1", domains = emptyList()))

            assertEquals(emptyList(), assertNotNull(RawMemoryRepositoryImpl(executor).findById("raw-1")).sources)
            assertEquals(emptyList(), assertNotNull(OrganizedMemoryRepositoryImpl(executor).findById("org-1")).domains)
        }

    private suspend fun assertRawSourcesRejected(
        executor: ContextDatabaseExecutor,
        id: String,
        json: String,
    ) {
        inject(executor, "raw_memory", "sources_json", id, json)
        val failure =
            assertFailsWith<ContextPersistenceException> {
                RawMemoryRepositoryImpl(executor).findById(id)
            }
        assertSafeFailure(failure)
    }

    private suspend fun assertDomainsRejected(
        executor: ContextDatabaseExecutor,
        id: String,
        json: String,
    ) {
        inject(executor, "organized_memory", "domains_json", id, json)
        val failure =
            assertFailsWith<ContextPersistenceException> {
                OrganizedMemoryRepositoryImpl(executor).findById(id)
            }
        assertSafeFailure(failure)
    }

    private suspend fun inject(
        executor: ContextDatabaseExecutor,
        table: String,
        column: String,
        id: String,
        json: String,
    ) {
        val updated =
            executor.updateRaw("UPDATE $table SET $column = '${json.replace("'", "''")}' WHERE id = '$id'")
        assertEquals(1, updated, "注入必须真实生效，否则读取异常不能归因于 codec")
        val stored =
            executor.queryRaw("SELECT $column FROM $table WHERE id = '$id'") { rs ->
                rs.next()
                rs.getString(1)
            }
        assertEquals(json, stored, "库内必须是注入的非法 JSON 原文")
    }

    private fun assertSafeFailure(failure: ContextPersistenceException) {
        assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        assertNull(failure.cause)
        val chain = describeChain(failure)
        assertFalse(chain.contains(RAW_MARKER), "异常链不得回显库内原文: $chain")
    }

    private fun describeChain(failure: Throwable): String =
        buildString {
            var current: Throwable? = failure
            while (current != null) {
                appendLine(current.message.orEmpty())
                appendLine(current.stackTraceToString())
                current = current.cause
            }
            failure.suppressed.forEach { appendLine(it.stackTraceToString()) }
        }

    private companion object {
        const val RAW_MARKER = "B2-RAW-MARKER-7a1"
    }
}
