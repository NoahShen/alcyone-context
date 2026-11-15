package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.repository.EventCursorStart
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFailsWith

class ReliableEventSqliteTest {
    @Test
    fun `v1 events migrate through every production migration with stable sequences and complete fields`() =
        runBlocking {
            withTempFile { file ->
                createVersion1(file)
                VfsStateDatabase.file(file).use { state ->
                    assertEquals(4L, state.driver.schemaVersion())
                    val rows = SqliteReliableEventRepository(state).readAfter(0, 100)
                    assertEquals(listOf(1L, 2L), rows.map { it.sequence })
                    assertEquals(listOf(EVENT_B, EVENT_A), rows.map { it.event.id.value })
                    assertEquals("operation-a", rows[1].event.operationId)
                    assertEquals("alcyone://resources/old/a.txt", rows[1].event.uri.toString())
                    assertEquals("alcyone://resources/old/source.txt", rows[1].event.sourceUri.toString())
                    assertEquals("alcyone://resources/old/a.txt", rows[1].event.targetUri.toString())
                    assertEquals(0L, scalar(file, "SELECT count(*) FROM consumer_cursor"))
                }
            }
        }

    @Test
    fun `cursor earliest latest restart ordering and cas use the real sqlite database`() =
        runBlocking {
            withTempFile { file ->
                VfsStateDatabase.file(file).use { state ->
                    val writer: EventRepository = SqliteEventRepository(state)
                    writer.append(testEvent(null, "alcyone://resources/two.txt", id = VfsEventId.parse(EVENT_B)))
                    writer.append(testEvent(null, "alcyone://resources/one.txt", id = VfsEventId.parse(EVENT_A)))
                    val reader = SqliteReliableEventRepository(state)
                    assertEquals(1L, reader.initializeCursor("earliest", EventCursorStart.EARLIEST))
                    assertEquals(listOf(EVENT_B, EVENT_A), reader.readAfter(0, 100).map { it.event.id.value })
                    assertEquals(3L, reader.initializeCursor("latest", EventCursorStart.LATEST))
                    reader.advanceCursor("earliest", 1, 2)
                    assertFailsWith<VfsException> { reader.advanceCursor("earliest", 1, 3) }
                    assertEquals(2L, reader.initializeCursor("earliest", EventCursorStart.LATEST))
                }
                VfsStateDatabase.file(file).use { state ->
                    val reader = SqliteReliableEventRepository(state)
                    assertEquals(2L, reader.initializeCursor("earliest", EventCursorStart.EARLIEST))
                    assertEquals(listOf(EVENT_A), reader.readAfter(1, 100).map { it.event.id.value })
                }
            }
        }

    @Test
    fun `real v3 journal and events migrate directly and new append continues sequence`() =
        runBlocking {
            withTempFile { file ->
                VfsStateDatabase.file(file).use { state ->
                    SqliteEventRepository(state).append(testEvent(null, "alcyone://resources/old/b.txt", id = VfsEventId.parse(EVENT_B)))
                    SqliteEventRepository(state).append(
                        testEvent(null, "alcyone://resources/old/a.txt", id = VfsEventId.parse(EVENT_A)).copy(
                            operationId = "operation-a",
                            sourceUri =
                                com.github.noahshen.alcyone.context.vfs.VfsUri
                                    .parse("alcyone://resources/old/source.txt"),
                            targetUri =
                                com.github.noahshen.alcyone.context.vfs.VfsUri
                                    .parse("alcyone://resources/old/a.txt"),
                        ),
                    )
                }
                downgradeToVersion3(file)
                assertEquals(1L, scalar(file, "SELECT count(*) FROM recovery_operation"))

                VfsStateDatabase.file(file).use { state ->
                    assertEquals(4L, state.driver.schemaVersion())
                    val reader = SqliteReliableEventRepository(state)
                    val rows = reader.readAfter(0, 100)
                    assertEquals(listOf(1L, 2L), rows.map { it.sequence })
                    assertEquals("operation-a", rows[1].event.operationId)
                    assertEquals("alcyone://resources/old/source.txt", rows[1].event.sourceUri.toString())
                    assertEquals(1L, scalar(file, "SELECT count(*) FROM recovery_operation"))
                    SqliteEventRepository(state).append(testEvent(null, "alcyone://resources/new.txt"))
                    assertEquals(3L, reader.readAfter(2, 100).single().sequence)
                }
            }
        }

    @Test
    fun `failed v3 to v4 migration rolls back schema data journal and version`() {
        withTempFile { file ->
            VfsStateDatabase.file(file).use { state ->
                SqliteEventRepository(state).append(
                    testEvent(null, "alcyone://resources/original.txt", id = VfsEventId.parse(EVENT_A)),
                )
            }
            // 损坏 v3 去掉旧唯一约束，仅用于制造重复 event_id，验证新 UNIQUE 失败的原子回滚。
            downgradeToVersion3(file, uniqueEventIds = false)
            rawConnection(file).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO event VALUES ('${EVENT_A}', 'FILE_CREATED', NULL, 100, " +
                            "'alcyone://resources/original.txt', NULL, NULL, NULL)",
                    )
                }
            }

