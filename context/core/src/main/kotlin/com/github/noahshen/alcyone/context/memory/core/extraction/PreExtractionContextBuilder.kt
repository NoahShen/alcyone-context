package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.SessionKey

/**
 * CT10 预提取上下文构建 Port（Core public，非 `context/api` SDK；D9）。
 *
 * 职责：在**同一只读快照**内取出锚点与窗口轮次、渲染 Prompt 并做预算分类，返回
 * [ExtractionBuildResult]。本批（B1）只定义接口：不实现 Builder、不查库、不调 LLM。
 */
interface PreExtractionContextBuilder {
    /**
     * 为 [sessionKey] 构建一次提取上下文。
     *
     * 不写库、不修改轮次状态；预算不足通过 [ExtractionBuildResult] 表达，不抛预算异常。
     */
    suspend fun build(sessionKey: SessionKey): ExtractionBuildResult
}
