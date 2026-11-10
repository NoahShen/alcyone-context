package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DreamingConversationEvidenceTracerTest {
    @Test
    fun `direct conversation ref uses business key and adds same session previous and next turns`() =
        withDreamingDatabase { fixture ->
            val before = turn(fixture, "before", EVIDENCE_BASE.minusSeconds(1))
            val anchor = turn(fixture, "anchor", EVIDENCE_BASE)
            val after = turn(fixture, "after", EVIDENCE_BASE.plusSeconds(1))
            turn(fixture, "other-session", EVIDENCE_BASE.plusSeconds(2), sessionId = "other-session")
            val raw = evidenceRaw("R-1", sources = listOf(conversationRef(anchor)))
            insertEvidenceRaw(fixture.database, raw)

            val result = DreamingConversationEvidenceTracer(fixture.database).trace(candidate(raw = listOf(raw)), listOf("R-1"))

            assertEquals(listOf(before.id, anchor.id, after.id), result.historicalConversationTurns.map { it.id })
            assertEquals(listOf("R-1"), result.rawMemories.map { it.id })
        }

    @Test
    fun `same timestamp forward window uses strict id tie break and handles first and last anchors`() =
        withDreamingDatabase { fixture ->
            val first = turn(fixture, "first", EVIDENCE_BASE)
            val anchor = turn(fixture, "anchor", EVIDENCE_BASE)
            val later = turn(fixture, "later", EVIDENCE_BASE)
            val raw = evidenceRaw("R-1", sources = listOf(conversationRef(anchor)))
            insertEvidenceRaw(fixture.database, raw)
            val tracer = DreamingConversationEvidenceTracer(fixture.database)

            val middle = tracer.trace(candidate(raw = listOf(raw)), listOf("R-1"))
            assertEquals(listOf(first.id, anchor.id, later.id), middle.historicalConversationTurns.map { it.id })

            val firstRaw = evidenceRaw("R-FIRST", sources = listOf(conversationRef(first)), createdAt = EVIDENCE_BASE.plusSeconds(10))
            insertEvidenceRaw(fixture.database, firstRaw)
            assertEquals(
                listOf(first.id, anchor.id),
                tracer.trace(candidate(raw = listOf(firstRaw)), listOf("R-FIRST")).historicalConversationTurns.map { it.id },
            )

            val lastRaw = evidenceRaw("R-LAST", sources = listOf(conversationRef(later)), createdAt = EVIDENCE_BASE.plusSeconds(11))
            insertEvidenceRaw(fixture.database, lastRaw)
            assertEquals(
                listOf(anchor.id, later.id),
                tracer.trace(candidate(raw = listOf(lastRaw)), listOf("R-LAST")).historicalConversationTurns.map { it.id },
            )
        }

    @Test
    fun `neighbors are only one hop and current topic business key is excluded`() =
        withDreamingDatabase { fixture ->
            val turns =
                (1..5).map { index ->
                    turn(fixture, "turn-$index", EVIDENCE_BASE.plusSeconds(index.toLong()))
                }
            val anchor = turns[2]
            val raw = evidenceRaw("R-1", sources = listOf(conversationRef(anchor)))
            insertEvidenceRaw(fixture.database, raw)
            val newTurn = anchor.copy(id = 999L)

            val result =
                DreamingConversationEvidenceTracer(fixture.database)
                    .trace(candidate(newTurns = listOf(newTurn), raw = listOf(raw)), listOf("R-1"))

            assertEquals(
                listOf(turns[1].sessionTurnId, turns[3].sessionTurnId),
                result.historicalConversationTurns.map { it.sessionTurnId },
            )
        }

    @Test
    fun `multi level raw refs contribute every chain raw and indirect conversation source`() =
        withDreamingDatabase { fixture ->
            val anchor = turn(fixture, "anchor", EVIDENCE_BASE)
            val leaf = evidenceRaw("R-LEAF", sources = listOf(conversationRef(anchor)), createdAt = EVIDENCE_BASE.plusSeconds(2))
            val child =
                evidenceRaw("R-CHILD", sources = listOf(MemorySourceRef.RawMemoryRef(leaf.id)), createdAt = EVIDENCE_BASE.plusSeconds(1))
            val root = evidenceRaw("R-ROOT", sources = listOf(MemorySourceRef.RawMemoryRef(child.id)))
            listOf(leaf, child, root).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }

            val result = DreamingConversationEvidenceTracer(fixture.database).trace(candidate(raw = listOf(root)), listOf(root.id))

            assertEquals(listOf("R-ROOT", "R-CHILD", "R-LEAF"), result.rawMemories.map { it.id })
            assertEquals(listOf(anchor.id), result.historicalConversationTurns.map { it.id })
        }

    @Test
    fun `diamond raw references are accepted and deduplicated`() =
        withDreamingDatabase { fixture ->
            val anchor = turn(fixture, "anchor", EVIDENCE_BASE)
            val shared = evidenceRaw("R-SHARED", sources = listOf(conversationRef(anchor)), createdAt = EVIDENCE_BASE.plusSeconds(2))
            val left =
                evidenceRaw("R-LEFT", sources = listOf(MemorySourceRef.RawMemoryRef(shared.id)), createdAt = EVIDENCE_BASE.plusSeconds(1))
            val right =
                evidenceRaw("R-RIGHT", sources = listOf(MemorySourceRef.RawMemoryRef(shared.id)), createdAt = EVIDENCE_BASE.plusSeconds(3))
            listOf(shared, left, right).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }

            val result =
                DreamingConversationEvidenceTracer(fixture.database)
                    .trace(candidate(raw = listOf(left, right)), listOf(left.id, right.id))

            assertEquals(listOf("R-LEFT", "R-SHARED", "R-RIGHT"), result.rawMemories.map { it.id })
            assertEquals(1, result.historicalConversationTurns.size)
        }

    @Test
    fun `self cycle and two node cycle fail closed`() =
        withDreamingDatabase { fixture ->
            val self = evidenceRaw("R-SELF", sources = listOf(MemorySourceRef.RawMemoryRef("R-SELF")))
            insertEvidenceRaw(fixture.database, self)
            assertFailsWith<DreamingEvidenceTraceException> {
                DreamingConversationEvidenceTracer(fixture.database).trace(candidate(raw = listOf(self)), listOf(self.id))
            }

            val first = evidenceRaw("R-1", sources = listOf(MemorySourceRef.RawMemoryRef("R-2")))
            val second = evidenceRaw("R-2", sources = listOf(MemorySourceRef.RawMemoryRef("R-1")))
            listOf(first, second).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }
            val failure =
                assertFailsWith<DreamingEvidenceTraceException> {
                    DreamingConversationEvidenceTracer(fixture.database).trace(candidate(raw = listOf(first)), listOf(first.id))
                }
            assertTrue(!failure.message.orEmpty().contains("R-1"))
            assertTrue(!failure.message.orEmpty().contains("R-2"))
        }

    @Test
    fun `missing linked raw and missing conversation turn fail closed`() =
        withDreamingDatabase { fixture ->
            val missingRaw = evidenceRaw("R-ROOT", sources = listOf(MemorySourceRef.RawMemoryRef("R-MISSING")))
            insertEvidenceRaw(fixture.database, missingRaw)
            assertFailsWith<DreamingEvidenceTraceException> {
                DreamingConversationEvidenceTracer(fixture.database).trace(candidate(raw = listOf(missingRaw)), listOf(missingRaw.id))
            }

            val missingTurn =
                evidenceRaw(
                    "R-TURN",
                    sources = listOf(MemorySourceRef.ConversationTurnRef("runtime", "agent", "session", "missing-turn")),
                )
            insertEvidenceRaw(fixture.database, missingTurn)
            assertFailsWith<DreamingEvidenceTraceException> {
                DreamingConversationEvidenceTracer(fixture.database).trace(candidate(raw = listOf(missingTurn)), listOf(missingTurn.id))
            }
        }

    @Test
    fun `unselected B1 hits stay in final raw but only selected roots are traced`() =
        withDreamingDatabase { fixture ->
            val anchor = turn(fixture, "anchor", EVIDENCE_BASE)
            val unselected =
                evidenceRaw(
                    "R-UNSELECTED",
                    sources = listOf(MemorySourceRef.ConversationTurnRef("runtime", "agent", "session", "missing")),
                    createdAt = EVIDENCE_BASE.plusSeconds(1),
                )
            val selected = evidenceRaw("R-SELECTED", sources = listOf(conversationRef(anchor)), createdAt = EVIDENCE_BASE.plusSeconds(2))
            listOf(unselected, selected).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }

            val result =
                DreamingConversationEvidenceTracer(fixture.database)
                    .trace(candidate(raw = listOf(unselected, selected)), listOf(selected.id))

            assertEquals(listOf("R-UNSELECTED", "R-SELECTED"), result.rawMemories.map { it.id })
            assertEquals(listOf(anchor.id), result.historicalConversationTurns.map { it.id })
        }

    @Test
    fun `every B1 and chain raw is reverse enriched and merged with B1 organized evidence`() =
        withDreamingDatabase { fixture ->
            val anchor = turn(fixture, "anchor", EVIDENCE_BASE)
            val b1Raw = evidenceRaw("R-B1", createdAt = EVIDENCE_BASE.plusSeconds(1))
            val chainRaw = evidenceRaw("R-CHAIN", sources = listOf(conversationRef(anchor)), createdAt = EVIDENCE_BASE.plusSeconds(2))
            val rootRaw =
                evidenceRaw("R-ROOT", sources = listOf(MemorySourceRef.RawMemoryRef(chainRaw.id)), createdAt = EVIDENCE_BASE.plusSeconds(3))
            listOf(b1Raw, chainRaw, rootRaw).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }
            val o1 = evidenceOrganized("O-1", listOf(b1Raw.id))
            val o2 = evidenceOrganized("O-2", listOf(rootRaw.id))
            val o3 = evidenceOrganized("O-3", listOf(chainRaw.id))
            listOf(o1, o2, o3).forEach { organized -> insertEvidenceOrganized(fixture.database, organized) }

            val result =
                DreamingConversationEvidenceTracer(fixture.database)
                    .trace(candidate(organized = listOf(o1), raw = listOf(b1Raw, rootRaw)), listOf(rootRaw.id))

            assertEquals(listOf("O-1", "O-2", "O-3"), result.organizedMemories.map { it.id })
            assertEquals(listOf("R-B1", "R-CHAIN", "R-ROOT"), result.rawMemories.map { it.id })
        }

    @Test
    fun `missing reverse organized id fails the whole trace`() =
        withDreamingDatabase { fixture ->
            val raw = evidenceRaw("R-1")
            insertEvidenceRaw(fixture.database, raw)
            val tracking = TrackingEvidenceDatabase(fixture.database) { _ -> listOf("O-MISSING") }

            assertFailsWith<DreamingEvidenceTraceException> {
                DreamingConversationEvidenceTracer(tracking).trace(candidate(raw = listOf(raw)), emptyList())
            }
        }

    @Test
    fun `same turn from multiple raw roots is counted once`() =
        withDreamingDatabase { fixture ->
            val anchor = turn(fixture, "anchor", EVIDENCE_BASE)
            val first = evidenceRaw("R-1", sources = listOf(conversationRef(anchor), conversationRef(anchor)), createdAt = EVIDENCE_BASE)
            val second = evidenceRaw("R-2", sources = listOf(conversationRef(anchor)), createdAt = EVIDENCE_BASE.plusSeconds(1))
            listOf(first, second).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }

            val result =
                DreamingConversationEvidenceTracer(fixture.database)
                    .trace(candidate(raw = listOf(first, second)), listOf(first.id, second.id))

            assertEquals(listOf(anchor.id), result.historicalConversationTurns.map { it.id })
        }

    @Test
    fun `empty need roots preserve all B1 raw and reverse organized evidence without source trace`() =
        withDreamingDatabase { fixture ->
            val raw =
                evidenceRaw(
                    "R-1",
                    sources = listOf(MemorySourceRef.ConversationTurnRef("runtime", "agent", "session", "missing")),
                )
            insertEvidenceRaw(fixture.database, raw)
            val organized = evidenceOrganized("O-1", listOf(raw.id))
            insertEvidenceOrganized(fixture.database, organized)

            val result = DreamingConversationEvidenceTracer(fixture.database).trace(candidate(raw = listOf(raw)), emptyList())

            assertEquals(listOf("R-1"), result.rawMemories.map { it.id })
            assertEquals(listOf("O-1"), result.organizedMemories.map { it.id })
            assertEquals(emptyList(), result.historicalConversationTurns)
        }

    @Test
    fun `current batch turns are not neighbor anchors and multiple root windows union once`() =
        withDreamingDatabase { fixture ->
            val otherBefore = turn(fixture, "other-before", EVIDENCE_BASE.minusSeconds(1), sessionId = "other-session")
            val current = turn(fixture, "current", EVIDENCE_BASE, sessionId = "other-session")
            val otherAfter = turn(fixture, "other-after", EVIDENCE_BASE.plusSeconds(1), sessionId = "other-session")
            val firstAnchor = turn(fixture, "anchor-1", EVIDENCE_BASE.plusSeconds(10))
            val secondAnchor = turn(fixture, "anchor-2", EVIDENCE_BASE.plusSeconds(11))
            val firstRaw = evidenceRaw("R-1", sources = listOf(conversationRef(firstAnchor)), createdAt = EVIDENCE_BASE.plusSeconds(2))
            val secondRaw = evidenceRaw("R-2", sources = listOf(conversationRef(secondAnchor)), createdAt = EVIDENCE_BASE.plusSeconds(3))
            listOf(firstRaw, secondRaw).forEach { raw -> insertEvidenceRaw(fixture.database, raw) }

            val result =
                DreamingConversationEvidenceTracer(fixture.database)
                    .trace(candidate(newTurns = listOf(current), raw = listOf(firstRaw, secondRaw)), listOf(firstRaw.id, secondRaw.id))

            assertEquals(listOf(firstAnchor.id, secondAnchor.id), result.historicalConversationTurns.map { it.id })
            assertTrue(result.historicalConversationTurns.none { it.id == otherBefore.id || it.id == otherAfter.id })
        }

    private suspend fun turn(
        fixture: DreamingTestDatabase,
        sessionTurnId: String,
        timestamp: java.time.Instant,
        runtimeId: String = "runtime",
        agentId: String = "agent",
        sessionId: String = "session",
    ): StoredConversationTurn =
        insertEvidenceConversationTurn(
            database = fixture.database,
            runtimeId = runtimeId,
            agentId = agentId,
            sessionId = sessionId,
            sessionTurnId = sessionTurnId,
            timestamp = timestamp,
        )

    private fun conversationRef(turn: StoredConversationTurn): MemorySourceRef.ConversationTurnRef =
        MemorySourceRef.ConversationTurnRef(turn.runtimeId, turn.agentId, turn.sessionId, turn.sessionTurnId)

    private fun candidate(
        newTurns: List<StoredConversationTurn> = emptyList(),
        organized: List<OrganizedMemory> = emptyList(),
        raw: List<RawMemory>,
    ): DreamingTopicEvidenceCandidate =
        DreamingTopicEvidenceCandidate(
            topic = evidenceTopic(sourceTurnIds = newTurns.map { turn -> turn.id }),
            decision = DreamingNoveltyDecision.NEEDS_EVIDENCE,
            newConversationTurns = newTurns,
            organizedMemories = organized,
            rawMemories = raw,
        )
}
