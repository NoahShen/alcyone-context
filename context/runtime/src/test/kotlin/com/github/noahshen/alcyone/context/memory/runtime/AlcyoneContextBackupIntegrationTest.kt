package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.*
import com.github.noahshen.alcyone.context.memory.runtime.backup.BackupConfig
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlcyoneContextBackupIntegrationTest {
    @Test fun `manual backup publishes a consistent independently readable snapshot`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val vfs = fixture.vfsConfig(namespaces = setOf("memory"), mounts = listOf(fixture.localMount()))
            val config = fixture.config(vfsConfig = vfs).copy(backup = BackupConfig(tempDirectory = fixture.directory))
            val context = AlcyoneContextFactory.create(config, vfs)
            try {
                runBlocking {
                    context.start()
                    context.ingestConversation(
                        ConversationTurn(
                            "runtime",
                            "agent",
                            "session",
                            "turn",
                            Instant.EPOCH,
                            listOf(ChatMessage(ChatRole.USER, "snapshot evidence")),
                        ),
                    )
                    val result = context.backupNow()
                    val backupUri = VfsUri.parse(result.uri)
                    assertEquals(listOf("memory", "backups"), backupUri.path.segments.take(2))
                    assertTrue(
                        backupUri.path.segments
                            .last()
                            .startsWith("alcyone-"),
                    )
                    assertTrue(result.completedAt >= Instant.EPOCH)
                    assertEquals(WorkerState.IDLE, context.backupStatus().state)
                    val file = fixture.mountRoot.resolve("backups/" + result.uri.substringAfterLast('/'))
                    assertEquals(Files.size(file), result.sizeBytes)
                    DriverManager.getConnection("jdbc:sqlite:${file.toUri()}?mode=ro", Properties()).use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("PRAGMA quick_check").use { rs ->
                                assertTrue(rs.next())
                                assertEquals("ok", rs.getString(1))
                            }
                            statement.executeQuery("SELECT messages_json FROM conversation_turn WHERE session_turn_id='turn'").use { rs ->
                                assertTrue(rs.next())
                                assertTrue(rs.getString(1).contains("snapshot evidence"))
                            }
                        }
                    }
                    // The manual API does not require the automatic scheduler to be enabled.
                    assertEquals(BackupConfig().cron, null)
                }
            } finally {
                context.close()
            }
            runBlocking { assertFailsWith<ContextLifecycleException> { context.backupNow() } }
            Unit
        }
}
