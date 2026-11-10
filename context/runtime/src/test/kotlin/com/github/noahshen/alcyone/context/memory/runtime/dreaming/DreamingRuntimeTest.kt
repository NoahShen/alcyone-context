package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGeneratorImpl
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DreamingRuntimeTest {
    @Test
    fun `manual runtime is start gated and empty run is real success`() =
        withDreamingDatabase { fixture ->
            val runtime = runtime(fixture, DreamingConfig())
            assertFailsWith<ContextLifecycleException> { runtime.runOnce() }
            runtime.start()
            val result = runtime.runOnce()
            assertEquals(0L, result.startCheckpoint)
            assertEquals(0L, result.endCheckpoint)
            assertEquals(0, result.discoveredTopics)
            assertNull(runtime.periodicCronForTesting())
            runtime.closeAsync()
            assertFailsWith<ContextLifecycleException> { runtime.runOnce() }
            assertFailsWith<ContextLifecycleException> { runtime.start() }
            assertEquals("CLOSED", runtime.stateForTesting())
        }

    @Test
    fun `automatic runtime owns one cron and close joins it`() =
        withDreamingDatabase { fixture ->
            val ticks = ManualDreamingTicks()
            val runtime = runtime(fixture, DreamingConfig(cron = "0 3 * * *"), ticks)
            try {
                runtime.start()
                assertEquals("RUNNING", runtime.periodicCronForTesting()?.stateForTesting())
                assertEquals(0, runtime.coordinatorForTesting().ownedRunCountForTesting())
            } finally {
                runtime.closeAsync()
                ticks.close()
            }
            assertEquals("CLOSED", runtime.periodicCronForTesting()?.stateForTesting())
        }

    @Test
    fun `concurrent close shares one failure and closed runtime is idempotent`() =
        withDreamingDatabase { fixture ->
            coroutineScope {
                val runtime = runtime(fixture, DreamingConfig())
                val failure = IllegalStateException("ct27-runtime-close-failure")
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val calls = AtomicInteger(0)
                runtime.closeHookForTesting = {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    runBlocking { release.await() }
                }
                runtime.closeFailureForTesting = failure
                runtime.start()
                val first = async(Dispatchers.Default) { runCatching { runtime.closeAsync() } }
                entered.await()
                val second = async(Dispatchers.Default) { runCatching { runtime.closeAsync() } }
                release.complete(Unit)
                assertSame(failure, first.await().exceptionOrNull())
                assertSame(failure, second.await().exceptionOrNull())
                assertSame(failure, runCatching { runtime.closeAsync() }.exceptionOrNull())
                assertEquals(1, calls.get())
                assertEquals("CLOSED", runtime.stateForTesting())
            }
        }

    private fun runtime(
        fixture: DreamingTestDatabase,
        config: DreamingConfig,
        ticks: ManualDreamingTicks? = null,
    ): DreamingRuntime {
        val profile = LlmProfileConfig("dreaming", "https://example.test/v1", "secret", "model", 16_000, LlmResponseFormat.JSON_SCHEMA)
        val tasks =
            listOf(LlmTask.DREAMING_TOPIC, LlmTask.MEMORY_SEARCH, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION)
                .associateWith { LlmTaskConfig("dreaming", 4_000, 1_000) }
        val llm = LlmConfig(mapOf("dreaming" to profile), tasks)
        val unusedClient =
            object : LlmClient {
                override suspend fun complete(request: LlmRequest): LlmResponse = error("no LLM expected in lifecycle test")
            }
        return DreamingRuntime(
            config,
            fixture.database,
            unusedClient,
            llm,
            OrganizationWakeup {
            },
            Clock.systemUTC(),
            MemoryIdGeneratorImpl(Clock.systemUTC()),
            ticks,
        )
    }
}
