package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DreamingEvidenceRealDbTest {
    @Test
    fun `real sqlite reads both snapshots in one context and llm runs outside it`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", listOf("R-1")))
            val tracking = TrackingEvidenceDatabase(fixture.database)
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    assertTrue(!tracking.readActive.get(), "LLM must run outside the read context")
                    when {
                        request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                        request.messages
                            .last()
                            .content
                            .contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                        else -> evidenceResponse(candidateIdsResponse(emptyList()))
                    }
                }
            val result = collector(tracking, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))

            assertEquals(1, tracking.reads.get())
            assertEquals(listOf("O-1"), result.single().organizedMemories.map { it.id })
            assertEquals(emptyList(), result.single().rawMemories)
        }

    @Test
    fun `reverse lookup id missing fails the whole batch`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1"))
            insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", emptyList()))
            val tracking = TrackingEvidenceDatabase(fixture.database) { _ -> listOf("O-MISSING") }
            val client =
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
            val failure =
                assertFailsWith<LlmException> { collector(tracking, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic())) }

            assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode)
        }

    @Test
    fun `raw without organized association remains in candidate and collector does not write`() =
        withDreamingDatabase { fixture ->
            insertEvidenceRaw(fixture.database, evidenceRaw("R-1", MemoryOrigin.EXTRACTION))
            insertEvidenceRaw(fixture.database, evidenceRaw("R-2", MemoryOrigin.DREAMING))
            val before = fixture.database.read { read -> read.rawMemories.scanPage(0, 100).map { it.id } }
            val client =
                FakeEvidenceLlmClient { request, _ ->
                    if (request.messages
                            .last()
                            .content
                            .contains("<raw_memories count=")
                    ) {
                        evidenceResponse(candidateIdsResponse(listOf("R-1", "R-2")))
                    } else {
                        evidenceResponse(candidateIdsResponse(emptyList()))
                    }
                }
            val result = collector(fixture.database, client).collect(listOf(evidenceTurn(1)), listOf(evidenceTopic()))
            val after = fixture.database.read { read -> read.rawMemories.scanPage(0, 100).map { it.id } }

            assertEquals(listOf("R-1", "R-2"), result.single().rawMemories.map { it.id })
            assertEquals(emptyList(), result.single().organizedMemories)
            assertEquals(before, after)
        }

    @Test
    fun `full real sqlite stage composes discovery collector need chain reverse business key and neighbors without writes`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val before =
                    insertEvidenceConversationTurn(fixture.database, sessionTurnId = "before", timestamp = EVIDENCE_BASE.minusSeconds(2))
                val currentOne =
                    insertEvidenceConversationTurn(
                        fixture.database,
                        sessionTurnId = "current-one",
                        timestamp = EVIDENCE_BASE.minusSeconds(1),
                    )
                val currentTwo = insertEvidenceConversationTurn(fixture.database, sessionTurnId = "current-two", timestamp = EVIDENCE_BASE)
                val after =
                    insertEvidenceConversationTurn(fixture.database, sessionTurnId = "after", timestamp = EVIDENCE_BASE.plusSeconds(1))
                val chainRaw = evidenceRaw("R-CHAIN", createdAt = EVIDENCE_BASE.plusSeconds(3))
                val selectedRaw =
                    evidenceRaw(
                        "R-1",
                        sources =
                            listOf(
                                MemorySourceRef.ConversationTurnRef(
                                    currentTwo.runtimeId,
                                    currentTwo.agentId,
                                    currentTwo.sessionId,
                                    currentTwo.sessionTurnId,
                                ),
                                MemorySourceRef.RawMemoryRef(chainRaw.id),
                            ),
                        createdAt = EVIDENCE_BASE.plusSeconds(2),
                    )
                listOf(selectedRaw, chainRaw).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-1", listOf(selectedRaw.id)))
                insertEvidenceOrganized(fixture.database, evidenceOrganized("O-2", listOf(chainRaw.id)))
                val tracking = TrackingEvidenceDatabase(fixture.database)
                val config = evidenceLlmConfig(includeTopic = true)
                val client =
                    FakeEvidenceLlmClient { request, _ ->
                        assertTrue(!tracking.readActive.get(), "LLM must run outside every db read")
                        when {
                            request.task == LlmTask.DREAMING_TOPIC &&
                                request.messages
                                    .last()
                                    .content
                                    .contains("<topics count=")
                            ->
                                evidenceResponse(
                                    dreamingTopicResponse(
                                        discoveredTopic(sourceTurnIds = listOf(currentOne.id, currentTwo.id)),
                                    ),
                                )
                            request.task == LlmTask.DREAMING_TOPIC ->
                                evidenceResponse(
                                    dreamingTopicResponse(
                                        discoveredTopic(title = "one", sourceTurnIds = listOf(currentOne.id)),
                                        discoveredTopic(title = "two", sourceTurnIds = listOf(currentTwo.id)),
                                    ),
                                )
                            request.task == LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(listOf("O-1")))
                            request.messages
                                .last()
                                .content
                                .contains("<organized count=") ->
                                evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                            request.messages
                                .last()
                                .content
                                .contains("<raw_memories count=") ->
                                evidenceResponse(candidateIdsResponse(listOf("R-1")))
                            request.messages
                                .last()
                                .content
                                .contains("<raw_candidates count=") ->
                                evidenceResponse(conversationNeedResponse(listOf("R-1")))
                            else -> error("unexpected request")
                        }
                    }
                val discovery = LlmDreamingTopicDiscoveryStage(client, config)
                val stage = LlmDreamingEvidenceStage(tracking, client, config, discovery)

                val result = stage.collect(listOf(currentOne, currentTwo))
                val evidence = result.topics.single()

                assertEquals(
                    listOf(
                        LlmTask.DREAMING_TOPIC,
                        LlmTask.DREAMING_TOPIC,
                        LlmTask.MEMORY_SEARCH,
                        LlmTask.DREAMING_EVIDENCE,
                        LlmTask.DREAMING_EVIDENCE,
                        LlmTask.DREAMING_EVIDENCE,
                    ),
                    client.requests.map { it.task },
                )
                assertEquals(DreamingEvidenceDecision.NEEDS_REFLECTION, evidence.decision)
                assertEquals(listOf(currentOne.id, currentTwo.id), evidence.newConversationTurns.map { it.id })
                assertEquals(listOf("R-1", "R-CHAIN"), evidence.rawMemories.map { it.id })
                assertEquals(listOf("O-1", "O-2"), evidence.organizedMemories.map { it.id })
                assertEquals(listOf(after.id), evidence.historicalConversationTurns.map { it.id })
                assertTrue(
                    evidence.historicalConversationTurns.none {
                        it.id == before.id || it.id == currentOne.id || it.id == currentTwo.id
                    },
                )
                assertEquals(0, tracking.writes.get())
            }
        }

    private fun collector(
        database: com.github.noahshen.alcyone.context.memory.core.ContextDatabase,
        client: FakeEvidenceLlmClient,
    ): LlmDreamingEvidenceCollector =
        LlmDreamingEvidenceCollector(
            database,
            client,
            evidenceLlmConfig(),
            EvidenceCountingEstimator(organizedPerItem = 0, noveltyPerItem = 0, rawPerItem = 0),
        )
}
