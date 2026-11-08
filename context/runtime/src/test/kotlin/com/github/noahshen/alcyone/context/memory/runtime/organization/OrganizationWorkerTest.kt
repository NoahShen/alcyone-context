package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceAction
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecision
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitResult
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CT16 B1 A04/A07：SourceGuard、固定轮列表、逐条隔离、Map 接缝与 CE。 */
class OrganizationWorkerTest {
    private val now = Instant.parse("2026-11-07T04:00:00Z")

    @Test
    fun `an associated source with pending cleans it without scanner governance commit or map`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-associated-pending"
            prepareRaw(test.database, rawId, now, pending = true)
            insertOrganized(test.database, target(), listOf(rawId))
            val retriever = RecordingCandidateRetriever()
            val decider = RecordingGovernanceDecider { _, _ -> decision() }
            val commit = RecordingCommitOperation()
            val map = RecordingMapWakeup()

            worker(test.database, retriever, decider, commit, map).runRound()

            assertFalse(test.database.read { it.pending.exists(rawId) })
            assertTrue(retriever.raws.isEmpty())
            assertTrue(decider.calls.isEmpty())
            assertTrue(commit.calls.isEmpty())
            assertEquals(0, map.calls.get())
        }

    @Test
    fun `an associated source without pending is a no-op`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-associated"
            prepareRaw(test.database, rawId, now, pending = false)
            insertOrganized(test.database, target(), listOf(rawId))
            val retriever = RecordingCandidateRetriever()
            val map = RecordingMapWakeup()

            worker(test.database, retriever, RecordingGovernanceDecider { _, _ -> decision() }, RecordingCommitOperation(), map).runRound()

            assertTrue(retriever.raws.isEmpty())
            assertEquals(0, map.calls.get())
        }

    @Test
    fun `a source disappearing between guard read and write never deletes pending`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-source-race"
            prepareRaw(test.database, rawId, now, pending = true)
            insertOrganized(test.database, target(), listOf(rawId))
            var removed = false
            val racingDatabase =
                object : ContextDatabase {
                    override suspend fun <T> read(block: (ReadContext) -> T): T = test.database.read(block)

                    override suspend fun <T> write(block: (TransactionContext) -> T): T {
                        if (!removed) {
                            removed = true
                            executeSqlite(
                                test.path,
                                "DELETE FROM organized_memory_source WHERE raw_memory_id = '$rawId'",
                            )
                        }
                        return test.database.write(block)
                    }
                }

            assertFalse(OrganizationSourceGuard(racingDatabase).isAlreadyAssociated(rawId))
            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertTrue(test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) }.isEmpty())
        }

    @Test
    fun `unassociated raw runs scanner governance commit and one map wakeup`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-new"
            prepareRaw(test.database, rawId, now, pending = true)
            val candidate = target()
            val selected = decision()
            val retriever = RecordingCandidateRetriever { listOf(candidate) }
            val decider = RecordingGovernanceDecider { _, _ -> selected }
            val commit =
                RecordingCommitOperation { id, candidates, actualDecision ->
                    assertEquals(rawId, id)
                    assertEquals(listOf(candidate), candidates)
                    assertEquals(selected, actualDecision)
                    OrganizationCommitResult.Committed(GovernanceAction.NEW, "org-new")
                }
            val map = RecordingMapWakeup()

            worker(test.database, retriever, decider, commit, map).runRound()

            assertEquals(listOf(rawId), retriever.raws.map { it.id })
            assertEquals(1, decider.calls.size)
            assertEquals(1, commit.calls.size)
            assertEquals(1, map.calls.get())
        }

    @Test
    fun `a round list is fixed before processing and new pending appears only on the next round`() =
        withOrganizationDatabase { test ->
            val firstRaw = "raw-first"
            val newRaw = "raw-new-during-round"
            prepareRaw(test.database, firstRaw, now, pending = true)
            val retriever =
                RecordingCandidateRetriever {
                    if (it.id == firstRaw && it.id !in inserted) {
                        inserted += it.id
                        prepareRaw(test.database, newRaw, now.plusMillis(1), pending = true)
                    }
                    emptyList()
                }
            val worker = worker(test.database, retriever, RecordingGovernanceDecider { _, _ -> decision() }, RecordingCommitOperation())

            worker.runRound()
            assertEquals(listOf(firstRaw), retriever.raws.map { it.id })

            worker.runRound()
            assertEquals(listOf(firstRaw, firstRaw, newRaw), retriever.raws.map { it.id })
        }

    @Test
    fun `one item failure continues with later items and preserves only the failed pending`() =
        withOrganizationDatabase { test ->
            Slf4jCapture().use { capture ->
                val failedRaw = "raw-a-fails"
                val successRaw = "raw-b-succeeds"
                prepareRaw(test.database, failedRaw, now.minusSeconds(1), pending = true)
                prepareRaw(test.database, successRaw, now, pending = true)
                val retriever =
                    RecordingCandidateRetriever {
                        if (it.id == failedRaw) {
                            throw ContextPersistenceException(
                                ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                                "ct16-item-failure-marker",
                            )
                        }
                        emptyList()
                    }
                val commit =
                    RecordingCommitOperation { id, _, _ ->
                        assertEquals(successRaw, id)
                        test.database.write { tx -> assertEquals(1, tx.pending.dequeue(id)) }
                        OrganizationCommitResult.Committed(GovernanceAction.NEW, "org-success")
                    }

                worker(test.database, retriever, RecordingGovernanceDecider { _, _ -> decision() }, commit).runRound()

                assertEquals(listOf(failedRaw, successRaw), retriever.raws.map { it.id })
                assertTrue(test.database.read { it.pending.exists(failedRaw) }, "失败项 Pending 必须保留")
                assertFalse(test.database.read { it.pending.exists(successRaw) })
                val itemLog = capture.logs.single { it.formattedMessage.contains("organization.item.failed") }
                assertNull(itemLog.throwable)
                assertFalse(itemLog.formattedMessage.contains("ct16-item-failure-marker"))
                assertFalse(itemLog.formattedMessage.contains(failedRaw))
            }
        }

    @Test
    fun `round level pending list failure escapes for scheduler backoff handling`() =
        runBlocking {
            withOrganizationDatabase { test ->
                val tracking = TrackingOrganizationDatabase(test.database, readFailures = 1)
                val retriever = RecordingCandidateRetriever()
                val worker = worker(tracking, retriever, RecordingGovernanceDecider { _, _ -> decision() }, RecordingCommitOperation())

                val failure = assertFailsWith<ContextPersistenceException> { worker.runRound() }

                assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
                assertTrue(retriever.raws.isEmpty(), "固定列表读取失败必须在逐条处理前退出")
            }
        }

    @Test
    fun `cancellation from an item propagates unchanged and leaves pending`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-cancel-item"
            prepareRaw(test.database, rawId, now, pending = true)
            val cancellation = CancellationException("ct16-item-cancel-marker")
            val retriever = RecordingCandidateRetriever { throw cancellation }

            val observed =
                assertFailsWith<CancellationException> {
                    worker(
                        test.database,
                        retriever,
                        RecordingGovernanceDecider {
                            _,
                            _,
                            ->
                            decision()
                        },
                        RecordingCommitOperation(),
                    ).runRound()
                }

            assertEquals(cancellation.message, observed.message)
            assertTrue(test.database.read { it.pending.exists(rawId) })
        }

    @Test
    fun `map ordinary failure is isolated without rollback or stopping later items`() =
        withOrganizationDatabase { test ->
            Slf4jCapture().use { capture ->
                prepareRaw(test.database, "raw-map-a", now.minusSeconds(1), pending = true)
                prepareRaw(test.database, "raw-map-b", now, pending = true)
                val commits = RecordingCommitOperation { id, _, _ -> OrganizationCommitResult.Committed(GovernanceAction.NEW, "org-$id") }
                val map = RecordingMapWakeup { if (mapAttempts++ == 0) throw IllegalStateException("ct16-map-marker") }

                worker(
                    test.database,
                    RecordingCandidateRetriever(),
                    RecordingGovernanceDecider { _, _ -> decision() },
                    commits,
                    map,
                ).runRound()

                assertEquals(2, commits.calls.size)
                assertEquals(2, map.calls.get())
                val log = capture.logs.single { it.formattedMessage.contains("organization.map.wakeup.failed") }
                assertNull(log.throwable)
                assertFalse(log.formattedMessage.contains("ct16-map-marker"))
            }
        }

    @Test
    fun `map cancellation propagates`() =
        withOrganizationDatabase { test ->
            prepareRaw(test.database, "raw-map-cancel", now, pending = true)
            val cancellation = CancellationException("ct16-map-cancel-marker")
            val map = RecordingMapWakeup { throw cancellation }

            val observed =
                assertFailsWith<CancellationException> {
                    worker(
                        test.database,
                        RecordingCandidateRetriever(),
                        RecordingGovernanceDecider { _, _ -> decision() },
                        RecordingCommitOperation(),
                        map,
                    ).runRound()
                }

            assertEquals(cancellation.message, observed.message)
            assertEquals(1, map.calls.get())
        }

    @Test
    fun `already associated commit result does not invoke map`() =
        withOrganizationDatabase { test ->
            prepareRaw(test.database, "raw-already", now, pending = true)
            val map = RecordingMapWakeup()
            val commit = RecordingCommitOperation { _, _, _ -> OrganizationCommitResult.AlreadyAssociated(pendingRemoved = false) }

            worker(test.database, RecordingCandidateRetriever(), RecordingGovernanceDecider { _, _ -> decision() }, commit, map).runRound()

            assertEquals(1, commit.calls.size)
            assertEquals(0, map.calls.get())
        }

    private var mapAttempts = 0
    private val inserted = mutableSetOf<String>()

    private fun worker(
        database: ContextDatabase,
        retriever: RecordingCandidateRetriever,
        decider: RecordingGovernanceDecider,
        commit: RecordingCommitOperation,
        map: RecordingMapWakeup = RecordingMapWakeup(),
    ): DefaultOrganizationWorker =
        DefaultOrganizationWorker(
            database = database,
            sourceGuard = OrganizationSourceGuard(database),
            candidateRetriever = retriever,
            governanceDecider = decider,
            commit = commit,
            mapWakeup = map,
        )

    private fun target(): OrganizedMemory =
        OrganizedMemory(
            id = "org-target",
            content = "target",
            domains = listOf("domain"),
            rawMemoryIds = emptyList(),
            createdAt = now.minusSeconds(10),
            updatedAt = now.minusSeconds(5),
        )

    private fun decision(): GovernanceDecision =
        GovernanceDecision(GovernanceAction.NEW, content = "new content", domains = listOf("domain"))
}
