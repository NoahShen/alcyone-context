package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/** CT25 Reflection v1 Prompt、Schema 与确定性四类证据渲染。 */
object DreamingReflectionPrompt {
    const val VERSION: String = "v1"
    const val SCHEMA_VERSION: String = "v1"

    private const val PROMPT_RESOURCE = "/prompts/dreaming-reflection-v1.md"
    private const val SCHEMA_RESOURCE = "/prompts/dreaming-reflection-schema-v1.json"

    fun reflectionSystemPrompt(): String = readResource(PROMPT_RESOURCE)

    fun reflectionSchema(): String = readResource(SCHEMA_RESOURCE)

    fun reflectionMessages(
        topic: DiscoveredTopic,
        newConversationTurns: List<StoredConversationTurn>,
        organizedMemories: List<OrganizedMemory>,
        rawMemories: List<RawMemory>,
        historicalConversationTurns: List<StoredConversationTurn>,
    ): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = reflectionSystemPrompt()),
            LlmMessage(
                role = USER_ROLE,
                content =
                    renderReflectionContent(
                        topic = topic,
                        newConversationTurns = newConversationTurns,
                        organizedMemories = organizedMemories,
                        rawMemories = rawMemories,
                        historicalConversationTurns = historicalConversationTurns,
                    ),
            ),
        )

    internal fun renderReflectionContent(
        topic: DiscoveredTopic,
        newConversationTurns: List<StoredConversationTurn>,
        organizedMemories: List<OrganizedMemory>,
        rawMemories: List<RawMemory>,
        historicalConversationTurns: List<StoredConversationTurn>,
    ): String =
        buildString {
            append("<topic>\n")
            append("<title>").appendCdata(topic.title).append("</title>\n")
            append("<description>").appendCdata(topic.description).append("</description>\n")
            append("<keywords>")
            topic.keywords.forEach { keyword -> append("<keyword>").appendCdata(keyword).append("</keyword>") }
            append("</keywords>\n")
            append("<sourceTurnIds>").append(topic.sourceTurnIds.joinToString(",")).append("</sourceTurnIds>\n")
            append("</topic>\n")

            appendTurns(
                container = "new_conversation",
                description = "本次新增、可直接作为来源的 Conversation Turn",
                turns = newConversationTurns,
            )
            appendOrganized(organizedMemories)
            appendRawCandidates(rawMemories)
            appendTurns(
                container = "historical_conversation",
                description = "已核验、可直接作为来源的历史 Conversation Turn",
                turns = historicalConversationTurns,
            )
        }

    private fun StringBuilder.appendTurns(
        container: String,
        description: String,
        turns: List<StoredConversationTurn>,
    ) {
        append("\n<")
            .append(container)
            .append(" count=\"")
            .append(turns.size)
            .append("\">\n")
        append("<description>").appendCdata(description).append("</description>\n")
        turns.sortedBy { turn -> turn.id }.forEach { turn ->
            append("<turn id=\"").appendXmlAttribute(turn.id.toString())
            append("\" timestamp=\"").appendXmlAttribute(TimeCodec.format(turn.timestamp))
            append("\" runtimeId=\"").appendXmlAttribute(turn.runtimeId)
            append("\" agentId=\"").appendXmlAttribute(turn.agentId)
            append("\" sessionId=\"").appendXmlAttribute(turn.sessionId)
            append("\" sessionTurnId=\"").appendXmlAttribute(turn.sessionTurnId)
            append("\">\n")
            turn.messages.forEach { message ->
                append("<message role=\"").appendXmlAttribute(message.role.name.lowercase()).append("\">")
                appendCdata(message.content)
                append("</message>\n")
            }
            append("</turn>\n")
        }
        append("</").append(container).append(">")
    }

    private fun StringBuilder.appendOrganized(memories: List<OrganizedMemory>) {
        append("\n<current_organized count=\"").append(memories.size).append("\">\n")
        append("<description>当前认知基准；用于对照，不是独立原始证据，也不是可输出来源</description>\n")
        memories.sortedBy { memory -> memory.id }.forEach { memory ->
            append("<organized_memory id=\"").appendXmlAttribute(memory.id)
            append("\" created_at=\"").appendXmlAttribute(TimeCodec.format(memory.createdAt))
            append("\" updated_at=\"").appendXmlAttribute(TimeCodec.format(memory.updatedAt))
            append("\">\n")
            append("<domains>")
            memory.domains.forEach { domain -> append("<domain>").appendCdata(domain).append("</domain>") }
            append("</domains>\n")
            append("<content>").appendCdata(memory.content).append("</content>\n")
            append("<raw_ids>")
            memory.rawMemoryIds.forEach { rawId -> append("<raw_id>").appendCdata(rawId).append("</raw_id>") }
            append("</raw_ids>\n")
            append("</organized_memory>\n")
        }
        append("</current_organized>")
    }

    private fun StringBuilder.appendRawCandidates(memories: List<RawMemory>) {
        append("\n<raw_candidates count=\"").append(memories.size).append("\">\n")
        append("<description>候选历史 Raw；用于对照和来源核实，不是当前事实，也不是可输出来源</description>\n")
        memories
            .sortedWith(compareBy<RawMemory> { memory -> memory.createdAt }.thenBy { memory -> memory.id })
            .forEach { memory ->
                append("<raw_candidate id=\"").appendXmlAttribute(memory.id)
                append("\" generated_by=\"").appendXmlAttribute(memory.generatedBy.name)
                append("\" created_at=\"").appendXmlAttribute(TimeCodec.format(memory.createdAt))
                append("\">\n")
                append("<content>").appendCdata(memory.content).append("</content>\n")
                append("<sources>\n")
                memory.sources.forEach { source ->
                    when (source) {
                        is MemorySourceRef.ConversationTurnRef -> {
                            append("<conversation_source runtimeId=\"").appendXmlAttribute(source.runtimeId)
                            append("\" agentId=\"").appendXmlAttribute(source.agentId)
                            append("\" sessionId=\"").appendXmlAttribute(source.sessionId)
                            append("\" sessionTurnId=\"").appendXmlAttribute(source.sessionTurnId)
                            append("\" />\n")
                        }

                        is MemorySourceRef.RawMemoryRef -> {
                            append("<raw_source id=\"").appendXmlAttribute(source.memoryId).append("\" />\n")
                        }
                    }
                }
                append("</sources>\n</raw_candidate>\n")
            }
        append("</raw_candidates>")
    }

    private fun StringBuilder.appendCdata(value: String): StringBuilder {
        append("<![CDATA[")
        append(value.replace("]]>", "]]]]><![CDATA[>"))
        append("]]>")
        return this
    }

    private fun StringBuilder.appendXmlAttribute(value: String): StringBuilder =
        append(
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;"),
        )

    private fun readResource(path: String): String {
        val stream =
            DreamingReflectionPrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("dreaming reflection resource is missing: $path")
        val text = stream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) throw IllegalStateException("dreaming reflection resource is blank: $path")
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
