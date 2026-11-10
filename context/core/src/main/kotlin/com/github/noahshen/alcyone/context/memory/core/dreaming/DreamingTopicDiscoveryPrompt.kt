package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/** Dreaming Topic Discovery v1 Prompt/Schema 资源与确定性 Turn 渲染。 */
object DreamingTopicDiscoveryPrompt {
    const val VERSION: String = "v1"
    const val SCHEMA_VERSION: String = "v1"

    private const val SYSTEM_PROMPT_RESOURCE = "/prompts/dreaming-topic-discovery-v1.md"
    private const val SCHEMA_RESOURCE = "/prompts/dreaming-topic-discovery-schema-v1.json"

    fun systemPrompt(): String = readResource(SYSTEM_PROMPT_RESOURCE)

    fun schema(): String = readResource(SCHEMA_RESOURCE)

    fun messages(turns: List<StoredConversationTurn>): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = systemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderTurns(turns)),
        )

    /** 渲染完整消息与会话元数据；不渲染 extractionStatus。 */
    internal fun renderTurns(turns: List<StoredConversationTurn>): String =
        buildString {
            turns.forEachIndexed { index, turn ->
                if (index > 0) append('\n')
                append("<turn id=\"").append(turn.id)
                append("\" timestamp=\"").append(TimeCodec.format(turn.timestamp))
                append("\" runtimeId=\"").append(turn.runtimeId)
                append("\" agentId=\"").append(turn.agentId)
                append("\" sessionId=\"").append(turn.sessionId)
                append("\" sessionTurnId=\"").append(turn.sessionTurnId)
                append("\">\n")
                turn.messages.forEach { message ->
                    append(message.role.name.lowercase()).append(": ")
                    append(message.content)
                    append('\n')
                }
                append("</turn>")
            }
        }

    private fun readResource(path: String): String {
        val stream =
            DreamingTopicDiscoveryPrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("dreaming topic discovery resource is missing: $path")
        val text = stream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) throw IllegalStateException("dreaming topic discovery resource is blank: $path")
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
