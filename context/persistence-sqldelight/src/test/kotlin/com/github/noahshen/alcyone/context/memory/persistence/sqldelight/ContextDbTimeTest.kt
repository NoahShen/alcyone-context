package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A08：时间列使用 `common` 公共能力 `TimeCodec`，
 * 库里存的是固定 `+08:00` 毫秒精度原文，不是 `Z`。
 */
class ContextDbTimeTest {
    @Test
    fun `encode produces fixed +08 offset millisecond text`() {
        val instant = Instant.parse("2026-10-23T10:30:25.123Z")
        assertEquals("2026-10-23T18:30:25.123+08:00", ContextDbTime.encode(instant))
    }

    @Test
    fun `decode round trips the absolute instant`() {
        val original = Instant.parse("2026-10-23T18:30:25.123+08:00")
        val text = ContextDbTime.encode(original)
        assertEquals(original, ContextDbTime.decode(text))
    }

    @Test
    fun `non canonical text is rejected`() {
        assertFailsWith<IllegalArgumentException> { ContextDbTime.decode("2026-10-23T18:30:25.123Z") }
        assertFailsWith<IllegalArgumentException> { ContextDbTime.decode("2026-10-23T18:30:25.1+08:00") }
    }

    @Test
    fun `time written through the slot lands in the database as +08 text`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            ContextDatabaseFactory.open(path).use { executor ->
                kotlinx.coroutines.runBlocking {
                    val timestamp = Instant.parse("2026-10-23T10:30:25.123Z")
                    executor.write { tx ->
                        tx.update(
                            "INSERT INTO conversation_turn " +
                                "(runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, messages_json) " +
                                "VALUES ('rt', 'ag', 'se', 'st-1', '${ContextDbTime.encode(timestamp)}', " +
                                "'${ContextDbTime.encode(timestamp)}', '[]')",
                        )
                    }
                    val stored =
                        executor.queryRaw("SELECT timestamp FROM conversation_turn WHERE session_turn_id = 'st-1'") { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    assertEquals("2026-10-23T18:30:25.123+08:00", stored, "库内原文必须是固定 +08 毫秒文本")
                    assertTrue(!stored.endsWith("Z"), "不得写入 UTC 的 Z 形式")
                    assertEquals(timestamp, ContextDbTime.decode(stored))
                }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}
