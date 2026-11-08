package com.github.noahshen.alcyone.context.memory.core.map

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/** Memory Map Prompt/Schema v1 的 Core 资源与确定性输入渲染。 */
object MemoryMapPrompt {
    const val VERSION: String = "v1"
    const val SCHEMA_VERSION: String = "v1"

    private const val SYSTEM_PROMPT_RESOURCE = "/prompts/memory-map-v1.md"
    private const val SCHEMA_RESOURCE = "/prompts/memory-map-schema-v1.json"

    fun systemPrompt(): String = readResource(SYSTEM_PROMPT_RESOURCE)

    fun schema(): String = readResource(SCHEMA_RESOURCE)

    fun messages(snapshot: MemoryMapSnapshot): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = systemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderUserContent(snapshot)),
        )

    internal fun renderUserContent(snapshot: MemoryMapSnapshot): String =
        buildString {
            append("<memory_snapshot source_updated_at=\"")
            append(snapshot.sourceUpdatedAt?.let(TimeCodec::format) ?: "null")
            append("\">\n<domains>\n")
            snapshot.domains.forEach { domain ->
                append("<domain>").append(domain).append("</domain>\n")
            }
            append("</domains>\n<memories count=\"").append(snapshot.memories.size).append("\">\n")
            snapshot.memories.forEach { memory ->
                append("<memory id=\"").append(memory.id)
                append("\" created_at=\"").append(TimeCodec.format(memory.createdAt))
                append("\" updated_at=\"").append(TimeCodec.format(memory.updatedAt))
                append("\">\n<domains>\n")
                memory.domains.forEach { domain ->
                    append("<domain>").append(domain).append("</domain>\n")
                }
                append("</domains>\n<content>\n")
                append(memory.content)
                append("\n</content>\n</memory>\n")
            }
            append("</memories>\n</memory_snapshot>")
        }

    private fun readResource(path: String): String {
        val stream =
            MemoryMapPrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("memory map resource is missing: $path")
        val text = stream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) throw IllegalStateException("memory map resource is blank: $path")
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
