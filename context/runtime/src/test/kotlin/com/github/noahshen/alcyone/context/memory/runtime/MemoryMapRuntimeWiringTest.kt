package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.BootstrapContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CT19 B2 A06/A07：bootstrap 只读缓存、启动检查异步、Worker 状态与单轮失败隔离。 */
class MemoryMapRuntimeWiringTest {
    private val sourceUpdatedAt: Instant = Instant.parse("2026-11-08T03:59:00Z")

    @Test
    fun `bootstrap lifecycle and unconfigured memory map semantics perform no implicit start`() =
        runBlocking {
            ContextFixture().use { fixture ->
                val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
                val before = fixture.snapshot()
                try {
                    val notStarted = assertFailsWith<ContextLifecycleException> { context.bootstrap() }
                    assertEquals(ContextLifecycleErrorCode.NOT_STARTED, notStarted.errorCode)
                    assertEquals(before, fixture.snapshot())

                    fixture.initialize()
                    context.start()
                    val running = context.bootstrap()
                    assertNull(running.memoryMap)
                    assertEquals(MapStatus.MISSING, running.mapStatus)
                    assertEquals(BootstrapContext.DEFAULT_CAPABILITIES, running.capabilities)
                    assertFalse(context.status().workers.containsKey("memoryMap"))
                } finally {
                    context.close()
                }
                val closed = assertFailsWith<ContextLifecycleException> { context.bootstrap() }
                assertEquals(ContextLifecycleErrorCode.CLOSED, closed.errorCode)
            }
        }

    @Test
    fun `configured start returns without waiting and exposes running idle stopped workers`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                seedOrganized(fixture, "O-RUNTIME-1", sourceUpdatedAt)
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val generator =
                    RecordingMemoryMapGenerator {
                        entered.complete(Unit)
                        release.await()
                        mapArtifact(sourceUpdatedAt)
                    }
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub)),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        memoryMapGenerator = generator,
                    )
                try {
                    assertEquals(
                        WorkerState.IDLE,
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state,
                    )
                    withTimeout(2_000L) { context.start() }
                    entered.await()

                    assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                    assertEquals(
                        WorkerState.RUNNING,
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state,
                    )
                    val initial = withTimeout(500L) { context.bootstrap() }
                    assertTrue(initial.mapStatus == MapStatus.UNKNOWN || initial.mapStatus == MapStatus.MISSING)

                    release.complete(Unit)
                    awaitMemoryMapWiringUntil {
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state == WorkerState.IDLE &&
                            context.bootstrap().mapStatus == MapStatus.STALE
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
                        .getValue("memoryMap")
                        .state,
                )
            }
        }

    @Test
    fun `STARTING keeps map idle and activation performs exactly one startup generation`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                seedOrganized(fixture, "O-RUNTIME-2", sourceUpdatedAt)
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val generator = RecordingMemoryMapGenerator { mapArtifact(sourceUpdatedAt) }
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub)),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe =
                            AlcyoneContextFactory.StartProbe {
                                entered.complete(Unit)
                                release.await()
                            },
                        memoryMapGenerator = generator,
                    )
                try {
                    val starting = async(Dispatchers.Default) { context.start() }
                    entered.await()
                    assertEquals(ContextLifecycleState.STARTING, context.status().lifecycle)
                    assertEquals(
                        WorkerState.IDLE,
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state,
                    )
                    assertEquals(0, generator.calls.get(), "RUNNING 前不得生成")

                    release.complete(Unit)
                    starting.await()
                    awaitMemoryMapWiringUntil { generator.calls.get() == 1 }
                } finally {
                    release.complete(Unit)
                    context.close()
                    stub.close()
                }
            }
        }

    @Test
    fun `bootstrap returns the published stale view and repeated calls do not refresh or generate`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                seedOrganized(fixture, "O-RUNTIME-3", sourceUpdatedAt)
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator = RecordingMemoryMapGenerator { mapArtifact(sourceUpdatedAt) }
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
                    awaitMemoryMapWiringUntil { context.bootstrap().mapStatus == MapStatus.STALE }
                    val first = context.bootstrap()
                    val view = assertNotNull(first.memoryMap)
                    assertTrue(view.content.contains("# Memory Overview"))
                    assertEquals(BootstrapContext.DEFAULT_CAPABILITIES, first.capabilities)

                    repeat(20) { assertEquals(first, context.bootstrap()) }
                    assertEquals(1, generator.calls.get(), "bootstrap 不得触发生成")

                    impl.organizationMapWakeupForTesting().onOrganizedCommitted()
                    awaitMemoryMapWiringUntil { context.bootstrap().mapStatus == MapStatus.CURRENT }
                    assertEquals(1, generator.calls.get(), "水位已追平时不得再次生成")
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }

    @Test
    fun `a single map generation failure maps FAILED without failing the context`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                seedOrganized(fixture, "O-RUNTIME-4", sourceUpdatedAt)
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator =
                    RecordingMemoryMapGenerator {
                        throw IllegalStateException("ct19-b2-map-generation-failure")
                    }
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub)),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        memoryMapGenerator = generator,
                    )
                try {
                    context.start()
                    awaitMemoryMapWiringUntil {
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state == WorkerState.FAILED
                    }
                    assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                    val bootstrap = context.bootstrap()
                    assertTrue(bootstrap.mapStatus == MapStatus.UNKNOWN || bootstrap.mapStatus == MapStatus.MISSING)
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }

    private suspend fun seedOrganized(
        fixture: ContextFixture,
        id: String,
        updatedAt: Instant,
    ) {
        val executor = fixture.open()
        try {
            insertOrganized(ContextDatabaseImpl(executor), id, updatedAt)
        } finally {
            executor.close()
        }
    }
}
