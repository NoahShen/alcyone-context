package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CT20 B2 A07：CREATED/STARTING/STOPPING/CLOSED 对三 Retrieval 方法统一准入。 */
class RetrievalLifecycleWiringTest {
    @Test
    fun `created and starting retrieval methods fail not started`() =
        runBlocking {
            ContextFixture().use { fixture ->
                val created = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
                try {
                    assertLifecycle(created, ContextLifecycleErrorCode.NOT_STARTED)
                } finally {
                    created.close()
                }

                fixture.initialize()
                val gateEntered = CompletableDeferred<Unit>()
                val releaseGate = CompletableDeferred<Unit>()
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = fixture.config(),
                        vfsConfig = fixture.vfsConfig(),
                        probe = AlcyoneContextFactory.StartProbe { },
                        startGate =
                            AlcyoneContextFactory.StartGate {
                                gateEntered.complete(Unit)
                                releaseGate.await()
                            },
                    )
                try {
                    val starting = async(Dispatchers.Default) { context.start() }
                    gateEntered.await()
                    assertEquals(ContextLifecycleState.STARTING, context.status().lifecycle)
                    assertLifecycle(context, ContextLifecycleErrorCode.NOT_STARTED)
                    releaseGate.complete(Unit)
                    starting.await()
                } finally {
                    releaseGate.complete(Unit)
                    context.close()
                }
            }
        }

    @Test
    fun `stopping and closed retrieval methods fail closed`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val releaseEntered = CountDownLatch(1)
                val releaseClose = CountDownLatch(1)
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = fixture.config(),
                        vfsConfig = fixture.vfsConfig(),
                        probe = AlcyoneContextFactory.StartProbe { },
                        releaseProbe =
                            AlcyoneContextFactory.ReleaseProbe {
                                releaseEntered.countDown()
                                releaseClose.await(10, TimeUnit.SECONDS)
                            },
                    )
                try {
                    context.start()
                    val closing = async(Dispatchers.Default) { context.closeAsync() }
                    assertTrue(releaseEntered.await(10, TimeUnit.SECONDS))
                    assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)
                    assertLifecycle(context, ContextLifecycleErrorCode.CLOSED)
                    releaseClose.countDown()
                    withTimeout(10_000L) { closing.await() }
                    assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
                    assertLifecycle(context, ContextLifecycleErrorCode.CLOSED)
                } finally {
                    releaseClose.countDown()
                    runCatching { context.close() }
                }
            }
        }

    private suspend fun assertLifecycle(
        context: com.github.noahshen.alcyone.context.memory.api.AlcyoneContext,
        expected: ContextLifecycleErrorCode,
    ) {
        val calls: List<suspend () -> Unit> =
            listOf(
                { context.searchMemory("query") },
                { context.getMemory("id") },
                { context.getRawMemory("id") },
            )
        calls.forEach { call ->
            val failure = assertFailsWith<ContextLifecycleException> { call() }
            assertEquals(expected, failure.errorCode)
        }
    }
}
