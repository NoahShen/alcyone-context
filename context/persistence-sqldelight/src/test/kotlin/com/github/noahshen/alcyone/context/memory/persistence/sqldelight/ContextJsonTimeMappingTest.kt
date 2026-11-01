package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT05-B1 JSON / 时间落库映射：顺序、转义、中文、`+08:00` 毫秒文本往返。
 *
 * 关键原则：**落库内容用独立只读连接核对**，不拿 Repository 返回值代替落库断言。
 */
class ContextJsonTimeMappingTest {
    private val session = ConversationFixtures.SESSION

    @Test
    fun `messages_json keeps order escaping and Chinese text and time columns use plus eight milliseconds`() {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val messages =
                listOf(
                    ChatMessage(ChatRole.USER, "第一行\n第二行"),
                    ChatMessage(ChatRole.ASSISTANT, "quote \" and backslash \\ and tab \t"),
                    ChatMessage(ChatRole.TOOL, "中文与 emoji 🚀"),
                )
            val id =
                runBlocking {
                    ContextDatabaseFactory.open(path).use { executor ->
                        val created =
                            executor.write { tx ->
                                ConversationTxOpsImpl(tx).insertTurn(
                                    session,
                                    "turn-1",
                                    Instant.parse("2026-10-28T00:30:00.123Z"),
                                    Instant.parse("2026-10-28T00:31:00.456Z"),
                                    messages,
                                )
                            }
                        val stored = assertNotNull(ConversationRepositoryImpl(executor).getById(created.id))
                        assertEquals(messages, stored.messages, "读取路径必须按原顺序还原")
                        assertEquals(Instant.parse("2026-10-28T00:30:00.123Z"), stored.timestamp)
                        assertEquals(Instant.parse("2026-10-28T00:31:00.456Z"), stored.createdAt)
                        created.id
                    }
                }

            // 执行器关闭后，用独立只读连接核对磁盘上的真实文本。
            val raw =
                TestDatabases.readOnly(
                    path,
                    "SELECT messages_json, timestamp, created_at, extraction_status FROM conversation_turn WHERE id = $id",
                ) { rs ->
                    assertTrue(rs.next())
                    listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                }

            val json = raw[0]
            assertTrue(json.startsWith("[") && json.endsWith("]"), "messages_json 必须是 JSON 数组: $json")
            assertTrue(json.contains("\\n"), "换行必须被转义: $json")
            assertTrue(json.contains("\\\""), "双引号必须被转义: $json")
            assertTrue(json.contains("\\\\"), "反斜杠必须被转义: $json")
            assertTrue(json.contains("第一行") && json.contains("🚀"), "中文与 emoji 必须原样保留: $json")

            val parsed = Json.parseToJsonElement(json).jsonArray
            assertEquals(messages.size, parsed.size)
            messages.forEachIndexed { index, message ->
                val element = parsed[index].jsonObject
                assertEquals(message.role.name, element["role"]?.jsonPrimitive?.content)
                assertEquals(message.content, element["content"]?.jsonPrimitive?.content)
            }

            assertEquals("2026-10-28T08:30:00.123+08:00", raw[1], "timestamp 必须是固定 +08:00 毫秒文本")
            assertEquals("2026-10-28T08:31:00.456+08:00", raw[2], "created_at 必须是固定 +08:00 毫秒文本")
            assertEquals("pending", raw[3])
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }

