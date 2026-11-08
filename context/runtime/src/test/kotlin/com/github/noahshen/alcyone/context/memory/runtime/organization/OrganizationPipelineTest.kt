package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CT16 B1 A06/A07：activation gate、close 顺序、真实 join、共享失败与 Worker 状态。 */
class OrganizationPipelineTest {
    @Test
    fun `a requested round waits at the activation gate then runs once activated`() =
        runBlocking {
            val rounds = AtomicInteger(0)
            val pipeline = pipeline(OrganizationRoundRunner { rounds.incrementAndGet() }, config(periodic = false))
            pipeline.start()
            try {
                assertTrue(pipeline.requestImmediate())
                pipeline.runnerGateReachedForTesting.await()
                assertEquals(0, rounds.get(), "activate 前不得执行 Runner")
                assertEquals(OrganizationWorkerStatus.RUNNING, pipeline.workerStatusForTesting())

                pipeline.activate()
                awaitOrganizationUntil { rounds.get() == 1 }
                assertTrue(pipeline.isActivatedForTesting())
            } finally {
                pipeline.closeAsync()
            }
        }

    @Test
    fun `beginClosing closes queued admission so activation cannot start a business round`() =
        runBlocking {
            val rounds = AtomicInteger(0)
            val pipeline = pipeline(OrganizationRoundRunner { rounds.incrementAndGet() }, config(periodic = false))
            pipeline.start()
            try {
                assertTrue(pipeline.requestImmediate())
                pipeline.runnerGateReachedForTesting.await()
                pipeline.beginClosing()
                assertFalse(pipeline.requestImmediate())
                pipeline.activate()
                awaitOrganizationUntil { pipeline.schedulerForTesting().roundCountForTesting() == 1L }
                assertEquals(0, rounds.get())
            } finally {
                pipeline.closeAsync()
            }
        }

    @Test
    fun `close stops periodic cron before scheduler and both resources really end`() =
        runBlocking {
            val order = CopyOnWriteArrayList<String>()
            val ticks = ManualOrganizationTicks()
            val pipeline = pipeline(OrganizationRoundRunner { }, config(periodic = true), ticks)
            pipeline.start()
            val cron = requireNotNull(pipeline.periodicCronForTesting())
            cron.closeActionForTesting = { order += "cron" }
            pipeline.schedulerForTesting().closeActionForTesting = { order += "scheduler" }

            pipeline.closeAsync()

            assertEquals(listOf("cron", "scheduler"), order)
            assertEquals("CLOSED", cron.stateForTesting())
            assertEquals("CLOSED", pipeline.schedulerForTesting().stateForTesting())
            assertEquals(OrganizationWorkerStatus.STOPPED, pipeline.workerStatusForTesting())
            ticks.close()
        }

    @Test
    fun `close cancels and joins an in flight organization round`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val pipeline =
                pipeline(
                    OrganizationRoundRunner {
                        try {
                            entered.countDown()
                            awaitCancellation()
                        } finally {
                            finished.countDown()
                        }
                    },
                    config(periodic = false),
                )
            pipeline.start()
            pipeline.activate()
            assertTrue(pipeline.requestImmediate())
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(OrganizationWorkerStatus.RUNNING, pipeline.workerStatusForTesting())

            withTimeout(10_000L) { pipeline.closeAsync() }

