package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class LlmDreamingEvidenceCollectorTest {
    @Test
    fun `empty snapshots skip all llm and map new turns in batch order`() =
        withDreamingDatabase { fixture ->
            val batch = listOf(evidenceTurn(1), evidenceTurn(2))
            val client = FakeEvidenceLlmClient { _, _ -> fail("empty snapshots must not call llm") }
            val collector = collector(fixture.database, client)
            val result = collector.collect(batch, listOf(evidenceTopic(sourceTurnIds = listOf(2, 1))))

            assertEquals(1, result.size)
            assertEquals(listOf(1L, 2L), result.single().newConversationTurns.map { it.id })
            assertEquals(emptyList(), result.single().organizedMemories)
            assertEquals(emptyList(), result.single().rawMemories)
            assertEquals(
                com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision.NEEDS_EVIDENCE,
                result.single().decision,
            )
            assertEquals(0, client.requests.size)
        }

    @Test
    fun `organized hit needs novelty then scans full raw and preserves double origin`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1", MemoryOrigin.EXTRACTION))
            insertEvidenceRaw(fixture.database, evidenceRaw("R-2", MemoryOrigin.DREAMING))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", listOf("R-1")))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    when (request.task) {
                        LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        LlmTask.DREAMING_EVIDENCE -> {
                            if (request.messages
                                    .last()
                                    .content
                                    .contains("<organized count=")
                            ) {
                                evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                            } else {
                                evidenceResponse(candidateIdsResponse(listOf("R-1", "R-2")))
                            }
                        }
                        else -> fail("unexpected task ${request.task}")
                    }
                }
            val result = collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(listOf("O-1"), result.single().organizedMemories.map { it.id })
            assertEquals(listOf("R-1", "R-2"), result.single().rawMemories.map { it.id })
            assertEquals(listOf(MemoryOrigin.EXTRACTION, MemoryOrigin.DREAMING), result.single().rawMemories.map { it.generatedBy })
            assertEquals(3, client.requests.size)
        }

    @Test
    fun `fully covered skips raw scan and keeps organized evidence`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", listOf("R-1")))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    when (request.task) {
                        LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        LlmTask.DREAMING_EVIDENCE -> evidenceResponse(noveltyResponse("FULLY_COVERED", listOf("O-1")))
                        else -> fail("unexpected task ${request.task}")
                    }
                }
            val result = collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(listOf("O-1"), result.single().organizedMemories.map { it.id })
            assertEquals(emptyList(), result.single().rawMemories)
            assertEquals(2, client.requests.size)
            assertFalse(
                client.requests.any {
                    it.task == LlmTask.DREAMING_EVIDENCE &&
                        it.messages
                            .last()
                            .content
                            .contains("<raw_memories")
                },
            )
        }

    @Test
    fun `no organized candidate skips novelty and still scans full raw snapshot`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.messages
                            .last()
                            .content
                            .contains("<raw_memories count=")
                    ) {
                        evidenceResponse(candidateIdsResponse(listOf("R-1")))
                    } else {
                        fail("novelty must not run without organized candidates")
                    }
                }
            val result = collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(1, client.requests.size)
            assertEquals(emptyList(), result.single().organizedMemories)
            assertEquals(listOf("R-1"), result.single().rawMemories.map { it.id })
        }

    @Test
    fun `raw reverse lookup merges all organized associations and keeps unassociated raw`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceRaw(fixture.database, evidenceRaw("R-2"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", listOf("R-1")))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-2", listOf("R-1")))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    when {
                        request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        request.messages
                            .last()
                            .content
                            .contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                        else -> evidenceResponse(candidateIdsResponse(listOf("R-1", "R-2")))
                    }
                }
            val result = collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(listOf("O-1", "O-2"), result.single().organizedMemories.map { it.id })
            assertEquals(listOf("R-1", "R-2"), result.single().rawMemories.map { it.id })
        }

    @Test
    fun `two topics independently scan full organized snapshot`() =
        withDreamingDatabase { fixture ->
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-2", emptyList()))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.task == LlmTask.MEMORY_SEARCH) {
                        evidenceResponse(candidateIdsResponse(emptyList()))
                    } else {
                        evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                    }
                }
            val topics =
                listOf(evidenceTopic(title = "one", sourceTurnIds = listOf(1)), evidenceTopic(title = "two", sourceTurnIds = listOf(1)))
            val result = collector(fixture.database, client).collect(listOf(evidenceTurn(1)), topics)

            assertEquals(2, result.size)
            assertEquals(2, client.requests.count { it.task == LlmTask.MEMORY_SEARCH })
            assertTrue(
                client.requests
                    .filter {
                        it.task == LlmTask.MEMORY_SEARCH
                    }.all {
                        it.messages
                            .last()
                            .content
                            .contains("<candidates count=\"2\">")
                    },
            )
        }

    @Test
    fun `unknown organized scanner id fails before novelty and later requests`() =
        withDreamingDatabase { fixture ->
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client = FakeEvidenceLlmClient { _, _ -> evidenceResponse(candidateIdsResponse(listOf("O-UNKNOWN"))) }
            val failure =
                assertFailsWith<LlmException> {
                    collector(
                        fixture.database,
                        client,
                    ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.MEMORY_SEARCH)
            assertEquals(1, client.requests.size)
            assertFalse(client.requests.any { request -> request.task == LlmTask.DREAMING_EVIDENCE })
        }

    @Test
    fun `truncated organized scanner fails with memory search metadata and no later calls`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(
                fixture.database,
                evidenceOrganized("O-1", emptyList(), content = "SECRET-ORGANIZED"),
            )
            val client =
                FakeEvidenceLlmClient { _, _ ->
                    evidenceResponse(candidateIdsResponse(listOf("O-1")), finishReason = "length")
                }
            val failure =
                assertFailsWith<LlmException> {
                    collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.MEMORY_SEARCH)
            assertEquals(1, client.requests.size)
            assertFalse(client.requests.any { request -> request.task == LlmTask.DREAMING_EVIDENCE })
        }

    @Test
    fun `malformed organized scanner response is attributed to memory search`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(
                fixture.database,
                evidenceOrganized("O-1", emptyList(), content = "SECRET-ORGANIZED"),
            )
            val client = FakeEvidenceLlmClient { _, _ -> evidenceResponse("not json") }
            val failure =
                assertFailsWith<LlmException> {
                    collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.MEMORY_SEARCH)
            assertEquals(1, client.requests.size)
            assertFalse(client.requests.any { request -> request.task == LlmTask.DREAMING_EVIDENCE })
        }

    @Test
    fun `invalid novelty coverage id fails the whole batch`() =
        withDreamingDatabase { fixture ->
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.task == LlmTask.MEMORY_SEARCH) {
                        evidenceResponse(candidateIdsResponse(listOf("O-1")))
                    } else {
                        evidenceResponse(noveltyResponse("FULLY_COVERED", listOf("O-UNKNOWN")))
                    }
                }
            val failure =
                assertFailsWith<LlmException> {
                    collector(
                        fixture.database,
                        client,
                    ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.DREAMING_EVIDENCE)
            assertEquals(2, client.requests.size)
        }

    @Test
    fun `malformed novelty response is attributed to dreaming evidence without raw scan`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.task == LlmTask.MEMORY_SEARCH) {
                        evidenceResponse(candidateIdsResponse(listOf("O-1")))
                    } else {
                        evidenceResponse("not json")
                    }
                }
            val failure =
                assertFailsWith<LlmException> {
                    collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.DREAMING_EVIDENCE)
            assertEquals(2, client.requests.size)
            assertFalse(
                client.requests.any { request ->
                    request.messages
                        .last()
                        .content
                        .contains("<raw_memories count=")
                },
            )
        }

    @Test
    fun `truncated raw scanner fails after discovery and novelty without later calls`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    when {
                        request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        request.messages
                            .last()
                            .content
                            .contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                        else -> evidenceResponse(candidateIdsResponse(listOf("R-1")), finishReason = "length")
                    }
                }
            val failure =
                assertFailsWith<LlmException> {
                    collector(
                        fixture.database,
                        client,
                    ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.DREAMING_EVIDENCE)
        }

    @Test
    fun `malformed raw scanner response is attributed to dreaming evidence`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(
                fixture.database,
                evidenceRaw("R-1", content = "SECRET-RAW"),
            )
            val client = FakeEvidenceLlmClient { _, _ -> evidenceResponse("not json") }
            val failure =
                assertFailsWith<LlmException> {
                    collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.DREAMING_EVIDENCE)
            assertEquals(1, client.requests.size)
        }

    @Test
    fun `organized budget overrun makes zero llm calls`() =
        withDreamingDatabase { fixture ->
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client = FakeEvidenceLlmClient { _, _ -> fail("budget overrun must not call llm") }
            val collector =
                collector(
                    fixture.database,
                    client,
                    estimator = EvidenceCountingEstimator(organizedPerItem = 2),
                    maxInput = 1,
                )
            val failure = assertFailsWith<LlmException> { collector.collect(listOf(evidenceTurn(1)), listOf(evidenceTopic())) }

            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(0, client.requests.size)
        }

    @Test
    fun `schema tokens count only for json schema mode`() =
        withDreamingDatabase { fixture ->
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val schemaClient = FakeEvidenceLlmClient { _, _ -> fail("schema budget failure must not call llm") }
            val schemaCollector =
                collector(
                    fixture.database,
                    schemaClient,
                    estimator = EvidenceCountingEstimator(schemaTokens = 5),
                    mode = LlmResponseFormat.JSON_SCHEMA,
                    maxInput = 4,
                )
            val failure = assertFailsWith<LlmException> { schemaCollector.collect(listOf(evidenceTurn(1)), listOf(evidenceTopic())) }
            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(0, schemaClient.requests.size)

            val objectClient = FakeEvidenceLlmClient { _, _ -> evidenceResponse(candidateIdsResponse(emptyList())) }
            val objectCollector =
                collector(
                    fixture.database,
                    objectClient,
                    estimator = EvidenceCountingEstimator(schemaTokens = 5),
                    mode = LlmResponseFormat.JSON_OBJECT,
                    maxInput = 4,
                )
            objectCollector.collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
            assertTrue(objectClient.requests.isNotEmpty())
        }

    @Test
    fun `cancellation from organized scanner propagates unchanged`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                val cancellation = CancellationException("ct24-cancel")
                val client = FakeEvidenceLlmClient { _, _ -> throw cancellation }
                assertEquals(
                    cancellation,
                    assertFailsWith<CancellationException> {
                        collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                    },
                )
            }
        }

    @Test
    fun `binding requires both tasks and json mode before database access`() {
        val client = FakeEvidenceLlmClient { _, _ -> fail("must not call llm") }
        val missingSearch =
            assertFailsWith<ContextConfigException> {
                collector(
                    com.github.noahshen.alcyone.context.memory.runtime.dreaming.UnusedEvidenceDatabase,
                    client,
                    config = evidenceLlmConfig(includeMemorySearch = false),
                )
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, missingSearch.errorCode)
        val missingEvidence =
            assertFailsWith<ContextConfigException> {
                collector(
                    com.github.noahshen.alcyone.context.memory.runtime.dreaming.UnusedEvidenceDatabase,
                    client,
                    config = evidenceLlmConfig(includeEvidence = false),
                )
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, missingEvidence.errorCode)
    }

    @Test
    fun `organized scanner uses multiple batches and paginates full snapshot`() =
        withDreamingDatabase { fixture ->
            repeat(3) { index -> insertEvidenceOrganized(fixture.database, evidenceOrganized("O-${index + 1}", emptyList())) }
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.task == LlmTask.MEMORY_SEARCH) {
                        evidenceResponse(candidateIdsResponse(idsInRequest(request)))
                    } else {
                        evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                    }
                }
            val result =
                collector(
                    fixture.database,
                    client,
                    estimator = EvidenceCountingEstimator(organizedPerItem = 1, noveltyPerItem = 0),
                    maxInput = 2,
                    pageSize = 2,
                ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(2, client.requests.count { it.task == LlmTask.MEMORY_SEARCH })
            assertEquals(listOf("O-1", "O-2", "O-3"), result.single().organizedMemories.map { it.id })
        }

    @Test
    fun `raw scanner independently batches full raw snapshot`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceRaw(fixture.database, evidenceRaw("R-2"))
            val client = FakeEvidenceLlmClient { request, _ -> evidenceResponse(candidateIdsResponse(idsInRequest(request))) }
            val result =
                collector(
                    fixture.database,
                    client,
                    estimator = EvidenceCountingEstimator(organizedPerItem = 0, rawPerItem = 1),
                    maxInput = 1,
                ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(2, client.requests.size)
            assertEquals(listOf("R-1", "R-2"), result.single().rawMemories.map { it.id })
        }

    @Test
    fun `raw budget overrun occurs after novelty without raw llm call`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.task == LlmTask.MEMORY_SEARCH) {
                        evidenceResponse(candidateIdsResponse(listOf("O-1")))
                    } else if (request.messages
                            .last()
                            .content
                            .contains("<organized count=")
                    ) {
                        evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                    } else {
                        fail("raw budget overrun must not call llm")
                    }
                }
            val failure =
                assertFailsWith<LlmException> {
                    collector(
                        fixture.database,
                        client,
                        estimator = EvidenceCountingEstimator(organizedPerItem = 0, noveltyPerItem = 0, rawPerItem = 2),
                        maxInput = 1,
                    ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
            assertEquals(2, client.requests.size)
        }

    @Test
    fun `novelty truncation fails without raw scanner`() =
        withDreamingDatabase { fixture ->
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.task == LlmTask.MEMORY_SEARCH) {
                        evidenceResponse(candidateIdsResponse(listOf("O-1")))
                    } else {
                        evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"), finishReason = "length")
                    }
                }
            val failure =
                assertFailsWith<LlmException> {
                    collector(
                        fixture.database,
                        client,
                    ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.OUTPUT_TRUNCATED, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.DREAMING_EVIDENCE)
            assertEquals(2, client.requests.size)
            assertFalse(
                client.requests.any { request ->
                    request.messages
                        .last()
                        .content
                        .contains("<raw_memories count=")
                },
            )
        }

    @Test
    fun `unknown raw scanner id fails without reverse calls`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    when {
                        request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        request.messages
                            .last()
                            .content
                            .contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                        else -> evidenceResponse(candidateIdsResponse(listOf("R-UNKNOWN")))
                    }
                }
            val failure =
                assertFailsWith<LlmException> {
                    collector(
                        fixture.database,
                        client,
                    ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
                }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertLlmMetadata(failure, LlmTask.DREAMING_EVIDENCE)
            assertEquals(3, client.requests.size)
        }

    @Test
    fun `json schema mode sends both evidence schemas and json object omits both`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val schemaClient =
                FakeEvidenceLlmClient { request, _ ->
                    when {
                        request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        request.messages
                            .last()
                            .content
                            .contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                        else -> evidenceResponse(candidateIdsResponse(listOf("R-1")))
                    }
                }
            collector(
                fixture.database,
                schemaClient,
                mode = LlmResponseFormat.JSON_SCHEMA,
            ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
            assertEquals(3, schemaClient.requests.size)
            assertEquals(
                com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchPrompt
                    .scannerSchema(),
                schemaClient.requests[0].schema,
            )
            assertEquals(
                com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingEvidencePrompt
                    .noveltySchema(),
                schemaClient.requests[1].schema,
            )
            assertEquals(
                com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingEvidencePrompt
                    .rawSchema(),
                schemaClient.requests[2].schema,
            )
            assertTrue(schemaClient.requests.all { it.responseFormat == LlmResponseFormat.JSON_SCHEMA })

            val objectClient =
                FakeEvidenceLlmClient { request, _ ->
                    when {
                        request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        request.messages
                            .last()
                            .content
                            .contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                        else -> evidenceResponse(candidateIdsResponse(listOf("R-1")))
                    }
                }
            collector(
                fixture.database,
                objectClient,
                mode = LlmResponseFormat.JSON_OBJECT,
            ).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
            assertEquals(3, objectClient.requests.size)
            assertTrue(objectClient.requests.all { it.schema == null && it.responseFormat == LlmResponseFormat.JSON_OBJECT })
        }

    @Test
    fun `first topic failure prevents later topic requests`() =
        withDreamingDatabase { fixture ->
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val client = FakeEvidenceLlmClient { _, _ -> evidenceResponse(candidateIdsResponse(listOf("O-UNKNOWN"))) }
            val failure =
                assertFailsWith<LlmException> {
                    collector(fixture.database, client).collect(
                        listOf(evidenceTurn(1)),
                        listOf(evidenceTopic(title = "one"), evidenceTopic(title = "two")),
                    )
                }
            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
            assertEquals(1, client.requests.size)
        }

    @Test
    fun `candidate lists are defensive and toString does not expose content`() {
        val turns = mutableListOf(evidenceTurn(1, "SECRET-TURN"))
        val organized = mutableListOf(evidenceOrganized("O-1", emptyList(), "SECRET-ORGANIZED"))
        val raw = mutableListOf(evidenceRaw("R-1", content = "SECRET-RAW"))
        val candidate =
            DreamingTopicEvidenceCandidate(
                evidenceTopic(),
                com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision.NEEDS_EVIDENCE,
                turns,
                organized,
                raw,
            )
        turns += evidenceTurn(2)
        organized += evidenceOrganized("O-2", emptyList())
        raw += evidenceRaw("R-2")

        assertEquals(1, candidate.newConversationTurns.size)
        assertEquals(1, candidate.organizedMemories.size)
        assertEquals(1, candidate.rawMemories.size)
        assertFalse(candidate.toString().contains("SECRET"))
    }

    @Test
    fun `repeated raw scanner ids are stable deduped`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            val client = FakeEvidenceLlmClient { _, _ -> evidenceResponse(candidateIdsResponse(listOf("R-1", "R-1"))) }
            val result = collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(listOf("R-1"), result.single().rawMemories.map { it.id })
        }

    @Test
    fun `prompt only binding for either task fails with invalid config`() {
        val client = FakeEvidenceLlmClient { _, _ -> fail("prompt only must not call llm") }
        assertFailsWith<ContextConfigException> {
            LlmDreamingEvidenceCollector(
                UnusedEvidenceDatabase,
                client,
                evidenceLlmConfig(mode = LlmResponseFormat.PROMPT_ONLY),
                EvidenceCountingEstimator(),
            )
        }
    }

    private fun assertLlmMetadata(
        failure: LlmException,
        task: LlmTask,
    ) {
        assertEquals(task, failure.task)
        when (task) {
            LlmTask.MEMORY_SEARCH -> {
                assertEquals("memory-search", failure.profile)
                assertEquals("fake-memory-search", failure.model)
            }
            LlmTask.DREAMING_EVIDENCE -> {
                assertEquals("evidence", failure.profile)
                assertEquals("fake-evidence", failure.model)
            }
            else -> fail("unexpected task $task")
        }
        assertEquals(null, failure.cause)
        val message = failure.message.orEmpty()
        assertFalse(message.contains("ct24-safe-key-marker"))
        assertFalse(message.contains("http://"))
        assertFalse(message.contains("SECRET"))
    }

    private fun idsInRequest(request: com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest): List<String> =
        Regex("<(?:candidate|organized_memory|raw_memory) id=\\\"([^\\\"]+)\\\"")
            .findAll(request.messages.last().content)
            .map { match -> match.groupValues[1] }
            .toList()

    private fun collector(
        database: com.github.noahshen.alcyone.context.memory.core.ContextDatabase,
        client: FakeEvidenceLlmClient,
        estimator: EvidenceCountingEstimator = EvidenceCountingEstimator(organizedPerItem = 0, noveltyPerItem = 0, rawPerItem = 0),
        mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        maxInput: Int = 32_000,
        pageSize: Int = 256,
        config: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig =
            r1EvidenceLlmConfig(mode = mode, maxInputTokens = maxInput),
    ): LlmDreamingEvidenceCollector = LlmDreamingEvidenceCollector(database, client, config, estimator, pageSize)
}

