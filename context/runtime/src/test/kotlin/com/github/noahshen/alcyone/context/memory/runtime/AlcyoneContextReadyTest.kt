package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.OrganizationPendingTxOpsImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.RawMemoryTxOpsImpl
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import com.github.noahshen.alcyone.context.vfs.*
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CountingManagedVfs private constructor(
    private val delegate: AlcyoneVfs,
) : Vfs,
    AutoCloseable {
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    private val closed = AtomicBoolean()

    fun totalCalls(): Int = calls.values.sumOf { it.get() }

    private fun count(name: String) {
        calls.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
    }

    override suspend fun publishNew(
        uri: VfsUri,
        input: InputStream,
        options: PublishOptions,
    ): NodeInfo {
        count("publishNew")
        return delegate.publishNew(uri, input, options)
    }

    override suspend fun read(
        uri: VfsUri,
        options: ReadOptions,
    ): ByteArray {
        count("read")
        return delegate.read(uri, options)
    }

    override suspend fun openStream(
        uri: VfsUri,
        options: VfsStreamOptions,
    ): VfsStreamResult {
        count("openStream")
        return delegate.openStream(uri, options)
    }

    override suspend fun write(
        uri: VfsUri,
        content: ByteArray,
        options: WriteOptions,
    ): NodeInfo {
        count("write")
        return delegate.write(uri, content, options)
    }

    override suspend fun safeReplace(
        uri: VfsUri,
        content: ByteArray,
    ): NodeInfo {
        count("safeReplace")
        return delegate.safeReplace(uri, content)
    }

    override suspend fun stat(
        uri: VfsUri,
        options: StatOptions,
    ): NodeInfo {
        count("stat")
        return delegate.stat(uri, options)
    }

    override suspend fun getNode(id: NodeId): NodeInfo {
        count("getNode")
        return delegate.getNode(id)
    }

    override suspend fun list(uri: VfsUri): List<VfsEntry> {
        count("list")
        return delegate.list(uri)
    }

    override suspend fun move(
        source: VfsUri,
        target: VfsUri,
    ): NodeInfo {
        count("move")
        return delegate.move(source, target)
    }

    override suspend fun delete(
        uri: VfsUri,
        options: DeleteOptions,
    ) {
        count("delete")
        delegate.delete(uri, options)
    }

    override suspend fun getMetadata(id: NodeId): NodeMetadata {
        count("getMetadata")
        return delegate.getMetadata(id)
    }

    override suspend fun setMetadata(
        id: NodeId,
        metadata: NodeMetadata,
    ) {
        count("setMetadata")
        delegate.setMetadata(id, metadata)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runBlocking { delegate.close() }
    }

    class Factory : suspend (VfsRuntimeConfig) -> Vfs {
        lateinit var current: CountingManagedVfs
            private set

        override suspend fun invoke(config: VfsRuntimeConfig): Vfs = CountingManagedVfs(AlcyoneVfs.create(config)).also { current = it }
    }
}

class AlcyoneContextReadyTest {
    @Test
    fun `ready ignores pending map and llm history without any vfs or llm calls`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("unused"))
            val counting = CountingManagedVfs.Factory()
            val mapConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub))
            val context =
                AlcyoneContextFactory.create(
                    mapConfig,
                    memoryMapVfsConfig(fixture),
                    AlcyoneContextFactory.StartProbe {},
                    memoryMapGenerator = RecordingMemoryMapGenerator { mapArtifact(Instant.EPOCH) },
                    vfsFactory = counting,
                )
            runBlocking {
                try {
                    context.start()
                    awaitMemoryMapWiringUntil { context.bootstrap().mapStatus == MapStatus.STALE }
                    fixture.open().use { executor ->
                        executor.write { tx ->
                            val raw = RawMemory("ready-pending", "content", MemoryOrigin.EXTRACTION, emptyList(), Instant.EPOCH)
                            RawMemoryTxOpsImpl(tx).insertBatch(listOf(raw))
                            OrganizationPendingTxOpsImpl(tx).enqueue(raw.id)
                        }
                    }
                    val admin = context.getAdminStatus()
                    assertEquals(1, admin.pending?.count)
                    assertEquals(MapStatus.STALE, admin.mapStatus)
                    val vfsBefore = counting.current.totalCalls()
                    val llmBefore = stub.requests().size
                    assertTrue(context.checkReady())
                    assertTrue(context.checkReady())
                    assertEquals(vfsBefore, counting.current.totalCalls())
                    assertEquals(llmBefore, stub.requests().size)
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }

    @Test
    fun `starting and stopping are not ready and do not admit a database read`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val startEntered = CountDownLatch(1)
            val releaseStart = CountDownLatch(1)
            val stopEntered = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val admitted = AtomicInteger()
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    AlcyoneContextFactory.StartProbe {
                        startEntered.countDown()
                        assertTrue(releaseStart.await(10, TimeUnit.SECONDS))
                    },
                    AlcyoneContextFactory.ReleaseProbe { target ->
                        if (target == AlcyoneContextFactory.ReleaseTarget.EXECUTOR) {
                            stopEntered.countDown()
                            assertTrue(releaseStop.await(10, TimeUnit.SECONDS))
                        }
                    },
                )
            (context as AlcyoneContextImpl).adminReadHookForTesting = { admitted.incrementAndGet() }
            runBlocking {
                val starting = launch(Dispatchers.IO) { context.start() }
                assertTrue(startEntered.await(10, TimeUnit.SECONDS))
                assertFalse(context.checkReady())
                assertEquals(0, admitted.get())
                releaseStart.countDown()
                starting.join()
                assertTrue(context.checkReady())
                assertEquals(1, admitted.get())

                val stopping = launch(Dispatchers.IO) { context.close() }
                assertTrue(stopEntered.await(10, TimeUnit.SECONDS))
                assertFalse(context.checkReady())
                assertEquals(1, admitted.get())
                releaseStop.countDown()
                stopping.join()
            }
        }

    @Test
    fun `a ready probe admitted before close cannot publish true afterwards`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            (context as AlcyoneContextImpl).adminReadHookForTesting = {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
            runBlocking {
                context.start()
                val probe = async(Dispatchers.IO) { context.checkReady() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val closing = launch(Dispatchers.IO) { context.close() }
                release.countDown()
                assertFalse(probe.await())
                closing.join()
            }
        }

    @Test
    fun `pending rows do not change readiness and external cancellation propagates`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking {
                try {
                    context.start()
                    fixture.open().use { executor ->
                        executor.write {
                            RawMemoryTxOpsImpl(
                                it,
                            ).insertBatch(listOf(RawMemory("ready-raw", "content", MemoryOrigin.EXTRACTION, emptyList(), Instant.EPOCH)))
                            OrganizationPendingTxOpsImpl(it).enqueue("ready-raw")
                        }
                    }
                    assertTrue(context.checkReady())
                    val cancellation = CancellationException("ready caller cancellation")
                    (context as AlcyoneContextImpl).adminReadHookForTesting = { throw cancellation }
                    assertSame(cancellation, assertFailsWith<CancellationException> { context.checkReady() })
                } finally {
                    context.close()
                }
            }
        }
}
