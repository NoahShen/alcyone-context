package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingEvidencePrompt
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LlmDreamingEvidenceStageTest {
    @Test
    fun `fully covered topic skips conversation need and final raw history`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                val topic = discoveredTopic(sourceTurnIds = listOf(1))
                val llm =
                    FakeEvidenceLlmClient { request, _ ->
                        when {
                            request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                            request.messages
                                .last()
                                .content
                                .contains("<organized count=") ->
                                evidenceResponse(noveltyResponse("FULLY_COVERED", listOf("O-1")))
                            else -> fail("fully covered topic must not request raw or conversation evidence")
                        }
                    }

                val result = stage(fixture.database, llm, listOf(topic)).collect(listOf(evidenceTurn(1)))

                assertEquals(DreamingEvidenceDecision.FULLY_COVERED, result.topics.single().decision)
                assertEquals(
                    listOf("O-1"),
                    result.topics
                        .single()
                        .organizedMemories
                        .map { it.id },
                )
                assertEquals(emptyList(), result.topics.single().rawMemories)
                assertEquals(emptyList(), result.topics.single().historicalConversationTurns)
                assertEquals(2, llm.requests.size)
            }
        }

    @Test
    fun `empty raw candidate makes zero conversation need request`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val topic = discoveredTopic(sourceTurnIds = listOf(1))
                val llm = FakeEvidenceLlmClient { _, _ -> fail("empty snapshots must not call llm") }

                val result = stage(fixture.database, llm, listOf(topic)).collect(listOf(evidenceTurn(1)))

                assertEquals(DreamingEvidenceDecision.NEEDS_REFLECTION, result.topics.single().decision)
                assertEquals(emptyList(), result.topics.single().rawMemories)
                assertEquals(emptyList(), result.topics.single().historicalConversationTurns)
                assertEquals(0, llm.requests.size)
            }
        }

    @Test
    fun `conversation need empty keeps all B1 raw and skips source traversal`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val raw =
                    evidenceRaw(
                        "R-1",
                        sources = listOf(MemorySourceRef.ConversationTurnRef("runtime", "agent", "session", "missing")),
                    )
                insertEvidenceRaw(fixture.database, raw)
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", listOf("R-1")))
                val llm =
                    FakeEvidenceLlmClient { request, _ ->
                        routeNeedsWithRaw(request, rawIds = listOf("R-1")) { conversationNeedResponse() }
                    }

                val result =
                    stage(
                        fixture.database,
                        llm,
                        listOf(discoveredTopic(sourceTurnIds = listOf(1))),
                    ).collect(listOf(evidenceTurn(1)))

                assertEquals(
                    listOf("R-1"),
                    result.topics
                        .single()
                        .rawMemories
                        .map { it.id },
                )
                assertEquals(emptyList(), result.topics.single().historicalConversationTurns)
                assertEquals(4, llm.requests.size)
                assertEquals(
                    1,
                    llm.requests.count {
                        it.messages
                            .last()
                            .content
                            .contains("<raw_candidates count=")
                    },
                )
            }
        }

    @Test
    fun `need selects trace roots while all B1 and chain raw remain final evidence`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val anchor = insertEvidenceConversationTurn(fixture.database, sessionTurnId = "anchor", timestamp = EVIDENCE_BASE)
                val b1Raw =
                    evidenceRaw(
                        "R-1",
                        sources = listOf(conversationRef(anchor), MemorySourceRef.RawMemoryRef("R-CHAIN")),
                        createdAt = EVIDENCE_BASE.plusSeconds(1),
                    )
                val secondB1Raw = evidenceRaw("R-2", createdAt = EVIDENCE_BASE.plusSeconds(2))
                val chainRaw =
                    evidenceRaw(
                        "R-CHAIN",
                        sources = listOf(conversationRef(anchor)),
                        createdAt = EVIDENCE_BASE.plusSeconds(3),
                    )
                listOf(b1Raw, secondB1Raw, chainRaw).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", listOf(b1Raw.id)))
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-2", listOf(chainRaw.id)))
                val llm =
                    FakeEvidenceLlmClient { request, _ ->
                        routeNeedsWithRaw(request, rawIds = listOf("R-1", "R-2")) { conversationNeedResponse(listOf("R-1")) }
                    }

                val result =
                    stage(
                        fixture.database,
                        llm,
                        listOf(discoveredTopic(sourceTurnIds = listOf(1))),
                    ).collect(listOf(evidenceTurn(1)))

                val evidence = result.topics.single()
                assertEquals(listOf("R-1", "R-2", "R-CHAIN"), evidence.rawMemories.map { it.id })
                assertEquals(listOf("O-1", "O-2"), evidence.organizedMemories.map { it.id })
                assertEquals(listOf(anchor.id), evidence.historicalConversationTurns.map { it.id })
            }
        }

    @Test
    fun `conversation need request contains only current topic and B1 raw candidates`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
                insertEvidenceRaw(fixture.database, evidenceRaw("R-HIDDEN"))
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                val llm =
                    FakeEvidenceLlmClient { request, _ ->
                        routeNeedsWithRaw(request, rawIds = listOf("R-1")) { conversationNeedResponse() }
                    }

                stage(fixture.database, llm, listOf(discoveredTopic(sourceTurnIds = listOf(1)))).collect(listOf(evidenceTurn(1)))

                val request =
                    llm.requests.single {
                        it.messages
                            .last()
                            .content
                            .contains("<raw_candidates count=")
                    }
                val user = request.messages.last().content
                assertTrue(user.contains("<title>topic</title>"), user)
                assertTrue(user.contains("sessionTurnId=\"turn-1\""), user)
                assertTrue(user.contains("<raw_candidate id=\"R-1\""), user)
                assertFalse(user.contains("R-HIDDEN"), user)
                assertEquals(LlmTask.DREAMING_EVIDENCE, request.task)
            }
        }

    @Test
    fun `conversation need unknown raw id fails atomically with safe task metadata`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                val llm =
                    FakeEvidenceLlmClient { request, _ ->
                        routeNeedsWithRaw(request, rawIds = listOf("R-1")) { conversationNeedResponse(listOf("R-OTHER")) }
                    }

                val failure = assertFailsWith<LlmException> { stage(fixture.database, llm).collect(listOf(evidenceTurn(1))) }

                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
                assertEquals(LlmTask.DREAMING_EVIDENCE, failure.task)
                assertEquals("evidence", failure.profile)
                assertEquals("fake-evidence", failure.model)
                assertNull(failure.cause)
                assertFalse(failure.message.orEmpty().contains("R-OTHER"))
                assertEquals(4, llm.requests.size)
            }
        }

    @Test
    fun `conversation need malformed and truncated outputs fail with dreaming evidence attribution`() =
        runBlocking {
            listOf("not json" to "stop", conversationNeedResponse(listOf("R-1")) to "length").forEach { (content, finishReason) ->
                withDreamingDatabase { fixture ->
                    insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
                    insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                    val llm =
                        FakeEvidenceLlmClient { request, call ->
                            if (call < 4) {
                                routeNeedsWithRaw(request, rawIds = listOf("R-1")) { conversationNeedResponse() }
                            } else {
                                evidenceResponse(content, finishReason)
                            }
                        }

                    val failure = assertFailsWith<LlmException> { stage(fixture.database, llm).collect(listOf(evidenceTurn(1))) }
                    assertEquals(
                        if (finishReason ==
                            "length"
                        ) {
                            LlmErrorCode.OUTPUT_TRUNCATED
                        } else {
                            LlmErrorCode.INVALID_RESPONSE
                        },
                        failure.errorCode,
                    )
                    assertEquals(LlmTask.DREAMING_EVIDENCE, failure.task)
                    assertEquals("evidence", failure.profile)
                    assertEquals("fake-evidence", failure.model)
                    assertNull(failure.cause)
                }
            }
        }

    @Test
    fun `conversation need budget exact equality passes and overrun sends no need request`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                val exact =
                    FakeEvidenceLlmClient { request, _ -> routeNeedsWithRaw(request, listOf("R-1")) { conversationNeedResponse() } }
                stage(
                    fixture.database,
                    exact,
                    estimator = ConversationNeedCountingEstimator(base = 1, perRawCandidate = 1),
                    maxInput = 2,
                    maxOutput = 1,
                    contextWindow = 3,
                ).collect(listOf(evidenceTurn(1)))
                assertEquals(4, exact.requests.size)

                val overrun =
                    FakeEvidenceLlmClient { request, _ -> routeNeedsWithRaw(request, listOf("R-1")) { conversationNeedResponse() } }
                val failure =
                    assertFailsWith<LlmException> {
                        stage(
                            fixture.database,
                            overrun,
                            estimator = ConversationNeedCountingEstimator(base = 2, perRawCandidate = 1),
                            maxInput = 2,
                            maxOutput = 1,
                            contextWindow = 3,
                        ).collect(listOf(evidenceTurn(1)))
                    }
                assertEquals(LlmErrorCode.LOCAL_BUDGET_EXCEEDED, failure.errorCode)
                assertEquals(LlmTask.DREAMING_EVIDENCE, failure.task)
                assertEquals("evidence", failure.profile)
                assertEquals("fake-evidence", failure.model)
                assertEquals(3, overrun.requests.size)
            }
        }

    @Test
    fun `conversation need cancellation propagates unchanged`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                val cancellation = CancellationException("ct24-b2-stage-cancel")
                val llm =
                    FakeEvidenceLlmClient { request, call ->
                        if (call < 4) {
                            routeNeedsWithRaw(request, listOf("R-1")) { conversationNeedResponse() }
                        } else {
                            throw cancellation
                        }
                    }

                assertEquals(
                    cancellation,
                    assertFailsWith<CancellationException> {
                        stage(fixture.database, llm).collect(listOf(evidenceTurn(1)))
                    },
                )
            }
        }

    @Test
    fun `conversation need honors json schema and object modes`() =
        runBlocking {
            listOf(LlmResponseFormat.JSON_SCHEMA, LlmResponseFormat.JSON_OBJECT).forEach { mode ->
                withDreamingDatabase { fixture ->
                    insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
                    insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                    val llm =
                        FakeEvidenceLlmClient { request, _ -> routeNeedsWithRaw(request, listOf("R-1")) { conversationNeedResponse() } }
                    stage(fixture.database, llm, mode = mode).collect(listOf(evidenceTurn(1)))

                    val request =
                        llm.requests.single {
                            it.messages
                                .last()
                                .content
                                .contains("<raw_candidates count=")
                        }
                    assertEquals(mode, request.responseFormat)
                    assertEquals(
                        if (mode ==
                            LlmResponseFormat.JSON_SCHEMA
                        ) {
                            DreamingEvidencePrompt.conversationSchema()
                        } else {
                            null
                        },
                        request.schema,
                    )
                }
            }
        }

    @Test
    fun `llm never runs inside db read stage is ordered and no write occurs`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
                val tracking = TrackingEvidenceDatabase(fixture.database)
                var discoveryCalled = false
                val discovery =
                    DreamingTopicDiscoveryStage { batch ->
                        discoveryCalled = true
                        assertEquals(listOf(1L), batch.map { it.id })
                        listOf(discoveredTopic(sourceTurnIds = listOf(1)))
                    }
                val llm =
                    FakeEvidenceLlmClient { request, _ ->
                        assertTrue(discoveryCalled)
                        assertFalse(tracking.readActive.get())
                        routeNeedsWithRaw(request, listOf("R-1")) { conversationNeedResponse() }
                    }

                stage(tracking, llm, discovery = discovery).collect(listOf(evidenceTurn(1)))

                assertEquals(
                    listOf(
                        LlmTask.MEMORY_SEARCH,
                        LlmTask.DREAMING_EVIDENCE,
                        LlmTask.DREAMING_EVIDENCE,
                        LlmTask.DREAMING_EVIDENCE,
                    ),
                    llm.requests.map {
                        it.task
                    },
                )
                assertEquals(0, tracking.writes.get())
                assertTrue(tracking.reads.get() >= 2)
            }
        }

    @Test
    fun `final evidence lists are defensive and toString is content safe`() {
        val topic = discoveredTopic(sourceTurnIds = listOf(1))
        val newTurns = mutableListOf(evidenceTurn(1, "SECRET-NEW"))
        val organized = mutableListOf(evidenceOrganized("O-SECRET"))
        val raw = mutableListOf(evidenceRaw("R-SECRET", content = "SECRET-RAW"))
        val historical = mutableListOf(evidenceTurn(2, "SECRET-HISTORY"))
        val evidence =
            DreamingTopicEvidence(
                topic,
                DreamingEvidenceDecision.NEEDS_REFLECTION,
                newTurns,
                organized,
                raw,
                historical,
            )
        val batch = DreamingBatchEvidence(mutableListOf(evidence))
        newTurns.clear()
        organized.clear()
        raw.clear()
        historical.clear()

        assertEquals(1, evidence.newConversationTurns.size)
        assertEquals(1, evidence.organizedMemories.size)
        assertEquals(1, evidence.rawMemories.size)
        assertEquals(1, evidence.historicalConversationTurns.size)
        assertFalse(evidence.toString().contains("SECRET"))
        assertFalse(batch.toString().contains("SECRET"))
        assertEquals("DreamingBatchEvidence(topicCount=1)", batch.toString())
    }

    private fun stage(
        database: ContextDatabase,
        llm: FakeEvidenceLlmClient,
        topics: List<DiscoveredTopic> = listOf(discoveredTopic(sourceTurnIds = listOf(1))),
        estimator: com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator =
            EvidenceCountingEstimator(organizedPerItem = 0, noveltyPerItem = 0, rawPerItem = 0),
        mode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        maxInput: Int = 32_000,
        maxOutput: Int = 1_000,
        contextWindow: Int = 64_000,
        discovery: DreamingTopicDiscoveryStage = DreamingTopicDiscoveryStage { topics },
    ): LlmDreamingEvidenceStage =
        LlmDreamingEvidenceStage(
            database = database,
            llmClient = llm,
            llmConfig =
                evidenceLlmConfig(
                    mode = mode,
                    maxInputTokens = maxInput,
                    maxOutputTokens = maxOutput,
                    contextWindowTokens = contextWindow,
                ),
            discoveryStage = discovery,
            estimator = estimator,
        )

    private fun routeNeedsWithRaw(
        request: LlmRequest,
        rawIds: List<String>,
        conversationNeed: () -> String,
    ): com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse =
        when {
            request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
            request.messages
                .last()
                .content
                .contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
            request.messages
                .last()
                .content
                .contains("<raw_memories count=") -> evidenceResponse(candidateIdsResponse(rawIds))
            request.messages
                .last()
                .content
                .contains("<raw_candidates count=") -> evidenceResponse(conversationNeed())
            else -> fail("unexpected request: ${request.messages.last().content}")
        }

    private fun conversationRef(turn: StoredConversationTurn): MemorySourceRef.ConversationTurnRef =
        MemorySourceRef.ConversationTurnRef(turn.runtimeId, turn.agentId, turn.sessionId, turn.sessionTurnId)
}
