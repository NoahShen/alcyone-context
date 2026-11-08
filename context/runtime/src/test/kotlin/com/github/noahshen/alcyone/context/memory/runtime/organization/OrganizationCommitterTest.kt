package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceAction
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecision
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitException
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitReason
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitResult
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitter
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CT15-B1：真实 SQLite 上的 Source 幂等、原子写入、水位、回滚与异常传播。 */
class OrganizationCommitterTest {
    private val now = Instant.parse("2026-11-07T04:00:00.123456Z")
    private val nowMs = Instant.parse("2026-11-07T04:00:00.123Z")

    @Test
    fun `constructor bounds id retry attempts to one through three`() =
        withOrganizationDatabase { test ->
            listOf(0, 4).forEach { invalid ->
                assertFailsWith<IllegalArgumentException> {
                    OrganizationCommitter(
                        test.database,
                        ScriptedOrganizedIdGenerator(emptyList()),
                        OrganizationClock(now),
                        invalid,
                    )
                }
            }
        }

    @Test
    fun `a01 associated source with pending cleans pending before invalid decision`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-associated-pending"
            prepareRaw(test.database, rawId, now.minusSeconds(1), pending = true)
            val target = organized("org-existing", updatedAt = now.minusSeconds(10))
            insertOrganized(test.database, target, listOf(rawId))
            val before = assertNotNull(test.database.read { it.organizedMemories.findById(target.id) })
            val failureClock = CancellingOrganizationClock(CancellationException("must-not-read-clock"))
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), failureClock)

            val result =
                committer.commit(
                    rawId,
                    emptyList(),
                    GovernanceDecision(GovernanceAction.NEW, targetMemoryId = "invalid-target", content = "x", domains = listOf("d")),
                )

            assertIs<OrganizationCommitResult.AlreadyAssociated>(result)
            assertTrue(result.pendingRemoved)
            assertFalse(test.database.read { it.pending.exists(rawId) })
            assertEquals(before, test.database.read { it.organizedMemories.findById(target.id) })
            assertEquals(before.updatedAt, test.database.read { it.organizedMemories.maxUpdatedAt() })
        }

    @Test
    fun `a01 associated source without pending is a no op`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-associated"
            prepareRaw(test.database, rawId, now.minusSeconds(1), pending = false)
            val target = organized("org-existing", updatedAt = now.minusSeconds(10))
            insertOrganized(test.database, target, listOf(rawId))
            val before = assertNotNull(test.database.read { it.organizedMemories.findById(target.id) })
            val committer =
                OrganizationCommitter(
                    test.database,
                    ScriptedOrganizedIdGenerator(emptyList()),
                    CancellingOrganizationClock(CancellationException("must-not-read-clock")),
                )

            val result =
                committer.commit(
                    rawId,
                    emptyList(),
                    GovernanceDecision(GovernanceAction.UPDATE, targetMemoryId = "missing", content = "x"),
                )

            assertIs<OrganizationCommitResult.AlreadyAssociated>(result)
            assertFalse(result.pendingRemoved)
            assertEquals(before, test.database.read { it.organizedMemories.findById(target.id) })
        }

    @Test
    fun `a01 unassociated raw without pending is a stale decision`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-no-pending"
            prepareRaw(test.database, rawId, now, pending = false)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(rawId, emptyList(), validNewDecision())
                }

            assertEquals(OrganizationCommitReason.STALE_DECISION, failure.reason)
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) })
        }

    @Test
    fun `a02 new atomically inserts organized source and removes pending`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-new"
            prepareRaw(test.database, rawId, now, pending = true)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(listOf("org-new")), OrganizationClock(now))

            val result = committer.commit(rawId, emptyList(), validNewDecision())

            val committed = assertIs<OrganizationCommitResult.Committed>(result)
            assertEquals(GovernanceAction.NEW, committed.action)
            assertEquals("org-new", committed.organizedMemoryId)
            val stored = assertNotNull(test.database.read { it.organizedMemories.findById("org-new") })
            assertEquals("new content", stored.content)
            assertEquals(listOf("d1", "d2"), stored.domains)
            assertEquals(nowMs, stored.createdAt)
            assertEquals(nowMs, stored.updatedAt)
            assertEquals(listOf(rawId), stored.rawMemoryIds)
            assertEquals(listOf("org-new"), test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) })
            assertFalse(test.database.read { it.pending.exists(rawId) })
        }

    @Test
    fun `a02 id collision retries the whole transaction with a fresh id`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-collision"
            prepareRaw(test.database, rawId, now, pending = true)
            val existing = organized("org-existing", content = "existing content", updatedAt = now.minusSeconds(5))
            insertOrganized(test.database, existing)
            val existingBefore = assertNotNull(test.database.read { it.organizedMemories.findById("org-existing") })
            val committer =
                OrganizationCommitter(
                    test.database,
                    ScriptedOrganizedIdGenerator(listOf("org-existing", "org-new")),
                    OrganizationClock(now),
                )

            val result = committer.commit(rawId, emptyList(), validNewDecision())

            assertEquals("org-new", assertIs<OrganizationCommitResult.Committed>(result).organizedMemoryId)
            assertEquals(existingBefore, test.database.read { it.organizedMemories.findById("org-existing") }, "已有 ID 不得被覆盖")
            assertEquals(listOf("org-existing", "org-new"), test.database.read { it.organizedMemories.scanPage(0, 10) }.map { it.id })
            assertEquals(listOf("org-new"), test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) })
            assertFalse(test.database.read { it.pending.exists(rawId) })
        }

    @Test
    fun `a02 id collision exhaustion leaves no organized source or pending changes`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-collision-exhausted"
            prepareRaw(test.database, rawId, now, pending = true)
            val existing = organized("org-existing", content = "existing content", updatedAt = now.minusSeconds(5))
            insertOrganized(test.database, existing)
            val existingBefore = assertNotNull(test.database.read { it.organizedMemories.findById("org-existing") })
            val committer =
                OrganizationCommitter(
                    test.database,
                    ScriptedOrganizedIdGenerator(List(3) { "org-existing" }),
                    OrganizationClock(now),
                    maxIdAttempts = 3,
                )

            val failure = assertFailsWith<OrganizationCommitException> { committer.commit(rawId, emptyList(), validNewDecision()) }

            assertEquals(OrganizationCommitReason.ID_COLLISION_EXHAUSTED, failure.reason)
            assertEquals(listOf(existingBefore), test.database.read { it.organizedMemories.scanPage(0, 10) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) })
            assertTrue(test.database.read { it.pending.exists(rawId) })
        }

    @Test
    fun `a03 merge without content preserves fields and advances the watermark`() =
        withOrganizationDatabase { test ->
            val fixture = targetFixture(test.database)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val result =
                committer.commit(
                    fixture.currentRawId,
                    listOf(fixture.snapshot),
                    GovernanceDecision(GovernanceAction.MERGE, targetMemoryId = fixture.snapshot.id),
                )

            assertEquals(fixture.snapshot.id, assertIs<OrganizationCommitResult.Committed>(result).organizedMemoryId)
            val stored = assertNotNull(test.database.read { it.organizedMemories.findById(fixture.snapshot.id) })
            assertEquals(fixture.snapshot.content, stored.content)
            assertEquals(fixture.snapshot.domains, stored.domains)
            assertEquals(fixture.snapshot.createdAt, stored.createdAt)
            assertEquals(nowMs, stored.updatedAt, "nowMs 必须严格大于旧 MAX")
            assertEquals(listOf(fixture.oldRawId, fixture.currentRawId).sorted(), stored.rawMemoryIds)
            assertFalse(test.database.read { it.pending.exists(fixture.currentRawId) })
        }

    @Test
    fun `a03 merge with content preserves domains and all previous sources`() =
        withOrganizationDatabase { test ->
            val fixture = targetFixture(test.database)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val result =
                committer.commit(
                    fixture.currentRawId,
                    listOf(fixture.snapshot),
                    GovernanceDecision(GovernanceAction.MERGE, targetMemoryId = fixture.snapshot.id, content = "merged content"),
                )

            assertEquals(fixture.snapshot.id, assertIs<OrganizationCommitResult.Committed>(result).organizedMemoryId)
            val stored = assertNotNull(test.database.read { it.organizedMemories.findById(fixture.snapshot.id) })
            assertEquals("merged content", stored.content)
            assertEquals(fixture.snapshot.domains, stored.domains)
            assertEquals(nowMs, stored.updatedAt)
            assertEquals(listOf(fixture.oldRawId, fixture.currentRawId).sorted(), stored.rawMemoryIds)
        }

    @Test
    fun `a03 update replaces provided domains and preserves previous sources`() =
        withOrganizationDatabase { test ->
            val fixture = targetFixture(test.database)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val result =
                committer.commit(
                    fixture.currentRawId,
                    listOf(fixture.snapshot),
                    GovernanceDecision(
                        GovernanceAction.UPDATE,
                        targetMemoryId = fixture.snapshot.id,
                        content = "updated content",
                        domains = listOf("new-a", "new-b", "new-a"),
                    ),
                )

            assertEquals(fixture.snapshot.id, assertIs<OrganizationCommitResult.Committed>(result).organizedMemoryId)
            val stored = assertNotNull(test.database.read { it.organizedMemories.findById(fixture.snapshot.id) })
            assertEquals("updated content", stored.content)
            assertEquals(listOf("new-a", "new-b"), stored.domains)
            assertEquals(nowMs, stored.updatedAt)
            assertEquals(listOf(fixture.oldRawId, fixture.currentRawId).sorted(), stored.rawMemoryIds)
        }

    @Test
    fun `a03 update without domains preserves domains`() =
        withOrganizationDatabase { test ->
            val fixture = targetFixture(test.database)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            committer.commit(
                fixture.currentRawId,
                listOf(fixture.snapshot),
                GovernanceDecision(GovernanceAction.UPDATE, targetMemoryId = fixture.snapshot.id, content = "updated content"),
            )

            val stored = assertNotNull(test.database.read { it.organizedMemories.findById(fixture.snapshot.id) })
            assertEquals("updated content", stored.content)
            assertEquals(fixture.snapshot.domains, stored.domains)
        }

    @Test
    fun `a04 changed target updated at is stale and pending remains`() =
        withOrganizationDatabase { test ->
            val fixture = targetFixture(test.database)
            val changedAt = fixture.snapshot.updatedAt.plusMillis(7)
            test.database.write { tx -> assertEquals(1, tx.organizedMemories.advanceUpdatedAt(fixture.snapshot.id, changedAt)) }
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(
                        fixture.currentRawId,
                        listOf(fixture.snapshot),
                        GovernanceDecision(GovernanceAction.MERGE, targetMemoryId = fixture.snapshot.id),
                    )
                }

            assertEquals(OrganizationCommitReason.STALE_TARGET, failure.reason)
            assertTrue(test.database.read { it.pending.exists(fixture.currentRawId) })
            assertTrue(test.database.read { it.organizedMemories.findOrganizedByRaw(fixture.currentRawId) }.isEmpty())
            assertEquals(changedAt, assertNotNull(test.database.read { it.organizedMemories.findById(fixture.snapshot.id) }).updatedAt)
        }

    @Test
    fun `a04 missing target is stale and pending remains`() =
        withOrganizationDatabase { test ->
            val currentRawId = "raw-current-missing-target"
            prepareRaw(test.database, currentRawId, now, pending = true)
            val missing = organized("org-missing", updatedAt = now.minusSeconds(1))
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(
                        currentRawId,
                        listOf(missing),
                        GovernanceDecision(GovernanceAction.UPDATE, targetMemoryId = missing.id, content = "updated"),
                    )
                }

            assertEquals(OrganizationCommitReason.STALE_TARGET, failure.reason)
            assertTrue(test.database.read { it.pending.exists(currentRawId) })
            assertTrue(test.database.read { it.organizedMemories.findOrganizedByRaw(currentRawId) }.isEmpty())
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
        }

    @Test
    fun `a04 invalid decision fields are rejected without writes`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-invalid-decision"
            prepareRaw(test.database, rawId, now, pending = true)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(
                        rawId,
                        emptyList(),
                        GovernanceDecision(
                            GovernanceAction.NEW,
                            targetMemoryId = "forbidden-secret-id",
                            content = "secret-content",
                            domains = listOf("secret-domain"),
                        ),
                    )
                }

            assertEquals(OrganizationCommitReason.INVALID_DECISION, failure.reason)
            assertFalse(failure.message.orEmpty().contains("secret"))
            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
            assertTrue(test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) }.isEmpty())
        }

    @Test
    fun `a04 target outside the candidate snapshot is invalid without writes`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-unauthorized-target"
            prepareRaw(test.database, rawId, now, pending = true)
            val candidate = organized("org-candidate", updatedAt = now.minusSeconds(1))
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(
                        rawId,
                        listOf(candidate),
                        GovernanceDecision(GovernanceAction.UPDATE, targetMemoryId = "org-other", content = "updated"),
                    )
                }

            assertEquals(OrganizationCommitReason.INVALID_DECISION, failure.reason)
            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
        }

    @Test
    fun `a05 empty table uses truncated clock for created and updated`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-empty-watermark"
            prepareRaw(test.database, rawId, now, pending = true)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(listOf("org-empty")), OrganizationClock(now))

            committer.commit(rawId, emptyList(), validNewDecision())

            val stored = assertNotNull(test.database.read { it.organizedMemories.findById("org-empty") })
            assertEquals(nowMs, stored.createdAt)
            assertEquals(nowMs, stored.updatedAt)
            assertEquals(
                "2026-11-07T12:00:00.123+08:00",
                sqliteString(test.path, "SELECT created_at FROM organized_memory WHERE id = 'org-empty'"),
            )
            assertEquals(
                "2026-11-07T12:00:00.123+08:00",
                sqliteString(test.path, "SELECT updated_at FROM organized_memory WHERE id = 'org-empty'"),
            )
        }

    @Test
    fun `a05 an existing higher max forces strict next millisecond`() =
        withOrganizationDatabase { test ->
            val existingUpdatedAt = now.plusSeconds(10).truncatedTo(ChronoUnit.MILLIS)
            insertOrganized(test.database, organized("org-high", updatedAt = existingUpdatedAt))
            val rawId = "raw-after-high"
            prepareRaw(test.database, rawId, now, pending = true)
            val committer = OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(listOf("org-next")), OrganizationClock(now))

            committer.commit(rawId, emptyList(), validNewDecision())

            val stored = assertNotNull(test.database.read { it.organizedMemories.findById("org-next") })
            assertEquals(nowMs, stored.createdAt)
            assertEquals(existingUpdatedAt.plusMillis(1), stored.updatedAt)
        }

    @Test
    fun `a05 consecutive commits in the same millisecond strictly increase`() =
        withOrganizationDatabase { test ->
            prepareRaw(test.database, "raw-watermark-1", now, pending = true)
            prepareRaw(test.database, "raw-watermark-2", now, pending = true)
            val committer =
                OrganizationCommitter(
                    test.database,
                    ScriptedOrganizedIdGenerator(listOf("org-watermark-1", "org-watermark-2")),
                    OrganizationClock(now),
                )

            committer.commit("raw-watermark-1", emptyList(), validNewDecision())
            committer.commit("raw-watermark-2", emptyList(), validNewDecision())

            val first = assertNotNull(test.database.read { it.organizedMemories.findById("org-watermark-1") })
            val second = assertNotNull(test.database.read { it.organizedMemories.findById("org-watermark-2") })
            assertEquals(nowMs, first.updatedAt)
            assertEquals(nowMs.plusMillis(1), second.updatedAt)
            assertEquals(nowMs, first.createdAt)
            assertEquals(nowMs, second.createdAt)
        }

    @Test
    fun `a05 instant max fails explicitly without writes`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-after-max"
            prepareRaw(test.database, rawId, now, pending = true)
            val committer =
                OrganizationCommitter(
                    MaxWatermarkDatabase(test.database),
                    ScriptedOrganizedIdGenerator(listOf("org-new")),
                    OrganizationClock(now),
                )

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(rawId, emptyList(), validNewDecision())
                }

            assertEquals(OrganizationCommitReason.WATERMARK_EXHAUSTED, failure.reason)
            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
            assertTrue(test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) }.isEmpty())
        }

    @Test
    fun `a06 organized insert row count mismatch rolls back the transaction`() =
        assertRowCountRollback(RowCountFaultPoint.ORGANIZED_INSERT, 0)

    @Test
    fun `a06 organized update row count mismatch rolls back the transaction`() =
        withOrganizationDatabase { test ->
            val fixture = targetFixture(test.database)
            val failing = RowCountFaultDatabase(test.database, RowCountFaultPoint.ORGANIZED_UPDATE, 2)
            val committer = OrganizationCommitter(failing, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(
                        fixture.currentRawId,
                        listOf(fixture.snapshot),
                        GovernanceDecision(GovernanceAction.UPDATE, targetMemoryId = fixture.snapshot.id, content = "changed"),
                    )
                }

            assertEquals(OrganizationCommitReason.ROW_COUNT_MISMATCH, failure.reason)
            assertEquals(fixture.snapshot, test.database.read { it.organizedMemories.findById(fixture.snapshot.id) })
            assertTrue(test.database.read { it.pending.exists(fixture.currentRawId) })
            assertEquals(listOf(fixture.oldRawId), test.database.read { it.organizedMemories.findSourcesByOrganized(fixture.snapshot.id) })
        }

    @Test
    fun `a06 organized advance row count mismatch rolls back the transaction`() =
        withOrganizationDatabase { test ->
            val fixture = targetFixture(test.database)
            val failing = RowCountFaultDatabase(test.database, RowCountFaultPoint.ORGANIZED_ADVANCE, 0)
            val committer = OrganizationCommitter(failing, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(
                        fixture.currentRawId,
                        listOf(fixture.snapshot),
                        GovernanceDecision(GovernanceAction.MERGE, targetMemoryId = fixture.snapshot.id),
                    )
                }

            assertEquals(OrganizationCommitReason.ROW_COUNT_MISMATCH, failure.reason)
            assertEquals(
                fixture.snapshot.updatedAt,
                assertNotNull(test.database.read { it.organizedMemories.findById(fixture.snapshot.id) }).updatedAt,
            )
            assertTrue(test.database.read { it.pending.exists(fixture.currentRawId) })
            assertEquals(listOf(fixture.oldRawId), test.database.read { it.organizedMemories.findSourcesByOrganized(fixture.snapshot.id) })
        }

    @Test
    fun `a06 source insert and pending delete row counts roll back new writes`() {
        assertRowCountRollback(RowCountFaultPoint.SOURCE_INSERT, 2)
        assertRowCountRollback(RowCountFaultPoint.PENDING_DEQUEUE, 0)
    }

    @Test
    fun `a06 source trigger failure leaves pending and no organized half writes`() =
        withOrganizationDatabase { test ->
            executeSqlite(
                test.path,
                "CREATE TRIGGER fail_ct15_source BEFORE INSERT ON organized_memory_source " +
                    "BEGIN SELECT RAISE(ABORT, 'ct15 source failure'); END",
            )
            val rawId = "raw-source-trigger"
            prepareRaw(test.database, rawId, now, pending = true)
            val committer =
                OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(listOf("org-source-trigger")), OrganizationClock(now))

            assertFailsWith<ContextPersistenceException> { committer.commit(rawId, emptyList(), validNewDecision()) }

            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
            assertEquals(0L, sqliteLong(test.path, "SELECT COUNT(*) FROM organized_memory_source"))
        }

    @Test
    fun `a06 pending delete trigger failure rolls back organized and source`() =
        withOrganizationDatabase { test ->
            executeSqlite(
                test.path,
                "CREATE TRIGGER fail_ct15_pending BEFORE DELETE ON organization_pending " +
                    "BEGIN SELECT RAISE(ABORT, 'ct15 pending failure'); END",
            )
            val rawId = "raw-pending-trigger"
            prepareRaw(test.database, rawId, now, pending = true)
            val committer =
                OrganizationCommitter(test.database, ScriptedOrganizedIdGenerator(listOf("org-pending-trigger")), OrganizationClock(now))

            assertFailsWith<ContextPersistenceException> { committer.commit(rawId, emptyList(), validNewDecision()) }

            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
            assertEquals(0L, sqliteLong(test.path, "SELECT COUNT(*) FROM organized_memory_source"))
        }

    @Test
    fun `a06 associated pending dequeue mismatch rolls back cleanup`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-associated-row-fault"
            prepareRaw(test.database, rawId, now, pending = true)
            val target = organized("org-associated", updatedAt = now.minusSeconds(1))
            insertOrganized(test.database, target, listOf(rawId))
            val before = assertNotNull(test.database.read { it.organizedMemories.findById(target.id) })
            val failing = RowCountFaultDatabase(test.database, RowCountFaultPoint.PENDING_DEQUEUE, 2)
            val committer = OrganizationCommitter(failing, ScriptedOrganizedIdGenerator(emptyList()), OrganizationClock(now))

            val failure =
                assertFailsWith<OrganizationCommitException> {
                    committer.commit(rawId, emptyList(), validNewDecision())
                }

            assertEquals(OrganizationCommitReason.ROW_COUNT_MISMATCH, failure.reason)
            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(before, test.database.read { it.organizedMemories.findById(target.id) })
        }

    @Test
    fun `a07 unknown commit result converges through source idempotency on retry`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-unknown-result"
            prepareRaw(test.database, rawId, now, pending = true)
            val failure =
                ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, "ct15-unknown-after-commit")
            val uncertainDatabase = UnknownCommitResultDatabase(test.database, failure)
            val committer =
                OrganizationCommitter(uncertainDatabase, ScriptedOrganizedIdGenerator(listOf("org-uncertain")), OrganizationClock(now))

            val observed = assertFailsWith<ContextPersistenceException> { committer.commit(rawId, emptyList(), validNewDecision()) }

            assertSame(failure, observed)
            assertEquals(1, test.database.read { it.organizedMemories.scanPage(0, 10) }.size)
            assertEquals(listOf("org-uncertain"), test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) })
            assertFalse(test.database.read { it.pending.exists(rawId) })

            val retried = committer.commit(rawId, emptyList(), validNewDecision())

            assertIs<OrganizationCommitResult.AlreadyAssociated>(retried)
            assertFalse(retried.pendingRemoved)
            assertEquals(1, test.database.read { it.organizedMemories.scanPage(0, 10) }.size, "不得重复 NEW")
        }

    @Test
    fun `a07 cancellation from clock propagates unchanged with no writes`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-cancelled"
            prepareRaw(test.database, rawId, now, pending = true)
            val cancellation = CancellationException("ct15-cancelled")
            val committer =
                OrganizationCommitter(
                    test.database,
                    ScriptedOrganizedIdGenerator(listOf("org-cancelled")),
                    CancellingOrganizationClock(cancellation),
                )

            val observed = assertFailsWith<CancellationException> { committer.commit(rawId, emptyList(), validNewDecision()) }

            assertEquals(cancellation.message, observed.message)
            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) })
        }

    @Test
    fun `a07 context persistence exception passes through unchanged`() =
        withOrganizationDatabase { test ->
            val rawId = "raw-persistence-failure"
            prepareRaw(test.database, rawId, now, pending = true)
            val persistenceFailure =
                ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, "ct15-transaction-failure")
            val failingDatabase = FailingWriteDatabase(test.database, persistenceFailure)
            val committer =
                OrganizationCommitter(failingDatabase, ScriptedOrganizedIdGenerator(listOf("org-not-written")), OrganizationClock(now))

            val observed = assertFailsWith<ContextPersistenceException> { committer.commit(rawId, emptyList(), validNewDecision()) }

            assertSame(persistenceFailure, observed)
            assertTrue(test.database.read { it.pending.exists(rawId) })
            assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
        }

    private fun assertRowCountRollback(
        point: RowCountFaultPoint,
        reportedCount: Int,
    ) = withOrganizationDatabase { test ->
        val rawId = "raw-row-fault-${point.name.lowercase()}"
        prepareRaw(test.database, rawId, now, pending = true)
        val failing = RowCountFaultDatabase(test.database, point, reportedCount)
        val committer = OrganizationCommitter(failing, ScriptedOrganizedIdGenerator(listOf("org-row-fault")), OrganizationClock(now))

        val failure = assertFailsWith<OrganizationCommitException> { committer.commit(rawId, emptyList(), validNewDecision()) }

        assertEquals(OrganizationCommitReason.ROW_COUNT_MISMATCH, failure.reason)
        assertTrue(test.database.read { it.pending.exists(rawId) })
        assertEquals(emptyList(), test.database.read { it.organizedMemories.scanPage(0, 10) })
        assertEquals(emptyList(), test.database.read { it.organizedMemories.findOrganizedByRaw(rawId) })
    }

    private suspend fun targetFixture(database: ContextDatabase): TargetFixture {
        val oldRawId = "raw-old"
        val currentRawId = "raw-current"
        prepareRaw(database, oldRawId, now.minusSeconds(20), pending = false)
        prepareRaw(database, currentRawId, now, pending = true)
        val target = organized("org-target", content = "current content", updatedAt = now.minusSeconds(10))
        insertOrganized(database, target, listOf(oldRawId))
        val snapshot = assertNotNull(database.read { it.organizedMemories.findById(target.id) })
        return TargetFixture(oldRawId = oldRawId, currentRawId = currentRawId, snapshot = snapshot)
    }

    private fun validNewDecision(): GovernanceDecision =
        GovernanceDecision(
            action = GovernanceAction.NEW,
            content = "new content",
            domains = listOf("d1", "d1", "d2"),
        )

    private fun organized(
        id: String,
        content: String = "target content",
        domains: List<String> = listOf("target-domain"),
        updatedAt: Instant,
    ): OrganizedMemory =
        OrganizedMemory(
            id = id,
            content = content,
            domains = domains,
            rawMemoryIds = emptyList(),
            createdAt = updatedAt.minusSeconds(1).truncatedTo(ChronoUnit.MILLIS),
            updatedAt = updatedAt.truncatedTo(ChronoUnit.MILLIS),
        )

    private data class TargetFixture(
        val oldRawId: String,
        val currentRawId: String,
        val snapshot: OrganizedMemory,
    )
}
