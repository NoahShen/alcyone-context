package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.ReliableEventConsumer
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteEventRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteReliableEventRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** 真实独立 JVM：handler 已成功、cursor 尚未提交时强杀，重开必须重投同一 eventId。 */
class ReliableEventConsumerProcessTest {
    @Test
    fun `crash after handler and before cursor commit redelivers the same event after restart`() {
        val directory = Files.createTempDirectory("alcyone-reliable-process")
        val stateFile = directory.resolve("state.db")
        val handled = directory.resolve("handled.txt")
        try {
            val first = runConsumerChild(stateFile, handled, crash = true)
            assertEquals(97, first.exitCode, first.output)
            assertEquals(EVENT_ID, Files.readString(handled).trim())
            val second = runConsumerChild(stateFile, handled, crash = false)
            assertEquals(0, second.exitCode, second.output)
            assertEquals(listOf(EVENT_ID, EVENT_ID), Files.readAllLines(handled))
            assertTrue(second.output.contains("cursor=2"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun runConsumerChild(
        stateFile: Path,
        handled: Path,
        crash: Boolean,
    ): ChildResult {
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                reliableConsumerClasspath(),
                "com.github.noahshen.alcyone.context.vfs.integration.vfs.ReliableEventConsumerProcessTestKt",
                stateFile.toString(),
                handled.toString(),
                crash.toString(),
            ).redirectErrorStream(true).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("reliable consumer child timed out")
        }
        return ChildResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
    }

    private data class ChildResult(
        val exitCode: Int,
        val output: String,
    )
}

private const val EVENT_ID = "00000000-0000-7000-8000-000000000001"

fun main(args: Array<String>) =
    runBlocking {
        val stateFile = Path.of(args[0])
        val handled = Path.of(args[1])
        val crash = args[2].toBoolean()
        VfsStateDatabase.file(stateFile).use { state ->
            val reader = SqliteReliableEventRepository(state)
            if (!crash) {
                val before = reader.readAfter(0, 100)
                check(before.size == 1) { "event missing after crash: $before" }
            } else {
                SqliteEventRepository(state).append(
                    EventRecord(
                        VfsEventId.parse(EVENT_ID),
                        VfsEventType.FILE_CREATED,
                        null,
                        Instant.EPOCH,
                        VfsUri.parse("alcyone://resources/crash.txt"),
                    ),
                )
            }
            val consumer =
                ReliableEventConsumer(
                    reader,
                    "independent-jvm",
                    pollInterval = 10.milliseconds,
                    retryDelays = listOf(10.milliseconds),
                )
            val scope = CoroutineScope(Dispatchers.Default)
            val handledOne = CompletableDeferred<Unit>()
            consumer.start(scope) { event ->
                Files.writeString(
                    handled,
                    event.event.id.value + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
                if (crash) Runtime.getRuntime().halt(97)
                handledOne.complete(Unit)
            }
            kotlinx.coroutines.withTimeout(5_000) { handledOne.await() }
            consumer.close()
            scope.cancel()
            val next =
                reader.initializeCursor(
                    "independent-jvm",
                    com.github.noahshen.alcyone.context.vfs.core.repository.EventCursorStart.EARLIEST,
                )
            println("cursor=$next")
            check(next == 2L)
        }
    }

private fun reliableConsumerClasspath(): String {
    val paths = LinkedHashSet<String>()

    fun add(path: String) {
        if (path.isNotBlank()) paths += File(path).absolutePath
    }
    System
        .getProperty("java.class.path")
        .orEmpty()
        .split(File.pathSeparatorChar)
        .forEach(::add)
    var loader: ClassLoader? = Thread.currentThread().contextClassLoader
    while (loader != null) {
        if (loader is URLClassLoader) loader.urLs.forEach { add(File(it.toURI()).absolutePath) }
        loader = loader.parent
    }
    listOf(
        ReliableEventConsumerProcessTest::class.java,
        ReliableEventConsumer::class.java,
        VfsStateDatabase::class.java,
    ).forEach { type ->
        type.protectionDomain.codeSource
            ?.location
            ?.let { add(File(it.toURI()).absolutePath) }
    }
    return paths.joinToString(File.pathSeparator)
}
