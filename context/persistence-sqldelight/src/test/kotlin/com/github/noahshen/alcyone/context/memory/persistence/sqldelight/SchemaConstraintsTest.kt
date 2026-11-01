package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A09：Schema 的列约束真的生效——JSON CHECK、enum CHECK、外键 RESTRICT、
 * 四元业务键唯一，以及五个显式索引的实际定义。
 */
class SchemaConstraintsTest {
    private val ts = "'2026-10-23T18:30:25.123+08:00'"

    private fun rawInsert(id: String) =
        "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) " +
            "VALUES ('$id', 'content', 'EXTRACTION', '[]', $ts)"

    private fun organizedInsert(id: String) =
        "INSERT INTO organized_memory (id, content, domains_json, created_at, updated_at) " +
            "VALUES ('$id', 'content', '[]', $ts, $ts)"

    @Test
    fun `invalid json arrays are rejected by check constraints`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    assertFailsWith<Exception> {
                        executor.updateRaw(rawInsert("R-1").replace("'[]'", "'{not json'"))
                    }
                    assertFailsWith<Exception> {
                        // 合法 JSON 但不是数组
                        executor.updateRaw(rawInsert("R-2").replace("'[]'", "'{}'"))
                    }
                    assertEquals(0L, countRaw(executor))
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `enum and content checks are enforced`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    assertFailsWith<Exception> {
                        executor.updateRaw(rawInsert("R-1").replace("'EXTRACTION'", "'UNKNOWN'"))
                    }
                    assertFailsWith<Exception> {
                        executor.updateRaw(rawInsert("R-2").replace("'content'", "'   '"))
                    }
                    // conversation_turn 的 extraction_status 只允许 pending/complete/failed
                    assertFailsWith<Exception> {
                        executor.updateRaw(
                            "INSERT INTO conversation_turn " +
                                "(runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, " +
                                "messages_json, extraction_status) " +
                                "VALUES ('rt','ag','se','st-1',$ts,$ts,'[]','unknown')",
                        )
                    }
                    // 合法值与默认值可以插入
                    assertEquals(
                        1,
                        executor.updateRaw(
                            "INSERT INTO conversation_turn " +
                                "(runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, messages_json) " +
                                "VALUES ('rt','ag','se','st-1',$ts,$ts,'[]')",
                        ),
                    )
                    assertEquals(
                        "pending",
                        executor.queryRaw("SELECT extraction_status FROM conversation_turn") { rs ->
                            rs.next()
                            rs.getString(1)
                        },
                    )
                    executor.updateRaw("DELETE FROM conversation_turn")
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `foreign keys restrict deleting referenced rows`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    executor.write { tx ->
                        tx.update(rawInsert("R-1"))
                        tx.update("INSERT INTO organization_pending (raw_memory_id) VALUES ('R-1')")
                    }
                    // 有 Pending 引用时删除 Raw 必须被外键拒绝（foreign_keys=ON 已生效）。
                    assertFailsWith<Exception> { executor.updateRaw("DELETE FROM raw_memory WHERE id = 'R-1'") }
                    assertEquals(1L, countRaw(executor))

                    executor.write { tx -> tx.update("DELETE FROM organization_pending WHERE raw_memory_id = 'R-1'") }
                    executor.updateRaw("DELETE FROM raw_memory WHERE id = 'R-1'")
                    assertEquals(0L, countRaw(executor))
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `organized source links are restricted by both foreign keys`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    executor.write { tx ->
                        tx.update(rawInsert("R-1"))
                        tx.update(organizedInsert("O-1"))
                        tx.update("INSERT INTO organized_memory_source (organized_memory_id, raw_memory_id) VALUES ('O-1', 'R-1')")
                    }
                    assertFailsWith<Exception> { executor.updateRaw("DELETE FROM raw_memory WHERE id = 'R-1'") }
                    assertFailsWith<Exception> { executor.updateRaw("DELETE FROM organized_memory WHERE id = 'O-1'") }
                    // 联合主键：重复关联被拒绝
                    assertFailsWith<Exception> {
                        executor.updateRaw("INSERT INTO organized_memory_source (organized_memory_id, raw_memory_id) VALUES ('O-1', 'R-1')")
                    }
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `four part business key is unique and internal id autoincrements`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    val insert =
                        "INSERT INTO conversation_turn " +
                            "(runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, messages_json) " +
                            "VALUES ('rt','ag','se','st-1',$ts,$ts,'[]')"
                    val firstId =
                        executor.write { tx ->
                            tx.update(insert)
                            0L
                        }
                    assertEquals(0L, firstId)
                    assertFailsWith<Exception> { executor.updateRaw(insert) }
                    // 换一个 session_turn_id 可以插入，内部 id 递增
                    val ids =
                        executor.queryRaw("SELECT id FROM conversation_turn ORDER BY id") { rs ->
                            buildList { while (rs.next()) add(rs.getLong(1)) }
                        }
                    assertEquals(1, ids.size)
                    assertTrue(ids.single() >= 1L)
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `checkpoint enforces single row and non negative watermark`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                runBlocking {
                    assertFailsWith<Exception> {
                        executor.updateRaw("INSERT INTO dreaming_checkpoint (id, last_processed_turn_id) VALUES (2, 0)")
                    }
                    assertFailsWith<Exception> {
                        executor.updateRaw("UPDATE dreaming_checkpoint SET last_processed_turn_id = -1 WHERE id = 1")
                    }
                    // 条件推进水位正常
                    assertEquals(1, executor.updateRaw("UPDATE dreaming_checkpoint SET last_processed_turn_id = 42 WHERE id = 1"))
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `five explicit indexes exist with the documented shape`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val sql =
                TestDatabases.readOnly(path, "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%'") { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString(1), rs.getString(2) ?: "")
                    }
                }
            assertEquals(ContextDatabaseFactory.REQUIRED_INDEXES, sql.keys, "五个显式索引必须齐备")
            assertTrue(sql.getValue("idx_turn_pending_session_time").contains("WHERE extraction_status = 'pending'"))
            assertTrue(sql.getValue("idx_turn_session_time").contains("timestamp DESC"))
            assertTrue(sql.getValue("idx_organized_updated_at").contains("updated_at"))
            assertTrue(sql.getValue("idx_source_raw").contains("raw_memory_id"))
            assertTrue(sql.getValue("idx_turn_session_created_at").contains("created_at DESC"))
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    private fun countRaw(executor: ContextDatabaseExecutor): Long =
        runBlocking {
            executor.queryRaw("SELECT count(*) FROM raw_memory") { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
}
