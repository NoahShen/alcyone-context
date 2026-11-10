package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import app.cash.sqldelight.db.SqlPreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CT05-B1 核心查询的 EXPLAIN QUERY PLAN 证据。
 *
 * 只断言「使用了期望的既有索引」，不硬编码完整计划文本（SQLite 版本差异会让文本变化）；
 * 不新增索引，也不为测试改 Schema。
 */
class ContextQueryPlanTest {
    @Test
    fun `anchored session window uses idx_turn_session_time`() =
        ConversationFixtures.withDatabase { executor ->
            val plan =
                explain(executor, ContextSqlQueries.SELECT_SESSION_WINDOW) { bind ->
                    bind.bindString(0, "runtime-1")
                    bind.bindString(1, "agent-1")
                    bind.bindString(2, "session-1")
                    bind.bindString(3, "2026-10-28T08:30:00.123+08:00")
                    bind.bindString(4, "2026-10-28T08:30:00.123+08:00")
                    bind.bindLong(5, 42L)
                    bind.bindLong(6, 10L)
                }
            assertTrue(plan.contains("idx_turn_session_time"), "会话窗口必须走 idx_turn_session_time，实际计划:\n$plan")
        }

    @Test
    fun `latest session window uses idx_turn_session_time`() =
        ConversationFixtures.withDatabase { executor ->
            val plan =
                explain(executor, ContextSqlQueries.SELECT_SESSION_WINDOW_LATEST) { bind ->
                    bind.bindString(0, "runtime-1")
                    bind.bindString(1, "agent-1")
                    bind.bindString(2, "session-1")
                    bind.bindLong(3, 10L)
                }
            assertTrue(plan.contains("idx_turn_session_time"), "无锚点会话窗口必须走 idx_turn_session_time，实际计划:\n$plan")
        }

    @Test
    fun `forward session window uses idx_turn_session_time and seven bound parameters`() =
        ConversationFixtures.withDatabase { executor ->
            assertEquals(7, ContextSqlQueries.SELECT_SESSION_WINDOW_AFTER.count { char -> char == '?' })
            val plan =
                explain(executor, ContextSqlQueries.SELECT_SESSION_WINDOW_AFTER) { bind ->
                    bind.bindString(0, "runtime-1")
                    bind.bindString(1, "agent-1")
                    bind.bindString(2, "session-1")
                    bind.bindString(3, "2026-10-28T08:30:00.123+08:00")
                    bind.bindString(4, "2026-10-28T08:30:00.123+08:00")
                    bind.bindLong(5, 42L)
                    bind.bindLong(6, 10L)
                }
            assertTrue(plan.contains("idx_turn_session_time"), "前向会话窗口必须走 idx_turn_session_time，实际计划:\n$plan")
        }

    @Test
    fun `latest pending uses idx_turn_pending_session_time`() =
        ConversationFixtures.withDatabase { executor ->
            val plan =
                explain(executor, ContextSqlQueries.SELECT_LATEST_PENDING) { bind ->
                    bind.bindString(0, "runtime-1")
                    bind.bindString(1, "agent-1")
                    bind.bindString(2, "session-1")
                }
            assertTrue(plan.contains("idx_turn_pending_session_time"), "latestPending 必须走 pending 索引，实际计划:\n$plan")
        }

    @Test
    fun `count pending uses idx_turn_pending_session_time`() =
        ConversationFixtures.withDatabase { executor ->
            val plan =
                explain(executor, ContextSqlQueries.COUNT_PENDING) { bind ->
                    bind.bindString(0, "runtime-1")
                    bind.bindString(1, "agent-1")
                    bind.bindString(2, "session-1")
                }
            assertTrue(plan.contains("idx_turn_pending_session_time"), "countPending 必须走 pending 索引，实际计划:\n$plan")
        }

    @Test
    fun `max updatedAt uses idx_organized_updated_at`() =
        ConversationFixtures.withDatabase { executor ->
            val plan = explain(executor, ContextSqlQueries.SELECT_MAX_UPDATED_AT, {})
            assertTrue(plan.contains("idx_organized_updated_at"), "全局水位必须走 idx_organized_updated_at，实际计划:\n$plan")
        }

    @Test
    fun `organized by raw uses idx_source_raw`() =
        ConversationFixtures.withDatabase { executor ->
            val plan =
                explain(executor, ContextSqlQueries.SELECT_ORGANIZED_BY_RAW) { bind ->
                    bind.bindString(0, "raw-1")
                }
            assertTrue(plan.contains("idx_source_raw"), "来源反查必须走 idx_source_raw，实际计划:\n$plan")
        }

    @Test
    fun `sources by organized uses the primary key index`() =
        ConversationFixtures.withDatabase { executor ->
            val plan =
                explain(executor, ContextSqlQueries.SELECT_SOURCES_BY_ORGANIZED) { bind ->
                    bind.bindString(0, "org-1")
                }
            assertTrue(
                plan.contains("sqlite_autoindex_organized_memory_source_1"),
                "来源正查必须走 organized_memory_source 主键索引，实际计划:\n$plan",
            )
        }

    private suspend fun explain(
        executor: ContextDatabaseExecutor,
        sql: String,
        bind: (SqlPreparedStatement) -> Unit,
    ): String =
        executor.read { scope ->
            val lines = mutableListOf<String>()
            scope.queryPrepared("EXPLAIN QUERY PLAN $sql", bind) { cursor ->
                while (cursor.next().value) lines += cursor.getString(3).orEmpty()
            }
            lines.joinToString("\n")
        }
}
