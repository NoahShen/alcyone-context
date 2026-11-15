package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.ReliableEventConsumer
import com.github.noahshen.alcyone.context.vfs.core.repository.EventCursorStart
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteEventRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteReliableEventRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** 真实 SQLite 的 poll、处理期写入与关闭语义；不使用 Fake 代替锁/持久化证据。 */
class ReliableEventConsumerSqliteTest {
    @Test
    fun `poll without hints reads late events and sequence defeats equal and backwards timestamps`() =
        runBlocking {
            withState { state ->
                val writer = SqliteEventRepository(state)
                val reader = SqliteReliableEventRepository(state)
                val seen = Collections.synchronizedList(mutableListOf<Long>())
                val complete = CompletableDeferred<Unit>()
                val scope = CoroutineScope(Dispatchers.Default)
                val consumer = ReliableEventConsumer(reader, "poll", pollInterval = 20.milliseconds)
                consumer.start(scope) { event ->
                    seen += event.sequence
                    if (seen.size == 3) complete.complete(Unit)
                }
                delay(80)
                writer.append(event(1, 100))
                writer.append(event(2, 100))
                writer.append(event(3, 50))
                withTimeout(2_000) { complete.await() }
                consumer.close()
                scope.cancel()
                assertEquals(listOf(1L, 2L, 3L), seen)
            }
        }

    @Test
    fun `slow handler does not block append and close commits the completed batch`() =
        runBlocking {
            withState { state ->
                val writer = SqliteEventRepository(state)
                val reader = SqliteReliableEventRepository(state)
                writer.append(event(1, 100))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val scope = CoroutineScope(Dispatchers.Default)
                val consumer = ReliableEventConsumer(reader, "slow", pollInterval = 10.milliseconds)
                consumer.start(scope) {
                    entered.complete(Unit)
                    release.await()
                }
                withTimeout(2_000) { entered.await() }
                withTimeout(2_000) { writer.append(event(2, 100)) }
                val closing = Thread { consumer.close() }
                closing.start()
                delay(100)
                assertTrue(closing.isAlive)
                release.complete(Unit)
                closing.join(2_000)
                assertFalse(closing.isAlive)
                assertEquals(2L, reader.initializeCursor("slow", EventCursorStart.EARLIEST))
                scope.cancel()
            }
        }

    @Test
    fun `close after handler failure leaves cursor unchanged and stops retries`() =
        runBlocking {
            withState { state ->
                SqliteEventRepository(state).append(event(1, 100))
                val reader = SqliteReliableEventRepository(state)
                val entered = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                val scope = CoroutineScope(Dispatchers.Default)
                val consumer =
                    ReliableEventConsumer(
                        reader,
                        "failed",
                        pollInterval = 10.milliseconds,
                        retryDelays = listOf(50.milliseconds),
                    )
                consumer.start(scope) {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    throw IllegalStateException("permanent")
                }
                withTimeout(2_000) { entered.await() }
                val closing = Thread { consumer.close() }
                closing.start()
                closing.join(2_000)
                assertFalse(closing.isAlive)
                assertEquals(1L, reader.initializeCursor("failed", EventCursorStart.EARLIEST))
                assertEquals(1, calls.get())
                scope.cancel()
            }
        }

    private fun event(
        number: Int,
        millis: Long,
    ) = EventRecord(
        VfsEventId.parse("00000000-0000-7000-8000-00000000000$number"),
        VfsEventType.FILE_CREATED,
        null,
        Instant.ofEpochMilli(millis),
        VfsUri.parse("alcyone://resources/$number.txt"),
    )

    private fun withState(block: suspend (VfsStateDatabase) -> Unit) =
        runBlocking {
            val directory = Files.createTempDirectory("alcyone-reliable-consumer-sqlite")
            val file = directory.resolve("state.db")
            try {
                VfsStateDatabase.file(file).use { state -> block(state) }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
