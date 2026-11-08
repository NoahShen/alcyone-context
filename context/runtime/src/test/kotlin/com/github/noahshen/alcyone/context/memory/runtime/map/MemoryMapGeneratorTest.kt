package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapRenderer
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapSnapshotException
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryReadOps
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class MemoryMapGeneratorTest {
    private val base: Instant = Instant.parse("2026-11-08T01:00:00Z")
    private val generatedAt: Instant = Instant.parse("2026-11-08T03:00:00Z")
    private val clock: Clock = Clock.fixed(generatedAt, ZoneOffset.UTC)

    @Test
    fun `an empty database calls no llm and renders the deterministic empty map`() =
        withDatabase { database ->
            val llm = FakeLlmClient { _, _ -> fail("LLM must not be called for an empty database") }
            val artifact = generator(database, llm).generate()

            assertEquals(0, llm.calls)
            assertEquals(emptyList(), artifact.document.domains)
            assertNull(artifact.document.sourceUpdatedAt)
            assertEquals(generatedAt, artifact.document.generatedAt)
            val parsed = MemoryMapRenderer.parse(artifact.renderedBytes)
            assertNull(parsed.sourceUpdatedAt)
            assertTrue(parsed.overview.isNotBlank())
            assertTrue(parsed.navigation.isNotBlank())
        }

    @Test
    fun `more than one page is read completely sorted by id with source T1`() =
        withDatabase { database ->
            insert(database, "O-MEM-0003", "three", listOf("finance"), base.plusSeconds(3))
            insert(database, "O-MEM-0001", "one", listOf("work"), base.plusSeconds(1))
            insert(database, "O-MEM-0002", "two", listOf("work"), base.plusSeconds(2))
            insert(database, "O-MEM-0004", "four", listOf("finance"), base.plusSeconds(4))
            insert(database, "O-MEM-0005", "five", listOf("preferences"), base.plusSeconds(5))
            val llm = FakeLlmClient { _, _ -> validResponse() }
            val artifact = generator(database, llm, pageSize = 2).generate()

            assertEquals(1, llm.calls)
            assertEquals(1_000, llm.requests.single().maxOutputTokens)
            assertNull(llm.requests.single().schema)
            assertEquals(
                listOf("O-MEM-0001", "O-MEM-0002", "O-MEM-0003", "O-MEM-0004", "O-MEM-0005"),
                idsInPrompt(llm.requests.single()),
            )
            assertEquals(base.plusSeconds(5), artifact.document.sourceUpdatedAt)
            assertEquals(listOf("finance", "preferences", "work"), artifact.document.domains)
            Unit
        }

    @Test
    fun `an exact page multiple requires the terminating empty page and includes every row once`() =
        withDatabase { database ->
            repeat(
                4,
            ) { index ->
                insert(
                    database,
                    "O-MEM-%04d".format(index + 1),
                    "content-$index",
                    listOf("work"),
                    base.plusSeconds(index.toLong()),
                )
            }
            val llm = FakeLlmClient { _, _ -> validResponse() }
            val countingPages = PageCountingDatabase(database)

            generator(countingPages, llm, pageSize = 2).generate()

            assertEquals(3, countingPages.pageReads.get(), "two full pages require a final empty-page confirmation")
            assertEquals(4, idsInPrompt(llm.requests.single()).size)
            assertEquals(listOf("O-MEM-0001", "O-MEM-0002", "O-MEM-0003", "O-MEM-0004"), idsInPrompt(llm.requests.single()))
        }

    @Test
    fun `a write during llm does not change the captured snapshot or T1 and read context is released`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "old", listOf("work"), base)
            val counting = CountingDatabase(database)
            val llm =
                FakeLlmClient { request, _ ->
                    insert(database, "O-MEM-0002", "new", listOf("finance"), base.plusSeconds(30))
                    validResponse()
                }
            val artifact = generator(counting, llm).generate()

            assertEquals(1, counting.reads.get())
            assertEquals(listOf("O-MEM-0001"), idsInPrompt(llm.requests.single()))
            assertEquals(base, artifact.document.sourceUpdatedAt)
            assertEquals(listOf("work"), artifact.document.domains)
            assertEquals(2, countOrganized(database))
        }

    @Test
    fun `fixed input budget overrun fails before llm`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "content", listOf("work"), base)
            val llm = FakeLlmClient { _, _ -> fail("budget failure must not call LLM") }
            val config = config(maxInput = 100, maxOutput = 20, contextWindow = 120, margin = 0)
            val failure =
                assertFailsWith<LlmException> {
                    generator(database, llm, config = config, estimator = FixedEstimator(promptTokens = 101)).generate()
                }

            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(0, llm.calls)
        }

    @Test
    fun `safety margin overrun fails before llm while the exact boundary succeeds`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "content", listOf("work"), base)
            val overrunLlm = FakeLlmClient { _, _ -> fail("margin overrun must not call LLM") }
            val overrunConfig = config(maxInput = 100, maxOutput = 20, contextWindow = 120, margin = 20)
            assertFailsWith<LlmException> {
                generator(database, overrunLlm, config = overrunConfig, estimator = FixedEstimator(100)).generate()
            }
            assertEquals(0, overrunLlm.calls)

            val boundaryLlm = FakeLlmClient { _, _ -> validResponse() }
            val boundaryConfig = config(maxInput = 80, maxOutput = 20, contextWindow = 100, margin = 0)
            generator(database, boundaryLlm, config = boundaryConfig, estimator = FixedEstimator(80)).generate()
            assertEquals(1, boundaryLlm.calls)
        }

    @Test
    fun `finish reason length is output truncated and never parsed`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "content", listOf("work"), base)
            val llm = FakeLlmClient { _, _ -> LlmResponse("""{"overview":"o","navigation":"n","domains":[]}""", null, "length") }

            val failure = assertFailsWith<LlmException> { generator(database, llm).generate() }

            assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
        }

    @Test
    fun `validator invalid response is mapped to INVALID_RESPONSE`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "content", listOf("work"), base)
            val llm =
                FakeLlmClient { _, _ ->
                    LlmResponse("""{"overview":"o","navigation":"n","domains":["invented"]}""", null, "stop")
                }

            val failure = assertFailsWith<LlmException> { generator(database, llm).generate() }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        }

    @Test
    fun `snapshot max mismatch is an internal snapshot error with zero llm`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "content", listOf("work"), base)
            val mismatched = MismatchedMaxDatabase(database, base.plusSeconds(10))
            val llm = FakeLlmClient { _, _ -> fail("snapshot mismatch must not call LLM") }

            assertFailsWith<MemoryMapSnapshotException> { generator(mismatched, llm).generate() }
            assertEquals(0, llm.calls)
        }

    @Test
    fun `database failure remains the original persistence error with zero llm`() =
        withDatabase { database ->
            val expected = ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, "db failed")
            val failing =
                object : ContextDatabase {
                    override suspend fun <T> read(block: (ReadContext) -> T): T = throw expected

                    override suspend fun <T> write(block: (TransactionContext) -> T): T = database.write(block)
                }
            val llm = FakeLlmClient { _, _ -> fail("db failure must not call LLM") }

            val actual = assertFailsWith<ContextPersistenceException> { generator(failing, llm).generate() }
            assertSame(expected, actual)
            assertEquals(0, llm.calls)
        }

    @Test
    fun `llm cancellation is propagated unchanged`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "content", listOf("work"), base)
            val cancellation = CancellationException("ct18-map-cancel")
            val llm = FakeLlmClient { _, _ -> throw cancellation }

            val actual = assertFailsWith<CancellationException> { generator(database, llm).generate() }
            assertSame(cancellation, actual)
        }

    @Test
    fun `llm network failure remains the original LlmException`() =
        withDatabase { database ->
            insert(database, "O-MEM-0001", "content", listOf("work"), base)
            val expected = LlmException(LlmErrorCode.NETWORK, "network failed")
            val llm = FakeLlmClient { _, _ -> throw expected }

            val actual = assertFailsWith<LlmException> { generator(database, llm).generate() }
            assertSame(expected, actual)
        }

    @Test
    fun `paging loop observes cancellation before another page is read`() =
        withDatabase { database ->
            repeat(
                6,
            ) { index -> insert(database, "O-MEM-%04d".format(index + 1), "content", listOf("work"), base.plusSeconds(index.toLong())) }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val generator = generator(database, FakeLlmClient { _, _ -> fail("cancelled paging must not call LLM") }, pageSize = 2)
            generator.betweenPagesForTesting = {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "paging gate must be bounded")
            }
            runBlocking {
                val job = launch(Dispatchers.Default) { generator.generate() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                job.cancel()
                release.countDown()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        }

    @Test
    fun `missing MEMORY_MAP task and prompt only profile fail with invalid config`() {
        val noTask =
            assertFailsWith<ContextConfigException> {
                MemoryMapGenerator(
                    database = NoopDatabase,
                    llmClient = FakeLlmClient { _, _ -> validResponse() },
                    llmConfig = LlmConfig(profiles = mapOf("map" to profile()), tasks = emptyMap()),
                )
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, noTask.errorCode)

        val promptOnly =
            assertFailsWith<ContextConfigException> {
                MemoryMapGenerator(
                    database = NoopDatabase,
                    llmClient = FakeLlmClient { _, _ -> validResponse() },
                    llmConfig = config(jsonMode = LlmResponseFormat.PROMPT_ONLY),
                )
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, promptOnly.errorCode)

        val unknownProfile =
            assertFailsWith<ContextConfigException> {
                LlmConfig(
                    profiles = mapOf("map" to profile()),
                    tasks =
                        mapOf(
                            LlmTask.MEMORY_MAP to
                                LlmTaskConfig(
                                    profile = "missing",
                                    maxInputTokens = 100,
                                    maxOutputTokens = 20,
                                ),
                        ),
                )
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, unknownProfile.errorCode)
    }

    private fun generator(
        database: ContextDatabase,
        llm: LlmClient,
        pageSize: Int = 256,
        config: LlmConfig = config(),
        estimator: TokenEstimator = FixedEstimator(promptTokens = 10),
    ): MemoryMapGenerator = MemoryMapGenerator(database, llm, config, clock, estimator, pageSize)

    private fun config(
        maxInput: Int = 10_000,
        maxOutput: Int = 1_000,
        contextWindow: Int = 20_000,
        margin: Int = 20,
        jsonMode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
    ): LlmConfig =
        LlmConfig(
            profiles = mapOf("map" to profile(contextWindow, jsonMode)),
            tasks =
                mapOf(
                    LlmTask.MEMORY_MAP to
                        LlmTaskConfig(
                            profile = "map",
                            maxInputTokens = maxInput,
                            maxOutputTokens = maxOutput,
                        ),
                ),
            safetyMarginPercent = margin,
        )

    private fun profile(
        contextWindow: Int = 20_000,
        jsonMode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = "map",
            baseUrl = "http://127.0.0.1:1",
            apiKey = "test-key",
            model = "fake-map",
            contextWindowTokens = contextWindow,
            jsonMode = jsonMode,
            maxAttempts = 1,
        )

    private fun validResponse(): LlmResponse =
        LlmResponse("""{"overview":"overview","navigation":"navigation","domains":["work"]}""", null, "stop")

    private suspend fun insert(
        database: ContextDatabase,
        id: String,
        content: String,
        domains: List<String>,
        updatedAt: Instant,
    ) {
        database.write { tx ->
            tx.organizedMemories.insertNew(
                OrganizedMemory(
                    id = id,
                    content = content,
                    domains = domains,
                    rawMemoryIds = emptyList(),
                    createdAt = base,
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    private suspend fun countOrganized(database: ContextDatabase): Long = database.read { read -> countPages(read.organizedMemories) }

    private fun countPages(ops: OrganizedMemoryReadOps): Long {
        var offset = 0
        var count = 0L
        while (true) {
            val page = ops.scanPage(offset, 256)
            if (page.isEmpty()) return count
            count += page.size
            if (page.size < 256) return count
            offset += page.size
        }
    }

    private fun idsInPrompt(request: LlmRequest): List<String> =
        Regex("<memory id=\"([^\"]+)\"")
            .findAll(request.messages.last().content)
            .map { match -> match.groupValues[1] }
            .toList()

    private fun withDatabase(block: suspend (ContextDatabase) -> Unit) {
        ContextFixture().use { fixture ->
            fixture.initialize()
            fixture.open().use { executor ->
                runBlocking { block(ContextDatabaseImpl(executor)) }
            }
        }
    }

    private class FakeLlmClient(
        private val respond: suspend (LlmRequest, Int) -> LlmResponse,
    ) : LlmClient {
        val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()
        val calls: Int get() = requests.size

        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return respond(request, requests.size)
        }
    }

    private class FixedEstimator(
        private val promptTokens: Int,
    ) : TokenEstimator {
        override fun estimateText(text: String): Int = 1

        override fun estimatePrompt(messages: List<com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage>): Int = promptTokens

        override fun estimateSchema(schema: String?): Int = 0
    }

    private class CountingDatabase(
        private val delegate: ContextDatabase,
    ) : ContextDatabase {
        val reads = AtomicInteger(0)

        override suspend fun <T> read(block: (ReadContext) -> T): T =
            delegate.read { read ->
                reads.incrementAndGet()
                block(read)
            }

        override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)
    }

    private class MismatchedMaxDatabase(
        private val delegate: ContextDatabase,
        private val mismatchedMax: Instant,
    ) : ContextDatabase {
        override suspend fun <T> read(block: (ReadContext) -> T): T =
            delegate.read { read ->
                val wrapped =
                    object : ReadContext by read {
                        override val organizedMemories: OrganizedMemoryReadOps =
                            object : OrganizedMemoryReadOps by read.organizedMemories {
                                override fun maxUpdatedAt(): Instant = mismatchedMax
                            }
                    }
                block(wrapped)
            }

        override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)
    }

    private class PageCountingDatabase(
        private val delegate: ContextDatabase,
    ) : ContextDatabase {
        val pageReads = AtomicInteger(0)

        override suspend fun <T> read(block: (ReadContext) -> T): T =
            delegate.read { read ->
                val wrapped =
                    object : ReadContext by read {
                        override val organizedMemories: OrganizedMemoryReadOps =
                            object : OrganizedMemoryReadOps by read.organizedMemories {
                                override fun scanPage(
                                    offset: Int,
                                    limit: Int,
                                ): List<OrganizedMemory> {
                                    pageReads.incrementAndGet()
                                    return read.organizedMemories.scanPage(offset, limit)
                                }
                            }
                    }
                block(wrapped)
            }

        override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)
    }

    private object NoopDatabase : ContextDatabase {
        override suspend fun <T> read(block: (ReadContext) -> T): T = error("not used")

        override suspend fun <T> write(block: (TransactionContext) -> T): T = error("not used")
    }
}
