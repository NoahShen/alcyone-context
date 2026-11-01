package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Context 状态库 JSON 列的编解码（CT05）。
 *
 * 依据 CT05 方案 §3：`messages_json` / `sources_json` / `domains_json` 一律用
 * kotlinx.serialization 编解码；写入前保证是 JSON **数组**，读取时严格反序列化。
 *
 * 安全边界：
 * - 失败统一映射为 `PERSISTENCE_UNAVAILABLE`，消息只描述「哪个列不可用」，
 *   **绝不把库内原文、消息正文或底层异常链带出去**（与 [ContextSqliteErrors] 同一口径）；
 * - [CancellationException] 原样传播，不在这里被包装；
 * - 本对象只做纯函数编解码，不碰连接、作用域或事务。
 *
 * 存储编码（持久化层内部契约，不是公开 API）：
 * - `messages_json`：`[{"role":"USER","content":"..."}]`
 * - `domains_json`：`["domain-a","domain-b"]`（只允许 JSON 字符串元素）
 * - `sources_json`：
 *   - `{"type":"conversation_turn","runtime_id":"...","agent_id":"...","session_id":"...","session_turn_id":"..."}`
 *   - `{"type":"raw_memory","memory_id":"..."}`
 *
 * 所有字段都要求 JSON 字符串；数值、布尔、`null`、对象、数组、缺字段、未知 `type`
 * 一律拒绝，不做隐式类型转换。
 */
internal object ContextJsonCodecs {
    private const val ROLE = "role"
    private const val CONTENT = "content"
    private const val TYPE = "type"
    private const val TYPE_CONVERSATION_TURN = "conversation_turn"
    private const val TYPE_RAW_MEMORY = "raw_memory"
    private const val RUNTIME_ID = "runtime_id"
    private const val AGENT_ID = "agent_id"
    private const val SESSION_ID = "session_id"
    private const val SESSION_TURN_ID = "session_turn_id"
    private const val MEMORY_ID = "memory_id"

    /**
     * 把有序消息编码为 JSON 数组文本。
     *
     * 顺序严格保持输入顺序；引号、反斜杠、换行等由 kotlinx.serialization 统一转义，
     * 中文与 emoji 按 JSON 规范原样保留（不被转成 `\\uXXXX`）。
     */
    fun encodeMessages(messages: List<ChatMessage>): String =
        JsonArray(
            messages.map { message ->
                JsonObject(
                    mapOf(
                        ROLE to JsonPrimitive(message.role.name),
                        CONTENT to JsonPrimitive(message.content),
                    ),
                )
            },
        ).toString()

    /**
     * 严格解码 `messages_json`：必须是 JSON 数组，元素必须是 `{role, content}` 对象，
     * `role` 必须是 [ChatRole] 的合法名字。
     *
     * 任何不符合契约的内容都映射为 `PERSISTENCE_UNAVAILABLE`，不泄露原文。
     */
    fun decodeMessages(json: String): List<ChatMessage> =
        decodeArray(json, ::malformedMessages) { element ->
            val message = element as? JsonObject ?: throw malformedMessages()
            val roleText = stringField(message, ROLE, ::malformedMessages)
            val content = stringField(message, CONTENT, ::malformedMessages)
            val role = ChatRole.entries.firstOrNull { it.name == roleText } ?: throw malformedMessages()
            ChatMessage(role, content)
        }

    /** 编码领域列表：始终输出 JSON 字符串数组，顺序保持。 */
    fun encodeDomains(domains: List<String>): String = JsonArray(domains.map { JsonPrimitive(it) }).toString()

    /** 严格解码 `domains_json`：必须是 JSON 字符串数组；任何非字符串元素都拒绝。 */
    fun decodeDomains(json: String): List<String> =
        decodeArray(json, ::malformedDomains) { element ->
            val primitive = element as? JsonPrimitive ?: throw malformedDomains()
            if (!primitive.isString) throw malformedDomains()
            primitive.content
        }

