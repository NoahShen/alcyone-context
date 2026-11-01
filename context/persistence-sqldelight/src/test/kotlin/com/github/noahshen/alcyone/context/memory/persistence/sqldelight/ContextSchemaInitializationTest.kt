package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A01：显式初始化建出六表五索引、初始 Checkpoint 与 `user_version = 1`，并且绝不覆盖既有文件。 */
class ContextSchemaInitializationTest {
    @Test
    fun `initialize creates six tables five indexes checkpoint row and user version`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)

            assertTrue(Files.exists(path), "初始化后文件必须存在")
            assertTrue(Files.size(path) > 0, "初始化后文件不能是 0 字节")

            val objects =
                TestDatabases.readOnly(path, "SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1) to rs.getString(2))
                    }
                }
            val tables = objects.filter { it.first == "table" }.map { it.second }.toSet()
            val indexes = objects.filter { it.first == "index" }.map { it.second }.toSet()

            assertEquals(ContextDatabaseFactory.REQUIRED_TABLES, tables, "六张业务表必须齐备")
            assertEquals(ContextDatabaseFactory.REQUIRED_INDEXES, indexes, "五个显式索引必须齐备")

            val checkpoint =
                TestDatabases.readOnly(path, "SELECT id, last_processed_turn_id FROM dreaming_checkpoint") { rs ->
                    assertTrue(rs.next(), "必须存在 Checkpoint 初始行")
                    rs.getLong(1) to rs.getLong(2)
                }
            assertEquals(1L to 0L, checkpoint, "Checkpoint 必须是唯一一行 (1, 0)")

            assertEquals(
                ContextDatabaseInitializer.SCHEMA_VERSION,
                TestDatabases.userVersion(path),
                "user_version 必须与 Schema 版本一致",
            )
            assertEquals(
                "wal",
                TestDatabases.readOnly(path, "PRAGMA journal_mode") { rs ->
                    rs.next()
                    rs.getString(1)
                },
            )
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `initialize refuses an existing file and leaves its bytes untouched`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.databaseIn(directory)
            val original = "pre-existing-content-must-not-be-overwritten".toByteArray()
            Files.write(path, original)
            val before = TestDatabases.sha256(path)

            val failure = assertFailsWith<ContextPersistenceException> { ContextDatabaseInitializer.initialize(path) }

            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
            assertEquals(before, TestDatabases.sha256(path), "拒绝覆盖时文件字节必须完全不变")
            assertTrue(Files.readAllBytes(path).contentEquals(original))
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `initialize refuses an existing empty file instead of reusing it`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.databaseIn(directory)
            Files.createFile(path)
            val before = TestDatabases.sha256(path)

            val failure = assertFailsWith<ContextPersistenceException> { ContextDatabaseInitializer.initialize(path) }

            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
            assertEquals(0L, Files.size(path), "0 字节文件不能被偷偷初始化")
            assertEquals(before, TestDatabases.sha256(path))
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `schema version constant matches generated schema`() {
        assertEquals(1L, ContextDatabaseInitializer.SCHEMA_VERSION)
        assertEquals(ContextDatabaseSqlite.Schema.version, ContextDatabaseInitializer.SCHEMA_VERSION)
    }

    @Test
    fun `initialize creates missing parent directories`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val nested = directory.resolve("a").resolve("b").resolve("context.db")
            ContextDatabaseInitializer.initialize(nested)
            assertTrue(Files.exists(nested))
            assertEquals(1L, TestDatabases.userVersion(nested))
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
