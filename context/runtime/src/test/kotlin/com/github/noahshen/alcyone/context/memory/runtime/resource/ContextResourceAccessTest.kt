package com.github.noahshen.alcyone.context.memory.runtime.resource

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextResourceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextResourceException
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeInfo
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.ReadOptions
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.StorageStat
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsStreamResult
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContextResourceAccessTest {
    @Test
    fun `invalid URIs fail before VFS IO`() =
        runBlocking {
            val vfs = RecordingVfs()
            val access = ContextResourceAccess(vfs)
            val invalid =
                listOf(
                    "",
                    "http://resources/a",
                    "alcyone://resources//a",
                    "alcyone://resources/a?query=1",
                    "alcyone://resources/a#fragment",
                    "alcyone://resources/a\\b",
                    "alcyone://resources/%252e%252e/a",
                    "alcyone://resources/%2e%2e/a",
                )

            invalid.forEach { uri ->
                assertCode(ContextResourceErrorCode.INVALID_URI) { access.loadMeta(uri) }
                assertCode(ContextResourceErrorCode.INVALID_URI) { access.read(uri) }
                assertCode(ContextResourceErrorCode.INVALID_URI) { access.readStream(uri).toList() }
            }
            assertEquals(0, vfs.statCalls)
            assertEquals(0, vfs.readCalls)
            assertEquals(0, vfs.openStreamCalls)
        }

    @Test
    fun `non resources roots and namespaces fail before VFS IO`() =
        runBlocking {
            val vfs = RecordingVfs()
            val access = ContextResourceAccess(vfs)
            val outOfScope =
                listOf(
                    "alcyone://",
                    "alcyone://resources",
                    "alcyone://resources/",
                    "alcyone://memory/a",
                    "alcyone://resources-other/a",
                )

            outOfScope.forEach { uri ->
                assertCode(ContextResourceErrorCode.OUT_OF_SCOPE) { access.loadMeta(uri) }
                assertCode(ContextResourceErrorCode.OUT_OF_SCOPE) { access.read(uri) }
                assertCode(ContextResourceErrorCode.OUT_OF_SCOPE) { access.readStream(uri).toList() }
            }
            assertEquals(0, vfs.statCalls)
            assertEquals(0, vfs.readCalls)
            assertEquals(0, vfs.openStreamCalls)
        }

    @Test
    fun `valid percent encoded resource URI is parsed once and normalized`() =
        runBlocking {
            val modifiedAt = Instant.parse("2026-11-08T01:02:03Z")
            val vfs = RecordingVfs()
            vfs.statHandler = { uri ->
                node(uri, NodeType.FILE, StorageStat(12, modifiedAt))
            }
            val access = ContextResourceAccess(vfs)

            val metadata = access.loadMeta("alcyone://resources/docs/a%20b.txt")

            assertEquals("alcyone://resources/docs/a%20b.txt", metadata.uri)
            assertEquals(12, metadata.sizeBytes)
            assertEquals(modifiedAt, metadata.modifiedAt)
            assertEquals("alcyone://resources/docs/a%20b.txt", vfs.lastStatUri.toString())
            assertEquals(StatOptions(), vfs.lastStatOptions)
        }

    @Test
    fun `loadMeta keeps absent storage facts null and rejects directories`() =
        runBlocking {
            val vfs = RecordingVfs()
            vfs.statHandler = { uri -> node(uri, NodeType.FILE, storage = null) }
            val access = ContextResourceAccess(vfs)

            val metadata = access.loadMeta("alcyone://resources/a.txt")
            assertNull(metadata.sizeBytes)
            assertNull(metadata.modifiedAt)

            vfs.statHandler = { uri -> node(uri, NodeType.DIRECTORY, StorageStat(0, null)) }
            assertCode(ContextResourceErrorCode.TYPE_MISMATCH) { access.loadMeta("alcyone://resources/docs") }
            Unit
        }

    @Test
    fun `read returns raw bytes and always uses default ReadOptions`() =
        runBlocking {
            val bytes = byteArrayOf(0, 1, 2, 127, -1)
            val vfs = RecordingVfs()
            vfs.readHandler = { bytes.copyOf() }
            val access = ContextResourceAccess(vfs)

            assertTrue(bytes.contentEquals(access.read("alcyone://resources/a.bin")))
            assertEquals(ReadOptions(), vfs.lastReadOptions)
        }

    @Test
    fun `VFS failures map to safe resource codes without backend detail`() =
        runBlocking {
            val mappings =
                mapOf(
                    VfsErrorCode.NOT_FOUND to ContextResourceErrorCode.NOT_FOUND,
                    VfsErrorCode.TYPE_MISMATCH to ContextResourceErrorCode.TYPE_MISMATCH,
                    VfsErrorCode.LIMIT_EXCEEDED to ContextResourceErrorCode.LIMIT_EXCEEDED,
                    VfsErrorCode.MOUNT_NOT_FOUND to ContextResourceErrorCode.UNAVAILABLE,
                    VfsErrorCode.CLOSED to ContextResourceErrorCode.UNAVAILABLE,
                    VfsErrorCode.STORAGE_ERROR to ContextResourceErrorCode.UNAVAILABLE,
                    VfsErrorCode.STATE_ERROR to ContextResourceErrorCode.UNAVAILABLE,
                )

            mappings.forEach { (vfsCode, expected) ->
                val vfs = RecordingVfs()
                val backendFailure = { throw VfsException(vfsCode, "backend leaked /physical/secret and credential") }
                vfs.statHandler = { backendFailure() }
                vfs.readHandler = { backendFailure() }
                vfs.openHandler = { backendFailure() }
                val access = ContextResourceAccess(vfs)
                listOf(
                    assertCode(expected) { access.loadMeta("alcyone://resources/a") },
                    assertCode(expected) { access.read("alcyone://resources/a") },
                    assertCode(expected) { access.readStream("alcyone://resources/a").toList() },
                ).forEach { failure ->
                    assertNull(failure.cause)
                    assertTrue(!failure.message!!.contains("secret"))
                    assertTrue(!failure.message!!.contains("credential"))
                }
            }
        }

    @Test
    fun `cancellation from VFS is rethrown as the same instance`() =
        runBlocking {
            val cancellation = CancellationException("cancel resource read")
            val vfs = RecordingVfs()
            vfs.readHandler = { throw cancellation }

            val actual = assertFailsWith<CancellationException> { ContextResourceAccess(vfs).read("alcyone://resources/a") }
            assertSame(cancellation, actual)
        }

    @Test
    fun `readStream is cold emits fixed chunks and opens once per collect`() =
        runBlocking {
            val data = ByteArray(64 * 1024 + 1) { (it % 251).toByte() }
            val opened = mutableListOf<TrackingInputStream>()
            val vfs = RecordingVfs()
            vfs.openHandler = { uri ->
                TrackingInputStream(data).let { stream ->
                    opened += stream
                    VfsStreamResult(uri, data.size.toLong(), stream)
                }
            }
            val access = ContextResourceAccess(vfs)
            val flow = access.readStream("alcyone://resources/large.bin")
            assertEquals(0, vfs.openStreamCalls, "constructing the Flow must not open a file")

            val first = flow.toList()
            val second = flow.toList()

            assertEquals(listOf(64 * 1024, 1), first.map { it.size })
            assertEquals(listOf(64 * 1024, 1), second.map { it.size })
            assertTrue((first + second).fold(ByteArray(0)) { acc, chunk -> acc + chunk }.contentEquals(data + data))
            assertEquals(2, vfs.openStreamCalls)
            assertEquals(listOf(1, 1), opened.map { it.closeCount })
            assertEquals(VfsStreamOptions(), vfs.lastOpenOptions)
        }

    @Test
    fun `zero length stream emits nothing and closes once`() =
        runBlocking {
            val stream = TrackingInputStream(byteArrayOf())
            val vfs = RecordingVfs()
            vfs.openHandler = { uri -> VfsStreamResult(uri, 0, stream) }

            assertEquals(emptyList(), ContextResourceAccess(vfs).readStream("alcyone://resources/empty").toList())
            assertEquals(1, stream.closeCount)
        }

    @Test
    fun `take collector failure and cancellation all close the stream`() =
        runBlocking {
            val data = ByteArray(200 * 1024) { 7 }
            val streams = mutableListOf<TrackingInputStream>()
            val vfs = RecordingVfs()
            vfs.openHandler = { uri ->
                TrackingInputStream(data).let { stream ->
                    streams += stream
                    VfsStreamResult(uri, data.size.toLong(), stream)
                }
            }
            val access = ContextResourceAccess(vfs)

            assertEquals(
                1,
                access
                    .readStream("alcyone://resources/a")
                    .take(1)
                    .toList()
                    .size,
            )
            val collectorFailure = IllegalStateException("collector stopped")
            val actual =
                assertFailsWith<IllegalStateException> {
                    access.readStream("alcyone://resources/a").collect { throw collectorFailure }
                }
            assertSame(collectorFailure, actual)

            val emitted = CompletableDeferred<Unit>()
            val job =
                launch(Dispatchers.Default) {
                    access.readStream("alcyone://resources/a").collect {
                        emitted.complete(Unit)
                        awaitCancellation()
                    }
                }
            emitted.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(listOf(1, 1, 1), streams.map { it.closeCount })
        }

    @Test
    fun `read failure terminates the flow and closes without exposing IOException`() =
        runBlocking {
            val stream = TrackingInputStream(ByteArray(128 * 1024), failAt = 1)
            val vfs = RecordingVfs()
            vfs.openHandler = { uri -> VfsStreamResult(uri, 128 * 1024, stream) }

            val failure =
                assertCode(ContextResourceErrorCode.UNAVAILABLE) {
                    ContextResourceAccess(vfs).readStream("alcyone://resources/fail.bin").toList()
                }
            assertNull(failure.cause)
            assertTrue(!failure.message!!.contains("/physical/secret"))
            assertEquals(1, stream.closeCount)
        }

    @Test
    fun `close failure alone maps to UNAVAILABLE and hides backend detail`() =
        runBlocking {
            val stream = TrackingInputStream(byteArrayOf(1), closeFailure = VfsException(VfsErrorCode.STORAGE_ERROR, "/physical/secret"))
            val vfs = RecordingVfs()
            vfs.openHandler = { uri -> VfsStreamResult(uri, 1, stream) }

            val failure =
                assertCode(ContextResourceErrorCode.UNAVAILABLE) {
                    ContextResourceAccess(vfs).readStream("alcyone://resources/a").toList()
                }
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertTrue(!failure.message!!.contains("secret"))
        }

    @Test
    fun `read failure remains primary and close failure is a safe suppressed exception`() =
        runBlocking {
            val stream =
                TrackingInputStream(
                    ByteArray(128 * 1024),
                    failAt = 1,
                    closeFailure = VfsException(VfsErrorCode.STORAGE_ERROR, "/physical/secret"),
                )
            val vfs = RecordingVfs()
            vfs.openHandler = { uri -> VfsStreamResult(uri, 128 * 1024, stream) }

            val failure =
                assertCode(ContextResourceErrorCode.UNAVAILABLE) {
                    ContextResourceAccess(vfs).readStream("alcyone://resources/a").toList()
                }
            val suppressed = failure.suppressed.single()
            assertTrue(suppressed is ContextResourceException)
            assertNull(suppressed.cause)
            assertTrue(!failure.message!!.contains("secret"))
            assertTrue(!suppressed.message!!.contains("secret"))
        }

    @Test
    fun `fatal errors propagate unchanged from public resource methods and flow`() =
        runBlocking {
            val statFatal = AssertionError("fatal stat")
            val statVfs = RecordingVfs()
            statVfs.statHandler = { throw statFatal }
            val statContext = DelegatingResourceContext(ContextResourceAccess(statVfs))
            assertSame(statFatal, assertFailsWith<AssertionError> { statContext.loadMeta("alcyone://resources/a") })

            val readFatal = AssertionError("fatal read")
            val readVfs = RecordingVfs()
            readVfs.readHandler = { throw readFatal }
            val readContext = DelegatingResourceContext(ContextResourceAccess(readVfs))
            assertSame(readFatal, assertFailsWith<AssertionError> { readContext.read("alcyone://resources/a") })

            val openFatal = AssertionError("fatal open")
            val openVfs = RecordingVfs()
            openVfs.openHandler = { throw openFatal }
            val openContext = DelegatingResourceContext(ContextResourceAccess(openVfs))
            assertSame(
                openFatal,
                assertFailsWith<AssertionError> { openContext.readStream("alcyone://resources/a").toList() },
            )

            val streamReadFatal = AssertionError("fatal stream read")
            val failingStream = TrackingInputStream(byteArrayOf(1), readFailure = streamReadFatal)
            val streamVfs = RecordingVfs()
            streamVfs.openHandler = { uri -> VfsStreamResult(uri, 1, failingStream) }
            val streamContext = DelegatingResourceContext(ContextResourceAccess(streamVfs))
            assertSame(
                streamReadFatal,
                assertFailsWith<AssertionError> { streamContext.readStream("alcyone://resources/a").toList() },
            )
            assertEquals(1, failingStream.closeCount)

            val closeFatal = AssertionError("fatal close")
            val closeStream = TrackingInputStream(byteArrayOf(1), closeFailure = closeFatal)
            val closeVfs = RecordingVfs()
            closeVfs.openHandler = { uri -> VfsStreamResult(uri, 1, closeStream) }
            val closeContext = DelegatingResourceContext(ContextResourceAccess(closeVfs))
            assertSame(
                closeFatal,
                assertFailsWith<AssertionError> { closeContext.readStream("alcyone://resources/a").toList() },
            )
            Unit
        }

    private suspend fun assertCode(
        expected: ContextResourceErrorCode,
        block: suspend () -> Unit,
    ): ContextResourceException {
        val failure = assertFailsWith<ContextResourceException> { block() }
        assertEquals(expected, failure.errorCode)
        return failure
    }

    private fun node(
        uri: VfsUri,
        type: NodeType,
        storage: StorageStat?,
    ): NodeInfo =
        NodeInfo(
            id = NodeId.parse("018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8092"),
            uri = uri,
            type = type,
            registeredAt = Instant.parse("2026-11-08T00:00:00Z"),
            updatedAt = Instant.parse("2026-11-08T00:00:00Z"),
            storage = storage,
        )

    private class RecordingVfs : Vfs {
        var statHandler: suspend (VfsUri) -> NodeInfo = { error("stat handler not configured") }
        var readHandler: suspend (VfsUri) -> ByteArray = { error("read handler not configured") }
        var openHandler: suspend (VfsUri) -> VfsStreamResult = { error("open handler not configured") }

        var statCalls = 0
        var readCalls = 0
        var openStreamCalls = 0
        var lastStatUri: VfsUri? = null
        var lastReadUri: VfsUri? = null
        var lastOpenUri: VfsUri? = null
        var lastStatOptions: StatOptions? = null
        var lastReadOptions: ReadOptions? = null
        var lastOpenOptions: VfsStreamOptions? = null

        override suspend fun read(
            uri: VfsUri,
            options: ReadOptions,
        ): ByteArray {
            readCalls++
            lastReadUri = uri
            lastReadOptions = options
            return readHandler(uri)
        }

        override suspend fun openStream(
            uri: VfsUri,
            options: VfsStreamOptions,
        ): VfsStreamResult {
            openStreamCalls++
            lastOpenUri = uri
            lastOpenOptions = options
            return openHandler(uri)
        }

        override suspend fun stat(
            uri: VfsUri,
            options: StatOptions,
        ): NodeInfo {
            statCalls++
            lastStatUri = uri
            lastStatOptions = options
            return statHandler(uri)
        }

        override suspend fun write(
            uri: VfsUri,
            content: ByteArray,
            options: WriteOptions,
        ): NodeInfo = unsupported()

        override suspend fun getNode(id: NodeId): NodeInfo = unsupported()

        override suspend fun list(uri: VfsUri): List<com.github.noahshen.alcyone.context.vfs.VfsEntry> = unsupported()

        override suspend fun move(
            source: VfsUri,
            target: VfsUri,
        ): NodeInfo = unsupported()

        override suspend fun delete(
            uri: VfsUri,
            options: DeleteOptions,
        ): Unit = unsupported()

        override suspend fun getMetadata(id: NodeId): NodeMetadata = unsupported()

        override suspend fun setMetadata(
            id: NodeId,
            metadata: NodeMetadata,
        ): Unit = unsupported()

        private fun <T> unsupported(): T = throw UnsupportedOperationException("not used by resource tests")
    }

    private class DelegatingResourceContext(
        private val access: ContextResourceAccess,
    ) : AlcyoneContext {
        override suspend fun loadMeta(uri: String): ResourceMetadata = access.loadMeta(uri)

        override suspend fun read(uri: String): ByteArray = access.read(uri)

        override fun readStream(uri: String): Flow<ByteArray> = access.readStream(uri)

        override suspend fun ingestConversation(turn: ConversationTurn): IngestResult = error("not used")

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus = error("not used")
    }

    private class TrackingInputStream(
        private val data: ByteArray,
        private val failAt: Int? = null,
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : InputStream() {
        private var position = 0
        var closeCount = 0
            private set

        override fun read(): Int {
            if (position >= data.size) return -1
            readFailure?.let { throw it }
            failIfNeeded()
            return data[position++].toInt() and 0xFF
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= data.size) return -1
            readFailure?.let { throw it }
            failIfNeeded()
            val count = minOf(length, data.size - position, (failAt ?: Int.MAX_VALUE) - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }

        private fun failIfNeeded() {
            if (failAt != null && position >= failAt) {
                throw IOException("backend /physical/secret")
            }
        }
    }
}