            val failure = assertFailsWith<Exception> { VfsStateDatabase.file(file).close() }
            assertTrue(failure.message.orEmpty().isNotEmpty())
            assertEquals(3L, scalar(file, "PRAGMA user_version"))
            assertEquals(2L, scalar(file, "SELECT count(*) FROM event"))
            assertEquals(1L, scalar(file, "SELECT count(*) FROM recovery_operation"))
            assertEquals(0L, scalar(file, "SELECT count(*) FROM sqlite_master WHERE name = 'event_v4'"))
            assertEquals(0L, scalar(file, "SELECT count(*) FROM sqlite_master WHERE name = 'consumer_cursor'"))
            assertEquals(0L, scalar(file, "SELECT count(*) FROM pragma_table_info('event') WHERE name = 'sequence'"))
        }
    }

    private fun downgradeToVersion3(
        file: Path,
        uniqueEventIds: Boolean = true,
    ) {
        rawConnection(file).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE consumer_cursor")
                statement.execute("DROP INDEX event_occurred_at")
                statement.execute("ALTER TABLE event RENAME TO event_v4")
                statement.execute(
                    "CREATE TABLE event (event_id TEXT NOT NULL " + (if (uniqueEventIds) "PRIMARY KEY" else "") +
                        ", event_type TEXT NOT NULL, node_id TEXT, occurred_at INTEGER NOT NULL, uri TEXT NOT NULL, " +
                        "operation_id TEXT, source_uri TEXT, target_uri TEXT)",
                )
                statement.execute(
                    "INSERT INTO event SELECT event_id, event_type, node_id, occurred_at, uri, operation_id, source_uri, target_uri FROM event_v4",
                )
                statement.execute("DROP TABLE event_v4")
                statement.execute(
                    "INSERT INTO recovery_operation VALUES ('journal-v3', 'SAFE_REPLACE', 'REQUESTED', 0, NULL, " +
                        "'alcyone://resources/original.txt', NULL, '/resources', NULL, '', 1, 1)",
                )
                statement.execute("PRAGMA user_version = 3")
            }
            connection.commit()
        }
    }

    private fun createVersion1(file: Path) {
        rawConnection(file).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE mount (vfs_path TEXT NOT NULL PRIMARY KEY, storage_key TEXT NOT NULL)")
                statement.execute(
                    "CREATE TABLE node (id TEXT NOT NULL PRIMARY KEY, vfs_path TEXT NOT NULL, node_type TEXT NOT NULL, " +
                        "physical INTEGER NOT NULL, registered_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)",
                )
                statement.execute("CREATE UNIQUE INDEX node_active_path ON node(vfs_path) WHERE deleted_at IS NULL")
                statement.execute("CREATE TABLE metadata (node_id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
                statement.execute(
                    "CREATE TABLE event (event_id TEXT NOT NULL PRIMARY KEY, event_type TEXT NOT NULL, node_id TEXT, " +
                        "occurred_at INTEGER NOT NULL, uri TEXT NOT NULL, operation_id TEXT, source_uri TEXT, target_uri TEXT)",
                )
                statement.execute(
                    "INSERT INTO event VALUES ('$EVENT_B', 'FILE_CREATED', NULL, 200, " +
                        "'alcyone://resources/old/b.txt', NULL, NULL, NULL)",
                )
                statement.execute(
                    "INSERT INTO event VALUES ('$EVENT_A', 'FILE_MOVED', NULL, 100, 'alcyone://resources/old/a.txt', " +
                        "'operation-a', 'alcyone://resources/old/source.txt', 'alcyone://resources/old/a.txt')",
                )
                statement.execute("PRAGMA user_version = 1")
            }
        }
    }

    private fun rawConnection(file: Path) = DriverManager.getConnection("jdbc:sqlite:$file")

    private fun scalar(
        file: Path,
        sql: String,
    ): Long =
        rawConnection(file).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }

    private fun withTempFile(block: suspend (Path) -> Unit) =
        runBlocking {
            val directory = Files.createTempDirectory("alcyone-reliable-events")
            val file = directory.resolve("state.db")
            try {
                block(file)
            } finally {
                Files.deleteIfExists(file)
                Files.deleteIfExists(Path.of("$file-wal"))
                Files.deleteIfExists(Path.of("$file-shm"))
                Files.deleteIfExists(directory)
            }
        }

    companion object {
        private const val EVENT_A = "00000000-0000-7000-8000-000000000001"
        private const val EVENT_B = "00000000-0000-7000-8000-000000000002"
    }
}
