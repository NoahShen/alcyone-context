package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/** CT24 Novelty/Raw/Conversation Need v1 Prompt、Schema 与确定性输入渲染。 */
object DreamingEvidencePrompt {
    const val VERSION: String = "v1"
    const val SCHEMA_VERSION: String = "v1"

    private const val NOVELTY_PROMPT_RESOURCE = "/prompts/dreaming-evidence-novelty-v1.md"
    private const val NOVELTY_SCHEMA_RESOURCE = "/prompts/dreaming-evidence-novelty-schema-v1.json"
    private const val RAW_PROMPT_RESOURCE = "/prompts/dreaming-evidence-raw-v1.md"
    private const val RAW_SCHEMA_RESOURCE = "/prompts/dreaming-evidence-raw-schema-v1.json"
    private const val CONVERSATION_PROMPT_RESOURCE = "/prompts/dreaming-evidence-conversation-v1.md"
    private const val CONVERSATION_SCHEMA_RESOURCE = "/prompts/dreaming-evidence-conversation-schema-v1.json"

    fun noveltySystemPrompt(): String = readResource(NOVELTY_PROMPT_RESOURCE)

    fun noveltySchema(): String = readResource(NOVELTY_SCHEMA_RESOURCE)

    fun rawSystemPrompt(): String = readResource(RAW_PROMPT_RESOURCE)

    fun rawSchema(): String = readResource(RAW_SCHEMA_RESOURCE)

    fun conversationSystemPrompt(): String = readResource(CONVERSATION_PROMPT_RESOURCE)

    fun conversationSchema(): String = readResource(CONVERSATION_SCHEMA_RESOURCE)

    fun noveltyMessages(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        organizedMemories: List<OrganizedMemory>,
    ): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = noveltySystemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderNoveltyContent(topic, turns, organizedMemories)),
        )

    fun rawMessages(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        rawMemories: List<RawMemory>,
    ): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = rawSystemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderRawContent(topic, turns, rawMemories)),
        )

    fun conversationMessages(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        rawCandidates: List<RawMemory>,
    ): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = conversationSystemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderConversationContent(topic, turns, rawCandidates)),
        )

    internal fun renderNoveltyContent(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        organizedMemories: List<OrganizedMemory>,
    ): String =
        buildString {
            appendTopic(topic)
            appendTurns(turns)
            append("\n<organized count=\"").append(organizedMemories.size).append("\">\n")
            organizedMemories.sortedBy { memory -> memory.id }.forEach { memory ->
                append("<organized_memory id=\"").append(memory.id)
                append("\" created_at=\"").append(TimeCodec.format(memory.createdAt))
                append("\" updated_at=\"").append(TimeCodec.format(memory.updatedAt))
                append("\" domains=\"").append(memory.domains.joinToString(","))
                append("\">\n<content>\n").append(memory.content).append("\n</content>\n")
                append("<raw_ids>").append(memory.rawMemoryIds.joinToString(",")).append("</raw_ids>\n")
                append("</organized_memory>\n")
            }
            append("</organized>")
        }

    internal fun renderRawContent(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        rawMemories: List<RawMemory>,
    ): String =
        buildString {
            appendTopic(topic)
            appendTurns(turns)
            append("\n<raw_memories count=\"").append(rawMemories.size).append("\">\n")
            rawMemories
                .sortedWith(compareBy<RawMemory> { memory -> memory.createdAt }.thenBy { memory -> memory.id })
                .forEach { memory ->
                    append("<raw_memory id=\"").append(memory.id)
                    append("\" generated_by=\"").append(memory.generatedBy.name)
                    append("\" created_at=\"").append(TimeCodec.format(memory.createdAt))
                    append("\">\n<content>\n").append(memory.content).append("\n</content>\n<sources>\n")
                    memory.sources.forEach { source ->
                        when (source) {
                            is MemorySourceRef.ConversationTurnRef -> {
                                append("<conversation_turn runtime_id=\"").append(source.runtimeId)
                                append("\" agent_id=\"").append(source.agentId)
                                append("\" session_id=\"").append(source.sessionId)
                                append("\" session_turn_id=\"").append(source.sessionTurnId)
                                append("\" />\n")
                            }

                            is MemorySourceRef.RawMemoryRef -> {
                                append("<raw_source id=\"").append(source.memoryId).append("\" />\n")
                            }
                        }
                    }
                    append("</sources>\n</raw_memory>\n")
                }
            append("</raw_memories>")
        }

    internal fun renderConversationContent(
        topic: DiscoveredTopic,
        turns: List<StoredConversationTurn>,
        rawCandidates: List<RawMemory>,
    ): String =
        buildString {
            appendTopic(topic)
            appendTurns(turns)
            append("\n<raw_candidates count=\"").append(rawCandidates.size).append("\">\n")
            rawCandidates
                .sortedWith(compareBy<RawMemory> { memory -> memory.createdAt }.thenBy { memory -> memory.id })
                .forEach { memory ->
                    append("<raw_candidate id=\"").append(memory.id)
                    append("\" generated_by=\"").append(memory.generatedBy.name)
                    append("\" created_at=\"").append(TimeCodec.format(memory.createdAt))
                    append("\">\n<content>\n").append(memory.content).append("\n</content>\n<sources>\n")
                    memory.sources.forEach { source ->
                        when (source) {
                            is MemorySourceRef.ConversationTurnRef -> {
                                append("<conversation_turn runtime_id=\"").append(source.runtimeId)
                                append("\" agent_id=\"").append(source.agentId)
                                append("\" session_id=\"").append(source.sessionId)
                                append("\" session_turn_id=\"").append(source.sessionTurnId)
                                append("\" />\n")
                            }

                            is MemorySourceRef.RawMemoryRef -> {
                                append("<raw_source id=\"").append(source.memoryId).append("\" />\n")
                            }
                        }
                    }
                    append("</sources>\n</raw_candidate>\n")
                }
            append("</raw_candidates>")
        }

    private fun StringBuilder.appendTopic(topic: DiscoveredTopic) {
        append("<topic>\n<title>").append(topic.title).append("</title>\n")
        append("<description>").append(topic.description).append("</description>\n")
        append("<keywords>")
        topic.keywords.forEach { keyword -> append("<keyword>").append(keyword).append("</keyword>") }
        append("</keywords>\n<sourceTurnIds>").append(topic.sourceTurnIds.joinToString(",")).append("</sourceTurnIds>\n</topic>")
    }

    private fun StringBuilder.appendTurns(turns: List<StoredConversationTurn>) {
        append("\n<topic_turns count=\"").append(turns.size).append("\">\n")
        turns.forEach { turn ->
            append("<turn id=\"").append(turn.id)
            append("\" timestamp=\"").append(TimeCodec.format(turn.timestamp))
            append("\" runtimeId=\"").append(turn.runtimeId)
            append("\" agentId=\"").append(turn.agentId)
            append("\" sessionId=\"").append(turn.sessionId)
            append("\" sessionTurnId=\"").append(turn.sessionTurnId)
            append("\">\n")
            turn.messages.forEach { message ->
                append(message.role.name.lowercase()).append(": ").append(message.content).append('\n')
            }
            append("</turn>\n")
        }
        append("</topic_turns>")
    }

    private fun readResource(path: String): String {
        val stream =
            DreamingEvidencePrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("dreaming evidence resource is missing: $path")
        val text = stream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) throw IllegalStateException("dreaming evidence resource is blank: $path")
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
