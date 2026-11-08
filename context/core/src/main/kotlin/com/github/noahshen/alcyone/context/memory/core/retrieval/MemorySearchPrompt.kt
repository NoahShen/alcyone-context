package com.github.noahshen.alcyone.context.memory.core.retrieval

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/** Memory Search Scanner/Rerank v1 Prompt 与 Schema 资源。 */
object MemorySearchPrompt {
    const val VERSION: String = "v1"
    const val SCHEMA_VERSION: String = "v1"

    private const val SCANNER_PROMPT_RESOURCE = "/prompts/memory-search-v1.md"
    private const val SCANNER_SCHEMA_RESOURCE = "/prompts/memory-search-schema-v1.json"
    private const val RERANK_PROMPT_RESOURCE = "/prompts/memory-search-rerank-v1.md"
    private const val RERANK_SCHEMA_RESOURCE = "/prompts/memory-search-rerank-schema-v1.json"

    fun scannerSystemPrompt(): String = readResource(SCANNER_PROMPT_RESOURCE)

    fun scannerSchema(): String = readResource(SCANNER_SCHEMA_RESOURCE)

    fun rerankSystemPrompt(): String = readResource(RERANK_PROMPT_RESOURCE)

    fun rerankSchema(): String = readResource(RERANK_SCHEMA_RESOURCE)

    fun scannerMessages(
        query: String,
        candidates: List<OrganizedMemory>,
    ): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = scannerSystemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderScannerContent(query, candidates)),
        )

    fun rerankMessages(
        query: String,
        candidates: List<OrganizedMemory>,
        limit: Int,
    ): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = rerankSystemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderRerankContent(query, candidates, limit)),
        )

    internal fun renderScannerContent(
        query: String,
        candidates: List<OrganizedMemory>,
    ): String = renderContent(query, candidates, limit = null)

    internal fun renderRerankContent(
        query: String,
        candidates: List<OrganizedMemory>,
        limit: Int,
    ): String = renderContent(query, candidates, limit)

    private fun renderContent(
        query: String,
        candidates: List<OrganizedMemory>,
        limit: Int?,
    ): String =
        buildString {
            append("<search_query>\n").append(query.trim()).append("\n</search_query>\n")
            limit?.let { append("<limit>").append(it).append("</limit>\n") }
            append("<candidates count=\"").append(candidates.size).append("\">\n")
            candidates.sortedBy { candidate -> candidate.id }.forEach { candidate -> appendCandidate(candidate) }
            append("</candidates>")
        }

    private fun StringBuilder.appendCandidate(candidate: OrganizedMemory) {
        append("<candidate id=\"").append(candidate.id)
        append("\" created_at=\"").append(TimeCodec.format(candidate.createdAt))
        append("\" updated_at=\"").append(TimeCodec.format(candidate.updatedAt))
        append("\">\n<domains>\n")
        candidate.domains.forEach { domain -> append("<domain>").append(domain).append("</domain>\n") }
        append("</domains>\n<content>\n")
        append(candidate.content)
        append("\n</content>\n</candidate>\n")
    }

    private fun readResource(path: String): String {
        val stream =
            MemorySearchPrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("memory search resource is missing: $path")
        val text = stream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) throw IllegalStateException("memory search resource is blank: $path")
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
