package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** A02：普通打开不自动建库，缺库/0 字节/版本不符/损坏均拒绝，错版字节不变。 */
class ContextDatabaseOpenProtectionTest {
    @Test
    fun `open rejects missing database without creating any file`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.databaseIn(directory)
            assertFalse(Files.exists(path))

            assertFailsWith<ContextPersistenceException> { ContextDatabaseFactory.open(path) }

            assertFalse(Files.exists(path), "缺库打开不得自动创建任何文件")
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `open rejects zero-byte database`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.databaseIn(directory)
            Files.createFile(path)

            val failure = assertFailsWith<ContextPersistenceException> { ContextDatabaseFactory.open(path) }
            assertEquals(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, failure.errorCode)

            assertEquals(0L, Files.size(path), "0 字节不得被当作库打开")
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `open rejects schema version 0`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            TestDatabases.setUserVersion(path, 0)
            val before = TestDatabases.sha256(path)

            val failure = assertFailsWith<ContextPersistenceException> { ContextDatabaseFactory.open(path) }
            assertEquals(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, failure.errorCode)

            assertEquals(before, TestDatabases.sha256(path), "版本不符时库文件字节必须不变")
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `open rejects schema version 2 (newer than program)`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            TestDatabases.setUserVersion(path, ContextDatabaseInitializer.SCHEMA_VERSION + 1)
            val before = TestDatabases.sha256(path)

            val failure = assertFailsWith<ContextPersistenceException> { ContextDatabaseFactory.open(path) }
            assertEquals(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, failure.errorCode)

            assertEquals(before, TestDatabases.sha256(path), "高版本库不得被覆盖或自动迁移")
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `open rejects corrupt file without touching its bytes`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.databaseIn(directory)
            Files.write(path, "this is definitely not a sqlite database".toByteArray())
            val before = TestDatabases.sha256(path)

            val failure = assertFailsWith<ContextPersistenceException> { ContextDatabaseFactory.open(path) }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)

            assertEquals(before, TestDatabases.sha256(path), "损坏文件字节必须不变")
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `open succeeds on a properly initialized database`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)

            val executor = ContextDatabaseFactory.open(path)
            try {
                assertEquals(2, executor.readSlotCount())
                assertEquals("wal", executor.pragma(SlotKind.WRITE, 0, "journal_mode"))
                assertEquals("1", executor.pragma(SlotKind.WRITE, 0, "foreign_keys"))
            } finally {
                executor.close()
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
