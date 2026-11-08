package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPeriodicTickSource
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPipeline
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationRoundRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CT16 B2：公共/内部 Factory 的 Organization 生产接线、Periodic tick 与 workers 映射。 */
class OrganizationRuntimeWiringTest {
    private val now = Instant.parse("2026-11-08T02:00:00Z")

    @Test
    fun `organization null keeps no pipeline and empty workers`() =
        wiringTest { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertNull(impl.organizationPipelineForTesting())
                assertTrue(context.status().workers.isEmpty())
            } finally {
                context.close()
            }
        }

    @Test
    fun `legal explicit organization starts a pipeline and maps idle then stopped`() =
        wiringTest { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, governanceResponse("unused"))
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(
                        llm = organizationLlm(stub),
                        extraction = ExtractionConfig(idleEnabled = false),
                        organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
                    ),
                    fixture.vfsConfig(),
                )
            val impl = context as AlcyoneContextImpl
            try {
                assertNull(impl.organizationPipelineForTesting(), "create 不得提前构造 pipeline")
                runBlocking { context.start() }
                val pipeline = assertNotNull(impl.organizationPipelineForTesting())
                assertNull(pipeline.periodicCronForTesting(), "periodic disabled 不得创建 Cron")
                assertEquals(
                    WorkerState.IDLE,
                    context
                        .status()
                        .workers
                        .getValue("organization")
                        .state,
                )
            } finally {
                context.close()
                stub.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertEquals(
                WorkerState.STOPPED,
                context
                    .status()
                    .workers
                    .getValue("organization")
                    .state,
            )
        }

    @Test
    fun `periodic tick seam processes pending only after a tick`() =
        wiringTest { fixture ->
            fixture.initialize()
            val ticks = ManualOrganizationTicks()
            val stub = LlmHttpStub(200, governanceResponse("periodic raw"))
            val context =
                AlcyoneContextFactory.create(
                    contextConfig =
                        fixture.config().copy(
                            llm = organizationLlm(stub),
                            extraction = ExtractionConfig(idleEnabled = false),
                            organization = OrganizationConfig(immediateEnabled = false, periodicEnabled = true),
                        ),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    organizationTickSource = ticks,
                )
            val impl = context as AlcyoneContextImpl
            var pipeline: OrganizationPipeline? = null
            try {
                runBlocking { context.start() }
                pipeline = assertNotNull(impl.organizationPipelineForTesting())
                assertNotNull(pipeline.periodicCronForTesting())
                seedPending(impl, "raw-periodic", "periodic raw")
                assertEquals(0, stub.requests().size)

                ticks.tick()
                awaitUntil { organized(impl).size == 1 && pendingCount(impl) == 0L }
                assertEquals(1, stub.requests().size)
                assertEquals("periodic raw", organized(impl).single().content)
                assertEquals(
                    WorkerState.IDLE,
                    context
                        .status()
                        .workers
                        .getValue("organization")
                        .state,
                )
            } finally {
                context.close()
                ticks.close()
                stub.close()
            }
            assertEquals("CLOSED", assertNotNull(pipeline).schedulerForTesting().stateForTesting())
        }

    @Test
    fun `workers organization maps running to idle and stopped`() =
        wiringTest { fixture ->
            fixture.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stub = LlmHttpStub(200, governanceResponse("unused"))
            val context =
                AlcyoneContextFactory.create(
                    contextConfig =
                        fixture.config().copy(
                            llm = organizationLlm(stub),
                            extraction = ExtractionConfig(idleEnabled = false),
                            organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
                        ),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    organizationRunner =
                        OrganizationRoundRunner {
                            entered.complete(Unit)
                            release.await()
                        },
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                val pipeline = assertNotNull(impl.organizationPipelineForTesting())
                pipeline.wakeup.onRawCommitted()
                entered.await()
                assertEquals(
                    WorkerState.RUNNING,
                    context
                        .status()
                        .workers
                        .getValue("organization")
                        .state,
                )
                release.complete(Unit)
                awaitUntil {
                    context
                        .status()
                        .workers
                        .getValue("organization")
                        .state == WorkerState.IDLE
                }
            } finally {
                release.complete(Unit)
                context.close()
                stub.close()
            }
            assertEquals(
                WorkerState.STOPPED,
                context
                    .status()
                    .workers
                    .getValue("organization")
                    .state,
            )
        }

    @Test
    fun `a single governance failure keeps pending and never maps failed`() =
        wiringTest { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"action":"DELETE","targetMemoryId":null,"content":"x","domains":["d"]}"""))
            val context =
                AlcyoneContextFactory.create(
                    contextConfig =
                        fixture.config().copy(
                            llm = organizationLlm(stub),
                            extraction = ExtractionConfig(idleEnabled = false),
                            organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
                        ),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                seedPending(impl, "raw-invalid-governance", "raw content")
                val pipeline = assertNotNull(impl.organizationPipelineForTesting())
                pipeline.wakeup.onRawCommitted()

                awaitUntil { stub.requests().size == 1 }
                awaitUntil { pipeline.schedulerForTesting().roundCountForTesting() == 1L }
                assertFalse(
                    context
                        .status()
                        .workers
                        .getValue("organization")
                        .state == WorkerState.FAILED,
                )
                assertEquals(
                    WorkerState.IDLE,
                    context
                        .status()
                        .workers
                        .getValue("organization")
                        .state,
                )
                assertEquals(1L, pendingCount(impl))
            } finally {
                context.close()
                stub.close()
            }
        }

    @Test
    fun `unrecoverable worker termination maps failed while context remains running`() =
        wiringTest { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, governanceResponse("unused"))
            val context =
                AlcyoneContextFactory.create(
                    contextConfig =
                        fixture.config().copy(
                            llm = organizationLlm(stub),
                            extraction = ExtractionConfig(idleEnabled = false),
                            organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
                        ),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    organizationRunner =
                        OrganizationRoundRunner {
                            throw CancellationException("ct16-b2-worker-failed")
                        },
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertNotNull(impl.organizationPipelineForTesting()).wakeup.onRawCommitted()
                awaitUntil {
                    context
                        .status()
                        .workers
                        .getValue("organization")
                        .state == WorkerState.FAILED
                }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
            } finally {
                context.close()
                stub.close()
            }
            assertEquals(
                WorkerState.STOPPED,
                context
                    .status()
                    .workers
                    .getValue("organization")
                    .state,
            )
        }

    private fun wiringTest(block: suspend (ContextFixture) -> Unit) {
        ContextFixture().use { fixture -> runBlocking { block(fixture) } }
    }

    private suspend fun seedPending(
        impl: AlcyoneContextImpl,
        rawId: String,
        content: String,
    ) {
        impl.databaseForTesting()!!.write { tx ->
            assertEquals(
                1,
                tx.rawMemories.insertBatch(
                    listOf(RawMemory(rawId, content, MemoryOrigin.EXTRACTION, emptyList(), now)),
                ),
            )
            assertEquals(1, tx.pending.enqueue(rawId))
        }
    }

    private suspend fun organized(impl: AlcyoneContextImpl) =
        impl.databaseForTesting()!!.read { read -> read.organizedMemories.scanPage(0, 10) }

    private suspend fun pendingCount(impl: AlcyoneContextImpl): Long =
        impl.databaseForTesting()!!.read { read -> if (read.pending.hasAnyPending()) 1L else 0L }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) {
        withTimeout(10_000L) {
            while (!condition()) delay(10)
        }
    }

    private fun organizationLlm(stub: LlmHttpStub): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "organization" to
                        LlmProfileConfig(
                            name = "organization",
                            baseUrl = stub.baseUrl,
                            apiKey = KEY_MARKER,
                            model = "fake-model",
                            contextWindowTokens = 32_000,
                            jsonMode = LlmResponseFormat.JSON_OBJECT,
                            maxAttempts = 1,
                        ),
                ),
            tasks =
                mapOf(
                    LlmTask.CANDIDATE_SCANNER to
                        LlmTaskConfig(profile = "organization", maxInputTokens = 8_000, maxOutputTokens = 2_000),
                    LlmTask.GOVERNANCE to
                        LlmTaskConfig(profile = "organization", maxInputTokens = 8_000, maxOutputTokens = 2_000),
                ),
        )

    private fun governanceResponse(content: String): String =
        chatCompletionBody(
            """{"action":"NEW","targetMemoryId":null,"content":"$content","domains":["ct16-domain"]}""",
        )

    private class ManualOrganizationTicks : OrganizationPeriodicTickSource {
        private val ticks = Channel<Unit>(Channel.UNLIMITED)

        fun tick() {
            check(ticks.trySend(Unit).isSuccess)
        }

        fun close() {
            ticks.close()
        }

        override suspend fun awaitTick(): Boolean = ticks.receiveCatching().isSuccess
    }

    private companion object {
        const val KEY_MARKER = "ct16-b2-runtime-key-marker"
    }
}
