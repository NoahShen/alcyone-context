package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapArtifact
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapDocument
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapRenderer
import com.github.noahshen.alcyone.context.memory.core.repository.ReadContext
import com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

internal class MemoryMapFixture(
    val database: ContextDatabase,
    val vfs: AlcyoneVfs,
    val directory: Path,
    val mountRoot: Path,
    private val executor: ContextDatabaseExecutor,
) : AutoCloseable {
    override fun close() {
        runBlocking { vfs.close() }
        executor.close()
    }
}

internal fun withMemoryMapFixture(block: suspend (MemoryMapFixture) -> Unit) {
    val directory = Files.createTempDirectory("ct19-memory-map-")
    var fixture: MemoryMapFixture? = null
    try {
        val contextPath = directory.resolve("data/context.db")
        ContextDatabaseInitializer.initialize(contextPath)
        val executor = ContextDatabaseFactory.open(contextPath)
        val database = ContextDatabaseImpl(executor)
        val mountRoot = Files.createDirectories(directory.resolve("memory"))
        val vfs =
            runBlocking {
                AlcyoneVfs.create(
                    VfsRuntimeConfig(
                        stateDatabase = directory.resolve("data/vfs.db"),
                        namespaces = setOf("memory"),
                        mounts =
                            listOf(
                                MountConfig(
                                    vfsPath = VfsPath.parse("/memory"),
                                    storageKey = "ct19-memory-map",
                                    root = mountRoot,
                                ),
                            ),
                    ),
                )
            }
        fixture = MemoryMapFixture(database, vfs, directory, mountRoot, executor)
        runBlocking { block(fixture) }
    } finally {
        fixture?.close()
        deleteRecursively(directory)
    }
}

internal suspend fun MemoryMapFixture.insertOrganized(
    id: String,
    updatedAt: Instant,
    content: String = "memory-$id",
    domains: List<String> = listOf("domain"),
) {
    database.write { transaction ->
        check(
            transaction.organizedMemories.insertNew(
                OrganizedMemory(
                    id = id,
                    content = content,
                    domains = domains,
                    rawMemoryIds = emptyList(),
                    createdAt = updatedAt.minusSeconds(1),
                    updatedAt = updatedAt,
                ),
            ) == 1,
        )
    }
}

internal suspend fun MemoryMapFixture.databaseMax(): Instant? =
    database.read { transaction -> transaction.organizedMemories.maxUpdatedAt() }

internal fun mapArtifact(
    generatedAt: Instant,
    sourceUpdatedAt: Instant?,
    overview: String = "overview",
): MemoryMapArtifact {
    val document =
        MemoryMapDocument(
            overview = overview,
            navigation = "navigation",
            domains = emptyList(),
            generatedAt = generatedAt,
            sourceUpdatedAt = sourceUpdatedAt,
        )
    return MemoryMapArtifact(document, MemoryMapRenderer.render(document))
}

internal fun mapText(
    generatedAt: Instant,
    sourceUpdatedAt: Instant?,
): String = mapArtifact(generatedAt, sourceUpdatedAt).renderedBytes.toString(Charsets.UTF_8)

internal fun service(
    behavior: suspend () -> MemoryMapArtifact,
    publisher: MemoryMapPublisherPort = MemoryMapPublisherPort { throw AssertionError("publisher must not be called") },
): MemoryMapService = MemoryMapService(MemoryMapGeneratorPort(behavior), publisher)

internal class ManualMemoryMapTicks : MemoryMapPeriodicTickSource {
    private sealed interface Event {
        data object Tick : Event

        data class Failure(
            val failure: Throwable,
        ) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    fun tick() {
        check(events.trySend(Event.Tick).isSuccess)
    }

    fun fail(failure: Throwable = IllegalStateException("ct19-tick-failure-marker")) {
        check(events.trySend(Event.Failure(failure)).isSuccess)
    }

    fun close() {
        events.close()
    }

    override suspend fun awaitTick(): Boolean =
        when (val event = events.receiveCatching().getOrNull()) {
            null -> false
            is Event.Tick -> true
            is Event.Failure -> throw event.failure
        }
}

internal class TrackingMemoryMapDatabase(
    private val delegate: ContextDatabase,
    private val readFailures: Int = 0,
    private val failure: Throwable = IllegalStateException("ct19-db-read-failure-marker"),
) : ContextDatabase {
    private val remainingFailures = AtomicInteger(readFailures)
    val attempts = AtomicInteger(0)
    val reads = AtomicInteger(0)

    override suspend fun <T> read(block: (ReadContext) -> T): T {
        attempts.incrementAndGet()
        if (remainingFailures.getAndUpdate { current -> if (current > 0) current - 1 else 0 } > 0) throw failure
        return delegate.read(block).also { reads.incrementAndGet() }
    }

    override suspend fun <T> write(block: (TransactionContext) -> T): T = delegate.write(block)
}

internal class BlockingMemoryMapDatabase : ContextDatabase {
    val entered = CountDownLatch(1)
    val finished = CountDownLatch(1)

    override suspend fun <T> read(block: (ReadContext) -> T): T {
        entered.countDown()
        try {
            awaitCancellation()
        } finally {
            finished.countDown()
        }
    }

    override suspend fun <T> write(block: (TransactionContext) -> T): T = throw UnsupportedOperationException()
}

internal class CancellingMemoryMapDatabase(
    private val failure: CancellationException,
) : ContextDatabase {
    override suspend fun <T> read(block: (ReadContext) -> T): T = throw failure

    override suspend fun <T> write(block: (TransactionContext) -> T): T = throw UnsupportedOperationException()
}

internal suspend fun awaitMemoryMapUntil(condition: () -> Boolean) {
    withTimeout(10_000L) {
        while (!condition()) delay(10)
    }
}

private fun deleteRecursively(directory: Path) {
    if (!Files.exists(directory)) return
    val failures = mutableListOf<Throwable>()
    Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path ->
            try {
                Files.deleteIfExists(path)
            } catch (failure: Throwable) {
                failures += IllegalStateException("failed to delete test artifact $path", failure)
            }
        }
    }
    if (failures.isNotEmpty()) {
        val primary = failures.first()
        failures.drop(1).forEach(primary::addSuppressed)
        throw primary
    }
}
