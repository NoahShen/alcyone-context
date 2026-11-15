package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.*
import com.github.noahshen.alcyone.context.vfs.runtime.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RuntimePublishNewTest {
    @TempDir lateinit var temp: Path

    private fun root(name: String) = Files.createDirectory(temp.resolve(name))

    private fun config(
        root: Path,
        state: String = "state.sqlite",
    ) = VfsRuntimeConfig(temp.resolve(state), setOf("memory"), listOf(MountConfig(VfsPath.parse("/memory/backups"), "backups", root)))

    private fun uri(name: String = "snapshot.sqlite") = VfsUri.parse("alcyone://memory/backups/$name")

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    @Timeout(60)
    fun `concurrent writers publish exactly one complete file`() =
        runBlocking {
            val root = root("contention")
            val firstContent = bytes(200_000)
            val secondContent = bytes(100_000).reversedArray()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = AlcyoneVfs.create(config(root))
            val second = AlcyoneVfs.create(config(root, "second-state.sqlite"))
            try {
                val slow =
                    object : InputStream() {
                        val delegate = ByteArrayInputStream(firstContent)

                        override fun read(): Int {
                            if (!started.isCompleted) started.complete(Unit)
                            runBlocking { release.await() }
                            return delegate.read()
                        }
                    }
                val one =
                    async(
                        Dispatchers.IO,
                    ) {
                        runCatching {
                            first.publishNew(
                                uri(),
                                slow,
                                PublishOptions(firstContent.size.toLong(), firstContent.size.toLong()),
                            )
                        }
                    }
                started.await()
                val two =
                    async(Dispatchers.IO) {
                        runCatching {
                            second.publishNew(
                                uri(),
                                ByteArrayInputStream(secondContent),
                                PublishOptions(secondContent.size.toLong(), secondContent.size.toLong()),
                            )
                        }
                    }
                release.complete(Unit)
                val outcomes = listOf(one.await(), two.await())
                assertEquals(1, outcomes.count { it.isSuccess })
                outcomes.filter { it.isFailure }.forEach {
                    assertEquals(VfsErrorCode.ALREADY_EXISTS, assertIs<VfsException>(it.exceptionOrNull()).code)
                }
                val published =
                    when {
                        one.await().isSuccess -> firstContent
                        else -> secondContent
                    }
                assertContentEquals(published, Files.readAllBytes(root.resolve("snapshot.sqlite")))
                assertEquals(
                    listOf("snapshot.sqlite"),
                    Files.list(root).use {
                        it
                            .map { path ->
                                path.fileName.toString()
                            }.sorted()
                            .toList()
                    },
                )
            } finally {
                release.complete(Unit)
                second.close()
                first.close()
            }
        }

    @Test
    @Timeout(60)
    fun `large stream publishes without the ByteArray write limit`() =
        runBlocking {
            val root = root("large")
            val vfs = AlcyoneVfs.create(config(root))
            val size = 70L * 1024 * 1024
            try {
                val input =
                    object : InputStream() {
                        var read = 0L

                        override fun read(): Int = if (read++ < size) 0x5a else -1

                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            val count = minOf(length.toLong(), size - read).toInt()
                            if (count <= 0) return -1
                            java.util.Arrays.fill(buffer, offset, offset + count, 0x5a.toByte())
                            read += count
                            return count
                        }
                    }
                val info = vfs.publishNew(uri(), input, PublishOptions(size, size))
                assertEquals(size, info.storage?.sizeBytes)
                assertEquals(size, Files.size(root.resolve("snapshot.sqlite")))
                assertFailsWith<VfsException> { vfs.read(uri()) }.also { assertEquals(VfsErrorCode.LIMIT_EXCEEDED, it.code) }
            } finally {
                vfs.close()
            }
            Unit
        }

    @Test
    @Timeout(60)
    fun `close is reentrant and rejects work afterwards`() =
        runBlocking {
            val vfs = AlcyoneVfs.create(config(root("close")))
            coroutineScope { (1..4).map { launch { vfs.close() } }.joinAll() }
            vfs.close()
            assertFailsWith<VfsException> { vfs.publishNew(uri(), ByteArrayInputStream(bytes(1)), PublishOptions(1, 1)) }
            Unit
        }
}