    @Test
    fun `messages_json survives an independent raw update round-trip`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = ConversationRepositoryImpl(executor)
            val created = ConversationFixtures.insert(executor, "turn-1", messages = ConversationFixtures.messages("原始", "原文"))
            assertEquals(
                1,
                executor.updateRaw(
                    "UPDATE conversation_turn SET messages_json = " +
                        "'[{\"role\":\"ASSISTANT\",\"content\":\"raw \\u4e2d\\u6587\"}]' WHERE id = ${created.id}",
                ),
                "注入必须真实生效，否则后续读取断言没有意义",
            )
            val stored = assertNotNull(repository.getById(created.id))
            assertEquals(listOf(ChatMessage(ChatRole.ASSISTANT, "raw 中文")), stored.messages)
        }

    @Test
    fun `stored messages with a missing field fail safely without leaking the payload`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertEquals(
                1,
                executor.updateRaw(
                    "UPDATE conversation_turn SET messages_json = '[{\"role\":\"USER\"}]' WHERE id = ${created.id}",
                ),
                "注入必须真实生效",
            )
            val failure =
                assertFailsWith<ContextPersistenceException> {
                    runBlocking { ConversationRepositoryImpl(executor).getById(created.id) }
                }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
            assertFalse(failure.message.orEmpty().contains("USER"), "安全映射不得回显库内原文")
            assertNull(failure.cause)
        }

    @Test
    fun `stored messages with an unknown role fail safely`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertEquals(
                1,
                executor.updateRaw(
                    "UPDATE conversation_turn SET messages_json = '[{\"role\":\"SYSTEM\",\"content\":\"x\"}]' WHERE id = ${created.id}",
                ),
                "注入必须真实生效",
            )
            val failure =
                assertFailsWith<ContextPersistenceException> {
                    runBlocking { ConversationRepositoryImpl(executor).getById(created.id) }
                }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        }

    @Test
    fun `stored empty message array fails safely`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertEquals(
                1,
                executor.updateRaw("UPDATE conversation_turn SET messages_json = '[]' WHERE id = ${created.id}"),
                "注入必须真实生效",
            )
            val failure =
                assertFailsWith<ContextPersistenceException> {
                    runBlocking { ConversationRepositoryImpl(executor).getById(created.id) }
                }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        }

    @Test
    fun `stored non-canonical timestamp fails safely`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertEquals(
                1,
                executor.updateRaw(
                    "UPDATE conversation_turn SET timestamp = '2026-10-28T00:30:00.123Z' WHERE id = ${created.id}",
                ),
                "注入必须真实生效",
            )
            val failure =
                assertFailsWith<ContextPersistenceException> {
                    runBlocking { ConversationRepositoryImpl(executor).getById(created.id) }
                }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
            assertNull(failure.cause)
        }

    // ------------------------------------------------------------ 严格 JSON 类型（B1 复核修复）

    @Test
    fun `non-string numeric content is rejected`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertRejectedWithoutLeak(
                executor,
                created.id,
                """[{"role":"USER","content":123,"note":"$RAW_MARKER"}]""",
            )
        }

    @Test
    fun `non-string boolean content is rejected`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertRejectedWithoutLeak(
                executor,
                created.id,
                """[{"role":"USER","content":true,"note":"$RAW_MARKER"}]""",
            )
        }

    @Test
    fun `null content is rejected`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertRejectedWithoutLeak(
                executor,
                created.id,
                """[{"role":"USER","content":null,"note":"$RAW_MARKER"}]""",
            )
        }

    @Test
    fun `object content is rejected`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertRejectedWithoutLeak(
                executor,
                created.id,
                """[{"role":"USER","content":{"text":"$RAW_MARKER"}}]""",
            )
        }

    @Test
    fun `array content is rejected`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertRejectedWithoutLeak(
                executor,
                created.id,
                """[{"role":"USER","content":["$RAW_MARKER"]}]""",
            )
        }

    @Test
    fun `non-string role values are rejected`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            listOf("123", "true", "null").forEach { roleLiteral ->
                assertRejectedWithoutLeak(
                    executor,
                    created.id,
                    """[{"role":$roleLiteral,"content":"$RAW_MARKER"}]""",
                )
            }
        }

    @Test
    fun `missing role is rejected`() =
        ConversationFixtures.withDatabase { executor ->
            val created = ConversationFixtures.insert(executor, "turn-1")
            assertRejectedWithoutLeak(executor, created.id, """[{"content":"$RAW_MARKER"}]""")
        }

    @Test
    fun `string content that looks like a JSON scalar is accepted`() =
        ConversationFixtures.withDatabase { executor ->
            val values = listOf("123", "true", "null", "{}", "[1]")
            val messages = values.map { ChatMessage(ChatRole.USER, it) }
            val created = ConversationFixtures.insert(executor, "turn-1", messages = messages)
            val stored = assertNotNull(ConversationRepositoryImpl(executor).getById(created.id))
            assertEquals(messages, stored.messages)
        }

    /** 写入合法 JSON 数组但字段类型非法；读取必须安全失败且异常链不回显原文。 */
    private suspend fun assertRejectedWithoutLeak(
        executor: ContextDatabaseExecutor,
        id: Long,
        json: String,
    ) {
        val updated =
            executor.updateRaw("UPDATE conversation_turn SET messages_json = '${json.replace("'", "''")}' WHERE id = $id")
        assertEquals(1, updated, "注入必须真实生效，否则读取异常不能归因于 codec")
        val stored =
            executor.queryRaw("SELECT messages_json FROM conversation_turn WHERE id = $id") { rs ->
                assertTrue(rs.next())
                rs.getString(1)
            }
        assertEquals(json, stored, "库内必须是注入的非法 JSON 原文")
        val failure =
            assertFailsWith<ContextPersistenceException> {
                ConversationRepositoryImpl(executor).getById(id)
            }
        assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        assertNull(failure.cause)
        val chain = describeChain(failure)
        assertFalse(chain.contains(RAW_MARKER), "异常链不得回显库内原文: $chain")
    }

    private fun describeChain(failure: Throwable): String =
        buildString {
            var current: Throwable? = failure
            while (current != null) {
                appendLine(current.message.orEmpty())
                appendLine(current.stackTraceToString())
                current = current.cause
            }
            failure.suppressed.forEach { appendLine(it.stackTraceToString()) }
        }

    private companion object {
        const val RAW_MARKER = "B1-RAW-MARKER-9f3c"
    }
}
