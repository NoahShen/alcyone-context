package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class PublishNewTest {
    @TempDir lateinit var temp: Path

    private suspend fun open(): AlcyoneVfs {
        val disk = Files.createDirectories(temp.resolve("disk"))
        return AlcyoneVfs.create(
            VfsRuntimeConfig(
                stateDatabase = temp.resolve("state.db"),
                namespaces = setOf("memory"),
                mounts = listOf(MountConfig(VfsPath.parse("/memory"), "local", disk)),
            ),
        )
    }

    @Test fun `publishes more than the ByteArray read limit without buffering the file`() =
        runBlocking {
            val bytes = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }
            val vfs = open()
            try {
                val result =
                    vfs.publishNew(
                        VfsUri.parse("alcyone://memory/backups/large.sqlite"),
                        ByteArrayInputStream(bytes),
                        PublishOptions(maxBytes = bytes.size.toLong(), expectedSizeBytes = bytes.size.toLong()),
                    )
                assertEquals(bytes.size.toLong(), result.storage?.sizeBytes)
                assertEquals(bytes.size.toLong(), Files.size(temp.resolve("disk/backups/large.sqlite")))
                assertEquals(result.id, vfs.stat(VfsUri.parse("alcyone://memory/backups/large.sqlite")).id)
                assertEquals(
                    emptyList<Path>(),
                    Files.list(temp.resolve("disk/backups")).use {
                        it
                            .filter { p ->
                                p.fileName.toString().startsWith(".alcyone")
                            }.toList()
                    },
                )
            } finally {
                vfs.close()
            }
        }

    @Test fun `a concurrent existing target is never overwritten`() =
        runBlocking {
            val disk = Files.createDirectories(temp.resolve("disk"))
            val target = disk.resolve("existing.sqlite")
            Files.writeString(target, "original")
            val vfs = open()
            try {
                val failure =
                    assertFailsWith<VfsException> {
                        vfs.publishNew(
                            VfsUri.parse("alcyone://memory/existing.sqlite"),
                            ByteArrayInputStream("replacement".toByteArray()),
                            PublishOptions(maxBytes = 11, expectedSizeBytes = 11),
                        )
                    }
                assertEquals(VfsErrorCode.ALREADY_EXISTS, failure.code)
                assertEquals("original", Files.readString(target))
            } finally {
                vfs.close()
            }
        }

    @Test fun `short or long input is rejected and closes the stream`() =
        runBlocking {
            class Owned(
                bytes: ByteArray,
            ) : ByteArrayInputStream(bytes) {
                var closed = false

                override fun close() {
                    closed = true
                    super.close()
                }
            }
            for (content in listOf("short", "this is too long")) {
                val stream = Owned(content.toByteArray())
                val vfs = open()
                try {
                    assertFailsWith<VfsException> {
                        vfs.publishNew(VfsUri.parse("alcyone://memory/file"), stream, PublishOptions(maxBytes = 6, expectedSizeBytes = 6))
                    }
                    assertTrue(stream.closed)
                } finally {
                    vfs.close()
                }
            }
        }
}
