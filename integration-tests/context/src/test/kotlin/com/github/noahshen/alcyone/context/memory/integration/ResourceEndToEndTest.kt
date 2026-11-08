package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ResourceEndToEndTest {
    @Test
    fun `public Factory reads resources through real SQLite and LocalFs`() {
        TestDatabase().use { database ->
            val directory = Files.createTempDirectory("ct17-resource-e2e-")
            try {
                val mountRoot = Files.createDirectories(directory.resolve("storage"))
                val content = ByteArray(130 * 1024 + 3) { (it % 239).toByte() }
                val resource = mountRoot.resolve("nested").resolve("sample.bin")
                Files.createDirectories(resource.parent)
                Files.write(resource, content)
                val vfsConfig =
                    VfsRuntimeConfig(
                        stateDatabase = directory.resolve("vfs.db"),
                        namespaces = setOf("resources"),
                        mounts =
                            listOf(
                                MountConfig(
                                    vfsPath = VfsPath.parse("/resources"),
                                    storageKey = "e2e-resources",
                                    root = mountRoot,
                                ),
                            ),
                    )
                val config =
                    ContextConfig(
                        databasePath = database.path,
                        lifecycleShutdownTimeout = 30.seconds,
                        vfsConfig = vfsConfig,
                    )
                val context = AlcyoneContextFactory.create(config, vfsConfig)
                try {
                    runBlocking { context.start() }
                    assertEquals(ContextLifecycleState.RUNNING, runBlocking { context.getStatus() }.lifecycle)

                    val metadata = runBlocking { context.loadMeta("alcyone://resources/nested/sample.bin") }
                    assertEquals("alcyone://resources/nested/sample.bin", metadata.uri)
                    assertEquals(content.size.toLong(), metadata.sizeBytes)
                    assertTrue(metadata.modifiedAt != null)

                    assertTrue(
                        content.contentEquals(
                            runBlocking { context.read("alcyone://resources/nested/sample.bin") },
                        ),
                    )
                    val chunks = runBlocking { context.readStream("alcyone://resources/nested/sample.bin").toList() }
                    assertEquals(listOf(64 * 1024, 64 * 1024, content.size - 128 * 1024), chunks.map { it.size })
                    assertTrue(chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.contentEquals(content))
                } finally {
                    context.close()
                }
                assertEquals(ContextLifecycleState.CLOSED, runBlocking { context.getStatus() }.lifecycle)
            } finally {
                deleteTree(directory)
            }
        }
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