            assertEquals(0L, finished.count)
            assertEquals(OrganizationWorkerStatus.STOPPED, pipeline.workerStatusForTesting())
        }

    @Test
    fun `close before start and repeated close are safe`() =
        runBlocking {
            val pipeline = pipeline(OrganizationRoundRunner { }, config())
            withTimeout(5_000L) { pipeline.closeAsync() }
            withTimeout(5_000L) { pipeline.closeAsync() }
            assertEquals(OrganizationWorkerStatus.STOPPED, pipeline.workerStatusForTesting())
            assertTrue(pipeline.isActivatedForTesting(), "close 必须完成 activation 以免悬挂")
        }

    @Test
    fun `start failure remains observable and close safely finishes unstarted children`() =
        runBlocking {
            val startFailure = IllegalStateException("ct16-pipeline-start-failure")
            val pipeline = pipeline(OrganizationRoundRunner { }, config())
            pipeline.startFailureForTesting = startFailure

            val observed = assertFailsWith<IllegalStateException> { pipeline.start() }

            assertSame(startFailure, observed)
            assertFalse(pipeline.requestImmediate())
            withTimeout(5_000L) { pipeline.closeAsync() }
            assertEquals(OrganizationWorkerStatus.STOPPED, pipeline.workerStatusForTesting())
            assertEquals("CLOSED", requireNotNull(pipeline.periodicCronForTesting()).stateForTesting())
        }

    @Test
    fun `close failures are shared across concurrent waiters and preserve cron then scheduler order`() =
        runBlocking {
            val cronFailure = IllegalStateException("ct16-cron-close-failure")
            val schedulerFailure = IllegalStateException("ct16-scheduler-close-failure")
            val finalFailure = IllegalStateException("ct16-pipeline-close-failure")
            val ticks = ManualOrganizationTicks()
            val pipeline = pipeline(OrganizationRoundRunner { }, config(periodic = true), ticks)
            pipeline.start()
            val cron = requireNotNull(pipeline.periodicCronForTesting())
            cron.closeActionForTesting = { throw cronFailure }
            pipeline.schedulerForTesting().closeActionForTesting = { throw schedulerFailure }
            pipeline.closeFailureForTesting = finalFailure

            val first = assertFailsWith<IllegalStateException> { pipeline.closeAsync() }
            val second = assertFailsWith<IllegalStateException> { pipeline.closeAsync() }

            assertSame(cronFailure, first)
            assertSame(cronFailure, second)
            assertTrue(first.suppressed.any { it === schedulerFailure })
            assertTrue(first.suppressed.any { it === finalFailure })
            ticks.close()
        }

    @Test
    fun `immediate disabled wakeup is a no-op while periodic remains available`() =
        runBlocking {
            val pipeline = pipeline(OrganizationRoundRunner { }, config(immediate = false, periodic = true))
            pipeline.start()
            try {
                pipeline.activate()
                pipeline.wakeup.onRawCommitted()
                assertEquals(0, pipeline.schedulerForTesting().triggerCountForTesting())
                assertEquals(0, pipeline.schedulerForTesting().workerCreationCountForTesting())
            } finally {
                pipeline.closeAsync()
            }
        }

    @Test
    fun `worker status moves running to idle and never failed for a normal round`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pipeline =
                pipeline(
                    OrganizationRoundRunner {
                        entered.complete(Unit)
                        release.await()
                    },
                    config(periodic = false),
                )
            pipeline.start()
            try {
                pipeline.activate()
                assertTrue(pipeline.requestImmediate())
                entered.await()
                assertEquals(OrganizationWorkerStatus.RUNNING, pipeline.workerStatusForTesting())
                release.complete(Unit)
                awaitOrganizationUntil { pipeline.workerStatusForTesting() == OrganizationWorkerStatus.IDLE }
            } finally {
                release.complete(Unit)
                pipeline.closeAsync()
            }
        }

    private fun pipeline(
        runner: OrganizationRoundRunner,
        config: OrganizationConfig,
        ticks: ManualOrganizationTicks? = null,
    ): OrganizationPipeline = OrganizationPipeline(config, UnusedDatabase, runner, tickSource = ticks)

    private fun config(
        immediate: Boolean = true,
        periodic: Boolean = true,
    ): OrganizationConfig = OrganizationConfig(immediateEnabled = immediate, periodicEnabled = periodic)

    private object UnusedDatabase : ContextDatabase {
        override suspend fun <T> read(block: (ReadContext) -> T): T = throw UnsupportedOperationException()

        override suspend fun <T> write(block: (TransactionContext) -> T): T = throw UnsupportedOperationException()
    }
}
