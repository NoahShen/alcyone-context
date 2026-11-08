package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemorySearchServiceTest {
    @Test
    fun `full scan uses id ascending batches and authoritative rerank order`() =
        withSearchDatabase { fixture ->
            listOf("E", "B", "D", "A", "C").forEach { insertSearchMemory(fixture.database, it) }
            val tracking = TrackingSearchDatabase(fixture.database)
            val llm = FakeSearchLlmClient()
            val service = service(tracking, llm, pageSize = 2, estimator = CountingSearchEstimator(0, 0))

            val result = service.search("query")

            assertEquals(listOf("E", "D", "C", "B", "A"), result.map { it.id })
            assertEquals(listOf(0, 2, 4), tracking.scanOffsets)
            assertEquals(listOf(listOf("E", "D", "C", "B", "A")), tracking.findByIdsCalls)
            assertEquals(listOf("A", "B", "C", "D", "E"), requestCandidateIds(llm.requests.first()))
        }

    @Test
    fun `exact page multiple performs a final empty page`() =
        withSearchDatabase { fixture ->
            listOf("D", "B", "C", "A").forEach { insertSearchMemory(fixture.database, it) }
            val tracking = TrackingSearchDatabase(fixture.database)
            val service = service(tracking, FakeSearchLlmClient(), pageSize = 2, estimator = CountingSearchEstimator(0, 0))

            service.search("query")

            assertEquals(listOf(0, 2, 4), tracking.scanOffsets)
        }

    @Test
    fun `domain null empty single multi and trimmed deduplicated filters are exact and case sensitive`() =
        withSearchDatabase { fixture ->
            insertSearchMemory(fixture.database, "A", domains = listOf("Work"))
            insertSearchMemory(fixture.database, "B", domains = listOf("finance"))
            insertSearchMemory(fixture.database, "C", domains = listOf("work", "shared"))

            assertEquals(listOf("C", "B", "A"), search(fixture, domains = null))
            assertEquals(listOf("C", "B", "A"), search(fixture, domains = emptyList()))
            assertEquals(listOf("C"), search(fixture, domains = listOf("work")))
            assertEquals(listOf("C", "B"), search(fixture, domains = listOf("finance", "shared")))
            assertEquals(listOf("C"), search(fixture, domains = listOf(" work ", "work")))
            assertEquals(emptyList(), search(fixture, domains = listOf("WORK")))
        }

    @Test
    fun `query is trimmed and sent in scanner and rerank messages`() =
        withSearchDatabase { fixture ->
            insertSearchMemory(fixture.database, "A")
            insertSearchMemory(fixture.database, "B")
            val llm = FakeSearchLlmClient()
            val service = service(fixture.database, llm, estimator = CountingSearchEstimator(0, 0))

            val result = service.search("  hello world  ", limit = 1)

            assertEquals(listOf("B"), result.map { it.id })
            assertTrue(
                llm.requests.all { request ->
                    request.messages
                        .last()
                        .content
                        .contains("<search_query>\nhello world\n</search_query>")
                },
            )
        }

    @Test
    fun `blank query blank domain and invalid limit fail before database or llm`() =
        withSearchDatabase { fixture ->
            insertSearchMemory(fixture.database, "A")
            val tracking = TrackingSearchDatabase(fixture.database)
            val llm = FakeSearchLlmClient()
            val service = service(tracking, llm, maxLimit = 2)

            val calls: List<suspend () -> Unit> =
                listOf(
                    { service.search("   ") },
                    { service.search("q", domains = listOf(" ")) },
                    { service.search("q", limit = 0) },
                    { service.search("q", limit = 3) },
                )
            calls.forEach { call ->
                val failure = runSuspend { call() }
                assertEquals(ContextRetrievalErrorCode.INVALID_REQUEST, failure.errorCode)
            }
            assertEquals(0, tracking.readCalls.get())
            assertEquals(0, llm.requests.size)
        }

    @Test
    fun `invalid max limit fails with configuration`() =
        withSearchDatabase { fixture ->
            listOf(0, -1, 1_001).forEach { maxLimit ->
                val failure =
                    assertFailsWith<ContextRetrievalException> {
                        service(fixture.database, FakeSearchLlmClient(), maxLimit = maxLimit)
                    }
                assertEquals(ContextRetrievalErrorCode.CONFIGURATION, failure.errorCode)
            }
        }

    @Test
    fun `empty database and no domain match return empty with zero llm`() =
        withSearchDatabase { fixture ->
            val emptyLlm = FakeSearchLlmClient()
            assertEquals(emptyList(), service(fixture.database, emptyLlm).search("query"))
            assertEquals(0, emptyLlm.requests.size)

            insertSearchMemory(fixture.database, "A", domains = listOf("work"))
            val noMatchLlm = FakeSearchLlmClient()
            assertEquals(emptyList(), service(fixture.database, noMatchLlm).search("query", domains = listOf("finance")))
            assertEquals(0, noMatchLlm.requests.size)
        }

    @Test
    fun `dynamic planner creates serial scanner batches with only their own candidates`() =
        withSearchDatabase { fixture ->
            listOf("A", "B", "C").forEach { insertSearchMemory(fixture.database, it) }
            val llm = FakeSearchLlmClient()
            val service =
                service(
                    fixture.database,
                    llm,
                    estimator = CountingSearchEstimator(baseTokens = 10, itemTokens = 60, rerankBaseTokens = 0, rerankItemTokens = 0),
                    config = searchLlmConfig(maxInputTokens = 100),
                )

            service.search("query")

            val scannerRequests =
                llm.requests.filter {
                    !it.messages
                        .last()
                        .content
                        .contains("<limit>")
                }
            assertEquals(3, scannerRequests.size)
            assertEquals(listOf(listOf("A"), listOf("B"), listOf("C")), scannerRequests.map(::requestCandidateIds))
            assertEquals(
                1,
                llm.requests.count {
                    it.messages
                        .last()
                        .content
                        .contains("<limit>")
                },
            )
        }

    @Test
    fun `fixed overhead and single item over budget fail with zero llm`() =
        withSearchDatabase { fixture ->
            insertSearchMemory(fixture.database, "A")
            listOf(
                CountingSearchEstimator(baseTokens = 101, itemTokens = 0),
                CountingSearchEstimator(baseTokens = 50, itemTokens = 60),
            ).forEach { estimator ->
                val llm = FakeSearchLlmClient()
                val service = service(fixture.database, llm, estimator = estimator, config = searchLlmConfig(maxInputTokens = 100))
                val failure = assertFailsWith<LlmException> { service.search("query") }
                assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
                assertEquals(0, llm.requests.size)
            }
        }

    @Test
    fun `exact input budget equality is accepted`() =
        withSearchDatabase { fixture ->
            insertSearchMemory(fixture.database, "A")
            val llm = FakeSearchLlmClient()
            val service =
                service(
                    fixture.database,
                    llm,
                    estimator = CountingSearchEstimator(baseTokens = 40, itemTokens = 60),
                    config = searchLlmConfig(maxInputTokens = 100),
                )

            service.search("query")

            assertEquals(2, llm.requests.size)
        }

    @Test
    fun `invalid json truncation and invalid batch id stop after the failing batch`() =
        withSearchDatabase { fixture ->
            listOf("A", "B", "C").forEach { insertSearchMemory(fixture.database, it) }
            val behaviors =
                listOf<suspend (com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest, Int) -> LlmResponse>(
                    { _, _ -> LlmResponse("not-json", null, "stop") },
                    { _, _ -> LlmResponse("{}", null, "length") },
                    { _, _ -> LlmResponse(scannerResponse(listOf("UNKNOWN")), null, "stop") },
                )
            behaviors.forEach { behavior ->
                val llm = FakeSearchLlmClient(behavior)
                val service =
                    service(
                        fixture.database,
                        llm,
                        estimator = CountingSearchEstimator(10, 60),
                        config = searchLlmConfig(maxInputTokens = 100),
                    )
                val failure = assertFailsWith<LlmException> { service.search("query") }
                assertTrue(failure.errorCode in setOf(LlmErrorCode.INVALID_RESPONSE, LlmErrorCode.OUTPUT_TRUNCATED))
                assertEquals(1, llm.requests.size)
            }
        }

    @Test
    fun `cross batch duplicate selected id is deduplicated before global rerank`() =
        withSearchDatabase { fixture ->
            val duplicateA = memory("X", "first")
            val duplicateB = memory("X", "second")
            val tracking = TrackingSearchDatabase(fixture.database, snapshotOverride = listOf(duplicateA, duplicateB))
            val llm =
                FakeSearchLlmClient { request, _ ->
                    if (request.messages
                            .last()
                            .content
                            .contains("<limit>")
                    ) {
                        LlmResponse(rerankResponse(listOf("X")), null, "stop")
                    } else {
                        LlmResponse(scannerResponse(listOf("X")), null, "stop")
                    }
                }
            val service =
                service(
                    tracking,
                    llm,
                    pageSize = 1,
                    estimator = CountingSearchEstimator(10, 60),
                    config = searchLlmConfig(maxInputTokens = 100),
                )

            val result = service.search("query")

            assertEquals(listOf("X"), result.map { it.id })
            val rerank =
                llm.requests.single {
                    it.messages
                        .last()
                        .content
                        .contains("<limit>")
                }
            assertEquals(listOf("X"), requestCandidateIds(rerank))
        }

    @Test
    fun `all nonempty candidates rerank once even when count is below limit`() =
        withSearchDatabase { fixture ->
            listOf("A", "B").forEach { insertSearchMemory(fixture.database, it) }
            val llm = FakeSearchLlmClient()
            val service = service(fixture.database, llm, estimator = CountingSearchEstimator(0, 0))

            service.search("query", limit = 50)

            val rerankRequests =
                llm.requests.filter {
                    it.messages
                        .last()
                        .content
                        .contains("<limit>")
                }
            assertEquals(1, rerankRequests.size)
            assertEquals(listOf("A", "B"), requestCandidateIds(rerankRequests.single()))
        }

    @Test
    fun `rerank global order is truncated by limit and final read follows rerank ids`() =
        withSearchDatabase { fixture ->
            listOf("D", "C", "B", "A").forEach { insertSearchMemory(fixture.database, it) }
            val tracking = TrackingSearchDatabase(fixture.database)
            val service = service(tracking, FakeSearchLlmClient(), estimator = CountingSearchEstimator(0, 0))

            val result = service.search("query", limit = 2)

            assertEquals(listOf("D", "C"), result.map { it.id })
            assertEquals(listOf(listOf("D", "C")), tracking.findByIdsCalls)
        }

    @Test
    fun `empty rerank array is legal and avoids authoritative read`() =
        withSearchDatabase { fixture ->
            listOf("A", "B").forEach { insertSearchMemory(fixture.database, it) }
            val tracking = TrackingSearchDatabase(fixture.database)
            val llm =
                FakeSearchLlmClient { request, _ ->
                    if (request.messages
                            .last()
                            .content
                            .contains("<limit>")
                    ) {
                        LlmResponse(rerankResponse(emptyList()), null, "stop")
                    } else {
                        defaultSearchResponse(request)
                    }
                }
            val service = service(tracking, llm, estimator = CountingSearchEstimator(0, 0))

            assertEquals(emptyList(), service.search("query"))
            assertEquals(emptyList(), tracking.findByIdsCalls)
        }

    @Test
    fun `rerank unknown duplicate and blank ids are invalid responses`() =
        withSearchDatabase { fixture ->
            listOf("A", "B").forEach { insertSearchMemory(fixture.database, it) }
            listOf(listOf("C"), listOf("A", "A"), listOf(" ")).forEach { invalidIds ->
                val llm =
                    FakeSearchLlmClient { request, _ ->
                        if (request.messages
                                .last()
                                .content
                                .contains("<limit>")
                        ) {
                            LlmResponse(rerankResponse(invalidIds), null, "stop")
                        } else {
                            defaultSearchResponse(request)
                        }
                    }
                val failure =
                    assertFailsWith<LlmException> {
                        service(fixture.database, llm, estimator = CountingSearchEstimator(0, 0)).search("query")
                    }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            }
        }

    @Test
    fun `rerank over budget fails before rerank llm call and does not truncate candidates`() =
        withSearchDatabase { fixture ->
            listOf("A", "B").forEach { insertSearchMemory(fixture.database, it) }
            val llm = FakeSearchLlmClient()
            val service =
                service(
                    fixture.database,
                    llm,
                    estimator = CountingSearchEstimator(0, 60),
                    config = searchLlmConfig(maxInputTokens = 100),
                )

            val failure = assertFailsWith<LlmException> { service.search("query") }

            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(2, llm.requests.size, "两次单候选扫描后，Rerank 预算失败，不应发起 Rerank")
            assertTrue(
                llm.requests.none {
                    it.messages
                        .last()
                        .content
                        .contains("<limit>")
                },
            )
        }

    @Test
    fun `missing authoritative rerank id returns unavailable without partial results`() =
        withSearchDatabase { fixture ->
            listOf("A", "B").forEach { insertSearchMemory(fixture.database, it) }
            val llm =
                FakeSearchLlmClient { request, _ ->
                    if (request.messages
                            .last()
                            .content
                            .contains("<limit>")
                    ) {
                        executeSearchSql(fixture.path, "DELETE FROM organized_memory WHERE id = 'B'")
                        LlmResponse(rerankResponse(listOf("B", "A")), null, "stop")
                    } else {
                        defaultSearchResponse(request)
                    }
                }
            val service = service(fixture.database, llm, estimator = CountingSearchEstimator(0, 0))

            val failure = assertFailsWith<ContextRetrievalException> { service.search("query") }
            assertEquals(ContextRetrievalErrorCode.UNAVAILABLE, failure.errorCode)
        }

    @Test
    fun `database failure is preserved and no llm is called`() =
        withSearchDatabase { fixture ->
            val expected = persistenceFailure()
            val tracking = TrackingSearchDatabase(fixture.database, readFailure = expected)
            val llm = FakeSearchLlmClient()

            val actual =
                assertFailsWith<com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException> {
                    service(tracking, llm).search("query")
                }
            assertSame(expected, actual)
            assertEquals(0, llm.requests.size)
        }

    @Test
    fun `llm exception and cancellation propagate as the same instance`() =
        withSearchDatabase { fixture ->
            insertSearchMemory(fixture.database, "A")
            val llmFailure = LlmException(LlmErrorCode.PROVIDER_UNAVAILABLE, "ct20-llm-failure")
            val llmService =
                service(
                    fixture.database,
                    FakeSearchLlmClient {
                        _,
                        _,
                        ->
                        throw llmFailure
                    },
                    estimator = CountingSearchEstimator(0, 0),
                )
            assertSame(llmFailure, assertFailsWith<LlmException> { llmService.search("query") })

            val cancellation = CancellationException("ct20-llm-cancel")
            val cancelService =
                service(
                    fixture.database,
                    FakeSearchLlmClient {
                        _,
                        _,
                        ->
                        throw cancellation
                    },
                    estimator = CountingSearchEstimator(0, 0),
                )
            assertSame(cancellation, assertFailsWith<CancellationException> { cancelService.search("query") })
        }

    @Test
    fun `page cancellation propagates as the same instance`() =
        withSearchDatabase { fixture ->
            listOf("A", "B").forEach { insertSearchMemory(fixture.database, it) }
            val cancellation = CancellationException("ct20-page-cancel-instance")
            val tracking = TrackingSearchDatabase(fixture.database, readFailure = cancellation)
            val llm = FakeSearchLlmClient()

            val actual =
                assertFailsWith<CancellationException> {
                    service(tracking, llm, pageSize = 1).search("query")
                }
            assertSame(cancellation, actual)
            assertEquals(0, llm.requests.size)
        }

    @Test
    fun `cancellation between pages stops before any llm call`() =
        withSearchDatabase { fixture ->
            listOf("A", "B").forEach { insertSearchMemory(fixture.database, it) }
            val tracking =
                TrackingSearchDatabase(
                    fixture.database,
                    pageCancellationAt = 1,
                    pageCancellation = CancellationException("ct20-between-pages"),
                )
            val llm = FakeSearchLlmClient()

            assertFailsWith<CancellationException> {
                service(tracking, llm, pageSize = 1).search("query")
            }
            assertEquals(0, llm.requests.size)
        }

    @Test
    fun `missing binding fails at search invocation before database read`() =
        withSearchDatabase { fixture ->
            val tracking = TrackingSearchDatabase(fixture.database)
            val llm = FakeSearchLlmClient()
            val service = service(tracking, llm, config = searchLlmConfig(includeTask = false))

            val failure = assertFailsWith<ContextRetrievalException> { service.search("query") }

            assertEquals(ContextRetrievalErrorCode.CONFIGURATION, failure.errorCode)
            assertEquals(0, tracking.readCalls.get())
            assertEquals(0, llm.requests.size)
        }

    @Test
    fun `JSON object mode sends null schema and JSON schema mode sends resources`() =
        withSearchDatabase { fixture ->
            insertSearchMemory(fixture.database, "A")
            listOf(LlmResponseFormat.JSON_OBJECT, LlmResponseFormat.JSON_SCHEMA).forEach { mode ->
                val llm = FakeSearchLlmClient()
                service(
                    fixture.database,
                    llm,
                    estimator = CountingSearchEstimator(0, 0),
                    config = searchLlmConfig(mode = mode),
                ).search("query")
                if (mode == LlmResponseFormat.JSON_OBJECT) {
                    assertTrue(llm.requests.all { it.schema == null })
                } else {
                    assertTrue(llm.requests.all { !it.schema.isNullOrBlank() })
                }
            }
        }

    private suspend fun search(
        fixture: SearchDatabaseFixture,
        domains: List<String>?,
    ): List<String> =
        service(fixture.database, FakeSearchLlmClient(), estimator = CountingSearchEstimator(0, 0))
            .search("query", domains)
            .map { it.id }

    private fun service(
        database: com.github.noahshen.alcyone.context.memory.core.ContextDatabase,
        llm: FakeSearchLlmClient,
        estimator: CountingSearchEstimator = CountingSearchEstimator(0, 0),
        pageSize: Int = 256,
        maxLimit: Int = 50,
        config: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig = searchLlmConfig(),
    ): MemorySearchService =
        MemorySearchService(
            database = database,
            llmClient = llm,
            llmConfig = config,
            maxLimit = maxLimit,
            estimator = estimator,
            pageSize = pageSize,
        )

    private fun memory(
        id: String,
        content: String,
    ): OrganizedMemory =
        OrganizedMemory(
            id = id,
            content = content,
            domains = listOf("domain"),
            rawMemoryIds = emptyList(),
            createdAt = SEARCH_BASE,
            updatedAt = SEARCH_BASE.plusSeconds(1),
        )

    private fun runSuspend(block: suspend () -> Unit): ContextRetrievalException =
        kotlinx.coroutines.runBlocking {
            assertFailsWith<ContextRetrievalException> { block() }
        }
}
