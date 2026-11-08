package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.ScriptedHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.ScriptedReply
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.RetrievalConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.SearchConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.executeSearchSql
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** CT20 B2 A01/A05/A06：公共 Search/Get/GetRaw、HTTP wiring、maxLimit 与配置隔离。 */
class RetrievalRuntimeWiringTest {
    private val now: Instant = Instant.parse("2026-11-09T01:00:00Z")

    @Test
    fun `running search uses real Ktor scanner and rerank then get methods add no llm`() =
        wiringTest { fixture ->
            seedRetrievalData(fixture)
            val stub =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(200, chatCompletionBody("""{"candidate_ids":["O-1"]}""")),
                        ScriptedReply(200, chatCompletionBody("""{"memory_ids":["O-1"]}""")),
                    ),
                )
            val context =
                AlcyoneContextFactory.create(
                    retrievalContextConfig(fixture, retrievalLlm(stub), maxLimit = 2),
                    fixture.vfsConfig(),
                )
            try {
                context.start()
                val invalidLimit =
                    assertFailsWith<ContextRetrievalException> {
                        context.searchMemory("query", limit = 3)
                    }
                assertEquals(ContextRetrievalErrorCode.INVALID_REQUEST, invalidLimit.errorCode)
                assertEquals(0, stub.requestCount())

                val results = context.searchMemory("  query  ", limit = 1)
                assertEquals(listOf("O-1"), results.map { it.id })
                assertEquals("organized content", results.single().content)
                assertEquals(2, stub.requestCount())

                assertEquals("organized content", context.getMemory("O-1")?.content)
                assertEquals("raw content", context.getRawMemory("R-1")?.content)
                repeat(5) {
                    context.getMemory("O-1")
                    context.getRawMemory("R-1")
                }
                assertEquals(2, stub.requestCount(), "Get/GetRaw 不得调用 LLM")
            } finally {
                context.close()
                stub.close()
            }
        }

    @Test
    fun `no llm keeps get available while search is configuration and bootstrap unaffected`() =
        wiringTest { fixture ->
            seedRetrievalData(fixture)
            val context = AlcyoneContextFactory.create(retrievalContextConfig(fixture, llm = null), fixture.vfsConfig())
            try {
                context.start()
                assertEquals("organized content", context.getMemory("O-1")?.content)
                assertEquals("raw content", context.getRawMemory("R-1")?.content)
                val failure = assertFailsWith<ContextRetrievalException> { context.searchMemory("query") }
                assertEquals(ContextRetrievalErrorCode.CONFIGURATION, failure.errorCode)
                assertEquals(MapStatus.MISSING, context.bootstrap().mapStatus)
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
            } finally {
                context.close()
            }
        }

    @Test
    fun `missing task and prompt only remain call-time search configuration failures`() =
        wiringTest { fixture ->
            seedRetrievalData(fixture)
            val stub = ScriptedHttpStub(listOf(ScriptedReply(200, chatCompletionBody("unused"))))
            try {
                listOf(
                    retrievalLlm(stub, includeTask = false),
                    retrievalLlm(stub, mode = LlmResponseFormat.PROMPT_ONLY),
                ).forEach { llm ->
                    val context = AlcyoneContextFactory.create(retrievalContextConfig(fixture, llm), fixture.vfsConfig())
                    try {
                        context.start()
                        assertEquals("organized content", context.getMemory("O-1")?.content)
                        val failure = assertFailsWith<ContextRetrievalException> { context.searchMemory("query") }
                        assertEquals(ContextRetrievalErrorCode.CONFIGURATION, failure.errorCode)
                        assertEquals(0, stub.requestCount())
                    } finally {
                        context.close()
                    }
                }
            } finally {
                stub.close()
            }
        }

    @Test
    fun `get and get raw blank ids are invalid and missing ids return null`() =
        wiringTest { fixture ->
            seedRetrievalData(fixture)
            val context = AlcyoneContextFactory.create(retrievalContextConfig(fixture, llm = null), fixture.vfsConfig())
            try {
                context.start()
                listOf(
                    suspend { context.getMemory("  ") },
                    suspend { context.getRawMemory("  ") },
                ).forEach { call ->
                    val failure = assertFailsWith<ContextRetrievalException> { call() }
                    assertEquals(ContextRetrievalErrorCode.INVALID_REQUEST, failure.errorCode)
                }
                assertNull(context.getMemory("missing"))
                assertNull(context.getRawMemory("missing"))
            } finally {
                context.close()
            }
        }

    @Test
    fun `database query failure is preserved instead of being converted to null`() =
        wiringTest { fixture ->
            seedRetrievalData(fixture)
            val context = AlcyoneContextFactory.create(retrievalContextConfig(fixture, llm = null), fixture.vfsConfig())
            try {
                context.start()
                executeSearchSql(fixture.contextPath, "ALTER TABLE organized_memory RENAME TO organized_memory_ct20_broken")
                assertFailsWith<ContextPersistenceException> { context.getMemory("O-1") }
                assertEquals("raw content", context.getRawMemory("R-1")?.content)
            } finally {
                context.close()
            }
        }

    @Test
    fun `retrieval config maxLimit is applied by real Impl`() =
        wiringTest { fixture ->
            seedRetrievalData(fixture)
            val stub =
                ScriptedHttpStub(
                    listOf(
                        ScriptedReply(200, chatCompletionBody("""{"candidate_ids":["O-1"]}""")),
                        ScriptedReply(200, chatCompletionBody("""{"memory_ids":["O-1"]}""")),
                    ),
                )
            val context =
                AlcyoneContextFactory.create(
                    retrievalContextConfig(fixture, retrievalLlm(stub), maxLimit = 1),
                    fixture.vfsConfig(),
                )
            try {
                context.start()
                val failure = assertFailsWith<ContextRetrievalException> { context.searchMemory("query", limit = 2) }
                assertEquals(ContextRetrievalErrorCode.INVALID_REQUEST, failure.errorCode)
                assertEquals(0, stub.requestCount())
                assertEquals(listOf("O-1"), context.searchMemory("query", limit = 1).map { it.id })
            } finally {
                context.close()
                stub.close()
            }
        }

    private fun wiringTest(block: suspend (ContextFixture) -> Unit) {
        ContextFixture().use { fixture -> runBlocking { block(fixture) } }
    }

    private suspend fun seedRetrievalData(fixture: ContextFixture) {
        fixture.initialize()
        val executor = fixture.open()
        try {
            val database = ContextDatabaseImpl(executor)
            database.write { tx ->
                assertEquals(
                    1,
                    tx.organizedMemories.insertNew(
                        OrganizedMemory(
                            id = "O-1",
                            content = "organized content",
                            domains = listOf("domain"),
                            rawMemoryIds = listOf("R-1"),
                            createdAt = now,
                            updatedAt = now.plusSeconds(1),
                        ),
                    ),
                )
                assertEquals(
                    1,
                    tx.rawMemories.insertBatch(
                        listOf(
                            RawMemory(
                                id = "R-1",
                                content = "raw content",
                                generatedBy = MemoryOrigin.EXTRACTION,
                                sources = emptyList(),
                                createdAt = now,
                            ),
                        ),
                    ),
                )
            }
        } finally {
            executor.close()
        }
    }

    private fun retrievalContextConfig(
        fixture: ContextFixture,
        llm: LlmConfig?,
        maxLimit: Int = 50,
    ): ContextConfig =
        fixture
            .config()
            .copy(
                llm = llm,
                extraction = ExtractionConfig(idleEnabled = false),
                retrieval = RetrievalConfig(SearchConfig(maxLimit)),
            )

    private fun retrievalLlm(
        stub: ScriptedHttpStub,
        mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        includeTask: Boolean = true,
    ): LlmConfig {
        val profile =
            LlmProfileConfig(
                name = "search",
                baseUrl = stub.baseUrl,
                apiKey = "ct20-b2-safe-key-marker",
                model = "fake-search",
                contextWindowTokens = 64_000,
                jsonMode = mode,
                maxAttempts = 1,
            )
        return LlmConfig(
            profiles = mapOf(profile.name to profile),
            tasks =
                if (includeTask) {
                    mapOf(LlmTask.MEMORY_SEARCH to LlmTaskConfig("search", 32_000, 4_000))
                } else {
                    emptyMap()
                },
            safetyMarginPercent = 0,
        )
    }
}
