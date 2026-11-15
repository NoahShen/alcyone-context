package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryDiagnosticCode
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryOperation
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryOperationKind
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryPhase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class RecoveryJournalSqliteTest {
    @Test
    fun `begin advance and listPending use persistent generation state`() =
        withTempState { state ->
            val journal = SqliteRecoveryJournal(state)
            val requested = journal.begin(requestedOperation())

            assertEquals(0L, requested.generation)
            assertEquals(listOf(requested), journal.listPending())

            val observed = journal.advance(requested.operationId, 0L, RecoveryPhase.STORAGE_REPLACE_OBSERVED, at = TEST_NOW.plusSeconds(1))

            assertEquals(RecoveryPhase.STORAGE_REPLACE_OBSERVED, observed.phase)
            assertEquals(1L, observed.generation)
            assertEquals(observed, journal.find(requested.operationId))
            assertEquals(listOf(observed), journal.listPending())

            val stale =
                runCatching {
                    journal.advance(requested.operationId, 0L, RecoveryPhase.CLOSED, at = TEST_NOW.plusSeconds(2))
                }.exceptionOrNull()

            assertTrue(stale is VfsException)
            assertEquals(VfsErrorCode.STATE_ERROR, (stale as VfsException).code)
            assertEquals(observed, journal.find(requested.operationId))

            // operation_id 是主键；重复 begin 必须由真实 SQLite 拒绝，且不能覆盖已有进度。
            val duplicate = runCatching { journal.begin(requested) }.exceptionOrNull()
            assertTrue(duplicate is VfsException)
            assertEquals(VfsErrorCode.STATE_ERROR, (duplicate as VfsException).code)
            assertEquals(observed, journal.find(requested.operationId))

            val progressed =
                journal.diagnose(
                    requested.operationId,
                    observed.generation,
                    RecoveryDiagnosticCode.STATE_COMMIT_FAILURE,
                    at = TEST_NOW.plusSeconds(2),
                )
            assertEquals(observed.generation + 1, progressed.generation)
            assertEquals(RecoveryDiagnosticCode.STATE_COMMIT_FAILURE, progressed.diagnosticCode)
            assertEquals(progressed, journal.find(requested.operationId))
        }

    @Test
    fun `a closed operation rejects advance and diagnose and leaves the closed row unchanged`() =
        withTempState { state ->
            val journal = SqliteRecoveryJournal(state)
            val requested = journal.begin(requestedOperation())
            val observed = journal.advance(requested.operationId, 0L, RecoveryPhase.STORAGE_REPLACE_OBSERVED, at = TEST_NOW.plusSeconds(1))

            SqliteUnitOfWork(state, journal).inTransaction { scope ->
                scope.recovery.close(requested.operationId, observed.generation, TEST_NOW.plusSeconds(2))
            }

            val closed = requireNotNull(journal.find(requested.operationId))
            assertEquals(RecoveryPhase.CLOSED, closed.phase)
            assertTrue(journal.listPending().isEmpty())

            val advanceFailure =
                runCatching {
                    journal.advance(requested.operationId, closed.generation, RecoveryPhase.PUBLISH_OBSERVED, at = TEST_NOW.plusSeconds(3))
                }.exceptionOrNull()
            val diagnoseFailure =
                runCatching {
                    journal.diagnose(
                        requested.operationId,
                        closed.generation,
                        RecoveryDiagnosticCode.STORAGE_FAILURE,
                        at = TEST_NOW.plusSeconds(3),
                    )
                }.exceptionOrNull()
            val missingFailure =
                runCatching {
                    journal.advance("not-in-the-journal", 0L, RecoveryPhase.PUBLISH_OBSERVED, at = TEST_NOW.plusSeconds(3))
                }.exceptionOrNull()

            listOf(advanceFailure, diagnoseFailure, missingFailure).forEach { failure ->
                assertTrue(failure is VfsException)
                assertEquals(VfsErrorCode.STATE_ERROR, (failure as VfsException).code)
            }
            assertEquals(closed, journal.find(requested.operationId))
            assertTrue(journal.listPending().isEmpty())
        }

    @Test
    fun `transaction close rolls back with node and event but succeeds on retry`() =
        withTempState { state ->
            val journal = SqliteRecoveryJournal(state)
            val requested = journal.begin(requestedOperation())
            val observed = journal.advance(requested.operationId, 0L, RecoveryPhase.STORAGE_REPLACE_OBSERVED, at = TEST_NOW.plusSeconds(1))
            val unitOfWork = SqliteUnitOfWork(state, journal)
            val record = testRecord("/notes/a.txt")

            val staleCloseFailure =
                runCatching {
                    unitOfWork.inTransaction { scope ->
                        scope.nodes.register(record)
                        scope.events.append(testEvent(record.id, "alcyone://resources/notes/a.txt"))
                        // observed 已经是 generation 1；用 0 关闭时 CAS 失败，Node / Event / CLOSED 必须一起回滚。
                        scope.recovery.close(requested.operationId, requested.generation, TEST_NOW.plusSeconds(2))
                    }
                }.exceptionOrNull()

            assertTrue(staleCloseFailure is VfsException)
            assertEquals(VfsErrorCode.STATE_ERROR, (staleCloseFailure as VfsException).code)
            assertNull(SqliteNodeRepository(state).findByPath(record.path))
            assertEquals(0L, state.eventCount())
            assertEquals(observed, journal.find(requested.operationId))
            assertEquals(listOf(observed), journal.listPending())

            val failure =
                runCatching {
                    unitOfWork.inTransaction { scope ->
                        scope.nodes.register(record)
                        scope.events.append(testEvent(record.id, "alcyone://resources/notes/a.txt"))
                        scope.recovery.close(requested.operationId, observed.generation, TEST_NOW.plusSeconds(2))
                        error("rollback after close")
                    }
                }.exceptionOrNull()

            assertEquals("rollback after close", failure?.message)
            assertNull(SqliteNodeRepository(state).findByPath(record.path))
            assertEquals(0L, state.eventCount())
            assertEquals(observed, journal.find(requested.operationId))
            assertEquals(listOf(observed), journal.listPending())

            unitOfWork.inTransaction { scope ->
                scope.nodes.register(record)
                scope.events.append(testEvent(record.id, "alcyone://resources/notes/a.txt"))
                scope.recovery.close(requested.operationId, observed.generation, TEST_NOW.plusSeconds(3))
            }

            val closed = requireNotNull(journal.find(requested.operationId))
            assertEquals(RecoveryPhase.CLOSED, closed.phase)
            assertEquals(observed.generation + 1, closed.generation)
            assertTrue(journal.listPending().isEmpty())
            assertEquals(record, SqliteNodeRepository(state).findByPath(record.path))
            assertEquals(1L, state.eventCount())
        }

    @Test
    fun `a real v2 database migrates to v3 without changing nodes metadata events or mounts`() =
        runBlocking {
            withTempStateFile { file ->
                val record = testRecord("/notes/preserved.txt")
                val metadata = NodeMetadata(setOf("ct"), "preserved through migration")
                VfsStateDatabase.file(file).use { state ->
                    SqliteNodeRepository(state).register(record)
                    SqliteMetadataRepository(state).put(record.id, metadata)
                    SqliteEventRepository(state).append(
                        testEvent(record.id, "alcyone://resources${record.path}", operationId = "before-v3"),
                    )
                    state.insertMount("/resources", "main")
                }
                downgradeToVersion2(file)

                VfsStateDatabase.file(file).use { state ->
                    assertEquals(4L, state.driver.schemaVersion())
                    assertEquals(record, SqliteNodeRepository(state).findByPath(record.path))
                    assertEquals(metadata, SqliteMetadataRepository(state).get(record.id))
                    assertEquals(1L, state.eventCount())
                    assertEquals("before-v3", state.eventRows().single().operationId)
                    assertEquals(
                        1L,
                        scalar(file, "SELECT count(*) FROM mount WHERE vfs_path = '/resources' AND storage_key = 'main'"),
                    )
                    assertEquals(
                        1L,
                        scalar(file, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'recovery_operation'"),
                    )
                    assertEquals(
                        1L,
                        scalar(file, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'recovery_operation_pending'"),
                    )
                }
            }
        }

    @Test
    fun `a real v1 database migrates through v2 to v3 without losing existing state`() =
        runBlocking {
            withTempStateFile { file ->
                val record = testRecord("/notes/from-v1.txt")
                rawConnection(file).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE TABLE mount (vfs_path TEXT NOT NULL PRIMARY KEY, storage_key TEXT NOT NULL)")
                        statement.execute(
                            "CREATE TABLE node (id TEXT NOT NULL PRIMARY KEY, vfs_path TEXT NOT NULL, " +
                                "node_type TEXT NOT NULL, physical INTEGER NOT NULL CHECK (physical IN (0, 1)), " +
                                "registered_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)",
                        )
                        statement.execute("CREATE UNIQUE INDEX node_active_path ON node(vfs_path) WHERE deleted_at IS NULL")
                        statement.execute("CREATE TABLE metadata (node_id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
                        statement.execute(
                            "CREATE TABLE event (event_id TEXT NOT NULL PRIMARY KEY, event_type TEXT NOT NULL, " +
                                "node_id TEXT, occurred_at INTEGER NOT NULL, uri TEXT NOT NULL, operation_id TEXT, " +
                                "source_uri TEXT, target_uri TEXT)",
                        )
                        statement.execute("INSERT INTO mount VALUES ('/resources', 'main')")
                        statement.execute(
                            "INSERT INTO node VALUES ('${record.id}', '${record.path}', 'FILE', 1, 1000, 2000, NULL)",
                        )
                        statement.execute("INSERT INTO metadata VALUES ('${record.id}', '{\"tags\":[\"legacy\"]}')")
                        statement.execute(
                            "INSERT INTO event VALUES ('legacy-event', 'FILE_CREATED', '${record.id}', 2000, " +
                                "'alcyone://resources/notes/from-v1.txt', 'legacy-operation', NULL, NULL)",
                        )
                        statement.execute("PRAGMA user_version = 1")
                    }
                }

                VfsStateDatabase.file(file).use { state ->
                    assertEquals(4L, state.driver.schemaVersion())
                    val preserved = SqliteNodeRepository(state).findByPath(record.path)
                    assertEquals(record.id, preserved?.id)
                    assertEquals(record.path, preserved?.path)
                    assertEquals(record.type, preserved?.type)
                    assertEquals(record.physical, preserved?.physical)
                    assertEquals(1_000L, preserved?.registeredAt?.toEpochMilli())
                    assertEquals(2_000L, preserved?.updatedAt?.toEpochMilli())
                    assertEquals(
                        "{\"tags\":[\"legacy\"]}",
                        rawConnection(file).use { connection ->
                            connection.prepareStatement("SELECT payload FROM metadata WHERE node_id = ?").use { query ->
                                query.setString(1, record.id.toString())
                                query.executeQuery().use { rows ->
                                    check(rows.next())
                                    rows.getString(1)
                                }
                            }
                        },
                    )
                    assertEquals("legacy-operation", state.eventRows().single().operationId)
                    assertEquals(
                        1L,
                        scalar(file, "SELECT count(*) FROM mount WHERE backend_type = '' AND physical_root = ''"),
                    )
                    assertEquals(
                        1L,
                        scalar(file, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'recovery_operation'"),
                    )
                    assertEquals(
                        1L,
                        scalar(file, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'recovery_operation_pending'"),
                    )
                }
            }
        }

    @Test
    fun `a failed v2 to v3 migration is atomic and leaves the complete v2 database readable`() =
        runBlocking {
            withTempStateFile { file ->
                val record = testRecord("/notes/before-failed-migration.txt")
                VfsStateDatabase.file(file).use { state ->
                    SqliteNodeRepository(state).register(record)
                    SqliteMetadataRepository(state).put(record.id, NodeMetadata(description = "still here"))
                    SqliteEventRepository(state).append(testEvent(record.id, "alcyone://resources${record.path}"))
                }
                downgradeToVersion2(file)
                // 2.sqm 的第二条 DDL 与这个名字冲突：真实 SQLite 在迁移中途拒绝，验证事务会退回完整 v2。
                rawConnection(file).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE INDEX recovery_operation_pending ON node(vfs_path)")
                    }
                }

                val failure = runCatching { VfsStateDatabase.file(file).close() }.exceptionOrNull()
                assertTrue(failure is VfsException)
                assertEquals(VfsErrorCode.STATE_ERROR, (failure as VfsException).code)

                assertEquals(2L, scalar(file, "PRAGMA user_version"))
                assertEquals(
                    0L,
                    scalar(file, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'recovery_operation'"),
                )
                assertEquals(
                    1L,
                    scalar(file, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'recovery_operation_pending'"),
                )
                assertEquals(
                    1L,
                    scalar(file, "SELECT count(*) FROM node WHERE vfs_path = '/notes/before-failed-migration.txt' AND deleted_at IS NULL"),
                )
                assertEquals(1L, scalar(file, "SELECT count(*) FROM metadata"))
                assertEquals(1L, scalar(file, "SELECT count(*) FROM event"))
            }
        }

    private fun requestedOperation(): RecoveryOperation =
        RecoveryOperation.begin(
            operationId = "recovery-test-operation",
            kind = RecoveryOperationKind.SAFE_REPLACE,
            sourceUri = null,
            targetUri = VfsUri.parse("alcyone://resources/notes/a.txt"),
            sourceStorageKey = null,
            targetStorageKey = "/resources",
            nodeId = null,
            at = TEST_NOW,
        )

    private fun <T> withTempState(block: suspend (VfsStateDatabase) -> T): T =
        withTempStateFile { file ->
            VfsStateDatabase.file(file).use { state -> block(state) }
        }

    private fun <T> withTempStateFile(block: suspend (Path) -> T): T {
        val directory = Files.createTempDirectory("alcyone-recovery-journal")
        val file: Path = directory.resolve("state.db")
        return try {
            runBlocking { block(file) }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(Path.of("$file-shm"))
            Files.deleteIfExists(Path.of("$file-wal"))
            Files.deleteIfExists(directory)
        }
    }

    private fun downgradeToVersion2(file: Path) {
        rawConnection(file).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("DROP INDEX recovery_operation_pending")
                statement.execute("DROP TABLE recovery_operation")
                statement.execute("DROP TABLE consumer_cursor")
                statement.execute("DROP INDEX event_occurred_at")
                statement.execute("ALTER TABLE event RENAME TO event_v4")
                statement.execute(
                    "CREATE TABLE event (event_id TEXT NOT NULL PRIMARY KEY, event_type TEXT NOT NULL, node_id TEXT, " +
                        "occurred_at INTEGER NOT NULL, uri TEXT NOT NULL, operation_id TEXT, source_uri TEXT, target_uri TEXT)",
                )
                statement.execute(
                    "INSERT INTO event SELECT event_id, event_type, node_id, occurred_at, uri, operation_id, source_uri, target_uri FROM event_v4",
                )
                statement.execute("DROP TABLE event_v4")
                statement.execute("PRAGMA user_version = 2")
            }
            connection.commit()
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
}
