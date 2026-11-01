package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/**
 * CT10 提取 Prompt v1（Core public；Prompt 与 JSON Schema 以 **Core 资源**版本化，D3/D12）。
 *
 * - [systemPrompt] / [schema]：分别读取 `prompts/extraction-v1.md` 与
 *   `prompts/extraction-schema-v1.json`（Core jar classpath）；资源缺失或为空白**明确失败**，
 *   绝不静默返回空字符串。
 * - [messages]：`system` 消息（[systemPrompt]）+ `user` 消息（渲染所有轮次）。空轮次列表时
 *   `user` 内容为空串（用于基础请求的协议开销计算）。
 * - 每个轮次严格渲染为：
 *   ```
 *   <turn id="1001" timestamp="2026-10-30T10:00:00.000+08:00" target="true">
 *   user: ...
 *   assistant: ...
 *   </turn>
 *   ```
 *   `target="true"` **iff** `extractionStatus == PENDING`（Target/History 只在这里判定一次）；
 *   时间戳使用 [TimeCodec.format]（固定 `+08:00`、毫秒）；轮次顺序、角色与正文原样，不增删消息。
 *   输出不含 API Key、URL 或数据库内部元数据。
 */
object ExtractionPrompt {
    /** Prompt 版本（与资源文件名一一对应）。 */
    const val VERSION: String = "v1"

    /** 输出 JSON Schema 版本。 */
    const val SCHEMA_VERSION: String = "v1"

    private const val SYSTEM_PROMPT_RESOURCE = "/prompts/extraction-v1.md"
    private const val SCHEMA_RESOURCE = "/prompts/extraction-schema-v1.json"

    /** 读取 v1 System Prompt（资源缺失/空白 → [IllegalStateException]）。 */
    fun systemPrompt(): String = readResource(SYSTEM_PROMPT_RESOURCE)

    /** 读取 v1 输出 JSON Schema（资源缺失/空白 → [IllegalStateException]）。 */
    fun schema(): String = readResource(SCHEMA_RESOURCE)

    /** 组装 `system` + `user` 两条消息；空轮次列表时 `user` 内容为空串。 */
    fun messages(turns: List<StoredConversationTurn>): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = systemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderTurns(turns)),
        )

    /** 渲染轮次块（稳定、可测试；空列表返回空串）。 */
    internal fun renderTurns(turns: List<StoredConversationTurn>): String =
        buildString {
            turns.forEachIndexed { index, turn ->
                if (index > 0) append('\n')
                append("<turn id=\"")
                append(turn.id)
                append("\" timestamp=\"")
                append(TimeCodec.format(turn.timestamp))
                append("\" target=\"")
                append(if (turn.extractionStatus == ExtractionStatus.PENDING) "true" else "false")
                append("\">\n")
                turn.messages.forEach { message ->
                    append(message.role.name.lowercase())
                    append(": ")
                    append(message.content)
                    append('\n')
                }
                append("</turn>")
            }
        }

    private fun readResource(path: String): String {
        val stream =
            ExtractionPrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("extraction prompt resource is missing: $path")
        val text =
            stream.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        if (text.isBlank()) {
            throw IllegalStateException("extraction prompt resource is blank: $path")
        }
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
