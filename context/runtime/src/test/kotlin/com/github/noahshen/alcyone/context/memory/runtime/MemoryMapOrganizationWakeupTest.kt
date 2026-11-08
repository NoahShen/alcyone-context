package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceAction
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecision
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidateRetriever
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitResult
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import com.github.noahshen.alcyone.context.memory.runtime.organization.DefaultOrganizationWorker
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationCommitOperation
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationGovernanceDecider
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationSourceGuard
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CT19 B2 A04：生产 effective Map wakeup、无 Map no-op 与 Organization Committed 触发。 */
class MemoryMapOrganizationWakeupTest {
    private val sourceUpdatedAt: Instant = Instant.parse("2026-11-08T03:59:00Z")

    @Test
    fun `production effective wakeup only requests a merged map refresh`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator = RecordingMemoryMapGenerator { mapArtifact(sourceUpdatedAt.minusSeconds(1)) }
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
                    awaitMemoryMapWiringUntil { generator.calls.get() == 1 }

                    withTimeout(500L) { impl.organizationMapWakeupForTesting().onOrganizedCommitted() }
                    awaitMemoryMapWiringUntil { generator.calls.get() == 2 }
                    assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }

    @Test
    fun `wakeup without a configured map is a safe no-op`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
                val impl = context as AlcyoneContextImpl
                try {
                    context.start()
                    assertNull(impl.memoryMapPipelineForTesting())
                    withTimeout(500L) { impl.organizationMapWakeupForTesting().onOrganizedCommitted() }
                    assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                } finally {
                    context.close()
                }
            }
        }

    @Test
    fun `wakeup after close is a safe no-op and never creates a worker`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator = RecordingMemoryMapGenerator { mapArtifact(null) }
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
                    awaitMemoryMapWiringUntil { generator.calls.get() == 1 }
                    context.close()
                    val calls = generator.calls.get()

                    withTimeout(500L) { impl.organizationMapWakeupForTesting().onOrganizedCommitted() }
                    assertEquals(calls, generator.calls.get())
                    assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                } finally {
                    stub.close()
                }
            }
        }

    @Test
    fun `a committed organization round invokes the production map wakeup`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator = RecordingMemoryMapGenerator { mapArtifact(sourceUpdatedAt.minusSeconds(1)) }
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
                    awaitMemoryMapWiringUntil { generator.calls.get() == 1 }
                    val database = assertNotNull(impl.databaseForTesting())
                    insertPendingRaw(database, "raw-map-wakeup", sourceUpdatedAt)
                    val worker =
                        DefaultOrganizationWorker(
                            database = database,
                            sourceGuard = OrganizationSourceGuard(database),
                            candidateRetriever =
                                object : OrganizationCandidateRetriever {
                                    override suspend fun search(rawMemory: com.github.noahshen.alcyone.context.memory.api.RawMemory) =
                                        emptyList<com.github.noahshen.alcyone.context.memory.api.OrganizedMemory>()
                                },
                            governanceDecider =
                                OrganizationGovernanceDecider { _, _ ->
                                    GovernanceDecision(
                                        action = GovernanceAction.NEW,
                                        content = "organized content",
                                        domains = listOf("domain"),
                                    )
                                },
                            commit =
                                OrganizationCommitOperation { rawId, _, _ ->
                                    OrganizationCommitResult.Committed(GovernanceAction.NEW, rawId)
                                },
                            mapWakeup = impl.organizationMapWakeupForTesting(),
                        )

                    worker.runRound()
                    awaitMemoryMapWiringUntil { generator.calls.get() == 2 }
                    assertTrue(context.status().workers.containsKey("memoryMap"))
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }
}
