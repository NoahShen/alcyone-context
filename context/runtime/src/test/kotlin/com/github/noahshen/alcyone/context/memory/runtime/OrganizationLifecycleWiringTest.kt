package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionPipeline
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPipeline
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationRoundRunner
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CT16 B2：双 pipeline 启动失败清理、关闭顺序、close/start 竞态与回调内关闭拒绝。 */
class OrganizationLifecycleWiringTest {
    @Test
    fun `organization pipeline start failure closes context and releases all acquired resources`() =
        wiringTest { fixture ->
            fixture.initialize()
            val failure = IllegalStateException("ct16-b2-org-start-failure")
            val released = CopyOnWriteArrayList<AlcyoneContextFactory.ReleaseTarget>()
            var organizationPipeline: OrganizationPipeline? = null
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            val context =
                AlcyoneContextFactory.create(
                    contextConfig = organizationContext(fixture, stub, includeExtraction = false),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    releaseProbe = AlcyoneContextFactory.ReleaseProbe { released += it },
                    organizationPipelineConfigurator = { pipeline ->
                        organizationPipeline = pipeline
                        pipeline.startFailureForTesting = failure
                    },
                )
            val impl = context as AlcyoneContextImpl
            try {
                val observed = assertFailsWith<IllegalStateException> { runBlocking { context.start() } }
                assertSame(failure, observed)
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertNull(impl.databaseForTesting())
                assertEquals("CLOSED", assertNotNull(organizationPipeline).schedulerForTesting().stateForTesting())
                assertEquals(listOf(AlcyoneContextFactory.ReleaseTarget.VFS, AlcyoneContextFactory.ReleaseTarget.EXECUTOR), released)
                runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
                ContextDatabaseFactory.open(fixture.contextPath).close()
            } finally {
                stub.close()
            }
        }

    @Test
    fun `extraction start failure also closes the already started organization pipeline`() =
        wiringTest { fixture ->
            fixture.initialize()
            val failure = IllegalStateException("ct16-b2-extraction-start-failure")
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            var organizationPipeline: OrganizationPipeline? = null
            var extractionPipeline: ExtractionPipeline? = null
            val context =
                AlcyoneContextFactory.create(
                    contextConfig = organizationContext(fixture, stub, includeExtraction = true),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    organizationPipelineConfigurator = { organizationPipeline = it },
                    pipelineConfigurator = { pipeline ->
                        extractionPipeline = pipeline
                        pipeline.startFailureForTesting = failure
                    },
                )
            try {
                assertSame(failure, assertFailsWith<IllegalStateException> { runBlocking { context.start() } })
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertEquals("CLOSED", assertNotNull(organizationPipeline).schedulerForTesting().stateForTesting())
                assertEquals("CLOSED", assertNotNull(extractionPipeline).schedulerForTesting().stateForTesting())
                runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
                ContextDatabaseFactory.open(fixture.contextPath).close()
            } finally {
                stub.close()
            }
        }

    @Test
    fun `close releases extraction then organization then llm vfs executor`() =
        wiringTest { fixture ->
            fixture.initialize()
            val order = CopyOnWriteArrayList<String>()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            val context =
                AlcyoneContextFactory.create(
                    contextConfig = organizationContext(fixture, stub, includeExtraction = true),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    releaseProbe = AlcyoneContextFactory.ReleaseProbe { target -> order += target.name },
                    organizationPipelineConfigurator = { pipeline ->
                        pipeline.closeHookForTesting = { order += "ORGANIZATION" }
                    },
                    pipelineConfigurator = { pipeline ->
                        pipeline.closeHookForTesting = { order += "EXTRACTION" }
                    },
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertNotNull(impl.llmClientForTesting()).closeActionForTesting = { order += "LLM" }
                context.close()
                assertEquals(listOf("EXTRACTION", "ORGANIZATION", "LLM", "VFS", "EXECUTOR"), order)
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            } finally {
                stub.close()
            }
        }

    @Test
    fun `pipeline close failure does not skip later resources and shared failure is primary`() =
        wiringTest { fixture ->
            fixture.initialize()
            val extractionFailure = IllegalStateException("ct16-b2-extraction-close-failure")
            val organizationFailure = IllegalStateException("ct16-b2-organization-close-failure")
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            val context =
                AlcyoneContextFactory.create(
                    contextConfig = organizationContext(fixture, stub, includeExtraction = true),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    organizationPipelineConfigurator = { it.closeFailureForTesting = organizationFailure },
                    pipelineConfigurator = { it.closeFailureForTesting = extractionFailure },
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                val observed = assertFailsWith<IllegalStateException> { context.close() }
                assertSame(extractionFailure, observed)
                assertTrue(observed.suppressed.any { it === organizationFailure })
                assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                assertNull(impl.databaseForTesting())
                runBlocking { AlcyoneVfs.create(fixture.vfsConfig()).close() }
                ContextDatabaseFactory.open(fixture.contextPath).close()
            } finally {
                stub.close()
            }
        }

