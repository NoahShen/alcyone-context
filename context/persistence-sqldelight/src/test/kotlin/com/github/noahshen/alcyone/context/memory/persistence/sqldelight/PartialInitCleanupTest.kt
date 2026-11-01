package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A07：部分初始化失败时，已获取的 Slot / 连接逆序释放，不泄漏连接与线程。
 *
 * 手段：注入第 N 个读 Slot 打开失败，断言先前打开的 Slot 全部已关闭，
 * 且没有任何 `context-db-*` 线程残留（连接泄漏会连带线程泄漏）。
 */
class PartialInitCleanupTest {
    @Test
    fun `failure while opening the third read slot releases the first two`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val opened = AtomicInteger(0)
            val closed = AtomicInteger(0)

            val failure =
                assertFailsWith<IllegalStateException> {
                    ContextDatabaseExecutor(
                        path,
                        readSlotCount = 3,
                        readSlotOpener = { index ->
                            if (index == 2) throw IllegalStateException("injected failure opening read slot 2")
                            opened.incrementAndGet()
                            TrackingSlot(SlotKind.READ, index, path, closed)
                        },
                    )
                }

            assertEquals("injected failure opening read slot 2", failure.message)
            assertEquals(2, opened.get(), "前两个读 Slot 必须已打开")
            assertEquals(2, closed.get(), "已打开的读 Slot 必须逆序关闭，不能泄漏")

            val lingering = contextDbThreads()
            assertTrue(lingering.isEmpty(), "不得残留 context-db-* 线程，实际: $lingering")
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `failure on the first read slot still releases the write slot`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            assertFailsWith<IllegalStateException> {
                ContextDatabaseExecutor(
                    path,
                    readSlotCount = 1,
                    readSlotOpener = { throw IllegalStateException("injected failure opening read slot 0") },
                )
            }
            // 写 Slot 在构造期间已打开，失败后必须被关闭：再次打开不会因文件锁/句柄冲突失败。
            ContextDatabaseFactory.open(path).use { executor ->
                assertEquals("wal", executor.pragma(SlotKind.WRITE, 0, "journal_mode"))
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `successful open leaves no partially initialized state`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            assertTrue(Files.exists(path))
            val before = TestDatabases.userVersion(path)
            ContextDatabaseFactory.open(path, readSlotCount = 3).use { executor ->
                assertEquals(3, executor.readSlotCount())
            }
            assertEquals(before, TestDatabases.userVersion(path), "正常打开不得修改版本")
            val lingering = contextDbThreads()
            assertTrue(lingering.isEmpty(), "关闭后不得残留线程，实际: $lingering")
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    private fun contextDbThreads(): List<String> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive && it.name.startsWith("context-db-") }
            .map { it.name }

    /** 记账用 Slot：记录被关闭的次数，其余行为沿用真实 Slot。 */
    private class TrackingSlot(
        kind: SlotKind,
        index: Int,
        path: java.nio.file.Path,
        private val closedCount: AtomicInteger,
    ) : ContextSlot(kind, index, path, READ_PRAGMAS) {
        override fun close() {
            super.close()
            closedCount.incrementAndGet()
        }
    }
}
