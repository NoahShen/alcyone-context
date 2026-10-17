package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitBackupTest {
    // --- GitBackupConfig Tests ---

    @Test
    fun `test valid GitBackupConfig creation and defaults`() {
        val root = Paths.get("/tmp/repo")
        val eventRoots = setOf(VfsUri.parse("alcyone://resources/notes"))
        val config =
            GitBackupConfig(
                repositoryRoot = root,
                eventRoots = eventRoots,
                branch = "main",
            )
        assertEquals(root.toAbsolutePath().normalize(), config.normalizedRepositoryRoot)
        assertEquals(eventRoots, config.eventRoots)
        assertEquals("origin", config.remoteName)
        assertEquals("main", config.branch)
        assertEquals(Duration.ofSeconds(60), config.quietPeriod)
        assertEquals("0 3 * * *", config.cron)
        assertEquals("git", config.gitExecutable)
        assertEquals(Duration.ofSeconds(30), config.localCommandTimeout)
        assertEquals(Duration.ofSeconds(120), config.pushTimeout)
        assertEquals(Duration.ofSeconds(10), config.shutdownGracePeriod)
    }

    @Test
    fun `test GitBackupConfig defensive copy prevents mutable alias penetration`() {
        val mutableRoots = mutableSetOf(VfsUri.parse("alcyone://notes"))
        val config =
            GitBackupConfig(
                repositoryRoot = Paths.get("/tmp/repo"),
                eventRoots = mutableRoots,
                branch = "main",
            )
        val filter = GitBackupEventFilter(mutableRoots)

        // Clear original mutable set
        mutableRoots.clear()

        // Config eventRoots must remain unchanged
        assertEquals(1, config.eventRoots.size)
        assertTrue(config.eventRoots.contains(VfsUri.parse("alcyone://notes")))

        // Filter must still match
        val event =
            VfsEvent(
                id = VfsEventId.parse("018f25b2-7c30-7000-8000-000000000001"),
                type = VfsEventType.FILE_WRITTEN,
                nodeId = NodeId.parse("018f25b2-7c30-7000-8000-000000000002"),
                occurredAt = Instant.now(),
                uri = VfsUri.parse("alcyone://notes/a.txt"),
                operationId = "op-1",
            )
        assertTrue(filter.matches(event))
    }

    @Test
    fun `test GitBackupConfig invalid arguments`() {
        val validRoot = Paths.get("/tmp/repo")
        val validRoots = setOf(VfsUri.parse("alcyone://notes"))

        // empty / blank repositoryRoot
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(repositoryRoot = Paths.get("  "), eventRoots = validRoots, branch = "main")
        }

        // empty eventRoots
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(repositoryRoot = validRoot, eventRoots = emptySet(), branch = "main")
        }

        // blank remoteName
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(repositoryRoot = validRoot, eventRoots = validRoots, remoteName = " ", branch = "main")
        }

        // blank branch
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(repositoryRoot = validRoot, eventRoots = validRoots, branch = " ")
        }

        // non-positive quietPeriod
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(
                repositoryRoot = validRoot,
                eventRoots = validRoots,
                branch = "main",
                quietPeriod = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(
                repositoryRoot = validRoot,
                eventRoots = validRoots,
                branch = "main",
                quietPeriod = Duration.ofSeconds(-5),
            )
        }

        // blank gitExecutable
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(
                repositoryRoot = validRoot,
                eventRoots = validRoots,
                branch = "main",
                gitExecutable = "",
            )
        }

        // non-positive localCommandTimeout, pushTimeout, shutdownGracePeriod
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(
                repositoryRoot = validRoot,
                eventRoots = validRoots,
                branch = "main",
                localCommandTimeout = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(
                repositoryRoot = validRoot,
                eventRoots = validRoots,
                branch = "main",
                pushTimeout = Duration.ofSeconds(-1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(
                repositoryRoot = validRoot,
                eventRoots = validRoots,
                branch = "main",
                shutdownGracePeriod = Duration.ZERO,
            )
        }

        // invalid cron
        assertFailsWith<IllegalArgumentException> {
            GitBackupConfig(
                repositoryRoot = validRoot,
                eventRoots = validRoots,
                branch = "main",
                cron = "invalid cron",
            )
        }
    }

    // --- CronBackupSchedule Tests ---

    @Test
    fun `test CronBackupSchedule nextExecution with controlled clock`() {
        val zone = ZoneId.of("UTC")
        val fixedInstant = Instant.parse("2026-10-16T02:30:00Z")
        val clock = Clock.fixed(fixedInstant, zone)

        // "0 3 * * *" -> every day at 03:00
        val schedule = CronBackupSchedule("0 3 * * *", clock)
        val next = schedule.nextExecution()
        assertNotNull(next)
        assertEquals(ZonedDateTime.of(2026, 10, 16, 3, 0, 0, 0, zone), next)

        // After 03:00, next should be next day 03:00
        val fromAfter = ZonedDateTime.of(2026, 10, 16, 3, 1, 0, 0, zone)
        val nextAfter = schedule.nextExecution(fromAfter)
        assertNotNull(nextAfter)
        assertEquals(ZonedDateTime.of(2026, 10, 17, 3, 0, 0, 0, zone), nextAfter)
    }

    @Test
    fun `test CronBackupSchedule range step list and day of week`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val from = ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, zone) // 2026-10-16 is Friday (5)
        val clock = Clock.fixed(from.toInstant(), zone)

        // Range: 10-15 minute -> next is 10:10
        val rangeSchedule = CronBackupSchedule("10-15 * * * *", clock)
        val nextRange = rangeSchedule.nextExecution(from)
        assertNotNull(nextRange)
        assertEquals(ZonedDateTime.of(2026, 10, 16, 10, 10, 0, 0, zone), nextRange)

        // Step: every 15 minutes
        val stepSchedule = CronBackupSchedule("*/15 * * * *", clock)
        val nextStep = stepSchedule.nextExecution(from)
        assertNotNull(nextStep)
        assertEquals(ZonedDateTime.of(2026, 10, 16, 10, 15, 0, 0, zone), nextStep)

        // List: 10,20 minute
        val listSchedule = CronBackupSchedule("10,20 * * * *", clock)
        val nextList = listSchedule.nextExecution(from)
        assertNotNull(nextList)
        assertEquals(ZonedDateTime.of(2026, 10, 16, 10, 10, 0, 0, zone), nextList)

        // Day of week: 0-6 (0 is Sunday, 5 is Friday, 6 is Saturday)
        // Next Saturday at 12:00
        val dowSchedule = CronBackupSchedule("0 12 * * 6", clock)
        val nextSat = dowSchedule.nextExecution(from)
        assertNotNull(nextSat)
        assertEquals(ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, zone), nextSat)

        // Day of week with step: */7 (step=7 is valid)
        val step7Schedule = CronBackupSchedule("0 3 * * */7", clock)
        assertNotNull(step7Schedule.nextExecution(from))

        // Day of week with range and step: 1-5/2
        val rangeStepSchedule = CronBackupSchedule("0 3 * * 1-5/2", clock)
        assertNotNull(rangeStepSchedule.nextExecution(from))
    }

    @Test
    fun `test CronBackupSchedule DOM and DOW OR contract`() {
        val zone = ZoneId.of("UTC")
        // "0 3 1 * 1" -> Day of month 1 OR Day of week Monday (1) at 03:00
        val schedule = CronBackupSchedule("0 3 1 * 1")

        // Case 1: Start at 2026-10-16 (Friday).
        // Next Monday is 2026-10-19 (earlier than next 1st which is Nov 1st).
        val fromFriday = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone)
        val nextMonday = schedule.nextExecution(fromFriday)
        assertNotNull(nextMonday)
        assertEquals(ZonedDateTime.of(2026, 10, 19, 3, 0, 0, 0, zone), nextMonday)

        // Case 2: Start at 2026-10-30 (Friday).
        // Next 1st of Month is 2026-11-01 (Sunday, earlier than next Monday Nov 2nd).
        val fromLateOct = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, zone)
        val nextFirst = schedule.nextExecution(fromLateOct)
        assertNotNull(nextFirst)
        assertEquals(ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, zone), nextFirst)
    }

    @Test
    fun `test CronBackupSchedule day of week 7 base rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * * 7")
        }
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * * 1-7")
        }
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * * 0,7")
        }
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * * 7/2")
        }
    }

    @Test
    fun `test CronBackupSchedule English letters rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * JAN *")
        }
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * * MON")
        }
    }

    @Test
    fun `test CronBackupSchedule unreachable expressions rejected at init`() {
        // Feb 31st never exists
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 31 2 *")
        }
    }

    @Test
    fun `test CronBackupSchedule leap day across non-leap century`() {
        val zone = ZoneId.of("UTC")
        // "0 3 29 2 *" -> Feb 29 at 03:00.
        // Starting after leap day 2096 (e.g. 2096-03-01).
        // 2100 is NOT a leap year (century year not divisible by 400).
        // Next leap year is 2104.
        val clock = Clock.fixed(Instant.parse("2096-03-01T00:00:00Z"), zone)
        val schedule = CronBackupSchedule("0 3 29 2 *", clock)
        val from = ZonedDateTime.of(2096, 3, 1, 0, 0, 0, 0, zone)
        val next = schedule.nextExecution(from)
        assertNotNull(next)
        assertEquals(ZonedDateTime.of(2104, 2, 29, 3, 0, 0, 0, zone), next)
    }

    @Test
    fun `test CronBackupSchedule rejects 6 fields and Quartz chars`() {
        // 6 fields (seconds)
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 0 3 * * *")
        }

        // Quartz '?'
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 ? * *")
        }
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * * ?")
        }

        // Quartz 'L', 'W', '#'
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 L * *")
        }
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 * * 5#2")
        }
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("0 3 15W * *")
        }

        // Out of range (minute 60)
        assertFailsWith<IllegalArgumentException> {
            CronBackupSchedule("60 3 * * *")
        }
    }

    // --- GitBackupEventFilter Tests ---

    @Test
    fun `test GitBackupEventFilter matching rules and edge cases`() {
        val rootNotes = VfsUri.parse("alcyone://notes")
        val rootDocsSub = VfsUri.parse("alcyone://resources/docs")
        val filter = GitBackupEventFilter(setOf(rootNotes, rootDocsSub))

        val dummyEventId = VfsEventId.parse("018f25b2-7c30-7000-8000-000000000001")
        val dummyTime = Instant.now()
        val dummyNodeId = NodeId.parse("018f25b2-7c30-7000-8000-000000000002")

        // 1. Metadata updated should be ignored even if URI matches
        val metaEvent =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.METADATA_UPDATED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://notes/todo.txt"),
                operationId = "op-1",
            )
        assertFalse(filter.matches(metaEvent))

        // 2. Regular modification: FILE_CREATED, FILE_WRITTEN, FILE_DELETED, DIRECTORY_DELETED
        val fileWritten =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_WRITTEN,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://notes/todo.txt"),
                operationId = "op-2",
            )
        assertTrue(filter.matches(fileWritten))

        val fileDeleted =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_DELETED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://notes/obsolete.txt"),
                operationId = "op-3",
            )
        assertTrue(filter.matches(fileDeleted))

        val dirDeleted =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.DIRECTORY_DELETED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://resources/docs/old-dir"),
                operationId = "op-4",
            )
        assertTrue(filter.matches(dirDeleted))

        // 3. Percent-encoded URI matching decoded path segments
        // alcyone://resources/docs/my%20notes.txt decodes segment to "my notes.txt"
        val encodedEvent =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_CREATED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://resources/docs/my%20notes.txt"),
                operationId = "op-5",
            )
        assertTrue(filter.matches(encodedEvent))

        // 4. Submount conservative multi-trigger:
        // root is alcyone://resources/docs, event on submount path alcyone://resources/docs/submount/file.txt matches
        val submountEvent =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_WRITTEN,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://resources/docs/submount/file.txt"),
                operationId = "op-6",
            )
        assertTrue(filter.matches(submountEvent))

        // 5. Strict segment boundary check:
        // "alcyone://notes" should NOT match "alcyone://notes-archive/file.txt"
        val prefixBugEvent =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_WRITTEN,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://notes-archive/file.txt"),
                operationId = "op-7",
            )
        assertFalse(filter.matches(prefixBugEvent))

        // "alcyone://resources/docs" should NOT match "alcyone://resources/docspan/file.txt"
        val prefixBugSubEvent =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_WRITTEN,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://resources/docspan/file.txt"),
                operationId = "op-8",
            )
        assertFalse(filter.matches(prefixBugSubEvent))

        // 6. Move event: source matches, target doesn't
        val moveOut =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_MOVED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://other/moved.txt"),
                operationId = "op-9",
                sourceUri = VfsUri.parse("alcyone://notes/old.txt"),
                targetUri = VfsUri.parse("alcyone://other/moved.txt"),
            )
        assertTrue(filter.matches(moveOut))

        // Move event: target matches, source doesn't
        val moveIn =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_MOVED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://notes/new.txt"),
                operationId = "op-10",
                sourceUri = VfsUri.parse("alcyone://other/external.txt"),
                targetUri = VfsUri.parse("alcyone://notes/new.txt"),
            )
        assertTrue(filter.matches(moveIn))

        // Move event: both source and target match
        val moveInternal =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.DIRECTORY_MOVED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://notes/dirB"),
                operationId = "op-11",
                sourceUri = VfsUri.parse("alcyone://notes/dirA"),
                targetUri = VfsUri.parse("alcyone://notes/dirB"),
            )
        assertTrue(filter.matches(moveInternal))

        // Move event: neither source nor target match
        val moveIrrelevant =
            VfsEvent(
                id = dummyEventId,
                type = VfsEventType.FILE_MOVED,
                nodeId = dummyNodeId,
                occurredAt = dummyTime,
                uri = VfsUri.parse("alcyone://other/target.txt"),
                operationId = "op-12",
                sourceUri = VfsUri.parse("alcyone://other/source.txt"),
                targetUri = VfsUri.parse("alcyone://other/target.txt"),
            )
        assertFalse(filter.matches(moveIrrelevant))
    }
}