/** R1 使用不同 profile/model，证明 Organized 与 Novelty/Raw 的归属不是同值掩盖。 */
private fun r1EvidenceLlmConfig(
    mode: LlmResponseFormat,
    maxInputTokens: Int,
): LlmConfig {
    val memorySearchProfile =
        LlmProfileConfig(
            name = "memory-search",
            baseUrl = "http://127.0.0.1:1",
            apiKey = "ct24-safe-key-marker",
            model = "fake-memory-search",
            contextWindowTokens = 64_000,
            jsonMode = mode,
            maxAttempts = 1,
        )
    val evidenceProfile =
        LlmProfileConfig(
            name = "evidence",
            baseUrl = "http://127.0.0.1:1",
            apiKey = "ct24-safe-key-marker",
            model = "fake-evidence",
            contextWindowTokens = 64_000,
            jsonMode = mode,
            maxAttempts = 1,
        )
    return LlmConfig(
        profiles = mapOf(memorySearchProfile.name to memorySearchProfile, evidenceProfile.name to evidenceProfile),
        tasks =
            mapOf(
                LlmTask.MEMORY_SEARCH to LlmTaskConfig(memorySearchProfile.name, maxInputTokens, 1_000),
                LlmTask.DREAMING_EVIDENCE to LlmTaskConfig(evidenceProfile.name, maxInputTokens, 1_000),
            ),
        safetyMarginPercent = 0,
    )
}

private object UnusedEvidenceDatabase : com.github.noahshen.alcyone.context.memory.core.ContextDatabase {
    override suspend fun <T> read(block: (com.github.noahshen.alcyone.context.memory.core.repository.ReadContext) -> T): T =
        error("binding must not read")

    override suspend fun <T> write(block: (com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext) -> T): T =
        error("binding must not write")
}
