package com.github.noahshen.alcyone.context.memory.core.dreaming

import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/** Dreaming Topic Consolidation v1 Prompt/Schema 资源与确定性 Topic 渲染。 */
object DreamingTopicConsolidationPrompt {
    const val VERSION: String = "v1"
    const val SCHEMA_VERSION: String = "v1"

    private const val SYSTEM_PROMPT_RESOURCE = "/prompts/dreaming-topic-consolidation-v1.md"
    private const val SCHEMA_RESOURCE = "/prompts/dreaming-topic-consolidation-schema-v1.json"

    fun systemPrompt(): String = readResource(SYSTEM_PROMPT_RESOURCE)

    fun schema(): String = readResource(SCHEMA_RESOURCE)

    fun messages(topics: List<DiscoveredTopic>): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = systemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderTopics(topics)),
        )

    /** 稳定渲染全部 Discovery Topic，保持输入顺序，不携带系统或持久 ID。 */
    internal fun renderTopics(topics: List<DiscoveredTopic>): String =
        buildString {
            append("<topics count=\"").append(topics.size).append("\">")
            topics.forEachIndexed { index, topic ->
                append("\n<topic index=\"").append(index + 1).append("\">\n")
                append("<title>").append(topic.title).append("</title>\n")
                append("<description>").append(topic.description).append("</description>\n")
                append("<keywords>")
                topic.keywords.forEach { keyword -> append("<keyword>").append(keyword).append("</keyword>") }
                append("</keywords>\n")
                append("<sourceTurnIds>")
                topic.sourceTurnIds.forEachIndexed { sourceIndex, id ->
                    if (sourceIndex > 0) append(',')
                    append(id)
                }
                append("</sourceTurnIds>\n")
                append("</topic>")
            }
            append("\n</topics>")
        }

    private fun readResource(path: String): String {
        val stream =
            DreamingTopicConsolidationPrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("dreaming topic consolidation resource is missing: $path")
        val text = stream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) throw IllegalStateException("dreaming topic consolidation resource is blank: $path")
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
