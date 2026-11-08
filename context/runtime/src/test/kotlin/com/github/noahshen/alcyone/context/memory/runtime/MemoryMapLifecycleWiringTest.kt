package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapPipeline
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CT19 B2 A07：RUNNING 前零生成、STOPPING 窗口、关闭顺序、真实 join 与启动失败清理。 */
class MemoryMapLifecycleWiringTest {
    @Test
    fun `close releases extraction organization memory map llm vfs executor in frozen order`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val order = CopyOnWriteArrayList<String>()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator = RecordingMemoryMapGenerator { mapArtifact(null) }
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = allPipelineConfig(fixture, stub),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        releaseProbe = AlcyoneContextFactory.ReleaseProbe { target -> order += target.name },
                        pipelineConfigurator = { it.closeHookForTesting = { order += "EXTRACTION" } },
                        organizationPipelineConfigurator = { it.closeHookForTesting = { order += "ORGANIZATION" } },
                        memoryMapGenerator = generator,
                        memoryMapPipelineConfigurator = { pipeline ->
                            pipeline.coordinatorForTesting().closeActionForTesting = { order += "MEMORY_MAP" }
                        },
                    )
                val impl = context as AlcyoneContextImpl
                try {
                    context.start()
                    awaitMemoryMapWiringUntil { generator.calls.get() == 1 }
                    assertNotNull(impl.llmClientForTesting()).closeActionForTesting = { order += "LLM" }
                    context.close()
                    assertEquals(
                        listOf("EXTRACTION", "ORGANIZATION", "MEMORY_MAP", "LLM", "VFS", "EXECUTOR"),
                        order,
                    )
                    assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                } finally {
                    runCatching { context.close() }
                    stub.close()
                }
            }
        }

    @Test
    fun `in flight map generation is cancelled and joined before context becomes closed`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val entered = CountDownLatch(1)
                val finished = CountDownLatch(1)
                val generator =
                    RecordingMemoryMapGenerator {
                        try {
                            entered.countDown()
                            awaitCancellation()
                        } finally {
                            finished.countDown()
                        }
                    }
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub)),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        memoryMapGenerator = generator,
                    )
                val impl = context as AlcyoneContextImpl
                try {
                    context.start()
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    withTimeout(10_000L) { context.closeAsync() }
                    assertEquals(0L, finished.count)
                    assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                    assertEquals(
                        WorkerState.STOPPED,
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state,
                    )
                    assertNull(impl.memoryMapPipelineForTesting())
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }

    @Test
    fun `STOPPING window rejects new map wakeup without throwing or waiting for LLM`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator = RecordingMemoryMapGenerator { awaitCancellation() }
                val closeHookEntered = CountDownLatch(1)
                val releaseCloseHook = CountDownLatch(1)
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub)),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        memoryMapGenerator = generator,
                        memoryMapPipelineConfigurator = { pipeline ->
                            pipeline.coordinatorForTesting().closeActionForTesting = {
                                closeHookEntered.countDown()
                                releaseCloseHook.await(10, TimeUnit.SECONDS)
                            }
                        },
                    )
                val impl = context as AlcyoneContextImpl
                try {
                    context.start()
                    awaitMemoryMapWiringUntil { generator.calls.get() == 1 }
                    val closing = async(Dispatchers.Default) { context.closeAsync() }
                    assertTrue(closeHookEntered.await(10, TimeUnit.SECONDS))
                    assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)
                    assertEquals(
                        WorkerState.STOPPED,
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state,
                    )

                    withTimeout(500L) { impl.organizationMapWakeupForTesting().onOrganizedCommitted() }
                    releaseCloseHook.countDown()
                    closing.await()
                } finally {
                    releaseCloseHook.countDown()
                    context.close()
                    stub.close()
                }
            }
        }

    @Test
    fun `map start failure releases already acquired resources and closes the context`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val released = CopyOnWriteArrayList<AlcyoneContextFactory.ReleaseTarget>()
                var organizationPipeline: OrganizationPipeline? = null
                var memoryMapPipeline: MemoryMapPipeline? = null
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = allPipelineConfig(fixture, stub),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        releaseProbe = AlcyoneContextFactory.ReleaseProbe { released += it },
                        organizationPipelineConfigurator = { organizationPipeline = it },
                        memoryMapGenerator = RecordingMemoryMapGenerator { mapArtifact(null) },
                        memoryMapPipelineConfigurator = { pipeline ->
                            memoryMapPipeline = pipeline
                            pipeline.beginClosing()
                        },
                    )
                val impl = context as AlcyoneContextImpl
                try {
                    assertFailsWith<ContextLifecycleException> { context.start() }
                    assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                    assertNull(impl.databaseForTesting())
                    assertTrue(assertNotNull(memoryMapPipeline).isClosingForTesting())
                    assertEquals("CLOSED", assertNotNull(organizationPipeline).schedulerForTesting().stateForTesting())
                    assertEquals(
                        listOf(AlcyoneContextFactory.ReleaseTarget.VFS, AlcyoneContextFactory.ReleaseTarget.EXECUTOR),
                        released,
                    )
                } finally {
                    runCatching { context.close() }
                    stub.close()
                }
            }
        }

    @Test
    fun `concurrent close shares the primary failure and map failure is suppressed`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val extractionFailure = IllegalStateException("ct19-b2-extraction-close")
                val organizationFailure = IllegalStateException("ct19-b2-organization-close")
                val mapFailure = IllegalStateException("ct19-b2-memory-map-close")
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = allPipelineConfig(fixture, stub),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        pipelineConfigurator = { it.closeFailureForTesting = extractionFailure },
                        organizationPipelineConfigurator = { it.closeFailureForTesting = organizationFailure },
                        memoryMapGenerator = RecordingMemoryMapGenerator { mapArtifact(null) },
                        memoryMapPipelineConfigurator = { it.closeFailureForTesting = mapFailure },
                    )
                try {
                    context.start()
                    val first = assertFailsWith<IllegalStateException> { context.close() }
                    val second = assertFailsWith<IllegalStateException> { context.close() }
                    assertSame(extractionFailure, first)
                    assertSame(first, second)
                    assertTrue(first.suppressed.any { it === organizationFailure })
                    assertTrue(first.suppressed.any { it === mapFailure })
                    assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                } finally {
                    runCatching { context.close() }
                    stub.close()
                }
            }
        }

    private fun allPipelineConfig(
        fixture: ContextFixture,
        stub: LlmHttpStub,
    ): ContextConfig =
        memoryMapContextConfig(fixture, multiPipelineLlm(stub)).copy(
            extraction = ExtractionConfig(idleEnabled = false),
            organization = OrganizationConfig(immediateEnabled = true, periodicEnabled = false),
        )
}
