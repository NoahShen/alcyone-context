package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.*
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.transaction.FakeStateUnitOfWork
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultVfsPublishNewTest {
    private val notifiers = TrackedNotifiers()

    @AfterEach fun tearDown() = notifiers.closeAll()

    private fun harness() =
        VfsHarness(
            mounts = listOf(VfsHarness.Mounted("/resources/backups")),
            capabilityOverrides = mapOf("/resources/backups" to StorageCapabilities(atomicPublishNew = true)),
            notifier = notifiers.create(),
        )

    @Test fun `physical publication followed by state failure is reported as partial`() =
        runBlocking {
            val harness = harness()
            harness.uow.failOnCommit = FakeStateUnitOfWork.storageFailure("commit failed")
            val failure =
                assertFailsWith<VfsException> {
                    harness.vfs.publishNew(
                        VfsUri.parse("alcyone://resources/backups/snapshot.sqlite"),
                        ByteArrayInputStream("snapshot".toByteArray()),
                        PublishOptions(maxBytes = 8, expectedSizeBytes = 8),
                    )
                }
            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect)
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test fun `a stream limit is enforced before physical publication`() =
        runBlocking {
            val harness = harness()
            assertFailsWith<VfsException> {
                harness.vfs.publishNew(
                    VfsUri.parse("alcyone://resources/backups/snapshot.sqlite"),
                    ByteArrayInputStream("too long".toByteArray()),
                    PublishOptions(maxBytes = 7, expectedSizeBytes = 7),
                )
            }.also { assertEquals(VfsErrorCode.LIMIT_EXCEEDED, it.code) }
            assertTrue(harness.committedNodes().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }
}