    /** 编码来源列表：始终输出对象数组，元素结构与 [MemorySourceRef] 两个变体一一对应。 */
    fun encodeSources(sources: List<MemorySourceRef>): String =
        JsonArray(
            sources.map { source ->
                when (source) {
                    is MemorySourceRef.ConversationTurnRef ->
                        JsonObject(
                            mapOf(
                                TYPE to JsonPrimitive(TYPE_CONVERSATION_TURN),
                                RUNTIME_ID to JsonPrimitive(source.runtimeId),
                                AGENT_ID to JsonPrimitive(source.agentId),
                                SESSION_ID to JsonPrimitive(source.sessionId),
                                SESSION_TURN_ID to JsonPrimitive(source.sessionTurnId),
                            ),
                        )
                    is MemorySourceRef.RawMemoryRef ->
                        JsonObject(
                            mapOf(
                                TYPE to JsonPrimitive(TYPE_RAW_MEMORY),
                                MEMORY_ID to JsonPrimitive(source.memoryId),
                            ),
                        )
                }
            },
        ).toString()

    /**
     * 严格解码 `sources_json`：必须是对象数组，`type` 必须是已知字符串，且该变体的
     * 所有必需字段都存在并为 JSON 字符串。未知 `type`、非字符串字段、缺字段一律拒绝。
     */
    fun decodeSources(json: String): List<MemorySourceRef> =
        decodeArray(json, ::malformedSources) { element ->
            val source = element as? JsonObject ?: throw malformedSources()
            when (val type = stringField(source, TYPE, ::malformedSources)) {
                TYPE_CONVERSATION_TURN ->
                    MemorySourceRef.ConversationTurnRef(
                        runtimeId = stringField(source, RUNTIME_ID, ::malformedSources),
                        agentId = stringField(source, AGENT_ID, ::malformedSources),
                        sessionId = stringField(source, SESSION_ID, ::malformedSources),
                        sessionTurnId = stringField(source, SESSION_TURN_ID, ::malformedSources),
                    )
                TYPE_RAW_MEMORY ->
                    MemorySourceRef.RawMemoryRef(
                        memoryId = stringField(source, MEMORY_ID, ::malformedSources),
                    )
                else -> throw malformedSources()
            }
        }

    /** 统一的数组解码与失败收敛：业务异常原样透传，其余异常（含 JSON 解析）安全映射。 */
    private fun <T> decodeArray(
        json: String,
        failure: () -> ContextPersistenceException,
        decodeElement: (JsonElement) -> T,
    ): List<T> =
        try {
            val root = Json.parseToJsonElement(json)
            val array = root as? JsonArray ?: throw failure()
            array.map(decodeElement)
        } catch (failure: ContextPersistenceException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw failure()
        }

    /**
     * 严格取字符串字段：必须是 [JsonPrimitive] 且 `isString == true`。
     *
     * 不能只用 `contentOrNull`：它会把 JSON 数字 / 布尔（`123`、`true`）转成对应字符串，
     * 从而把非法类型静默当成合法正文。数值、布尔、`null`、对象、数组、缺字段一律拒绝。
     */
    private fun stringField(
        message: JsonObject,
        name: String,
        failure: () -> ContextPersistenceException,
    ): String {
        val primitive = message[name] as? JsonPrimitive ?: throw failure()
        if (!primitive.isString) throw failure()
        return primitive.content
    }

    private fun malformedMessages(): ContextPersistenceException =
        persistenceUnavailable("conversation messages_json is not a valid ChatMessage array")

    private fun malformedDomains(): ContextPersistenceException =
        persistenceUnavailable("organized memory domains_json is not a valid string array")

    private fun malformedSources(): ContextPersistenceException =
        persistenceUnavailable("raw memory sources_json is not a valid MemorySourceRef array")
}
