package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextResourceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextResourceException
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlcyoneContextResourceIntegrationTest {
    @Test
    fun `real Runtime and LocalFs close the resource read loop without events or content writes`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val data = byteArrayOf(0, 1, 2, 3, -1, -2)
            val resource = fixture.mountRoot.resolve("a.bin")
            Files.write(resource, data)
            val vfsConfig = resourceConfig(fixture)
            val context = AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
            try {
                runBlocking { context.start() }
                val eventCountBefore = eventCount(fixture.vfsPath)

                val metadata = runBlocking { context.loadMeta("alcyone://resources/a.bin") }
                assertEquals("alcyone://resources/a.bin", metadata.uri)
                assertEquals(data.size.toLong(), metadata.sizeBytes)
                assertTrue(metadata.modifiedAt != null)
                assertEquals(data.size.toLong(), Files.size(resource))
                assertTrue(data.contentEquals(Files.readAllBytes(resource)))
                assertEquals(eventCountBefore, eventCount(fixture.vfsPath), "loadMeta must not publish a file event")

                assertTrue(data.contentEquals(runBlocking { context.read("alcyone://resources/a.bin") }))
                val chunks = runBlocking { context.readStream("alcyone://resources/a.bin").toList() }
                assertTrue(chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.contentEquals(data))
            } finally {
                context.close()
            }

            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertNull((context as AlcyoneContextImpl).resourceAccessForTesting())
            runBlocking {
                assertCode(ContextLifecycleErrorCode.CLOSED) { context.loadMeta("alcyone://resources/a.bin") }
                assertCode(ContextLifecycleErrorCode.CLOSED) {
                    context.readStream("alcyone://resources/a.bin").toList()
                }
            }
        }

    @Test
    fun `read enforces the VFS ByteArray limit while readStream remains chunked`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val resource = fixture.mountRoot.resolve("large.bin")
            Files.newOutputStream(resource).use { output ->
                val block = ByteArray(1024 * 1024) { 11 }
                repeat(16) { output.write(block) }
                output.write(12)
            }
            val vfsConfig = resourceConfig(fixture)
            val context = AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
            try {
                runBlocking { context.start() }

                val failure =
                    assertFailsWith<ContextResourceException> {
                        runBlocking { context.read("alcyone://resources/large.bin") }
                    }
                assertEquals(ContextResourceErrorCode.LIMIT_EXCEEDED, failure.errorCode)

                var total = 0L
                var chunks = 0
                runBlocking {
                    context.readStream("alcyone://resources/large.bin").collect { chunk ->
                        assertTrue(chunk.size <= 64 * 1024)
                        total += chunk.size
                        chunks++
                    }
                }
                assertEquals(16L * 1024 * 1024 + 1, total)
                assertTrue(chunks > 1)
            } finally {
                context.close()
            }
        }

    @Test
    fun `read accepts zero and exact limit and rejects one byte over`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            Files.write(fixture.mountRoot.resolve("empty.bin"), byteArrayOf())
            Files.write(fixture.mountRoot.resolve("exact.bin"), byteArrayOf(1, 2, 3, 4))
            Files.write(fixture.mountRoot.resolve("over.bin"), byteArrayOf(1, 2, 3, 4, 5))
            val vfsConfig =
                resourceConfig(fixture).copy(
                    limits = VfsLimits(defaultReadMaxBytes = 4, defaultWriteMaxBytes = 4),
                )
            val context = AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
            try {
                runBlocking { context.start() }
                runBlocking {
                    assertEquals(0, context.read("alcyone://resources/empty.bin").size)
                    assertEquals(listOf<Byte>(1, 2, 3, 4), context.read("alcyone://resources/exact.bin").toList())
                }
                val failure =
                    assertFailsWith<ContextResourceException> {
                        runBlocking { context.read("alcyone://resources/over.bin") }
                    }
                assertEquals(ContextResourceErrorCode.LIMIT_EXCEEDED, failure.errorCode)
            } finally {
                context.close()
            }
        }

    @Test
    fun `missing resources mount keeps start compatible and fails only when called`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val vfsConfig = fixture.vfsConfig()
            val context = AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
            try {
                runBlocking { context.start() }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                val failure =
                    assertFailsWith<ContextResourceException> {
                        runBlocking { context.loadMeta("alcyone://resources/a.txt") }
                    }
                assertEquals(ContextResourceErrorCode.UNAVAILABLE, failure.errorCode)
            } finally {
                context.close()
            }
        }

    @Test
    fun `readStream is cold in CREATED and lifecycle errors are named`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val vfsConfig = resourceConfig(fixture)
            val context = AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)

            val cold = context.readStream("alcyone://resources/a.bin")
            runBlocking {
                assertCode(ContextLifecycleErrorCode.NOT_STARTED) { context.loadMeta("alcyone://resources/a.bin") }
                assertCode(ContextLifecycleErrorCode.NOT_STARTED) { cold.toList() }
            }
            context.close()
            runBlocking { assertCode(ContextLifecycleErrorCode.CLOSED) { cold.toList() } }
        }

    @Test
    fun `STARTING rejects resource access with NOT_STARTED`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val vfsConfig = resourceConfig(fixture)
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(vfsConfig = vfsConfig),
                    vfsConfig,
                    probe =
                        AlcyoneContextFactory.StartProbe {
                            entered.complete(Unit)
                            release.await()
                        },
                )
            try {
                runBlocking {
                    val starting = launch(Dispatchers.Default) { context.start() }
                    entered.await()
                    assertEquals(ContextLifecycleState.STARTING, context.status().lifecycle)
                    assertCode(ContextLifecycleErrorCode.NOT_STARTED) {
                        context.loadMeta("alcyone://resources/a.bin")
                    }
                    assertCode(ContextLifecycleErrorCode.NOT_STARTED) {
                        context.readStream("alcyone://resources/a.bin").toList()
                    }
                    release.complete(Unit)
                    starting.join()
                }
            } finally {
                release.complete(Unit)
                context.close()
            }
        }

    @Test
    fun `STOPPING rejects resource access with CLOSED and clears the access`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val vfsConfig = resourceConfig(fixture)
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(vfsConfig = vfsConfig),
                    vfsConfig,
                    probe = AlcyoneContextFactory.StartProbe { },
                    releaseProbe =
                        AlcyoneContextFactory.ReleaseProbe { target ->
                            if (target == AlcyoneContextFactory.ReleaseTarget.VFS) {
                                entered.countDown()
                                assertTrue(release.await(10, TimeUnit.SECONDS), "STOPPING gate must be bounded")
                            }
                        },
                )
            try {
                runBlocking { context.start() }
                runBlocking {
                    val closing = launch(Dispatchers.Default) { context.closeAsync() }
                    assertTrue(entered.await(10, TimeUnit.SECONDS), "close must reach the VFS release gate")
                    assertEquals(ContextLifecycleState.STOPPING, context.status().lifecycle)
                    assertNull((context as AlcyoneContextImpl).resourceAccessForTesting())
                    assertCode(ContextLifecycleErrorCode.CLOSED) {
                        context.loadMeta("alcyone://resources/a.bin")
                    }
                    assertCode(ContextLifecycleErrorCode.CLOSED) {
                        context.readStream("alcyone://resources/a.bin").toList()
                    }
                    release.countDown()
                    closing.join()
                }
            } finally {
                release.countDown()
                context.close()
            }
        }

    @Test
    fun `Context close reclaims an already opened Resource stream`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            Files.write(fixture.mountRoot.resolve("open.bin"), ByteArray(128 * 1024) { 3 })
            val vfsConfig = resourceConfig(fixture)
            val context = AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
            try {
                runBlocking { context.start() }
                val runningVfs = assertNotNull((context as AlcyoneContextImpl).vfsForTesting())
                runBlocking {
                    val firstChunk = CompletableDeferred<Unit>()
                    val collector =
                        launch(Dispatchers.Default) {
                            context.readStream("alcyone://resources/open.bin").collect {
                                firstChunk.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    firstChunk.await()
                    assertEquals(1, trackedStreamCount(runningVfs))

                    context.closeAsync()
                    assertEquals(0, trackedStreamCount(runningVfs), "Context close must reclaim the open VFS stream")
                    collector.cancelAndJoin()
                }
            } finally {
                context.close()
            }
        }

    private fun resourceConfig(fixture: ContextFixture) =
        fixture.vfsConfig(
            namespaces = setOf("resources"),
            mounts = listOf(fixture.localMount(vfsPath = "/resources", storageKey = "local-resources")),
        )

    private fun eventCount(database: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:${database.toUri()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM event").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    private fun trackedStreamCount(vfs: Any): Int =
        vfs.javaClass.methods
            .single { method -> method.parameterCount == 0 && method.name.startsWith("getTrackedStreamCount") }
            .invoke(vfs) as Int

    private suspend fun assertCode(
        expected: ContextLifecycleErrorCode,
        block: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<ContextLifecycleException> { block() }
        assertEquals(expected, failure.errorCode)
    }
}
