package com.github.noahshen.alcyone.context.vfs.integration.vfs

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/** CT17 B1：真实 SQLite + LocalFs + 公开 Runtime 栈上的 safeReplace 纵向证据。 */
class DefaultVfsSafeReplaceRealStackTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("disk"))
    }

    private fun config() =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk)),
        )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources$path")

    private fun eventTypes(): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_type FROM event ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    @Test
    @Timeout(60)
    fun `first create and replace preserve identity and reopen with the complete new bytes`() =
        runBlocking {
            prepare()
            val first = AlcyoneVfs.create(config())
            val created = first.safeReplace(uri("/maps/current.json"), "{\"v\":1}".toByteArray())
            val metadata = NodeMetadata(setOf("map"), "published")
            first.setMetadata(created.id, metadata)
            val replaced = first.safeReplace(uri("/maps/current.json"), "{\"v\":2,\"complete\":true}".toByteArray())

            assertEquals(created.id, replaced.id)
            assertEquals(created.registeredAt, replaced.registeredAt)
            assertEquals(metadata, first.getMetadata(created.id))
            assertEquals("{\"v\":2,\"complete\":true}", Files.readString(disk.resolve("maps/current.json")))
            assertEquals(listOf("FILE_CREATED", "METADATA_UPDATED", "FILE_WRITTEN"), eventTypes())
            first.close()

            val reopened = AlcyoneVfs.create(config())
            try {
                assertEquals("{\"v\":2,\"complete\":true}", reopened.read(uri("/maps/current.json")).toString(Charsets.UTF_8))
                assertEquals(created.id, reopened.getNode(created.id).id)
                assertEquals(metadata, reopened.getMetadata(created.id))
            } finally {
                reopened.close()
            }
        }

    @Test
    @Timeout(60)
    fun `real state commit failure leaves complete new bytes and reports STATE_ERROR PARTIAL`() =
        runBlocking {
            prepare()
            val first = AlcyoneVfs.create(config())
            val created = first.safeReplace(uri("/map.json"), "{\"v\":1}".toByteArray())
            val updatedAtBefore = first.getNode(created.id).updatedAt
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TRIGGER reject_file_written
                        BEFORE INSERT ON event
                        WHEN NEW.event_type = 'FILE_WRITTEN'
                        BEGIN
                            SELECT RAISE(ABORT, 'injected FILE_WRITTEN failure');
                        END
                        """.trimIndent(),
                    )
                }
            }

            val failure = assertFailsWith<VfsException> { first.safeReplace(uri("/map.json"), "{\"v\":2}".toByteArray()) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect)
            assertEquals("{\"v\":2}", Files.readString(disk.resolve("map.json")))
            assertEquals(listOf("FILE_CREATED"), eventTypes(), "failed state commit publishes no FILE_WRITTEN")
            assertEquals(created.id, first.getNode(created.id).id, "Node transaction rolls back")
            assertEquals(updatedAtBefore, first.getNode(created.id).updatedAt, "updatedAt does not claim a committed replace")
            first.close()

            val reopened = AlcyoneVfs.create(config())
            try {
                assertEquals("{\"v\":2}", reopened.read(uri("/map.json")).toString(Charsets.UTF_8), "reopen does not fake the old bytes")
                assertEquals(created.id, reopened.getNode(created.id).id)
                assertTrue(eventTypes().none { it == "FILE_WRITTEN" })
            } finally {
                reopened.close()
            }
        }
}