    @Test
    fun `close wins against start and both pipelines reach closed state`() =
        wiringTest { fixture ->
            fixture.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            var organizationPipeline: OrganizationPipeline? = null
            var extractionPipeline: ExtractionPipeline? = null
            val context =
                AlcyoneContextFactory.create(
                    contextConfig = organizationContext(fixture, stub, includeExtraction = true),
                    vfsConfig = fixture.vfsConfig(),
                    probe =
                        AlcyoneContextFactory.StartProbe {
                            entered.complete(Unit)
                            release.await()
                        },
                    organizationPipelineConfigurator = { organizationPipeline = it },
                    pipelineConfigurator = { extractionPipeline = it },
                )
            val startFailure = CompletableDeferred<Throwable?>()
            val closeFailure = CompletableDeferred<Throwable?>()
            coroutineScope {
                val startJob = launch(Dispatchers.Default) { startFailure.complete(runCatching { context.start() }.exceptionOrNull()) }
                entered.await()
                val closeJob =
                    launch(Dispatchers.Default) {
                        closeFailure.complete(runCatching { context.closeAsync() }.exceptionOrNull())
                    }
                awaitUntil { context.status().lifecycle == ContextLifecycleState.STOPPING }
                release.complete(Unit)
                listOf(startJob, closeJob).joinAll()
            }

            assertTrue(startFailure.await() is ContextLifecycleException)
            assertNull(closeFailure.await())
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertEquals("CLOSED", assertNotNull(organizationPipeline).schedulerForTesting().stateForTesting())
            assertEquals("CLOSED", assertNotNull(extractionPipeline).schedulerForTesting().stateForTesting())
            stub.close()
        }

    @Test
    fun `close from inside organization callback is rejected without closing context`() =
        wiringTest { fixture ->
            fixture.initialize()
            lateinit var context: AlcyoneContext
            val observed = CompletableDeferred<Throwable?>()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            context =
                AlcyoneContextFactory.create(
                    contextConfig = organizationContext(fixture, stub, includeExtraction = false),
                    vfsConfig = fixture.vfsConfig(),
                    probe = AlcyoneContextFactory.StartProbe { },
                    organizationRunner =
                        OrganizationRoundRunner {
                            observed.complete(runCatching { context.close() }.exceptionOrNull())
                        },
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertNotNull(impl.organizationPipelineForTesting()).wakeup.onRawCommitted()
                val failure = assertNotNull(observed.await()) as ContextLifecycleException
                assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
            } finally {
                context.close()
                stub.close()
            }
        }

    private fun wiringTest(block: suspend (ContextFixture) -> Unit) {
        ContextFixture().use { fixture -> runBlocking { block(fixture) } }
    }

    private fun organizationContext(
        fixture: ContextFixture,
        stub: LlmHttpStub,
        includeExtraction: Boolean,
    ): ContextConfig =
        fixture.config().copy(
            llm = llmConfig(stub, includeExtraction),
            extraction = ExtractionConfig(idleEnabled = false),
            organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
        )

    private fun llmConfig(
        stub: LlmHttpStub,
        includeExtraction: Boolean,
    ): LlmConfig {
        val tasks =
            buildMap {
                put(
                    LlmTask.CANDIDATE_SCANNER,
                    LlmTaskConfig(profile = "shared", maxInputTokens = 8_000, maxOutputTokens = 2_000),
                )
                put(
                    LlmTask.GOVERNANCE,
                    LlmTaskConfig(profile = "shared", maxInputTokens = 8_000, maxOutputTokens = 2_000),
                )
                if (includeExtraction) {
                    put(
                        LlmTask.EXTRACTION,
                        LlmTaskConfig(profile = "shared", maxInputTokens = 8_000, maxOutputTokens = 2_000),
                    )
                }
            }
        return LlmConfig(
            profiles =
                mapOf(
                    "shared" to
                        LlmProfileConfig(
                            name = "shared",
                            baseUrl = stub.baseUrl,
                            apiKey = "ct16-b2-lifecycle-key",
                            model = "fake-model",
                            contextWindowTokens = 32_000,
                            jsonMode = LlmResponseFormat.JSON_OBJECT,
                            maxAttempts = 1,
                        ),
                ),
            tasks = tasks,
        )
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(10_000L) {
            while (!condition()) delay(10)
        }
    }
}
