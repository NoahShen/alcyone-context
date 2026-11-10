package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import com.github.noahshen.alcyone.context.memory.api.DreamingRunStage
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitter
import com.github.noahshen.alcyone.context.memory.core.dreaming.ValidatedDreamingInsight
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LlmDreamingBatchProcessorTest {
    @Test
    fun `real discovery budget splits three turns and raw plus pending survive each commit`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val turns =
                    (1..3).map { index ->
                        insertEvidenceConversationTurn(
                            fixture.database,
                            sessionTurnId = "turn-$index",
                            timestamp = EVIDENCE_BASE.plusSeconds(index.toLong()),
                        )
                    }
                val llm =
                    FakeEvidenceLlmClient { request, _ ->
                        when (request.task) {
                            LlmTask.DREAMING_TOPIC -> {
                                val id =
                                    request.messages
                                        .last()
                                        .content
                                        .substringAfter("<turn id=\"")
                                        .substringBefore("\"")
                                evidenceResponse(
                                    dreamingTopicResponse(discoveredTopic(title = "topic-$id", sourceTurnIds = listOf(id.toLong()))),
                                )
                            }
                            LlmTask.MEMORY_SEARCH -> evidenceResponse(candidateIdsResponse(emptyList()))
                            LlmTask.DREAMING_EVIDENCE -> {
                                val user = request.messages.last().content
                                when {
                                    user.contains("<organized count=") -> evidenceResponse(noveltyResponse("NEEDS_EVIDENCE"))
                                    user.contains("<raw_memories count=") -> evidenceResponse(candidateIdsResponse(emptyList()))
                                    else -> error("unexpected evidence request")
                                }
                            }
                            LlmTask.DREAMING_REFLECTION -> {
                                val id =
                                    request.messages
                                        .last()
                                        .content
                                        .substringAfter("<sourceTurnIds>")
                                        .substringBefore("</sourceTurnIds>")
                                evidenceResponse("""{"insights":[{"content":"raw-$id","sourceTurnIds":[$id]}]}""")
                            }
                            else -> error("unexpected task: ${request.task}")
                        }
                    }
                val estimator = PipelineEstimator()
                val config = pipelineConfig()
                val discovery = LlmDreamingTopicDiscoveryStage(llm, config, estimator)
                val evidence = LlmDreamingEvidenceStage(fixture.database, llm, config, discovery, estimator)
                val reflection = LlmDreamingReflectionStage(llm, config, estimator)
                val wakeups = AtomicInteger()
                val processor =
                    LlmDreamingBatchProcessor(
                        database = fixture.database,
                        collectEvidence = evidence::collect,
                        reflectEvidence = reflection::reflect,
                        committer = DreamingCommitter(fixture.database, SequenceIds(), fixedClock()),
                        organizationWakeup = OrganizationWakeup { wakeups.incrementAndGet() },
                    )
                val coordinator =
                    DreamingRunCoordinator(fixture.database, processor, LlmDreamingTopicBatchBudget(discovery), pageSize = 100)
                try {
                    val result = coordinator.runOnce()

                    assertEquals(0L, result.startCheckpoint)
                    assertEquals(3L, result.endCheckpoint)
                    assertEquals(3, result.scannedTurns)
                    assertEquals(3, result.discoveredTopics)
                    assertEquals(3, result.generatedMemories)
                    assertEquals(3, llm.requests.count { it.task == LlmTask.DREAMING_TOPIC })
                    assertEquals(0, llm.requests.count { it.task == LlmTask.MEMORY_SEARCH })
                    assertEquals(3, llm.requests.count { it.task == LlmTask.DREAMING_REFLECTION })
                    assertEquals(
                        listOf(listOf(1L), listOf(2L), listOf(3L)),
                        llm.requests.filter { it.task == LlmTask.DREAMING_TOPIC }.map { request ->
                            Regex(
                                "<turn id=\\\"(\\d+)\\\"",
                            ).findAll(request.messages.last().content).map { it.groupValues[1].toLong() }.toList()
                        },
                    )
                    val raws = fixture.database.read { it.rawMemories.scanPage(0, 10) }
                    assertEquals(3, raws.size)
                    assertEquals(3, fixture.database.read { it.pending.listAllByRawCreatedAt().size })
                    assertEquals(3, wakeups.get())
                    assertTrue(raws.all { it.generatedBy == MemoryOrigin.DREAMING })
                    assertEquals(3L, fixture.database.read { it.dreaming.readCheckpoint() })
                    assertEquals(
                        turns.map { it.sessionTurnId },
                        raws.flatMap { raw ->
                            raw.sources.map { (it as MemorySourceRef.ConversationTurnRef).sessionTurnId }
                        },
                    )
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `range validation happens before evidence and accepts holes`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val first = inserted(fixture.database, "one")
                val third = inserted(fixture.database, "three")
                fixture.deleteTurn(2)
                var evidenceCalls = 0
                val processor =
                    fixture.processor(
                        collectEvidence = { turns ->
                            evidenceCalls++
                            DreamingBatchEvidence(listOf(evidence(turns)))
                        },
                        reflectEvidence = { evidence ->
                            evidence.topics.map { insight("raw-${it.topic.sourceTurnIds.single()}", it.newConversationTurns) }
                        },
                    )
                val valid = DreamingBatchContext(0, third.id, listOf(first, third))
                assertEquals(third.id, processor.process(valid).lastTurnId)
                assertEquals(1, evidenceCalls)
                val duplicate = valid.copy(turns = listOf(first, first))
                val invalid =
                    listOf(
                        valid.copy(expectedCheckpoint = -1),
                        valid.copy(upperBound = -1),
                        valid.copy(turns = emptyList()),
                        valid.copy(expectedCheckpoint = first.id),
                        valid.copy(upperBound = third.id - 1),
                        valid.copy(turns = listOf(third, first)),
                        duplicate,
                    )
                invalid.forEach { context -> assertFailsWith<IllegalArgumentException> { processor.process(context) } }
                assertEquals(1, evidenceCalls)
            }
        }

    @Test
    fun `three batches carry fixed upper bound and per batch committed expected`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                val turns = (1..5).map { inserted(fixture.database, "turn-$it") }
                fixture.deleteTurn(2)
                fixture.deleteTurn(4)
                val actual = fixture.database.read { read -> read.conversations.idRange(0, 5, 10) }
                val events = mutableListOf<String>()
                val wakeups = AtomicInteger()
                val processor =
                    fixture.processor(
                        collectEvidence = { batch ->
                            events += "evidence-${batch.last().id}"
                            DreamingBatchEvidence(listOf(evidence(batch)))
                        },
                        reflectEvidence = { evidence ->
                            events += "reflection-${evidence.topics.single().topic.sourceTurnIds.last()}"
                            listOf(
                                insight(
                                    "raw-${evidence.topics.single().topic.sourceTurnIds.last()}",
                                    batch = evidence.topics.single().newConversationTurns,
                                ),
                            )
                        },
                        wakeup =
                            OrganizationWakeup {
                                events += "wakeup-${fixture.database.read { it.dreaming.readCheckpoint() }}"
                                assertTrue(fixture.database.read { it.pending.hasAnyPending() })
                                wakeups.incrementAndGet()
                            },
                    )
                val tracking = TrackingDreamingDatabase(fixture.database)
                val coordinator = DreamingRunCoordinator(tracking, processor, CountingDreamingBudget(0, 1, 1), pageSize = 1)
                try {
                    val result = coordinator.runOnce()
                    assertEquals(5L, result.endCheckpoint)
                    assertEquals(3, result.scannedTurns)
                    assertEquals(3, result.generatedMemories)
                    assertEquals(3, result.discoveredTopics)
                    assertEquals(3, wakeups.get())
                    assertEquals(
                        listOf(
                            "evidence-1",
                            "reflection-1",
                            "wakeup-1",
                            "evidence-3",
                            "reflection-3",
                            "wakeup-3",
                            "evidence-5",
                            "reflection-5",
                            "wakeup-5",
                        ),
                        events,
                    )
                    assertEquals(5L, fixture.database.read { it.dreaming.readCheckpoint() })
                    assertEquals(3, fixture.database.read { it.rawMemories.scanPage(0, 10).size })
                    assertEquals(3, fixture.database.read { it.pending.listAllByRawCreatedAt().size })
                    assertEquals(1, tracking.checkpointReads.get())
                    assertEquals(1, tracking.maxTurnReads.get())
                    assertEquals(1, tracking.readsWithBothRangeFields.get())
                    assertEquals(listOf(0L, 1L, 3L, 5L), tracking.rangeCalls.map { it.first })
                    assertTrue(tracking.rangeCalls.all { it.second == 5L })
                    assertEquals(turns.map { it.id }.filter { it != 2L && it != 4L }, actual.map { it.id })
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `zero result advances checkpoint without notification and later turn uses new fixed range`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                inserted(fixture.database, "one")
                var wakeups = 0
                val processor =
                    fixture.processor(
                        collectEvidence = { DreamingBatchEvidence(emptyList()) },
                        reflectEvidence = { emptyList() },
                        wakeup = OrganizationWakeup { wakeups++ },
                    )
                val coordinator = DreamingRunCoordinator(fixture.database, processor, CountingDreamingBudget(0, 0, 0), pageSize = 1)
                try {
                    assertEquals(1L, coordinator.runOnce().endCheckpoint)
                    assertEquals(0, wakeups)
                    assertEquals(1L, fixture.database.read { it.dreaming.readCheckpoint() })
                    inserted(fixture.database, "two")
                    val second = coordinator.runOnce()
                    assertEquals(1L, second.startCheckpoint)
                    assertEquals(2L, second.endCheckpoint)
                    assertEquals(0, wakeups)
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `nonempty topic with zero insights advances real cas without notification and continues`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                inserted(fixture.database, "one")
                inserted(fixture.database, "two")
                inserted(fixture.database, "three")
                val evidenceCalls = mutableListOf<List<Long>>()
                var reflectionCalls = 0
                var wakeups = 0
                val faultDatabase = DreamingCommitFaultDatabase(fixture.database) { _, _ -> false }
                val processor =
                    fixture.processor(
                        collectEvidence = { batch ->
                            evidenceCalls += batch.map { it.id }
                            DreamingBatchEvidence(listOf(evidence(batch)))
                        },
                        reflectEvidence = { evidence ->
                            reflectionCalls++
                            assertTrue(evidence.topics.isNotEmpty())
                            assertTrue(
                                evidence.topics
                                    .single()
                                    .newConversationTurns
                                    .isNotEmpty(),
                            )
                            emptyList()
                        },
                        committer = DreamingCommitter(faultDatabase, SequenceIds(), fixedClock()),
                        wakeup = OrganizationWakeup { wakeups++ },
                    )
                val tracking = TrackingDreamingDatabase(fixture.database)
                val coordinator = DreamingRunCoordinator(tracking, processor, CountingDreamingBudget(0, 1, 1), pageSize = 1)
                try {
                    val result = coordinator.runOnce()

                    assertEquals(3L, result.endCheckpoint)
                    assertEquals(3, result.discoveredTopics)
                    assertEquals(0, result.generatedMemories)
                    assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), evidenceCalls)
                    assertEquals(3, reflectionCalls)
                    assertEquals(listOf(0L to 1L, 1L to 2L, 2L to 3L), faultDatabase.casAttempts)
                    assertEquals(listOf(0L to 3L, 1L to 3L, 2L to 3L, 3L to 3L), tracking.rangeCalls)
                    assertEquals(0, fixture.database.read { it.rawMemories.scanPage(0, 10).size })
                    assertEquals(0, fixture.database.read { it.pending.listAllByRawCreatedAt().size })
                    assertEquals(3L, fixture.database.read { it.dreaming.readCheckpoint() })
                    assertEquals(0, wakeups)
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `ordinary notification failure is sanitized and later batch continues`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                Slf4jCapture().use { capture ->
                    inserted(fixture.database, "one")
                    inserted(fixture.database, "two")
                    val sensitive = "private-notification-body-9f27"
                    val wakeups = AtomicInteger()
                    val committedCheckpoints = mutableListOf<Long>()
                    val processor =
                        fixture.processor(
                            collectEvidence = { batch -> DreamingBatchEvidence(listOf(evidence(batch))) },
                            reflectEvidence = { evidence ->
                                evidence.topics.map { insight("raw-${it.topic.sourceTurnIds.single()}", it.newConversationTurns) }
                            },
                            wakeup =
                                OrganizationWakeup {
                                    committedCheckpoints += fixture.database.read { it.dreaming.readCheckpoint() }
                                    wakeups.incrementAndGet()
                                    if (wakeups.get() == 1) throw IllegalStateException(sensitive)
                                },
                        )
                    val coordinator = DreamingRunCoordinator(fixture.database, processor, CountingDreamingBudget(0, 1, 1), pageSize = 1)
                    try {
                        val result = coordinator.runOnce()

                        assertEquals(2L, result.endCheckpoint)
                        assertEquals(2, result.generatedMemories)
                        assertEquals(listOf(1L, 2L), committedCheckpoints)
                        assertEquals(2, wakeups.get())
                        assertEquals(2, fixture.database.read { it.rawMemories.scanPage(0, 10).size })
                        assertEquals(2, fixture.database.read { it.pending.listAllByRawCreatedAt().size })
                        assertEquals(2L, fixture.database.read { it.dreaming.readCheckpoint() })
                        val warnings = capture.recordsFor("dreaming.organization.wakeup.failed")
                        assertEquals(1, warnings.size)
                        assertEquals(Level.WARN, warnings.single().level)
                        assertEquals("dreaming.organization.wakeup.failed batchLastTurnId=1", warnings.single().formattedMessage)
                        assertNull(warnings.single().throwable)
                        assertFalse(capture.logs.any { it.formattedMessage.contains(sensitive) })
                        assertFalse(capture.logs.any { it.throwable != null })
                    } finally {
                        coordinator.closeAsync()
                    }
                }
            }
        }

    @Test
    fun `notification cancellation preserves commit and next run does not redo batch`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                inserted(fixture.database, "one")
                inserted(fixture.database, "two")
                val cancellation = CancellationException("stop-after-commit")
                val calls = mutableListOf<Long>()
                val processor =
                    fixture.processor(
                        collectEvidence = { batch ->
                            calls += batch.last().id
                            DreamingBatchEvidence(listOf(evidence(batch)))
                        },
                        reflectEvidence = { evidence ->
                            evidence.topics.map { insight("raw-${it.topic.sourceTurnIds.single()}", it.newConversationTurns) }
                        },
                        wakeup =
                            OrganizationWakeup {
                                if (fixture.database.read { it.dreaming.readCheckpoint() } == 1L) throw cancellation
                            },
                    )
                val coordinator = DreamingRunCoordinator(fixture.database, processor, CountingDreamingBudget(0, 1, 1), pageSize = 1)
                try {
                    assertSame(cancellation, assertFailsWith<CancellationException> { coordinator.runOnce() })
                    assertEquals(listOf(1L), calls)
                    assertEquals(1L, fixture.database.read { it.dreaming.readCheckpoint() })
                    val result = coordinator.runOnce()
                    assertEquals(1L, result.startCheckpoint)
                    assertEquals(2L, result.endCheckpoint)
                    assertEquals(listOf(1L, 2L), calls)
                    assertEquals(2, fixture.database.read { it.rawMemories.scanPage(0, 10).size })
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `evidence failure preserves prior state and stops later batches`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                inserted(fixture.database, "one")
                inserted(fixture.database, "two")
                inserted(fixture.database, "three")
                val evidenceFailure = IllegalStateException("evidence")
                val evidenceCalls = mutableListOf<Long>()
                val reflectionCalls = mutableListOf<Long>()
                var wakeups = 0
                val processor =
                    fixture.processor(
                        collectEvidence = { batch ->
                            evidenceCalls += batch.last().id
                            if (batch.last().id == 2L) {
                                throw evidenceFailure
                            }
                            DreamingBatchEvidence(listOf(evidence(batch)))
                        },
                        reflectEvidence = { evidence ->
                            reflectionCalls +=
                                evidence.topics
                                    .single()
                                    .topic.sourceTurnIds
                                    .single()
                            evidence.topics.map { insight("raw-${it.topic.sourceTurnIds.single()}", it.newConversationTurns) }
                        },
                        wakeup = OrganizationWakeup { wakeups++ },
                    )
                val coordinator = DreamingRunCoordinator(fixture.database, processor, CountingDreamingBudget(0, 1, 1), pageSize = 1)
                try {
                    val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }
                    assertEquals(DreamingRunStage.BATCH_PROCESSING, failure.stage)
                    assertSame(evidenceFailure, failure.cause)
                    assertEquals(1L, failure.partialResult.endCheckpoint)
                    assertEquals(1L, fixture.database.read { it.dreaming.readCheckpoint() })
                    assertEquals(1, fixture.database.read { it.rawMemories.scanPage(0, 10).size })
                    assertEquals(listOf("raw-1"), fixture.database.read { it.rawMemories.scanPage(0, 10).map { it.content } })
                    assertEquals(1, fixture.database.read { it.pending.listAllByRawCreatedAt().size })
                    assertEquals(listOf(1L, 2L), evidenceCalls)
                    assertEquals(listOf(1L), reflectionCalls)
                    assertEquals(1, wakeups)
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `reflection failure preserves prior partial`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                inserted(fixture.database, "one")
                inserted(fixture.database, "two")
                inserted(fixture.database, "three")
                val reflectionFailure = IllegalStateException("reflection")
                val reflectionCalls = mutableListOf<Long>()
                var wakeups = 0
                val processor =
                    fixture.processor(
                        collectEvidence = { batch -> DreamingBatchEvidence(listOf(evidence(batch))) },
                        reflectEvidence = { evidence ->
                            val turnId =
                                evidence.topics
                                    .single()
                                    .topic.sourceTurnIds
                                    .single()
                            reflectionCalls += turnId
                            if (turnId == 2L) {
                                throw reflectionFailure
                            }
                            listOf(insight("first", evidence.topics.single().newConversationTurns))
                        },
                        wakeup = OrganizationWakeup { wakeups++ },
                    )
                val coordinator = DreamingRunCoordinator(fixture.database, processor, CountingDreamingBudget(0, 1, 1), pageSize = 1)
                try {
                    val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }
                    assertEquals(DreamingRunStage.BATCH_PROCESSING, failure.stage)
                    assertSame(reflectionFailure, failure.cause)
                    assertEquals(1L, failure.partialResult.endCheckpoint)
                    assertEquals(1, failure.partialResult.generatedMemories)
                    assertEquals(1L, fixture.database.read { it.dreaming.readCheckpoint() })
                    assertEquals(1, fixture.database.read { it.rawMemories.scanPage(0, 10).size })
                    assertEquals(listOf("first"), fixture.database.read { it.rawMemories.scanPage(0, 10).map { it.content } })
                    assertEquals(1, fixture.database.read { it.pending.listAllByRawCreatedAt().size })
                    assertEquals(listOf(1L, 2L), reflectionCalls)
                    assertEquals(1, wakeups)
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    @Test
    fun `real sqlite commit failure rolls back current batch and stops later batches`() =
        runBlocking {
            withDreamingDatabase { fixture ->
                inserted(fixture.database, "one")
                inserted(fixture.database, "two")
                inserted(fixture.database, "three")
                val reflectionCalls = mutableListOf<Long>()
                var wakeups = 0
                val faultDatabase = DreamingCommitFaultDatabase(fixture.database) { _, next -> next == 2L }
                val processor =
                    fixture.processor(
                        collectEvidence = { batch -> DreamingBatchEvidence(listOf(evidence(batch))) },
                        reflectEvidence = { evidence ->
                            val turnId =
                                evidence.topics
                                    .single()
                                    .topic.sourceTurnIds
                                    .single()
                            reflectionCalls += turnId
                            evidence.topics.map { insight("raw-$turnId", it.newConversationTurns) }
                        },
                        committer = DreamingCommitter(faultDatabase, SequenceIds(), fixedClock()),
                        wakeup = OrganizationWakeup { wakeups++ },
                    )
                val coordinator = DreamingRunCoordinator(fixture.database, processor, CountingDreamingBudget(0, 1, 1), pageSize = 1)
                try {
                    val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }

                    assertEquals(DreamingRunStage.BATCH_PROCESSING, failure.stage)
                    assertTrue(failure.cause is com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitException)
                    assertEquals(1L, failure.partialResult.endCheckpoint)
                    assertEquals(1, failure.partialResult.generatedMemories)
                    assertEquals(listOf(0L to 1L, 1L to 2L), faultDatabase.casAttempts)
                    assertEquals(listOf(1L, 2L), reflectionCalls)
                    assertEquals(1, wakeups)
                    val raws = fixture.database.read { it.rawMemories.scanPage(0, 10) }
                    assertEquals(listOf("raw-1"), raws.map { it.content })
                    assertEquals(listOf(raws.single().id), fixture.database.read { it.pending.listAllByRawCreatedAt() })
                    assertEquals(1L, fixture.database.read { it.dreaming.readCheckpoint() })
                } finally {
                    coordinator.closeAsync()
                }
            }
        }

    private fun DreamingTestDatabase.processor(
        collectEvidence: suspend (List<StoredConversationTurn>) -> DreamingBatchEvidence,
        reflectEvidence: suspend (DreamingBatchEvidence) -> List<ValidatedDreamingInsight>,
        committer: DreamingCommitter = DreamingCommitter(database, SequenceIds(), fixedClock()),
        wakeup: OrganizationWakeup = OrganizationWakeup { },
    ): LlmDreamingBatchProcessor =
        LlmDreamingBatchProcessor(
            database = database,
            collectEvidence = collectEvidence,
            reflectEvidence = reflectEvidence,
            committer = committer,
            organizationWakeup = wakeup,
        )

    private suspend fun inserted(
        database: ContextDatabase,
        id: String,
    ): StoredConversationTurn {
        insertDreamingTurn(database, sessionTurnId = id)
        return database.read { read -> read.conversations.idRange(0, Long.MAX_VALUE, 100).last() }
    }

    private fun evidence(batch: List<StoredConversationTurn>): DreamingTopicEvidence =
        DreamingTopicEvidence(
            topic =
                com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic(
                    "topic",
                    "description",
                    emptyList(),
                    listOf(batch.last().id),
                ),
            decision = DreamingEvidenceDecision.NEEDS_REFLECTION,
            newConversationTurns = listOf(batch.last()),
            organizedMemories = emptyList(),
            rawMemories = emptyList(),
            historicalConversationTurns = emptyList(),
        )

    private fun insight(
        content: String,
        batch: List<StoredConversationTurn>,
    ): ValidatedDreamingInsight =
        ValidatedDreamingInsight(
            content,
            listOf(
                com.github.noahshen.alcyone.context.memory.api.MemorySourceRef.ConversationTurnRef(
                    batch.last().runtimeId,
                    batch.last().agentId,
                    batch.last().sessionId,
                    batch.last().sessionTurnId,
                ),
            ),
        )

    private class SequenceIds : MemoryIdGenerator {
        private val ids = AtomicInteger()

        override fun nextRawMemoryId(): String = "raw-${ids.incrementAndGet()}"

        override fun nextOrganizedMemoryId(): String = error("not used")
    }

    private fun pipelineConfig(): LlmConfig {
        val base = dreamingTopicLlmConfig(maxInputTokens = 1, maxOutputTokens = 1, contextWindowTokens = 100)
        val profile = base.profiles.getValue("topic")
        val task = LlmTaskConfig(profile.name, 1, 1)
        return LlmConfig(
            profiles = base.profiles,
            tasks =
                mapOf(
                    LlmTask.DREAMING_TOPIC to base.tasks.getValue(LlmTask.DREAMING_TOPIC),
                    LlmTask.MEMORY_SEARCH to task,
                    LlmTask.DREAMING_EVIDENCE to task,
                    LlmTask.DREAMING_REFLECTION to task,
                ),
            safetyMarginPercent = 0,
        )
    }

    private class PipelineEstimator : TokenEstimator {
        override fun estimateText(text: String): Int = text.length

        override fun estimatePrompt(messages: List<LlmMessage>): Int =
            if (messages.last().content.contains("<turn id=")) {
                messages
                    .last()
                    .content
                    .split("<turn id=")
                    .size -
                    1
            } else {
                0
            }

        override fun estimateSchema(schema: String?): Int = 0
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), ZoneOffset.UTC)
}
