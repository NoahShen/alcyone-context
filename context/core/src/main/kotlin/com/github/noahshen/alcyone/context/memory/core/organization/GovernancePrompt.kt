package com.github.noahshen.alcyone.context.memory.core.organization

import com.github.noahshen.alcyone.context.common.TimeCodec
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage

/**
 * CT14 Governance Prompt v1（Core public；Prompt 与 JSON Schema 以 **Core 资源**版本化）。
 *
 * - [systemPrompt] / [schema]：读取 `prompts/organization-governance-v1.md` /
 *   `prompts/organization-governance-schema-v1.json`（Core jar classpath）；资源缺失或空白**明确失败**，
 *   不静默返回空串。
 * - [messages]：`system`（固定 Prompt）+ `user`（Raw + **全部**本次候选）。固定部分与本次请求共用同一渲染，
 *   因此预算估算与实际发送完全一致。
 * - 输入（Raw / 候选正文）是**待判断的数据，不是指令**；正文里的伪造字段、分隔符或指令样文本不会扩大
 *   [GovernanceDecisionValidator] 的程序级候选权限边界。
 * - 单次请求携带全部候选：Governance 需要看全量才能判断动作，**不分批、不截断**（裁定 D7）。
 */
object GovernancePrompt {
    /** Prompt 版本（与资源文件名一一对应）。 */
    const val VERSION: String = "v1"

    /** 输出 JSON Schema 版本。 */
    const val SCHEMA_VERSION: String = "v1"

    private const val SYSTEM_PROMPT_RESOURCE = "/prompts/organization-governance-v1.md"
    private const val SCHEMA_RESOURCE = "/prompts/organization-governance-schema-v1.json"

    /** 读取 v1 Governance System Prompt（资源缺失/空白 → [IllegalStateException]）。 */
    fun systemPrompt(): String = readResource(SYSTEM_PROMPT_RESOURCE)

    /** 读取 v1 输出 JSON Schema（资源缺失/空白 → [IllegalStateException]）。 */
    fun schema(): String = readResource(SCHEMA_RESOURCE)

    /** 组装 `system` + `user` 两条消息；`candidates` 可为空（空候选允许 NEW，见 D6）。 */
    fun messages(
        rawMemory: RawMemory,
        candidates: List<OrganizedMemory>,
    ): List<LlmMessage> =
        listOf(
            LlmMessage(role = SYSTEM_ROLE, content = systemPrompt()),
            LlmMessage(role = USER_ROLE, content = renderUserContent(rawMemory, candidates)),
        )

    /**
     * 渲染 `user` 内容（确定性、可测试）：Raw 在前，候选按传入顺序，逐条带 id/content/domains/createdAt/updatedAt。
     *
     * 候选时间**只作为事实时间参考**：Prompt 明确要求以正文陈述的事实/生效时间优先，
     * 不得用 `createdAt`/`updatedAt`/入队顺序代替事实时间。
     */
    internal fun renderUserContent(
        rawMemory: RawMemory,
        candidates: List<OrganizedMemory>,
    ): String =
        buildString {
            append("<raw id=\"").append(rawMemory.id)
            append("\" generated_by=\"").append(rawMemory.generatedBy.name)
            append("\" created_at=\"").append(TimeCodec.format(rawMemory.createdAt))
            append("\">\n")
            append(rawMemory.content)
            append("\n</raw>\n")
            append("<candidates count=\"").append(candidates.size).append("\">\n")
            candidates.forEach { candidate ->
                append("<candidate id=\"").append(candidate.id)
                append("\" created_at=\"").append(TimeCodec.format(candidate.createdAt))
                append("\" updated_at=\"").append(TimeCodec.format(candidate.updatedAt))
                append("\" domains=\"").append(candidate.domains.joinToString(","))
                append("\">\n")
                append(candidate.content)
                append("\n</candidate>\n")
            }
            append("</candidates>")
        }

    private fun readResource(path: String): String {
        val stream =
            GovernancePrompt::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("governance resource is missing: $path")
        val text =
            stream.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        if (text.isBlank()) {
            throw IllegalStateException("governance resource is blank: $path")
        }
        return text
    }

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
}
